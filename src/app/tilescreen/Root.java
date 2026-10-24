package app.tilescreen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;

/**
 * Root, where the tablet has it (Magisk): one shell kept open, used to drive the backlight directly so a level of 0
 * really turns it off. Everything still works without it.
 */
final class Root {
    private static Process shell;
    private static OutputStream in;
    private static Boolean available;
    private static String backlight;
    private static int max = 255;

    private Root() {}

    /** Asks for root once (Magisk shows its prompt the first time); true when granted. */
    static synchronized boolean available() {
        if (available != null) return available;
        try {
            shell = Runtime.getRuntime().exec("su");
            in = shell.getOutputStream();
            in.write("id\n".getBytes("UTF-8"));
            in.flush();
            Thread.sleep(300);
            available = isAlive(shell);
        } catch (Exception e) {
            available = false;
        }
        App.get().log(available ? "root: granted" : "root: not available (fine; the backlight can't go fully off)");
        return available;
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    /** The backlight's sysfs file, or null. */
    static synchronized String backlight() {
        if (backlight != null) return backlight;
        String[] candidates = {"/sys/class/leds/lcd-backlight/brightness"};
        for (String c : candidates) {
            if (new File(c).exists()) backlight = c;
        }
        if (backlight == null) {
            File[] dirs = new File("/sys/class/backlight").listFiles();
            if (dirs != null && dirs.length > 0) backlight = dirs[0].getPath() + "/brightness";
        }
        if (backlight != null) {
            String m = read(backlight.replace("/brightness", "/max_brightness"));
            try {
                if (m != null) max = Integer.parseInt(m.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return backlight;
    }

    static synchronized int max() {
        backlight();
        return max;
    }

    /** Writes the backlight's raw level through root; false without root. */
    static synchronized boolean writeBacklight(int raw) {
        String path = backlight();
        if (path == null || !available() || !isAlive(shell)) return false;
        try {
            in.write(("echo " + Math.max(0, Math.min(max, raw)) + " > " + path + "\n").getBytes("UTF-8"));
            in.flush();
            return true;
        } catch (Exception e) {
            App.get().log("root: writing the backlight failed: " + e);
            return false;
        }
    }

    /** Runs a command in the root shell without waiting for it; false without root. */
    static synchronized boolean run(String command) {
        if (!available() || !isAlive(shell)) return false;
        try {
            in.write((command + "\n").getBytes("UTF-8"));
            in.flush();
            App.get().log("root: " + command);
            return true;
        } catch (Exception e) {
            App.get().log("root: " + command + " failed: " + e);
            return false;
        }
    }

    /** The backlight's raw level now, if the file can be read. */
    static String readBacklight() {
        String path = backlight();
        return path == null ? null : read(path);
    }

    private static String read(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String s = r.readLine();
            r.close();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
