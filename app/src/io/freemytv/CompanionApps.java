package io.freemytv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.net.Uri;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional apps, downloaded only after OK. Independent of the launcher's own update state and jobs. */
final class CompanionApps {
    static final class App {
        final String name, repo, pkg, prefix, certificate, art;
        volatile String state = "idle", version = "";
        volatile int progress;
        App(String name, String repo, String pkg, String prefix, String certificate, String art) {
            this.name=name; this.repo=repo; this.pkg=pkg; this.prefix=prefix; this.certificate=certificate; this.art=art;
        }
    }
    static final App[] APPS = {
        new App("Controle LDZ", "Controle-LDZ", "br.com.ldz.controle", "Controle-LDZ",
            "a782cd40ed0b6d380cb243c4b8cdd120b7de91792cfac1105e273bba4cdd445c", "controle"),
        new App("Quadro LDZ", "Quadro-LDZ", "br.com.galeria.painel", "Quadro-LDZ",
            "749b6ef9bf363b1c45b602cacf83a64f81c863cf046f62c92757d91efd8e1471", "quadro"),
        new App("Tempo LDZ", "tempoLDZ", "br.com.ldz.tempotv", "Tempo-LDZ",
            "02625d71f9422eb9bec6c4a9187f0b271758c981d64128896d56b59220c1c909", "tempo")
    };
    static final String ACTION = "io.freemytv.COMPANION_INSTALL";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile boolean working;
    private static final long MAX_APK = 120L * 1024 * 1024;
    private CompanionApps() {}

    static String label(int index) {
        App app=APPS[index];
        switch (app.state) {
            case "checking": return Ui.tr("Checking…");
            case "downloading": return Ui.trf("Downloading {n}%", "n", app.progress);
            case "ready": return Ui.tr("Install") + " " + app.version;
            case "installing": return Ui.tr("Confirm on Android");
            case "current": return Ui.tr("Already up to date");
            case "unavailable": return Ui.tr("No APK published yet");
            case "failed": return Ui.tr("Could not download. OK to retry.");
            default: return Ui.tr("Get latest version");
        }
    }

    private static File file(Context c, int index) {
        return new File(c.getCacheDir(), "companion/" + index + ".apk");
    }

