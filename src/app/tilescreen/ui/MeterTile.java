package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * A sensor's meter (firmware 0.56.0, docs/METER.md, meter_card.h): where its value stands in a range, as a ring open
 * at the bottom, a tape (a ruler with a pointer) or a bar of cells. The range, the segments with their colours and the
 * thresholds are the tile's options (meter.py); a value outside the range stands at its end.
 */
final class MeterTile {
    private static final int GREEN = 0xFF4CAF50, ORANGE = 0xFFFF9800, RED = 0xFFF44336;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;

    MeterTile(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
        line.setStyle(Paint.Style.STROKE);
    }

    /** The meter's range, segments and colours, read from the tile's options. */
    private static final class Plan {
        double low = 0, high = 100, value = Double.NaN;
        int[] colors;
        double[] thresholds;
        String style = "ring";

        int colorAt(double v) {
            int i = 0;
            if (thresholds != null) while (i < thresholds.length && v >= thresholds[i]) i++;
            return colors[Math.min(i, colors.length - 1)];
        }

        float fraction(double v) {
            if (Double.isNaN(v)) return 0;
            return (float) Math.max(0, Math.min(1, (v - low) / (high - low)));
        }
    }

    private static Plan plan(Tessera.Tile t, int accent) {
        Plan p = new Plan();
        Map<String, Object> o = t.options;
        String style = Json.str(o, "meter");
        if ("tape".equals(style) || "bar".equals(style)) p.style = style;
        Double lo = Json.dbl(o, "min"), hi = Json.dbl(o, "max");
        if (lo != null && hi != null && hi > lo) {
            p.low = lo;
            p.high = hi;
        }
        try {
            p.value = Double.parseDouble(t.state);
        } catch (Exception ignored) {
        }
        Integer seg = Json.integer(o, "segments");
        int segments = seg == null ? 1 : Math.max(1, Math.min(3, seg));
        List<Object> colors = Json.arr(o == null ? null : o.get("colors"));
        p.colors = new int[segments];
        int[] defaults = segments == 1 ? new int[]{accent} : segments == 2 ? new int[]{GREEN, RED} : new int[]{GREEN, ORANGE, RED};
        for (int i = 0; i < segments; i++) {
            Object c = colors != null && i < colors.size() ? colors.get(i) : null;
            p.colors[i] = c instanceof Double ? 0xFF000000 | (((Double) c).intValue() & 0xFFFFFF) : defaults[i];
        }
        if (segments > 1) {
            // A screen that gets no thresholds puts them at 80 % and 90 % of the range.
            List<Object> th = Json.arr(o == null ? null : o.get("thresholds"));
            p.thresholds = new double[segments - 1];
            double[] shares = {0.8, 0.9};
            for (int i = 0; i < segments - 1; i++) {
                Object v = th != null && i < th.size() ? th.get(i) : null;
                p.thresholds[i] = v instanceof Double ? (Double) v : p.low + (p.high - p.low) * shares[segments == 2 ? 0 : i];
            }
        }
        return p;
    }

    private String valueText(Tessera.Tile t, Plan p) {
        String unit = t.text("unit_of_measurement");
        String word = Json.str(t.extra, "sw");  // Home Assistant's words for a duration ("1h 30m")
        if (word != null && !word.isEmpty()) return word;
        String v = Double.isNaN(p.value) ? t.state : t.state;
        if (unit == null || unit.isEmpty()) return v;
        return unit.equals("%") ? v + "%" : v + " " + unit;
    }

    private float fitSize(String s, float w, float size, Typeface f) {
        text.setTypeface(f);
        text.setTextSize(size);
        float tw = text.measureText(s);
        return tw > w ? size * w / tw : size;
    }

