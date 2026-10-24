package android.os;
/** Runs posted work only when the test asks, never on its own. */
public class Handler {
    public static final java.util.List<Runnable> posted = new java.util.ArrayList<Runnable>();
    public Handler(Looper l) {}
    public boolean post(Runnable r) { posted.add(r); return true; }
    public boolean postDelayed(Runnable r, long d) { return true; }
    public void removeCallbacks(Runnable r) {}
    public static void drain() { while (!posted.isEmpty()) posted.remove(0).run(); }
}