    static synchronized void activate(Activity activity, int index) {
        if (index<0 || index>=APPS.length || working) return;
        App app=APPS[index];
        if ("installing".equals(app.state)) return;
        if ("ready".equals(app.state) && file(activity,index).isFile()) {
            if (Build.VERSION.SDK_INT>=26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity,Ui.tr("Allow TV LDZ to install apps, then press OK again."),Toast.LENGTH_LONG).show();
                try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:"+activity.getPackageName()))); }
                catch (RuntimeException e) { Toast.makeText(activity,Ui.tr("Open Android settings to allow app installs."),Toast.LENGTH_LONG).show(); }
                return;
            }
            app.state="installing";
            working=true;
            Context context=activity.getApplicationContext();
            WORK.execute(() -> install(context,index));
        } else {
            app.state="checking";
            working=true;
            Context context=activity.getApplicationContext();
            WORK.execute(() -> fetch(context,index));
        }
    }

    private static void fetch(Context c,int index) {
        App app=APPS[index]; File part=new File(file(c,index)+".part");
        try {
            String manifestUrl="https://raw.githubusercontent.com/ludezam/"+app.repo+"/main/update.json?t="+System.currentTimeMillis();
            HttpURLConnection manifestConnection=open(manifestUrl);
            JSONObject manifest;
            try {
                manifestConnection.setRequestProperty("Cache-Control","no-cache, no-store");
                manifestConnection.setRequestProperty("Pragma","no-cache");
                int status=manifestConnection.getResponseCode();
                if(status==404) { app.state="unavailable"; return; }
                if(status!=200) throw new IOException("Manifest HTTP "+status);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream input=manifestConnection.getInputStream()) {
                    byte[] buffer=new byte[8192]; int n;
                    while((n=input.read(buffer))!=-1) {
                        if(bytes.size()+n>16*1024) throw new IOException("Update manifest too large");
                        bytes.write(buffer,0,n);
                    }
                }
                manifest=new JSONObject(bytes.toString("UTF-8"));
            } finally { manifestConnection.disconnect(); }
            if(manifest.optInt("schema")!=1) throw new IOException("Unsupported update manifest schema");
            app.version=manifest.optString("version").replaceFirst("^[vV]", "");
            String tag=manifest.optString("tag"), assetName=manifest.optString("apk");
            String digest=manifest.optString("sha256").toLowerCase(java.util.Locale.ROOT);
            long size=manifest.optLong("size");
            if(app.version.isEmpty() || !tag.matches("v?[0-9]+(?:\\.[0-9]+){1,3}")
                    || !tag.replaceFirst("^[vV]", "").equals(app.version)
                    || !app.pkg.equals(manifest.optString("package"))
                    || !assetName.startsWith(app.prefix) || !assetName.endsWith(".apk")
                    || assetName.contains("/") || assetName.indexOf('\\') >= 0 || assetName.contains("..")
                    || !digest.matches("[0-9a-f]{64}") || size<=0 || size>MAX_APK)
                throw new IOException("Invalid update manifest for "+app.name);
            String url="https://github.com/ludezam/"+app.repo+"/releases/download/"+tag+"/"+assetName;
            try {
                PackageInfo installed=c.getPackageManager().getPackageInfo(app.pkg,0);
                if(app.version.equals(installed.versionName)) { app.state="current"; return; }
            } catch(PackageManager.NameNotFoundException ignored) {}
            part.getParentFile().mkdirs();
            app.state="downloading"; app.progress=0;
            HttpURLConnection connection=open(url);
            try {
                if(connection.getResponseCode()!=200) throw new IOException("APK HTTP "+connection.getResponseCode());
                MessageDigest sha=MessageDigest.getInstance("SHA-256"); long total=0;
                try(InputStream input=connection.getInputStream(); OutputStream output=new FileOutputStream(part)) {
                    byte[] buffer=new byte[32768]; int n;
                    while((n=input.read(buffer))!=-1) {
                        total+=n; if(total>size) throw new IOException("APK too large");
                        output.write(buffer,0,n); sha.update(buffer,0,n); app.progress=(int)(total*100/size);
                    }
                }
                if(total!=size || !hex(sha.digest()).equalsIgnoreCase(digest)) throw new IOException("APK digest mismatch");
            } finally { connection.disconnect(); }
            validate(c,app,part);
            File apk=file(c,index); if(apk.exists() && !apk.delete()) throw new IOException("Cannot replace cached APK");
            if(!part.renameTo(apk)) throw new IOException("Cannot save APK");
            app.state="ready";
        } catch(Exception e) {
            AppLog.w("companion "+app.name+": "+e); app.state="failed";
        } finally { part.delete(); working=false; }
    }

    @SuppressWarnings("deprecation")
    private static void validate(Context c,App app,File file) throws Exception {
        PackageInfo apk=c.getPackageManager().getPackageArchiveInfo(file.getPath(),PackageManager.GET_SIGNATURES);
        if(apk==null || !app.pkg.equals(apk.packageName) || apk.signatures==null || apk.signatures.length!=1
                || !app.certificate.equals(hex(MessageDigest.getInstance("SHA-256").digest(apk.signatures[0].toByteArray()))))
            throw new IOException("APK package or signing key mismatch");
        if(apk.applicationInfo==null || apk.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT) throw new IOException("Android version unsupported");
    }

    private static void install(Context c,int index) {
        App app=APPS[index]; int sessionId=-1;
        PackageInstaller installer=c.getPackageManager().getPackageInstaller();
        try {
            File apk=file(c,index); validate(c,app,apk);
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.pkg); params.setSize(apk.length());
            if(Build.VERSION.SDK_INT>=31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            sessionId=installer.createSession(params);
            try(PackageInstaller.Session session=installer.openSession(sessionId)) {
                try(InputStream input=new FileInputStream(apk); OutputStream output=session.openWrite("base.apk",0,apk.length())) {
                    byte[] buffer=new byte[32768]; int n;
                    while((n=input.read(buffer))!=-1) output.write(buffer,0,n);
                    session.fsync(output);
                }
                Intent result=new Intent(c,CompanionInstallReceiver.class).setAction(ACTION).putExtra("app",index);
                int flags=PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT>=31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getBroadcast(c,sessionId,result,flags).getIntentSender());
            }
        } catch(Exception e) {
            if(sessionId>=0) try { installer.abandonSession(sessionId); } catch(RuntimeException ignored) {}
            AppLog.w("companion install "+app.name+": "+e); app.state="failed";
        } finally { working=false; }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent","TV-LDZ"); return connection;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(); for(byte b:bytes) result.append(String.format("%02x",b)); return result.toString();
    }
}
