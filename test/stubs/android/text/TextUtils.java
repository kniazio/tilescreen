package android.text;
public class TextUtils {
    public enum TruncateAt { END }
    public static String join(CharSequence d, Iterable<?> parts) {
        StringBuilder b = new StringBuilder(); for (Object o : parts) { if (b.length() > 0) b.append(d); b.append(o); } return b.toString();
    }
    public static CharSequence ellipsize(CharSequence text, TextPaint p, float avail, TruncateAt where) {
        String s = text.toString();
        if (p.measureText(s) <= avail) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n) + "…";
            if (p.measureText(t) <= avail) return t;
        }
        return "";
    }
}
