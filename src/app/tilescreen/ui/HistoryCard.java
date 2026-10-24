package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import app.tilescreen.core.Tessera;

/**
 * A card's history, after runtime_tiles.h (render_history_detail) and history_view.h: the value now with the highest
 * and lowest of the range, a smooth line through 24 averages for numbers or a timeline of states with its legend, the
 * times under it, and the range keys (an hour, a day, a week). A finger on the graph reads the moment under it.
 */
final class HistoryCard {
    interface Hits {
        void range(RectF r, int hours);
        void scrub(RectF r);
    }

    static final int[] RANGES = {1, 24, 168};
    private static final String[] RANGE_WORDS = {"Hour", "Day", "Week"};
    private static final int PARTS = 24;

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    HistoryCard() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Whether a card of this domain shows a history (history_card). */
    static boolean shows(String domain) {
        return domain.equals("sensor") || domain.equals("binary_sensor") || domain.equals("switch") || domain.equals("input_boolean")
                || domain.equals("person") || domain.equals("number") || domain.equals("input_number");
    }

    /** Before the history comes: a line for numbers, a timeline for the rest (history_line_guess). */
    static boolean lineGuess(Tessera.Tile t) {
        String d = t.domain();
        if (d.equals("number") || d.equals("input_number")) return true;
        if (!d.equals("sensor")) return false;
        String dc = t.text("device_class");
        if ("enum".equals(dc) || "timestamp".equals(dc) || "date".equals(dc)) return false;
        if (t.text("unit_of_measurement") != null) return true;
        return parse(t.state) != null;
    }

