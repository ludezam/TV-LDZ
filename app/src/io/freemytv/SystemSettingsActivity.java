package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** A normal task below Android's Wi-Fi page: Back returns here, then explicitly opens the LDZ home task. */
public final class SystemSettingsActivity extends Activity {
    private boolean away;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            away = true;
            return;
        }
        try {
            // No NEW_TASK or CLEAR_TASK: do not resume or clear a manufacturer's unrelated settings task.
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, Ui.tr("Wi-Fi settings unavailable"), Toast.LENGTH_SHORT).show();
            away = true;
        }
    }

    @Override protected void onPause() {
        away = true;
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (away) {
            returnHome(this);
            finish();
        }
    }

    static void returnHome(Activity activity) {
        Class<?> home = HomeMode.AMAZON ? FireHomeActivity.class : HomeActivity.class;
        Intent intent = new Intent(activity, home).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Prefs.fastMode()) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.startActivity(intent);
    }

    @Override public void onBackPressed() {
        returnHome(this);
        finish();
    }
}
