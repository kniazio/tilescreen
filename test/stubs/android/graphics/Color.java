package android.graphics;
public class Color {
    public static int HSVToColor(float[] hsv) {
        int rgb = java.awt.Color.HSBtoRGB(hsv[0] / 360f, hsv[1], hsv[2]);
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }
}
