package android.graphics;
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1;
    public static final Typeface DEFAULT = new Typeface(new java.awt.Font("DejaVu Sans", java.awt.Font.PLAIN, 12));
    public final java.awt.Font font;
    Typeface(java.awt.Font f) { font = f; }
    public static Typeface create(String family, int style) {
        String name = family != null && family.contains("light") ? "DejaVu Sans ExtraLight" : "DejaVu Sans";
        int s = family != null && family.contains("medium") || style == BOLD ? java.awt.Font.BOLD : java.awt.Font.PLAIN;
        return new Typeface(new java.awt.Font(name, s, 12));
    }
    public static Typeface createFromAsset(android.content.res.AssetManager a, String path) {
        try { return new Typeface(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, a.open(path))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
