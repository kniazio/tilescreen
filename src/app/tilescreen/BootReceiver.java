package app.tilescreen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the screen after the tablet boots. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        c.startService(new Intent(c, ScreenService.class));
        if (App.get().prefs().getBoolean("open_on_boot", true)) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
