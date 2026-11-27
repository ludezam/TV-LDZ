package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Curated public-domain/CC0 landscape and space photography from Wikimedia Commons, with a small offline cache. */
final class OnlineLandscapes {
    private static final String PREFS = "online_landscapes";
    private static final String CATALOG_KEY = "catalog";
    private static final String FETCHED_KEY = "fetched_at";
    private static final String CATALOG_REVISION_KEY = "catalog_revision";
    private static final String RETRY_AFTER_KEY = "retry_after";
    private static final String DOWNLOAD_RETRY_AFTER_KEY = "download_retry_after";
    private static final String LOW_RESOLUTION_KEY = "low_resolution_ids";
    private static final long CATALOG_TTL_MS = 24L * 60 * 60_000L;
    private static final long RETRY_DELAY_MS = 15L * 60_000L;
    private static final int CATALOG_REVISION = 10;
    private static final int RESPONSE_LIMIT = 4_000_000;
    private static final int IMAGE_LIMIT = 12 * 1024 * 1024;
    private static final int OUTPUT_WIDTH = 1920;
    private static final int OUTPUT_HEIGHT = 1080;
    private static final int CACHE_FILES = 8;
    private static final long CACHE_BYTES = 64L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvldz-landscape-prefetch");
        t.setDaemon(true);
        return t;
    });
    private static final java.util.Set<String> PREFETCHING = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> DOWNLOAD_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private OnlineLandscapes() {}

    static Bitmap image(Context context, long slot) {
        Context app = context.getApplicationContext();
        // Refresh the catalog revision before honoring a file-server cooldown so new filters can invalidate it.
        List<Photo> catalog = catalog(app);
        if (downloadCoolingDown(app)) {
            AppLog.w("online landscape downloads paused after Wikimedia HTTP 429; using bundled fallback");
            return null;
        }
        if (catalog.isEmpty()) {
            AppLog.w("online landscape result: no eligible catalog entries; home will use bundled fallback");
            return null;
        }
        int start = Math.floorMod(slot, catalog.size());
        java.util.Set<String> lowResolution = lowResolutionIds(app);
        int attempts = 0, maxAttempts = Math.min(8, catalog.size());
        for (int offset = 0; offset < catalog.size() && attempts < maxAttempts; offset++) {
            if (downloadCoolingDown(app)) {
                AppLog.w("online landscape downloads paused after Wikimedia HTTP 429; using bundled fallback");
                return null;
            }
            int index = (start + offset) % catalog.size();
            Photo selected = catalog.get(index);
            if (lowResolution.contains(selected.id)) continue;
            attempts++;
            File file = cachedFile(app, selected);
            if (!file.isFile()) {
                AppLog.w("online landscape cache miss: " + shortId(selected.id) + " candidate=" + attempts);
                if (!download(app, selected, file)) continue;
            } else {
                AppLog.w("online landscape cache hit: " + shortId(selected.id));
            }
            Bitmap bitmap = decode(file);
            if (bitmap == null) {
                AppLog.w("online landscape decode failed; deleting cache entry " + shortId(selected.id));
                file.delete();
                continue;
            }
            file.setLastModified(System.currentTimeMillis());
            prefetchNext(app, catalog, index + 1L);
            return bitmap;
        }
        AppLog.w("online landscape result: no usable image after checking " + attempts + " candidates; home will use bundled fallback");
        return null;
    }

    private static java.util.Set<String> lowResolutionIds(Context context) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        try {
            JSONArray array = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(LOW_RESOLUTION_KEY, "[]"));
            for (int i = 0; i < array.length() && out.size() < 96; i++) out.add(array.optString(i, ""));
        } catch (Exception e) {
            AppLog.w("online landscape low-resolution list: " + e.getMessage());
        }
        return out;
    }

    private static void markLowResolution(Context context, String id) {
        java.util.HashSet<String> ids = new java.util.HashSet<>(lowResolutionIds(context));
        if (!ids.add(id)) return;
        JSONArray array = new JSONArray();
        int added = 0;
        for (String value : ids) {
            if (added++ >= 96) break;
            array.put(value);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LOW_RESOLUTION_KEY, array.toString()).apply();
    }

    private static List<Photo> catalog(Context context) {
        synchronized (LOCK) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getInt(CATALOG_REVISION_KEY, 0) != CATALOG_REVISION) {
                AppLog.w("online landscape catalog: refreshing after search rules changed");
                prefs.edit().remove(CATALOG_KEY).remove(FETCHED_KEY).remove(RETRY_AFTER_KEY)
                        .remove(DOWNLOAD_RETRY_AFTER_KEY).remove(LOW_RESOLUTION_KEY)
                        .putInt(CATALOG_REVISION_KEY, CATALOG_REVISION).apply();
                clearImageCache(context);
            }
            List<Photo> old = parse(prefs.getString(CATALOG_KEY, ""));
            long now = System.currentTimeMillis();
            long fetched = prefs.getLong(FETCHED_KEY, 0);
            if (!old.isEmpty() && now - fetched < CATALOG_TTL_MS) return old;
            if (!old.isEmpty()) {
                AppLog.w("online landscape catalog: using " + old.size() + " stale entries while refreshing");
                refreshAsync(context);
                return old;
            }
            long retryAfter = prefs.getLong(RETRY_AFTER_KEY, 0);
            if (now < retryAfter) {
                AppLog.w("online landscape catalog: retry held for " + ((retryAfter - now) / 1000) + " s after previous failure");
                return old;
            }
            AppLog.w("online landscape catalog: fetching Wikimedia Commons catalog");
            List<Photo> fresh = fetchCatalog();
            if (!fresh.isEmpty()) {
                saveCatalog(prefs, fresh);
                prefs.edit().remove(RETRY_AFTER_KEY).apply();
            } else {
                prefs.edit().putLong(RETRY_AFTER_KEY, now + RETRY_DELAY_MS).apply();
                AppLog.w("online landscape catalog: zero eligible entries; retry in 15 min");
            }
            return fresh;
        }
    }

    private static void refreshAsync(Context context) {
        if (!PREFETCHING.add("catalog")) return;
        PREFETCH.execute(() -> {
            try {
                List<Photo> fresh = fetchCatalog();
                android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!fresh.isEmpty()) {
                    saveCatalog(prefs, fresh);
                    prefs.edit().remove(RETRY_AFTER_KEY).apply();
                } else {
                    prefs.edit().putLong(RETRY_AFTER_KEY, System.currentTimeMillis() + RETRY_DELAY_MS).apply();
                    AppLog.w("online landscape catalog refresh: zero eligible entries; retry in 15 min");
                }
            } finally {
                PREFETCHING.remove("catalog");
            }
        });
    }

    private static void saveCatalog(android.content.SharedPreferences prefs, List<Photo> photos) {
        JSONArray array = new JSONArray();
        for (Photo photo : photos) array.put(photo.toJson());
        prefs.edit().putString(CATALOG_KEY, array.toString()).putLong(FETCHED_KEY, System.currentTimeMillis()).apply();
    }

    private static List<Photo> parse(String json) {
        ArrayList<Photo> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && out.size() < 48; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                Photo photo = Photo.fromJson(o);
                if (photo != null) out.add(photo);
            }
        } catch (Exception e) {
            AppLog.w("online landscapes catalog: " + e.getMessage());
        }
        return out;
    }

    private static List<Photo> fetchCatalog() {
        ArrayList<Photo> landscapes = new ArrayList<>();
        String[] landscapeQueries = {"forest river landscape photograph", "forest landscape photograph", "waterfall landscape photograph",
                "beach ocean landscape photograph", "sunset mountain landscape photograph"};
        for (String q : landscapeQueries) {
            if (landscapes.size() >= 30) break;
            appendUnique(landscapes, searchCommons(q, false), 30);
        }
        ArrayList<Photo> universe = new ArrayList<>();
        String[] universeQueries = {"galaxy nebula photograph", "milky way night sky photograph", "aurora borealis stars photograph"};
        for (String q : universeQueries) {
            if (universe.size() >= 18) break;
            appendUnique(universe, searchCommons(q, true), 18);
        }
        ArrayList<Photo> combined = new ArrayList<>();
        for (int i = 0; i < Math.max(landscapes.size(), universe.size()) && combined.size() < 48; i++) {
            if (i < landscapes.size()) combined.add(landscapes.get(i));
            if (i < universe.size()) combined.add(universe.get(i));
        }
        AppLog.w("online landscape catalog: accepted " + combined.size() + " Wikimedia entries (landscapes="
                + landscapes.size() + ", space=" + universe.size() + ")");
        return combined;
    }

    private static void appendUnique(ArrayList<Photo> target, List<Photo> extra, int limit) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Photo p : target) seen.add(p.id);
        for (Photo p : extra) { if (target.size() >= limit) return; if (seen.add(p.id)) target.add(p); }
    }

    private static ArrayList<Photo> searchCommons(String query, boolean universe) {
        ArrayList<Photo> out = new ArrayList<>();
        try {
            String urlText = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=80&gsrsearch="
                    + URLEncoder.encode("filetype:bitmap " + query, "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920&format=json&formatversion=2";
            JSONObject root = getJson(urlText, "Wikimedia Commons");
            JSONObject pages = root.optJSONObject("query");
            JSONArray items = pages == null ? null : pages.optJSONArray("pages");
            int count = items == null ? 0 : items.length();
            if (items != null) for (int i = 0; i < items.length() && out.size() < 18; i++) {
                JSONObject page = items.optJSONObject(i);
                JSONArray infos = page == null ? null : page.optJSONArray("imageinfo");
                JSONObject info = infos == null ? null : infos.optJSONObject(0);
                if (info == null) continue;
                String title = page.optString("title", "");
                JSONObject meta = info.optJSONObject("extmetadata");
                String license = metadataValue(meta, "LicenseShortName");
                String licenseLower = license.toLowerCase(Locale.ROOT);
                if (!(licenseLower.contains("public domain") || licenseLower.contains("cc0") || licenseLower.equals("pdm"))) continue;
                int width = info.optInt("thumbwidth", info.optInt("width"));
                int height = info.optInt("thumbheight", info.optInt("height"));
                String url = info.optString("thumburl", "");
                if (!validDimensions(width, height) || !url.startsWith("https://")) {
                    width = info.optInt("width"); height = info.optInt("height"); url = info.optString("url", "");
                }
                String text = (title + " " + metadataValue(meta, "ImageDescription") + " "
                        + metadataValue(meta, "Categories") + " " + metadataValue(meta, "ObjectName") + " "
                        + metadataValue(meta, "Artist")).toLowerCase(Locale.ROOT);
                if (!validDimensions(width, height) || tooBusy(text) || !hasTheme(text, universe) || !url.startsWith("https://")) continue;
                String id = "commons:" + title;
                out.add(new Photo(id, url, width, height, "commons", license, metadataValue(meta, "Artist"), universe));
            }
            AppLog.w("Wikimedia Commons search: query='" + query + "', results=" + count + ", accepted=" + out.size());
        } catch (Exception e) { AppLog.w("Wikimedia Commons search failed: " + e.getMessage()); }
        return out;
    }

    private static JSONObject getJson(String address, String provider) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(7_000); c.setReadTimeout(9_000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "TV-LDZ/2.2.20 (Android TV; wallpaper catalog)");
            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new java.io.IOException(provider + " HTTP " + status);
            return new JSONObject(new String(readBounded(c.getInputStream(), RESPONSE_LIMIT), StandardCharsets.UTF_8));
        } finally { if (c != null) c.disconnect(); }
    }

    private static String metadataValue(JSONObject meta, String key) {
        JSONObject value = meta == null ? null : meta.optJSONObject(key);
        return value == null ? "" : value.optString("value", "").replaceAll("<[^>]*>", " ").trim();
    }

    private static boolean validDimensions(int width, int height) {
        return width >= 1600 && height >= 900 && height > 0 && width / (double) height >= 1.55 && width / (double) height <= 2.2;
    }

    private static String tags(JSONArray tags) {
        if (tags == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tags.length() && i < 30; i++) {
            JSONObject tag = tags.optJSONObject(i);
            if (tag != null) b.append(' ').append(tag.optString("name", ""));
        }
        return b.toString();
    }

    private static boolean tooBusy(String text) {
        String[] reject = {"people", "person", "portrait", "crowd", "city", "urban", "building", "architecture",
                "vehicle", "traffic", "logo", "screenshot", "poster", "infographic", "collage", "watermark",
                "astronaut", "crew", "spacecraft", "rocket", "mission control", "diagram", "illustration", "model",
                "man", "woman", "human", "nude", "nudity", "children", "child", "hiker", "hiking",
                "black and white", "black-and-white", "monochrome", "monochromatic", "grayscale", "greyscale",
                "b&w", "bw photo", "text", "lettering", "typography", "caption", "label", "inscription", "sign", "map", "chart",
                "written", "words", "title", "book cover", "cover art", "cover image", "poster", "subtitle",
                "text overlay", "text on image", "with text", "contains text", "watermarked", "banner", "postcard",
                "black white", "blackwhite", "silver gelatin", "gelatin silver", "sepia", "line art", "woodcut", "lithograph",
                "collaborative", "cflrp", "restoration program", "painting", "drawing", "watercolor", "watercolour",
                "etching", "engraving", "oil on canvas", "artwork", "museum", "scan"};
        for (String word : reject) if (text.matches("(?s).*\\b" + word + "\\b.*")) return true;
        return false;
    }

    private static boolean hasTheme(String text, boolean universe) {
        String[] words = universe
                ? new String[] {"galaxy", "nebula", "space", "universe", "astronomy", "astronomical", "cosmos", "milky way", "night sky", "stars", "planet", "moon"}
                : new String[] {"landscape", "mountain", "forest", "lake", "beach", "ocean", "coast", "shore", "sea", "valley", "nature", "waterfall", "river", "island", "desert", "cliff", "sky"};
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static File cachedFile(Context context, Photo photo) {
        File dir = new File(context.getFilesDir(), "online-landscapes");
        if (!dir.isDirectory() && !dir.mkdirs()) AppLog.w("couldn't create landscape cache");
        return new File(dir, safeName(photo.id) + ".jpg");
    }

    private static void clearImageCache(Context context) {
        File dir = new File(context.getFilesDir(), "online-landscapes");
        File[] files = dir.listFiles();
        int removed = 0;
        if (files != null) for (File file : files) if (file.isFile() && file.delete()) removed++;
        AppLog.w("online landscape cache: removed " + removed + " files after source/filter update");
    }

    private static String safeName(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(id.hashCode());
        }
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    private static boolean download(Context context, Photo photo, File target) {
        Object lock = DOWNLOAD_LOCKS.computeIfAbsent(photo.id, key -> new Object());
        synchronized (lock) {
            if (target.isFile() && target.length() > 0) return true;
            return downloadLocked(context, photo, target);
        }
    }

    private static boolean downloadLocked(Context context, Photo photo, File target) {
        if (downloadCoolingDown(context)) return false;
        boolean[] lowResolution = {false};
        AppLog.w("landscape download: provider=" + photo.provider + ", license=" + photo.license
                + (photo.credit.isEmpty() ? "" : ", credit=" + photo.credit));
        return downloadFrom(context, photo, target, photo.url, photo.provider + " 1920px thumbnail", lowResolution);
    }

    private static boolean downloadFrom(Context context, Photo photo, File target, String imageUrl, String source,
                                        boolean[] lowResolution) {
        File temporary = new File(target.getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "TV-LDZ/2.2.20 (Android TV; wallpaper)");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK
                    || !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                if (status == 429) {
                    long retryAt = System.currentTimeMillis() + RETRY_DELAY_MS;
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong(DOWNLOAD_RETRY_AFTER_KEY, retryAt).apply();
                    AppLog.w("Wikimedia file server HTTP 429; pausing new image downloads for 15 min");
                }
                AppLog.w("landscape download " + source + " HTTP " + status);
                return false;
            }
            long length = connection.getContentLengthLong();
            if (length > IMAGE_LIMIT) {
                AppLog.w("landscape download rejected from " + source + ": content length exceeds 12 MB");
                return false;
            }
            byte[] bytes = readBounded(connection.getInputStream(), IMAGE_LIMIT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (!validDimensions(bounds.outWidth, bounds.outHeight)) {
                AppLog.w("landscape download rejected from " + source + ": API metadata " + photo.width + "x" + photo.height
                        + ", received " + bounds.outWidth + "x" + bounds.outHeight);
                if (bounds.outWidth < 1600 || bounds.outHeight < 900) {
                    lowResolution[0] = true;
                    markLowResolution(context, photo.id);
                }
                return false;
            }
            if (!hasVisibleColor(bytes)) {
                lowResolution[0] = true;
                markLowResolution(context, photo.id);
                AppLog.w("landscape download rejected from " + source + ": monochrome/near-monochrome image");
                return false;
            }
            if (!writeFullHd(bytes, bounds.outWidth, bounds.outHeight, temporary)) return false;
            long savedLength = temporary.length();
            if (target.exists() && !target.delete()) return false;
            if (!temporary.renameTo(target)) return false;
            trim(context, target);
            AppLog.w("landscape download saved via " + source + ": " + shortId(photo.id) + " source="
                    + bounds.outWidth + "x" + bounds.outHeight + ", cached=1920x1080 (" + savedLength + " bytes)");
            return true;
        } catch (Exception e) {
            AppLog.w("landscape download: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary.exists()) temporary.delete();
        }
    }

    private static boolean downloadCoolingDown(Context context) {
        return System.currentTimeMillis() < context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(DOWNLOAD_RETRY_AFTER_KEY, 0);
    }

    /** Reject monochrome scans without requiring an OCR or image-processing dependency. */
    private static boolean hasVisibleColor(byte[] bytes) {
        Bitmap sample = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int factor = 1;
            while (bounds.outWidth / (factor * 2) >= 120 && bounds.outHeight / (factor * 2) >= 68) factor *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = factor;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            sample = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (sample == null) return false;
            int colored = 0, total = 0;
            for (int y = 0; y < sample.getHeight(); y++) for (int x = 0; x < sample.getWidth(); x++) {
                int pixel = sample.getPixel(x, y);
                int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max > 36 && max - min > Math.max(14, max / 9)) colored++;
                total++;
            }
            return total > 0 && colored / (float) total >= 0.025f;
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("landscape color check failed: " + e.getMessage());
            return false;
        } finally { if (sample != null && !sample.isRecycled()) sample.recycle(); }
    }

    /** Crop to the TV's 16:9 frame and keep every cached download at an exact Full HD size. */
    private static boolean writeFullHd(byte[] bytes, int width, int height, File target) {
        Bitmap source = null;
        Bitmap output = null;
        try {
            int sample = 1;
            while (width / (sample * 2) >= OUTPUT_WIDTH && height / (sample * 2) >= OUTPUT_HEIGHT) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (source == null) {
                AppLog.w("landscape normalize failed: Android could not decode source image");
                return false;
            }
            float sourceRatio = source.getWidth() / (float) source.getHeight();
            float targetRatio = OUTPUT_WIDTH / (float) OUTPUT_HEIGHT;
            int left = 0, top = 0, right = source.getWidth(), bottom = source.getHeight();
            if (sourceRatio > targetRatio) {
                int cropWidth = Math.round(source.getHeight() * targetRatio);
                left = (source.getWidth() - cropWidth) / 2;
                right = left + cropWidth;
            } else if (sourceRatio < targetRatio) {
                int cropHeight = Math.round(source.getWidth() / targetRatio);
                top = (source.getHeight() - cropHeight) / 2;
                bottom = top + cropHeight;
            }
            output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(source, new Rect(left, top, right, bottom),
                    new Rect(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT), paint);
            try (FileOutputStream out = new FileOutputStream(target)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    AppLog.w("landscape normalize failed: JPEG encoder returned false");
                    return false;
                }
                out.flush();
                out.getFD().sync();
            }
            return true;
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("landscape normalize failed: " + e.getMessage());
            return false;
        } finally {
            if (source != null && !source.isRecycled()) source.recycle();
            if (output != null && !output.isRecycled()) output.recycle();
        }
    }

    private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= Backdrop.W * 1.5f
                    && bounds.outHeight / (sample * 2) >= Backdrop.H * 1.5f) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (RuntimeException e) {
            AppLog.w("landscape decode: " + e.getMessage());
            return null;
        }
    }

    private static void prefetchNext(Context context, List<Photo> photos, long slot) {
        if (photos.size() < 2) return;
        Photo next = photos.get(Math.floorMod(slot, photos.size()));
        File file = cachedFile(context, next);
        if (file.isFile() || !PREFETCHING.add(next.id)) return;
        PREFETCH.execute(() -> {
            try { download(context, next, file); }
            finally { PREFETCHING.remove(next.id); }
        });
    }

    private static void trim(Context context, File keep) {
        File[] files = keep.getParentFile().listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        List<File> ordered = new ArrayList<>();
        Collections.addAll(ordered, files);
        ordered.sort(Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : ordered) total += file.length();
        if (ordered.size() <= CACHE_FILES && total <= CACHE_BYTES) return;
        int count = ordered.size();
        for (File file : ordered) {
            if ((count <= CACHE_FILES && total <= CACHE_BYTES) || file.equals(keep)) continue;
            long bytes = file.length();
            if (file.delete()) { total -= bytes; count--; }
        }
    }

    private static byte[] readBounded(InputStream in, int limit) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = source.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new java.io.IOException("response too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static final class Photo {
        final String id, url, provider, license, credit;
        final int width, height;
        final boolean universe;
        Photo(String id, String url, int width, int height) { this(id, url, width, height, "", "", "", false); }
        Photo(String id, String url, int width, int height, String provider, String license, String credit, boolean universe) {
            this.id = id; this.url = url; this.width = width; this.height = height;
            this.provider = provider; this.license = license; this.credit = credit; this.universe = universe;
        }
        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try { out.put("id", id).put("url", url).put("width", width).put("height", height)
                    .put("provider", provider).put("license", license).put("credit", credit).put("universe", universe); }
            catch (org.json.JSONException e) { AppLog.w("online landscape metadata: " + e.getMessage()); }
            return out;
        }
        static Photo fromJson(JSONObject o) {
            String id = o.optString("id", ""), url = o.optString("url", "");
            int w = o.optInt("width"), h = o.optInt("height");
            String provider = o.optString("provider", ""), license = o.optString("license", "");
            if (provider.isEmpty() || id.isEmpty() || !url.startsWith("https://")) return null;
            if ("commons".equals(provider) && !validDimensions(w, h)) return null;
            return new Photo(id, url, w, h, provider, license, o.optString("credit", ""), o.optBoolean("universe", false));
        }
    }
}
