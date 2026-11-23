package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Only the Android installer callback; never installs another app without the user's confirmation. */
public final class CompanionInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        if(!CompanionApps.ACTION.equals(intent.getAction())) return;
        int index=intent.getIntExtra("app",-1);
        if(index<0 || index>=CompanionApps.APPS.length) return;
        CompanionApps.App app=CompanionApps.APPS[index];
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if(confirm==null) throw new IllegalStateException("Missing install confirmation");
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch(RuntimeException e) { app.state="ready"; AppLog.w("companion confirmation: "+e); }
        } else {
            app.state=status==PackageInstaller.STATUS_SUCCESS ? "current" : "ready";
            String text=status==PackageInstaller.STATUS_SUCCESS ? Ui.tr("Installed")
                    : status==PackageInstaller.STATUS_FAILURE_ABORTED ? Ui.tr("Installation cancelled") : Ui.tr("Android could not install this app");
            Toast.makeText(c,app.name+": "+text,Toast.LENGTH_LONG).show();
            if(status!=PackageInstaller.STATUS_SUCCESS) AppLog.w("companion installer: "+intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