    private static Double parse(String s) {
        try {
            double v = Double.parseDouble(s);
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- words
    private static String clock(long epoch, int offset, boolean h24, boolean weekday) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis((epoch + offset) * 1000L);
        int hour = c.get(Calendar.HOUR_OF_DAY), minute = c.get(Calendar.MINUTE);
        String t = h24 ? String.format(Locale.US, "%02d:%02d", hour, minute)
                : String.format(Locale.US, "%d:%02d %s", hour % 12 == 0 ? 12 : hour % 12, minute, hour < 12 ? "AM" : "PM");
        if (!weekday) return t;
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        return days[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + t;
    }

    private static String axisClock(long epoch, int offset, boolean h24) {
        String t = clock(epoch, offset, h24, false);
        if (h24) return t;
        int colon = t.indexOf(':');
        return colon >= 0 && t.startsWith(":00", colon) ? t.substring(0, colon) + t.substring(colon + 3) : t;
    }

    static String duration(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return s / 60 + " min";
        if (s < 86400) {
            long m = s / 60 % 60;
            return m == 0 ? s / 3600 + " h" : s / 3600 + " h " + m + " min";
        }
        long h = s / 3600 % 24;
        return h == 0 ? s / 86400 + " d" : s / 86400 + " d " + h + " h";
    }

    static String number(double v, int decimals, String unit) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        String t = String.format(Locale.US, "%,." + Math.max(0, Math.min(4, decimals)) + "f", v);
        if (t.matches("-0(\\.0*)?")) t = t.substring(1);
        if (unit == null || unit.isEmpty()) return t;
        return unit.equals("%") ? t + "%" : t + " " + unit;
    }

    private float width(String s, float size, Typeface f) {
        text.setTypeface(f);
        text.setTextSize(size);
        return text.measureText(s);
    }

    private void put(Canvas c, String s, float x, float baseline, float size, int color, Typeface f, Paint.Align a) {
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
        return TextUtils.ellipsize(s, text, Math.max(1, w), TextUtils.TruncateAt.END).toString();
    }

    // ---- the card
    void draw(Canvas c, Tessera.Tile t, Tessera.History h, int hours, RectF area, float fs, float u, int accent,
              boolean h24, float scrubX, String nowWords, Hits hits) {
        boolean ready = h != null;
        boolean isLine = ready ? h.line : lineGuess(t);
        boolean week = hours == 168;
        Double current = parse(t.state);
        if ("unavailable".equals(t.state) || "unknown".equals(t.state)) current = null;
        // The range keys at the bottom: an hour, a day, a week.
        float keysH = fs * 2.2f;
        RectF keys = new RectF(area.left, area.bottom - keysH, area.right, area.bottom);
        fill.setColor(Style.RAISED);
        c.drawRoundRect(keys, keysH / 2, keysH / 2, fill);
        float inset = Math.max(3, keysH / 10), sw = (keys.width() - 2 * inset) / 3;
        for (int i = 0; i < 3; i++) {
            RectF seg = new RectF(keys.left + inset + i * sw, keys.top + inset, keys.left + inset + (i + 1) * sw, keys.bottom - inset);
            boolean on = hours == RANGES[i];
            if (on) {
                fill.setColor(accent);
                c.drawRoundRect(seg, seg.height() / 2, seg.height() / 2, fill);
            }
            put(c, RANGE_WORDS[i], seg.centerX(), seg.centerY() + fs * 0.33f, fs * 0.9f, on ? Style.DARK_TEXT : Style.TEXT, medium, Paint.Align.CENTER);
            hits.range(seg, RANGES[i]);
        }

        // The header: the value now, and what the history says about it at the right.
        float big = fs * 2.2f;
        int decimals = 0;
        int dot = t.state.indexOf('.');
        if (dot >= 0) decimals = Math.min(4, t.state.length() - dot - 1);
        if (ready && h.line) decimals = h.decimals;
        String unit = t.text("unit_of_measurement");
        String value, first = "", second = "";
        double high = Double.NaN, low = Double.NaN;
        long highAt = 0, lowAt = 0;
        if (isLine) {
            value = current == null ? (t.state.equals("unavailable") ? "Unavailable" : nowWords) : number(current, decimals, unit);
            if (ready) {
                high = h.high;
                highAt = h.highAt;
                low = h.low;
                lowAt = h.lowAt;
                if (current != null && !Double.isNaN(high) && current > high) { high = current; highAt = h.end; }
                if (current != null && !Double.isNaN(low) && current < low) { low = current; lowAt = h.end; }
                if (!Double.isNaN(high)) first = "High " + number(high, h.decimals, h.unit) + " · " + clock(highAt, h.offset, h24, week);
                if (!Double.isNaN(low)) second = "Low " + number(low, h.decimals, h.unit) + " · " + clock(lowAt, h.offset, h24, week);
            }
        } else {
            value = ready && h.words.containsKey(t.state) ? h.words.get(t.state) : nowWords;
            if (ready && h.active >= 0) {
                Object[] st = h.states.get(h.active);
                first = st[0] + " · " + duration((Long) st[2]);
                second = h.began > 0 ? h.began + (h.began == 1 ? " time" : " times") : "";
            }
        }
        float headerH = Math.max(big * 1.2f, fs * 2.6f);
        float chartTop = area.top + headerH + u, chartBottom = keys.top - 1.5f * u;
        RectF panel = new RectF(area.left, chartTop, area.right, chartBottom);
        fill.setColor(Style.CARD);
        c.drawRoundRect(panel, 2 * u, 2 * u, fill);
        line.setStrokeWidth(1);
        line.setColor(Style.LINE);
        c.drawRoundRect(panel, 2 * u, 2 * u, line);

        boolean empty = ready && (h.line ? none(h.has) : h.states.isEmpty());
        // The plot's own rectangle, shared with the finger's readout.
        RectF plot = null;
        if (ready && !empty) {
            plot = h.line ? drawLine(c, h, panel, fs, u, accent, h24, current, high, highAt, low, lowAt)
                    : drawTimeline(c, h, panel, fs, u, h24);
            hits.scrub(new RectF(panel.left, panel.top, panel.right, panel.bottom));
        } else {
            put(c, !ready ? "Loading the history…" : "No history for this range", panel.centerX(), panel.centerY() + fs * 0.33f,
                    fs, Style.DIM, regular, Paint.Align.CENTER);
        }
        // A finger on the graph: the moment under it in the header.
        int valueColor = Style.TEXT;
        if (plot != null && !Float.isNaN(scrubX)) {
            float frac = Math.max(0, Math.min(1, (scrubX - plot.left) / Math.max(1, plot.width())));
            if (h.line) {
                int i = Math.max(0, Math.min(PARTS - 1, (int) Math.floor(frac * PARTS)));
                long span = h.end - h.start, begin = h.start + span * i / PARTS, finish = h.start + span * (i + 1) / PARTS;
                value = h.has[i] ? number(h.values[i], h.decimals, h.unit) : "No data";
                valueColor = h.has[i] ? accent : Style.DIM;
                first = clock(begin, h.offset, h24, week) + " – " + clock(finish, h.offset, h24, false);
                second = !h.has[i] ? "" : h.hours == 1 ? "Average" : "Average of " + duration(finish - begin);
                // Where the finger reads.
                float x = plot.left + (i + 0.5f) / PARTS * plot.width();
                line.setColor(Style.FAINT);
                line.setStrokeWidth(1);
                c.drawLine(x, plot.top, x, plot.bottom, line);
            } else {
                int r = runAt(h, frac);
                if (r >= 0) {
                    long[] run = h.runs.get(r);
                    value = run[1] < 0 ? "No data" : (String) h.states.get((int) run[1])[0];
                    long begin = h.start + run[2], finish = h.start + run[3];
                    first = clock(begin, h.offset, h24, week) + " – " + clock(finish, h.offset, h24, week && finish - begin >= 43200);
                    second = duration(run[4]);
                }
            }
        }
        float valueW = Math.min(width(value, big, light) + 6, area.width() * 0.55f);
        put(c, fit(value, valueW, big, light), area.left, area.top + big * 0.95f, big, valueColor, light, Paint.Align.LEFT);
        float wordsW = area.right - (area.left + valueW + 2 * u);
        put(c, fit(first, wordsW, fs * 0.85f, regular), area.right, area.top + fs * 1.0f, fs * 0.85f, Style.DIM, regular, Paint.Align.RIGHT);
        put(c, fit(second, wordsW, fs * 0.85f, regular), area.right, area.top + fs * 2.1f, fs * 0.85f, Style.DIM, regular, Paint.Align.RIGHT);
    }

    private static boolean none(boolean[] has) {
        for (boolean b : has) if (b) return false;
        return true;
    }

    private static int runAt(Tessera.History h, float frac) {
        if (h.runs.isEmpty() || h.slots == 0) return -1;
        int slot = Math.max(0, Math.min(h.slots - 1, (int) Math.floor(frac * h.slots)));
        int found = -1;
        for (int i = 0; i < h.runs.size(); i++) {
            if (h.runs.get(i)[0] <= slot) found = i;
            else break;
        }
        return found;
    }

    private static long runEnd(Tessera.History h, int i) {
        return i + 1 < h.runs.size() ? h.runs.get(i + 1)[0] : h.slots;
    }

    /** Times under a graph: round times where they fit, and "Now" at the end. */
    private void times(Canvas c, Tessera.History h, float x, float w, float y, float fs, float u, boolean h24) {
        float small = fs * 0.8f, nowW = width("Now", small, medium), gap = u;
        put(c, "Now", x + w, y + small, small, Style.TEXT, medium, Paint.Align.RIGHT);
        float last = x - 1000;
        for (long[] at : h.times) {
            long a = at[0];
            if (a <= h.start || a >= h.end) continue;
            String words = h.hours == 168 ? clock(a, h.offset, true, true).substring(0, 3) : axisClock(a, h.offset, h24);
            float tw = width(words, small, regular), cx = x + (float) (a - h.start) / (h.end - h.start) * w;
            float left = Math.max(x - u, cx - tw / 2);
            if (left <= last + gap || left + tw > x + w - nowW - gap) continue;
            fill.setColor(Style.FAINT);
            c.drawRect(cx, y - 0.8f * u, cx + 1, y, fill);
            put(c, words, left, y + small, small, Style.DIM, regular, Paint.Align.LEFT);
            last = left + tw;
        }
    }

    private RectF drawLine(Canvas c, Tessera.History h, RectF panel, float fs, float u, int accent, boolean h24,
                           Double current, double high, long highAt, double low, long lowAt) {
        double bottom = h.bottom, top = h.top;
        if (current != null && (current < bottom || current > top)) {
            double margin = (Math.max(top, current) - Math.min(bottom, current)) * 0.06;
            if (current < bottom) bottom = current - margin; else top = current + margin;
        }
        float small = fs * 0.8f, edge = 1.6f * u;
        float labelW = 0;
        for (Object[] tick : h.ticks) labelW = Math.max(labelW, width((String) tick[1], small, regular));
        float px = panel.left + edge + labelW + (labelW > 0 ? u : 0), pw = panel.right - 2 * u - px;
        float py = panel.top + 2.2f * u, ph = panel.bottom - (small * 1.2f + 1.6f * u) - py;
        if (pw < 40 || ph < 24) return null;
        RectF plot = new RectF(px, py, px + pw, py + ph);
        for (Object[] tick : h.ticks) {
            float ty = y((Double) tick[0], bottom, top, plot);
            fill.setColor(Style.LINE);
            c.drawRect(plot.left, ty, plot.right, ty + 1, fill);
            put(c, (String) tick[1], panel.left + edge + labelW, ty + small * 0.35f, small, Style.DIM, regular, Paint.Align.RIGHT);
        }
        // Through the middle of each part, from the left edge when the range begins with a value, to "Now" at the
        // right; the highest and lowest moments on the line where their rings are.
        float[] xs = new float[PARTS + 4], ys = new float[PARTS + 4];
        int n = 0;
        for (int i = 0; i < PARTS; i++) {
            if (!h.has[i]) continue;
            float yy = y(h.values[i], bottom, top, plot);
            if (i == 0) { xs[n] = plot.left; ys[n++] = yy; }
            xs[n] = plot.left + (i + 0.5f) / PARTS * (pw - 1);
            ys[n++] = yy;
        }
        if (n > 0 && current != null) { xs[n] = plot.right - 1; ys[n++] = y(current, bottom, top, plot); }
        float highX = at(highAt, h, plot), lowX = at(lowAt, h, plot);
        if (n > 0) {
            float snap = (pw - 1) / (2f * PARTS);
            int[] count = {n};
            int kept = -1;
            if (!Double.isNaN(high)) {
                kept = through(xs, ys, count, highX, y(high, bottom, top, plot), snap, -1);
                if (kept >= 0) highX = xs[kept];
            }
            if (!Double.isNaN(low)) {
                int a = through(xs, ys, count, lowX, y(low, bottom, top, plot), snap, kept);
                if (a >= 0) lowX = xs[a];
            }
            n = count[0];
        }
        if (n >= 2) {
            float[][] pts = monotone(xs, ys, n, 6);
            Path area = new Path(), stroke = new Path();
            area.moveTo(pts[0][0], plot.bottom);
            for (int i = 0; i < pts.length; i++) {
                area.lineTo(pts[i][0], pts[i][1]);
                if (i == 0) stroke.moveTo(pts[i][0], pts[i][1]); else stroke.lineTo(pts[i][0], pts[i][1]);
            }
            area.lineTo(pts[pts.length - 1][0], plot.bottom);
            fill.setColor((accent & 0xFFFFFF) | 0x33000000);
            c.drawPath(area, fill);
            line.setColor(accent);
            line.setStrokeWidth(Math.max(2, 0.5f * u));
            c.drawPath(stroke, line);
        }
        // The highest and lowest moment as rings with their values; the value now as a dot at "Now".
        float ring = 2 * u;
        if (!Double.isNaN(high)) marker(c, highX, y(high, bottom, top, plot), ring, number(high, h.decimals, ""), true, plot, fs, accent);
        if (!Double.isNaN(low) && low != high) marker(c, lowX, y(low, bottom, top, plot), ring, number(low, h.decimals, ""), false, plot, fs, accent);
        if (current != null) {
            float cy = y(current, bottom, top, plot);
            fill.setColor(Style.CARD);
            c.drawCircle(plot.right - 1, cy, ring * 0.6f + 2, fill);
            fill.setColor(accent);
            c.drawCircle(plot.right - 1, cy, ring * 0.6f, fill);
        }
        times(c, h, plot.left, pw, plot.bottom + 1.2f * u, fs, u, h24);
        return plot;
    }

    private void marker(Canvas c, float x, float y, float ring, String words, boolean high, RectF plot, float fs, int accent) {
        fill.setColor(Style.CARD);
        c.drawCircle(x, y, ring / 2, fill);
        line.setColor(accent);
        line.setStrokeWidth(Math.max(2, ring * 0.22f));
        c.drawCircle(x, y, ring / 2, line);
        float size = fs * 0.8f, tw = width(words, size, medium);
        float ly = high ? y - ring / 2 - size * 0.35f : y + ring / 2 + size * 1.05f;
        if (ly - size < plot.top - fs) ly = y + ring / 2 + size * 1.05f;
        if (ly > plot.bottom + fs * 0.6f) ly = y - ring / 2 - size * 0.35f;
        float lx = Math.max(plot.left + tw / 2, Math.min(x, plot.right - tw / 2));
        put(c, words, lx, ly, size, Style.TEXT, medium, Paint.Align.CENTER);
    }

    private static float y(double v, double bottom, double top, RectF plot) {
        double cl = Math.max(bottom, Math.min(top, v));
        return (float) (plot.bottom - (cl - bottom) / (top - bottom) * plot.height());
    }

    private static float at(long epoch, Tessera.History h, RectF plot) {
        long a = Math.max(h.start, Math.min(h.end, epoch));
        return plot.left + (float) (a - h.start) / Math.max(1, h.end - h.start) * (plot.width() - 1);
    }

    /** history_view::through: a moment takes the nearest point within `snap`, else it is put in its place. */
    private static int through(float[] xs, float[] ys, int[] n, float x, float y, float snap, int keep) {
        int nearest = -1;
        for (int i = 0; i < n[0]; i++) {
            float d = Math.abs(xs[i] - x);
            if (i != keep && d <= snap && (nearest < 0 || d < Math.abs(xs[nearest] - x))) nearest = i;
        }
        if (nearest >= 0) {
            ys[nearest] = y;
            return nearest;
        }
        if (n[0] >= xs.length) return -1;
        int at = n[0];
        while (at > 0 && xs[at - 1] > x) {
            xs[at] = xs[at - 1];
            ys[at] = ys[at - 1];
            at--;
        }
        xs[at] = x;
        ys[at] = y;
        n[0]++;
        return at;
    }

    /** history_view::monotone: a smooth line through the points that never overshoots them (Fritsch-Carlson). */
    static float[][] monotone(float[] xs, float[] ys, int n, int steps) {
        float[] slope = new float[n], tangent = new float[n];
        for (int i = 0; i + 1 < n; i++) {
            float dx = xs[i + 1] - xs[i];
            slope[i] = dx > 0 ? (ys[i + 1] - ys[i]) / dx : 0;
        }
        tangent[0] = slope[0];
        tangent[n - 1] = slope[n - 2];
        for (int i = 1; i + 1 < n; i++) tangent[i] = slope[i - 1] * slope[i] <= 0 ? 0 : (slope[i - 1] + slope[i]) / 2;
        for (int i = 0; i + 1 < n; i++) {
            if (slope[i] == 0) {
                tangent[i] = tangent[i + 1] = 0;
                continue;
            }
            float a = tangent[i] / slope[i], b = tangent[i + 1] / slope[i], sum = a * a + b * b;
            if (sum > 9) {
                float scale = 3 / (float) Math.sqrt(sum);
                tangent[i] = scale * a * slope[i];
                tangent[i + 1] = scale * b * slope[i];
            }
        }
        float[][] out = new float[(n - 1) * steps + 1][];
        int k = 0;
        for (int i = 0; i + 1 < n; i++) {
            float hh = xs[i + 1] - xs[i];
            for (int s = 0; s < steps; s++) {
                float t = (float) s / steps, t2 = t * t, t3 = t2 * t;
                float yy = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * hh * tangent[i] + (-2 * t3 + 3 * t2) * ys[i + 1]
                        + (t3 - t2) * hh * tangent[i + 1];
                out[k++] = new float[]{xs[i] + t * hh, yy};
            }
        }
        out[k] = new float[]{xs[n - 1], ys[n - 1]};
        return out;
    }

    /** The timeline: a bar of runs coloured by state, the times under it and the legend with each state's time. */
    private RectF drawTimeline(Canvas c, Tessera.History h, RectF panel, float fs, float u, boolean h24) {
        float edge = 2 * u, small = fs * 0.85f, rowH = small * 1.8f, square = small * 0.8f;
        float bx = panel.left + edge, bw = panel.width() - 2 * edge, bh = Math.min(6 * u, panel.height() * 0.3f);
        float above = bh + u + small * 1.2f + 4 * u;
        int rows = Math.max(1, Math.min((h.states.size() + 1) / 2, (int) ((panel.height() - 2 * edge - above) / rowH)));
        float block = above + rows * rowH;
        float by = panel.top + Math.max(edge, (panel.height() - block) / 2);
        RectF bar = new RectF(bx, by, bx + bw, by + bh);
        for (int i = 0; i < h.runs.size(); i++) {
            long[] run = h.runs.get(i);
            int state = (int) run[1];
            fill.setColor(state < 0 || state >= h.states.size() ? Style.RAISED : shade((Integer) h.states.get(state)[1]));
            float x1 = bx + bw * run[0] / h.slots, x2 = bx + bw * runEnd(h, i) / h.slots;
            c.drawRect(x1, by, Math.max(x1 + 1, x2), by + bh, fill);
        }
        times(c, h, bx, bw, by + bh + u, fs, u, h24);
        // The legend is read, so it keeps a hand's width in the middle (overlay_card::reach), in two columns.
        float listW = Math.min(bw, 90 * u), listX = bx + (bw - listW) / 2, gutter = 4 * u, colW = (listW - gutter) / 2;
        float top = by + above + small;
        int shown = Math.min(h.states.size(), rows * 2);
        for (int i = 0; i < shown; i++) {
            Object[] st = h.states.get(i);
            float lx = listX + (i % 2) * (colW + gutter), ly = top + (i / 2) * rowH;
            fill.setColor(shade((Integer) st[1]));
            float sy = ly - small * 0.36f;
            c.drawRoundRect(new RectF(lx, sy - square / 2, lx + square, sy + square / 2), u * 0.5f, u * 0.5f, fill);
            String time = duration((Long) st[2]);
            float tw = width(time, small, regular);
            put(c, fit((String) st[0], colW - square - tw - 4 * u, small, regular), lx + square + u, ly, small, Style.TEXT, regular, Paint.Align.LEFT);
            put(c, time, lx + colW, ly, small, Style.DIM, regular, Paint.Align.RIGHT);
        }
        return bar;
    }

    /** A state's colour as the add-on sends it; the greys of "off" and "no data" darker on a dark card (theme::state). */
    private static int shade(int rgb) {
        int c = 0xFF000000 | rgb;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        boolean grey = Math.abs(r - g) < 12 && Math.abs(g - b) < 12;
        if (grey && Style.BG < 0xFF808080) return 0xFF000000 | ((r * 45 / 100) << 16) | ((g * 45 / 100) << 8) | (b * 45 / 100);
        return c;
    }
}
