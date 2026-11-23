package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;

/** Narrow, package-checked bridge for Control LDZ personalization. */
public final class RemoteSettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private void authorize() {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String pkg : packages) if ("br.com.ldz.controle".equals(pkg)) return;
        throw new SecurityException("Control LDZ is required");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize(); Bundle out = new Bundle();
        try {
            if ("get".equals(method) || "set".equals(method)) Prefs.reload();
            if ("get".equals(method)) out.putString("settings", new JSONObject()
                    .put("wallpaperMode", Prefs.wallpaperMode()).put("wallpaperBlurred", Prefs.wallpaperBlurred())
                    .put("saverBackground", Prefs.saverBackground()).put("photos", Prefs.photos())
                    .put("photoCount", Photos.files(getContext()).size()).toString());
            else if ("set".equals(method)) {
                String key = extras == null ? "" : extras.getString("key", ""), value = extras == null ? "" : extras.getString("value", "");
                if ("wallpaperMode".equals(key) && oneOf(value,"sunset","forest","snow","beach","weather")) { Prefs.setPhotos(false); Prefs.setWallpaperMode(value); }
                else if ("photos".equals(key) && ("true".equals(value)||"false".equals(value))) {
                    if (Boolean.parseBoolean(value) && Photos.files(getContext()).isEmpty()) throw new IllegalArgumentException("Envie pelo menos uma foto primeiro.");
                    Prefs.setPhotos(Boolean.parseBoolean(value));
                }
                else if ("wallpaperBlurred".equals(key) && ("true".equals(value)||"false".equals(value))) Prefs.setWallpaperBlurred(Boolean.parseBoolean(value));
                else if ("saverBackground".equals(key) && oneOf(value,"selected","rotate","weather","sunset","forest","snow","beach")) Prefs.setSaverBackground(value);
                else throw new IllegalArgumentException("Opção inválida");
                HomeActivity.remoteSettingsChanged(); out.putBoolean("ok",true);
            } else if ("listPhotos".equals(method)) {
                JSONArray photos = new JSONArray(); int i = 0;
                for (File file : Photos.files(getContext())) {
                    String thumbnail = "";
                    try { thumbnail = "data:image/jpeg;base64,"+Base64.encodeToString(Photos.thumbnail(file),Base64.NO_WRAP); }
                    catch (Exception ignored) { }
                    photos.put(new JSONObject().put("name",file.getName()).put("thumbnail",thumbnail).put("index",++i));
                }
                out.putString("photos",new JSONObject().put("photos",photos).toString()); out.putBoolean("ok",true);
            } else if ("deletePhoto".equals(method)) {
                String name = extras == null ? "" : extras.getString("name","");
                if (!Photos.delete(getContext(),name)) throw new IllegalArgumentException("Esta foto não está mais na TV.");
                if (Photos.files(getContext()).isEmpty()) Prefs.setPhotos(false);
                HomeActivity.remoteSettingsChanged(); out.putBoolean("ok",true);
            } else if ("import".equals(method)) { Prefs.setPhotos(true); new Handler(Looper.getMainLooper()).postDelayed(() -> Photos.importInbox(getContext(), () -> { Prefs.setPhotos(true); HomeActivity.remoteSettingsChanged(); }), 300); out.putBoolean("ok",true); }
            else if ("clearPhotos".equals(method)) { Photos.removeAll(getContext()); Prefs.setPhotos(false); HomeActivity.remoteSettingsChanged(); out.putBoolean("ok",true); }
            else throw new IllegalArgumentException("Ação indisponível");
        } catch (Exception e) { out.putBoolean("ok",false); out.putString("error",e.getMessage()==null?"Falha ao aplicar opção":e.getMessage()); }
        return out;
    }
    private static boolean oneOf(String v,String... all) { for(String s:all) if(s.equals(v)) return true; return false; }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
