package android.content;
public class Context {
    public static final String BATTERY_SERVICE = "batterymanager";
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
    public Object getSystemService(String name) { return name.equals(BATTERY_SERVICE) ? new android.os.BatteryManager() : null; }
}