    private void words(Canvas c, String s, float x, float baseline, float size, int color, Typeface f, Paint.Align a) {
        text.setTypeface(f);
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(a);
        c.drawText(s, x, baseline, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private String fit(String s, float w, float size, Typeface f) {
        text.setTypeface(f);
        text.setTextSize(size);
        return TextUtils.ellipsize(s == null ? "" : s, text, Math.max(1, w), TextUtils.TruncateAt.END).toString();
    }

    private static int dim(int color) { return (color & 0xFFFFFF) | 0x40000000; }

    void draw(Canvas c, Tessera.Tile t, RectF box, float fs, int ink, int muted, int track, int accent, boolean dead) {
        Plan p = plan(t, accent);
        if (p.style.equals("tape")) tape(c, t, p, box, fs, ink, muted, track);
        else if (p.style.equals("bar")) bar(c, t, p, box, fs, ink, muted, track);
        else ring(c, t, p, box, fs, ink, muted, track, dead);
    }

    // ---- the ring: open at the bottom, filled to the value, the tile's icon in its tinted circle in the middle
    private void ring(Canvas c, Tessera.Tile t, Plan p, RectF box, float fs, int ink, int muted, int track, boolean dead) {
        String value = valueText(t, p);
        float W = box.width(), H = box.height();
        boolean beside = W > H * 1.8f;
        // Under the ring: the value, then the name; the ring's open ends reach into the value's line.
        float vsMax = Math.min(fs * 1.5f, H * 0.2f), nameS = fs * 0.8f;
        float textH = vsMax * 1.05f + nameS * 1.35f;
        float rr, cx, cy, stroke;
        if (beside) {
            float d = Math.min(H, W * 0.42f);
            stroke = Math.max(3, d * 0.09f);
            rr = d / 2 - stroke / 2;
            cx = box.left + d / 2;
            cy = box.centerY();
        } else {
            // The ring reaches 0.71 of its radius below its middle (its ends at 135° and 45°).
            rr = Math.min(W * 0.42f, (H - textH) / 1.75f);
            rr = Math.max(fs, rr);
            stroke = Math.max(3, rr * 0.18f);
            cx = box.centerX();
            cy = box.top + stroke / 2 + rr;
        }
        float d = 2 * rr;
        RectF oval = new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(stroke);
        line.setColor(track);
        arc(c, oval, 135, 270);
        float frac = p.fraction(p.value);
        if (frac > 0 && !dead) {
            line.setColor(p.colorAt(Math.max(p.low, Math.min(p.high, p.value))));
            arc(c, oval, 135, 270 * frac);
        }
        // The icon in its tinted circle in the middle.
        float ic = rr * 0.9f;
        int tone = p.colorAt(Double.isNaN(p.value) ? p.low : p.value);
        fill.setColor(dead ? track : (tone & 0xFFFFFF) | 0x33000000);
        c.drawCircle(cx, cy, ic / 2, fill);
        glyphs.setColor(dead ? muted : tone);
        glyphs.setTextSize(ic * 0.55f);
        c.drawText(Icons.forTile(context, t), cx, cy + ic * 0.2f, glyphs);
        if (beside) {
            float tx = box.left + d + fs, tw = box.right - tx;
            float vs = fitSize(value, tw, fs * 2, light);
            words(c, value, tx, box.centerY() + vs * 0.1f, vs, ink, light, Paint.Align.LEFT);
            words(c, fit(t.name, tw, fs * 0.9f, regular), tx, box.centerY() + vs * 0.1f + fs * 1.3f, fs * 0.9f, muted, regular, Paint.Align.LEFT);
        } else {
            // The value between the ring's ends, the name quiet under it.
            float vs = fitSize(value, Math.min(W, rr * 2.6f), vsMax, medium);
            float vy = Math.min(box.bottom - nameS * 1.35f, cy + rr * 0.71f + vs * 0.45f);
            words(c, value, cx, vy, vs, ink, medium, Paint.Align.CENTER);
            words(c, fit(t.name, W, nameS, regular), cx, vy + nameS * 1.3f, nameS, muted, regular, Paint.Align.CENTER);
        }
    }

    // ---- the tape: the name small and the value large over a still ruler, one pointer at the value
    private void tape(Canvas c, Tessera.Tile t, Plan p, RectF box, float fs, int ink, int muted, int track) {
        String value = valueText(t, p);
        float W = box.width(), H = box.height();
        words(c, fit(t.name.toUpperCase(Locale.getDefault()), W, fs * 0.75f, medium), box.left, box.top + fs * 0.8f, fs * 0.75f, muted, medium, Paint.Align.LEFT);
        float vs = fitSize(value, W, Math.min(fs * 2.6f, H * 0.28f), light);
        words(c, value, box.left, box.top + fs * 1.2f + vs * 0.85f, vs, ink, light, Paint.Align.LEFT);
        boolean percent = "%".equals(t.text("unit_of_measurement"));
        float endsH = percent ? 0 : fs * 1.0f;
        float rulerTop = box.top + fs * 1.5f + vs, rulerBottom = box.bottom - endsH;
        if (rulerBottom - rulerTop < fs * 0.6f) rulerTop = rulerBottom - fs * 0.6f;
        int marks = Math.max(11, Math.min(51, (int) (W / (fs * 0.6f)) | 1));
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setStrokeWidth(1);
        for (int i = 0; i < marks; i++) {
            float x = box.left + W * i / (marks - 1);
            boolean major = i % 5 == 0;
            float top = major ? rulerTop : rulerTop + (rulerBottom - rulerTop) * 0.35f;
            line.setColor(p.thresholds != null ? (p.colorAt(p.low + (p.high - p.low) * i / (marks - 1)) & 0xFFFFFF) | 0x90000000 : major ? muted : track);
            c.drawLine(x, top, x, rulerBottom, line);
        }
        // The pointer: the segment's colour, or the tape's orange with one segment.
        float frac = p.fraction(p.value);
        float px = box.left + W * frac;
        int color = p.thresholds != null ? p.colorAt(p.value) : (t.options != null && Json.arr(t.options.get("colors")) != null ? p.colors[0] : ORANGE);
        line.setColor(color);
        line.setStrokeWidth(Math.max(3, fs * 0.22f));
        line.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(px, rulerTop - fs * 0.2f, px, rulerBottom, line);
        if (!percent) {
            words(c, number(p.low), box.left, box.bottom - fs * 0.1f, fs * 0.75f, muted, regular, Paint.Align.LEFT);
            words(c, number(p.high), box.right, box.bottom - fs * 0.1f, fs * 0.75f, muted, regular, Paint.Align.RIGHT);
        }
    }

    // ---- the bar: the value and the name at the top, narrow cells lit in the value's colour over the room left
    private void bar(Canvas c, Tessera.Tile t, Plan p, RectF box, float fs, int ink, int muted, int track) {
        String value = valueText(t, p);
        float W = box.width(), H = box.height();
        boolean standing = H > W * 1.25f;
        float frac = p.fraction(p.value);
        if (standing) {
            // A standing card stands the bar up beside the words.
            float bw = Math.min(W * 0.35f, fs * 3);
            RectF cells = new RectF(box.right - bw, box.top, box.right, box.bottom);
            float tw = W - bw - fs;
            float vs = fitSize(value, tw, fs * 1.8f, light);
            words(c, value, box.left, box.top + vs * 0.9f, vs, ink, light, Paint.Align.LEFT);
            words(c, fit(t.name, tw, fs * 0.85f, regular), box.left, box.top + vs * 0.9f + fs * 1.2f, fs * 0.85f, muted, regular, Paint.Align.LEFT);
            int n = Math.max(8, Math.min(60, (int) (cells.height() / (fs * 0.55f))));
            float gap = Math.max(1, cells.height() / n * 0.3f), ch = (cells.height() - (n - 1) * gap) / n;
            for (int i = 0; i < n; i++) {
                double at = p.low + (p.high - p.low) * (i + 0.5) / n;
                int col = p.thresholds != null ? p.colorAt(at) : p.colors[0];
                boolean lit = (i + 0.5f) / n <= frac;
                fill.setColor(lit ? col : dim(col));
                float y = cells.bottom - (i + 1) * ch - i * gap;
                c.drawRoundRect(new RectF(cells.left, y, cells.right, y + ch), ch * 0.25f, ch * 0.25f, fill);
            }
            return;
        }
        float vs = fitSize(value, W * 0.6f, Math.min(fs * 1.8f, H * 0.3f), light);
        words(c, value, box.left, box.top + vs * 0.9f, vs, ink, light, Paint.Align.LEFT);
        words(c, fit(t.name, W * 0.4f - fs, fs * 0.85f, regular), box.right, box.top + vs * 0.8f, fs * 0.85f, muted, regular, Paint.Align.RIGHT);
        RectF cells = new RectF(box.left, box.top + vs * 1.3f, box.right, box.bottom);
        if (cells.height() < 4) return;
        // Wider cells on a big card, never more than 60, as a real LED bar graph has.
        int n = Math.max(10, Math.min(60, (int) (W / (fs * 0.7f))));
        float gap = Math.max(1, W / n * 0.28f), cw = (W - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) {
            double at = p.low + (p.high - p.low) * (i + 0.5) / n;
            int col = p.thresholds != null ? p.colorAt(at) : p.colors[0];
            boolean lit = (i + 0.5f) / n <= frac;
            fill.setColor(lit ? col : dim(col));
            float x = cells.left + i * (cw + gap);
            c.drawRoundRect(new RectF(x, cells.top, x + cw, cells.bottom), cw * 0.25f, cw * 0.25f, fill);
        }
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.US, "%.1f", v);
    }

    /** An arc as a path of short lines from `start` (degrees, clockwise from 3 o'clock) through `sweep`. */
    private void arc(Canvas c, RectF oval, float start, float sweep) {
        Path path = new Path();
        int steps = Math.max(6, (int) (Math.abs(sweep) / 4));
        float cx = oval.centerX(), cy = oval.centerY(), r = oval.width() / 2;
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(start + sweep * i / steps);
            float x = cx + r * (float) Math.cos(a), y = cy + r * (float) Math.sin(a);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }
}
