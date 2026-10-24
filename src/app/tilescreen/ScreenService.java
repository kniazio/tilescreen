package app.tilescreen;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the screen's server running while the activity is in the background. */
public final class ScreenService extends Service {
    private PowerManager.WakeLock cpu;

    @Override
    public void onCreate() {
        super.onCreate();
        App.get().screen();
        // The processor stays awake while Android has the glass off (Use Android screensaver), so Home Assistant can
        // still reach the screen and Wake it. The app always kept the screen on before, which did the same.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tilescreen:server");
        cpu.setReferenceCounted(false);
        cpu.acquire();
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, 0);
        Notification n = new Notification.Builder(this)
                .setContentTitle("Tile Screen")
                .setContentText("Serving " + App.get().friendly() + " to Home Assistant")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (cpu != null) cpu.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
