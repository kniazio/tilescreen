package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.Json;

/**
 * The screensaver clock's faces, a port of Tessera's module_clock_faces.cpp (docs/SCREENSAVER.md): Glass, Classic and
 * Bauhaus, in the same proportions, colours (theme.h) and rules. Tessera sends the choice as `cf` in the clock saver
 * ({f: face, n: numerals 0/4/12, s: seconds hand 1/0, l: dark/light/auto}) to screens whose hello lists clock_faces.
 * The letters are Android's own, sized to the same room the firmware gives its font steps.
 */
final class ClockFaces {
    static final int DIGITAL = 0, GLASS = 1, CLASSIC = 2, BAUHAUS = 3;

    /** The face Tessera chose, as page_receiver.cpp reads `cf`. */
    static final class Face {
        int kind = DIGITAL, numerals = 4;
        boolean seconds = true;
        String look = "dark";

        static Face of(Map<String, Object> saver) {
            Face f = new Face();
            Map<String, Object> cf = saver == null ? null : Json.obj(saver.get("cf"));
            if (cf == null) return f;
            String k = nz(Json.str(cf, "f"));
            f.kind = k.equals("glass") ? GLASS : k.equals("classic") ? CLASSIC : k.equals("bauhaus") ? BAUHAUS : DIGITAL;
            Integer n = Json.integer(cf, "n");
            f.numerals = n == null ? 4 : n == 0 || n == 12 ? n : 4;
            Integer s = Json.integer(cf, "s");
            f.seconds = s == null || s != 0;
            String l = nz(Json.str(cf, "l"));
            f.look = l.equals("light") || l.equals("auto") ? l : "dark";
            return f;
        }
    }

    private static final float PI = (float) Math.PI;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    ClockFaces(TextPaint glyphs) {
        this.glyphs = glyphs;
        line.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
    }

    // ---- colours: theme.h's roles {light, dark}
    private static int mix(int a, int b, int weight) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int v = (((a >> shift) & 255) * weight + ((b >> shift) & 255) * (255 - weight)) / 255;
            out |= v << shift;
        }
        return out;
    }

    private static final class Ink {
        boolean dark;
        int face, ink, soft, tick, minor, hand, edge, accent, raised;
    }

    private static Ink ink(boolean dark) {
        Ink k = new Ink();
        k.dark = dark;
        k.face = dark ? 0x000000 : 0xE7E7E7;          // CAMERA_PAGE dark, PAGE light
        k.ink = dark ? 0xDADADA : 0x1B1B1B;           // INK
        k.soft = mix(k.ink, k.face, 150);
        k.tick = mix(k.ink, k.face, dark ? 225 : 235);
        k.minor = mix(k.ink, k.face, dark ? 120 : 110);
        k.hand = 0xFFFFFF;                            // CLOCK_HAND
        k.edge = dark ? k.face : mix(k.ink, k.face, 200);
        k.accent = 0xFF9800;                          // ha::ORANGE
        k.raised = dark ? 0x1A1A1A : 0xFFFFFF;        // CARD
        return k;
    }

    // ---- drawing helpers
    private void stroke(Canvas c, float x1, float y1, float x2, float y2, float w, int color, boolean round) {
        line.setStrokeWidth(Math.max(1, w));
        line.setStrokeCap(round ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        line.setColor(0xFF000000 | color);
        c.drawLine(x1, y1, x2, y2, line);
    }

    private void dot(Canvas c, float x, float y, float r, int color) {
        fill.setColor(0xFF000000 | color);
        c.drawCircle(x, y, Math.max(1, r), fill);
    }

    private void box(Canvas c, float x, float y, float w, float h, int color, boolean round) {
        fill.setColor(0xFF000000 | color);
        if (round) c.drawRoundRect(new RectF(x, y, x + w, y + h), w / 2, h / 2, fill);
        else c.drawRect(x, y, x + w, y + h, fill);
    }

    /** Digits' visible height for a text size (Roboto's figures stand about 0.71 of the size). */
    private static float digitsH(float size) { return size * 0.71f; }
    private static float lineH(float size) { return size * 1.17f; }

    /** The largest size whose `s` fits w and whose digits are at most h tall. */
    private float largest(String s, float w, float h) {
        float size = h / 0.71f;
        text.setTextSize(size);
        float tw = text.measureText(s);
        if (tw > w) size *= w / tw;
        return Math.max(8, size);
    }

    /** The first of these sizes in which every text fits w. */
    private float fitting(float[] sizes, List<String> texts, float w, Typeface face) {
        text.setTypeface(face);
        for (float size : sizes) {
            text.setTextSize(size);
            boolean ok = true;
            for (String t : texts) ok = ok && text.measureText(t) <= w;
            if (ok) return size;
        }
        return sizes[sizes.length - 1];
    }

    private float width(String s, float size, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        return text.measureText(s);
    }

    /** Digits centred on (cx, cy). */
    private void digitsMid(Canvas c, String s, float cx, float cy, float size, int color) {
        text.setTypeface(regular);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF000000 | color);
        c.drawText(s, cx, cy + digitsH(size) / 2, text);
    }

    /** Words with their line box's top at y. */
    private void words(Canvas c, String s, float x, float top, float size, int color, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFF000000 | color);
        c.drawText(s, x, top + (lineH(size) + digitsH(size)) / 2, text);
    }

    // ---- hands
    private static float hourAngle(Calendar t) { return (t.get(Calendar.HOUR) + t.get(Calendar.MINUTE) / 60f) * PI / 6; }
    private static float minuteAngle(Calendar t) { return t.get(Calendar.MINUTE) * PI / 30; }
    private static float secondAngle(int s) { return s * PI / 30; }

    private static final class Hands {
        float cx, cy, hour, minute, second, tail, mm;
        int blade, stem, sec, hand, edge, accent, face;
        boolean rim, seconds;
    }

    private void secondHand(Canvas c, Hands h, int second) {
        float a = secondAngle(second), dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        stroke(c, h.cx - dx * h.tail, h.cy - dy * h.tail, h.cx + dx * h.second, h.cy + dy * h.second, h.sec, h.accent, true);
        dot(c, h.cx, h.cy, h.blade * 0.42f, h.accent);
        dot(c, h.cx, h.cy, Math.max(1f, h.blade * 0.16f), h.face);
    }

    private void hand(Canvas c, Hands h, float a, float len) {
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a), stem = Math.max(2 * h.mm, len * 0.16f);
        if (h.rim) {
            int rim = Math.max(1, h.blade / 7);
            stroke(c, h.cx, h.cy, h.cx + dx * stem, h.cy + dy * stem, h.stem + 2 * rim, h.edge, true);
            stroke(c, h.cx + dx * stem, h.cy + dy * stem, h.cx + dx * len, h.cy + dy * len, h.blade + 2 * rim, h.edge, true);
        }
        stroke(c, h.cx, h.cy, h.cx + dx * stem, h.cy + dy * stem, h.stem, h.hand, true);
        stroke(c, h.cx + dx * stem, h.cy + dy * stem, h.cx + dx * len, h.cy + dy * len, h.blade, h.hand, true);
    }

    private void hands(Canvas c, Hands h, Calendar t) {
        hand(c, h, hourAngle(t), h.hour);
        hand(c, h, minuteAngle(t), h.minute);
        float r = h.blade * 0.62f;
        if (h.rim) dot(c, h.cx, h.cy, r + Math.max(1, h.blade / 7), h.edge);
        dot(c, h.cx, h.cy, r, h.hand);
        if (h.seconds) secondHand(c, h, t.get(Calendar.SECOND));
    }

    // ---- the face on the glass
    /**
     * Draws the whole face: `mm` is a millimetre of glass in pixels, `screenDark` the screen's Dark mode (for the
     * auto look), `lines` the clock's row {icon glyph or null, text}.
     */
    void draw(Canvas c, Face f, int W, int H, float mm, boolean screenDark, List<String[]> lines) {
        boolean dark = f.look.equals("dark") || (f.look.equals("auto") && screenDark);
        Ink k = ink(dark);
        Calendar now = Calendar.getInstance();
        c.drawColor(0xFF000000 | k.face);
        c.save();
        if (f.kind != BAUHAUS) {
            // Glass and Classic stand a fraction further along a slow circle every minute (saver_view::drift).
            int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            float a = minute * 0.7f;
            c.translate(Math.round(Math.cos(a) * mm), Math.round(Math.sin(a) * mm));
        }
        if (f.kind == GLASS) glass(c, f, k, W, H, mm, now);
        else if (f.kind == CLASSIC) classic(c, f, k, W, H, mm, now, lines);
        else bauhaus(c, f, k, W, H, now);
        c.restore();
    }

    // ---- Glass: the dial is the glass, the ticks along its edge.
    private static float ringEdge(float theta, float hx, float hy) {
        float dx = (float) Math.sin(theta), dy = (float) -Math.cos(theta);
        float a = Math.abs(dx) > 1e-6f ? hx / Math.abs(dx) : Float.MAX_VALUE;
        float b = Math.abs(dy) > 1e-6f ? hy / Math.abs(dy) : Float.MAX_VALUE;
        return Math.min(a, b);
    }

    private static boolean touches(List<float[]> ticks, float l, float t, float r, float b, float air) {
        for (float[] s : ticks) {
            for (int i = 0; i <= 12; i++) {
                float x = s[0] + (s[2] - s[0]) * i / 12, y = s[1] + (s[3] - s[1]) * i / 12, g = air + s[4] / 2;
                if (x >= l - g && x <= r + g && y >= t - g && y <= b + g) return true;
            }
        }
        return false;
    }

    private void glass(Canvas c, Face f, Ink k, int W, int H, float mm, Calendar now) {
        int S = Math.min(W, H);
        float cx = W / 2f, cy = H / 2f;
        float m = Math.max(2 * mm, S * 0.03f), hx = cx - m, hy = cy - m;
        float major = S * 0.075f, minor = S * 0.034f;
        int wmaj = Math.max(3, Math.round(S * 0.0095f)) | 1, wmin = Math.max(1, Math.round(S * 0.0042f));
        int numerals = f.numerals;
        // Twelve numerals only on glass about as wide as it is tall (the editor offers them only there).
        if (numerals == 12 && Math.max(W, H) / (float) Math.min(W, H) > 1.25f) numerals = 4;
        float nf = numerals == 0 ? 0 : largest("12", W, S * (numerals == 4 ? 0.13f : 0.085f));
        List<float[]> ticks = new ArrayList<float[]>();
        for (int i = 0; i < 60; i++) {
            float a = i * PI / 30, dx = (float) Math.sin(a), dy = (float) -Math.cos(a), e = ringEdge(a, hx, hy);
            boolean hour = i % 5 == 0;
            int n = i / 5 == 0 ? 12 : i / 5;
            if (hour && nf > 0 && (numerals == 12 || n % 3 == 0)) continue;  // the numeral stands in its tick's place
            float len = hour ? major : minor;
            float[] s = {cx + dx * e, cy + dy * e, cx + dx * (e - len), cy + dy * (e - len), hour ? wmaj : wmin};
            ticks.add(s);
            stroke(c, s[0], s[1], s[2], s[3], s[4], hour ? k.tick : k.minor, false);
        }
        float air = Math.max(mm, S * 0.012f);
        if (nf > 0) {
            for (int n = 1; n <= 12; n++) {
                if (numerals == 4 && n % 3 != 0) continue;
                float a = n * PI / 6, dx = (float) Math.sin(a), dy = (float) -Math.cos(a), e = ringEdge(a, hx, hy);
                String s = Integer.toString(n);
                float w = width(s, nf, regular), h = digitsH(nf);
                float d = e - minor - air - (Math.abs(dx) * w / 2f + Math.abs(dy) * h / 2f);
                for (; d > 0; d -= 1) {
                    float x = cx + dx * d, y = cy + dy * d;
                    if (!touches(ticks, x - w / 2, y - h / 2, x + w / 2, y + h / 2, air)) break;
                }
                digitsMid(c, s, Math.round(cx + dx * d), Math.round(cy + dy * d), nf, k.ink);
            }
        }
        float inner = Math.min(hx, hy);
        int blade = Math.max(6, Math.round(S * 0.036f));
        Hands h = new Hands();
        h.cx = cx; h.cy = cy; h.hour = inner * 0.62f; h.minute = inner - major * 0.35f; h.second = inner - minor * 0.4f;
        h.tail = S * 0.06f; h.blade = blade; h.stem = Math.max(3, blade / 3); h.sec = Math.max(2, Math.round(S * 0.0055f));
        h.hand = k.hand; h.edge = k.edge; h.accent = k.accent; h.face = k.face; h.rim = !k.dark; h.seconds = f.seconds; h.mm = mm;
        hands(c, h, now);
    }

    // ---- Classic: a round dial, and the day beside it (long glass) or under it (standing glass); on square glass the
    // date in a window at 3.
    private void dial(Canvas c, Face f, Ink k, float cx, float cy, float R, boolean window, float mm, Calendar now) {
        box(c, cx - R, cy - R, 2 * R, 2 * R, k.raised, true);
        float ro = R * 0.94f, major = R * 0.09f, minor = R * 0.045f;
        int wmaj = Math.max(3, Math.round(R * 0.022f)) | 1, wmin = Math.max(1, Math.round(R * 0.008f));
        for (int i = 0; i < 60; i++) {
            float a = i * PI / 30, dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            boolean hour = i % 5 == 0;
            float len = hour ? major : minor;
            stroke(c, cx + dx * ro, cy + dy * ro, cx + dx * (ro - len), cy + dy * (ro - len), hour ? wmaj : wmin, hour ? k.tick : k.minor, false);
        }
        float nf = largest("12", 2 * R, R * 0.15f), inset = ro - major - R * 0.07f;
        for (int n = 1; n <= 12; n++) {
            if (window && n == 3) continue;
            float a = n * PI / 6, dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            String s = Integer.toString(n);
            float d = inset - (Math.abs(dx) * width(s, nf, regular) / 2f + Math.abs(dy) * digitsH(nf) / 2f);
            digitsMid(c, s, Math.round(cx + dx * d), Math.round(cy + dy * d), nf, k.ink);
        }
        if (window) {
            // The weekday and the day in a framed box in the place of 3, the day in the accent.
            String day = new SimpleDateFormat("EEE", Locale.getDefault()).format(now.getTime());
            String number = Integer.toString(now.get(Calendar.DAY_OF_MONTH));
            float label = Math.max(10, R * 0.085f), pad = mm + 2, space = width(" ", label, regular);
            float dw = width(day, label, regular), nw = width(number, label, regular);
            float ww = dw + space + nw + 2 * pad + 4, wh = lineH(label) + pad;
            float x = cx + inset - ww, y = cy - wh / 2;
            box(c, x, y, ww, wh, k.face, false);
            line.setStrokeWidth(Math.max(1, wmin));
            line.setColor(0xFF000000 | k.minor);
            c.drawRect(x, y, x + ww, y + wh, line);
            words(c, day, x + pad, y + (wh - lineH(label)) / 2, label, k.soft, regular);
            words(c, number, x + pad + dw + space, y + (wh - lineH(label)) / 2, label, k.accent, regular);
        }
        int blade = Math.max(5, Math.round(R * 0.055f));
        Hands h = new Hands();
        h.cx = cx; h.cy = cy; h.hour = R * 0.48f; h.minute = R * 0.78f; h.second = R * 0.86f; h.tail = R * 0.14f;
        h.blade = blade; h.stem = Math.max(3, blade / 3); h.sec = Math.max(2, Math.round(R * 0.011f));
        h.hand = k.hand; h.edge = k.edge; h.accent = k.accent; h.face = k.face; h.rim = !k.dark; h.seconds = f.seconds; h.mm = mm;
        hands(c, h, now);
    }

    /** The day's block: its sizes and how many of the row's lines it takes (plan_day). */
    private static final class Day {
        float big, word, value, width, height;
        int lines;
    }

    private boolean withIcons(List<String[]> lines) {
        for (String[] l : lines) if (l[0] != null) return true;
        return false;
    }

    private float lineW(String[] l, float value, float iconSize, boolean icons) {
        return (icons ? iconSize + 0 : 0) + width(l[1], value, regular);
    }

    private Day planDay(String weekday, String month, String number, List<String[]> lines, float w, float h, float dialD,
                        boolean stacked, float mm) {
        Day d = new Day();
        float g = 2 * mm, gap = mm, icon = 4.6f * mm;
        float[] fonts = {5.5f * mm, 4.2f * mm, 3.2f * mm};
        List<String> pair = new ArrayList<String>();
        pair.add(weekday);
        pair.add(month);
        d.word = fitting(fonts, pair, stacked ? w : w / 2, regular);
        List<String> texts = new ArrayList<String>();
        for (String[] l : lines) texts.add(l[1]);
        d.value = fitting(fonts, texts, Math.max(1, w - (icon + 2 * mm)), regular);
        float rowH = Math.max(icon, lineH(d.value)), wordsH = lineH(d.word);
        float wordW = Math.max(width(weekday, d.word, regular), width(month, d.word, regular));
        for (d.lines = lines.size(); d.lines >= 0; --d.lines) {
            float rows = d.lines > 0 ? 2 * g + d.lines * rowH + (d.lines - 1) * gap : 0;
            float room = Math.min(h - (stacked ? 2 * wordsH + 2 * g : 0) - rows, dialD * 0.3f);
            float side = stacked ? w : w - wordW - 4 * mm;
            d.big = largest(number, side, Math.max(1, room));
            if (room > 8 && digitsH(d.big) <= room) break;
        }
        d.lines = Math.max(0, d.lines);
        float dh = digitsH(d.big);
        float widest = stacked ? Math.max(width(number, d.big, regular), wordW) : width(number, d.big, regular) + 4 * mm + wordW;
        boolean icons = withIcons(lines);
        for (int i = 0; i < d.lines; i++) widest = Math.max(widest, lineW(lines.get(i), d.value, icon + 2 * mm, icons));
        d.width = Math.min(w, widest);
        d.height = (stacked ? 2 * wordsH + 2 * g + dh : Math.max(dh, 2 * wordsH))
                + (d.lines > 0 ? 2 * g + d.lines * rowH + (d.lines - 1) * gap : 0);
        return d;
    }

    private void drawDay(Canvas c, Ink k, Day d, String weekday, String month, String number, List<String[]> lines,
                         float x, float y, boolean stacked, float mm) {
        float g = 2 * mm, gap = mm, wordsH = lineH(d.word), dh = digitsH(d.big), icon = 4.6f * mm;
        float yy = y;
        text.setTextAlign(Paint.Align.LEFT);
        if (stacked) {
            words(c, weekday, x, yy, d.word, k.accent, regular);
            yy += wordsH + g;
            text.setTypeface(regular);
            text.setTextSize(d.big);
            text.setColor(0xFF000000 | k.ink);
            c.drawText(number, x, yy + dh, text);
            yy += dh + g;
            words(c, month, x, yy, d.word, k.soft, regular);
            yy += wordsH;
        } else {
            float nw = width(number, d.big, regular);
            float side = Math.max(width(weekday, d.word, regular), width(month, d.word, regular));
            float group = nw + 4 * mm + side, gx = x + (d.width - group) / 2, rh = Math.max(dh, 2 * wordsH);
            text.setTypeface(regular);
            text.setTextSize(d.big);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(0xFF000000 | k.ink);
            c.drawText(number, gx, yy + (rh - dh) / 2 + dh, text);
            words(c, weekday, gx + nw + 4 * mm, yy + rh / 2 - wordsH, d.word, k.accent, regular);
            words(c, month, gx + nw + 4 * mm, yy + rh / 2, d.word, k.soft, regular);
            yy += rh;
        }
        if (d.lines == 0) return;
        yy += 2 * g;
        boolean icons = withIcons(lines);
        float col = d.width;
        if (!stacked) {
            col = 0;
            for (int i = 0; i < d.lines; i++) col = Math.max(col, lineW(lines.get(i), d.value, icon + 2 * mm, icons));
        }
        float lx = stacked ? x : x + (d.width - col) / 2, rowH = Math.max(icon, lineH(d.value));
        glyphs.setTextSize(icon);
        glyphs.setColor(0xFF000000 | k.soft);
        for (int i = 0; i < d.lines; i++) {
            String[] l = lines.get(i);
            float xx = lx + (icons ? icon + 2 * mm : 0);
            if (l[0] != null) c.drawText(l[0], lx + icon / 2, yy + rowH / 2 + icon * 0.36f, glyphs);
            words(c, ellipsize(l[1], d.value, lx + d.width - xx), xx, yy + (rowH - lineH(d.value)) / 2, d.value, k.ink, regular);
            yy += rowH + gap;
        }
    }

    private String ellipsize(String s, float size, float w) {
        text.setTypeface(regular);
        text.setTextSize(size);
        return android.text.TextUtils.ellipsize(s, text, Math.max(1, w), android.text.TextUtils.TruncateAt.END).toString();
    }

    private void classic(Canvas c, Face f, Ink k, int W, int H, float mm, Calendar now, List<String[]> lines) {
        int S = Math.min(W, H);
        float m = Math.max(3 * mm, S * 0.05f);
        boolean side = W >= H * 1.3f, under = H >= W * 1.3f;
        float D = S - 2 * m;
        if (side || under) {
            Date t = now.getTime();
            String weekday = new SimpleDateFormat("EEEE", Locale.getDefault()).format(t);
            String month = new SimpleDateFormat("MMMM", Locale.getDefault()).format(t);
            String number = Integer.toString(now.get(Calendar.DAY_OF_MONTH));
            if (side) {
                float gap = Math.max(m, W * 0.05f), room = W - 2 * m - D - gap;
                Day d = planDay(weekday, month, number, lines, room, H - 2 * m, D, true, mm);
                float x0 = Math.max(m, (W - (D + gap + d.width)) / 2);
                dial(c, f, k, x0 + D / 2f, H / 2f, D / 2f, false, mm, now);
                drawDay(c, k, d, weekday, month, number, lines, x0 + D + gap, (H - d.height) / 2, true, mm);
            } else {
                float gap = Math.max(m, H * 0.04f), room = H - 2 * m - D - gap;
                Day d = planDay(weekday, month, number, lines, W - 2 * m, room, D, false, mm);
                float y0 = Math.max(m, (H - (D + gap + d.height)) / 2);
                dial(c, f, k, W / 2f, y0 + D / 2f, D / 2f, false, mm, now);
                drawDay(c, k, d, weekday, month, number, lines, (W - d.width) / 2, y0 + D + gap, false, mm);
            }
            return;
        }
        dial(c, f, k, W / 2f, H / 2f, D / 2f, true, mm, now);
    }

    // ---- Bauhaus: a grid of squares in the school's colours with a round clock on top. One square takes a new shape
    // every minute, the whole glass a new composition every ten, and the clock then moves near the middle.
    private static int mix32(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private static int unsignedMod(int x, int n) { return (int) ((x & 0xFFFFFFFFL) % n); }

    private int seed;

    private int next() {
        seed = mix32(seed + 0x9e3779b9);
        return seed & 0x7FFF;
    }

    private void bauhaus(Canvas c, Face f, Ink k, int W, int H, Calendar now) {
        boolean dk = k.dark;
        int red = dk ? 0xB23A26 : 0xD9442B, yellow = dk ? 0xC9932F : 0xF2B33D, blue = dk ? 0x284783 : 0x2B4E9B;
        int black = dk ? 0x0B0B0B : 0x1D1D1B, cream = dk ? 0x1A1A19 : 0xF2EBDD;
        int[] colours = {red, yellow, blue, black, cream};
        int hand = dk ? 0xF2EBDD : 0x1D1D1B;
        int S = Math.min(W, H);
        c.drawColor(0xFF000000 | cream);
        int n = 3, cell = Math.max(1, S / n);
        int rows = Math.max(n, (H + cell / 2) / cell), cols = Math.max(n, (W + cell / 2) / cell), cells = rows * cols;
        int hour = now.get(Calendar.HOUR_OF_DAY), minute = now.get(Calendar.MINUTE);
        int period = now.get(Calendar.DAY_OF_YEAR) * 144 + hour * 6 + minute / 10;
        int[] turns = new int[cells];
        for (int m = 1; m <= minute % 10; m++) turns[unsignedMod(mix32(period * 977 + m), cells)]++;
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                seed = mix32(period * 7919 + (r * cols + col) * 131 + (turns[r * cols + col] & 0xFF) * 31337);
                int x = col * W / cols, y = r * H / rows, w = (col + 1) * W / cols - x, h = (r + 1) * H / rows - y, e = Math.min(w, h);
                int ground = colours[next() % 5];
                int fig = colours[next() % 5];
                if (fig == ground) fig = ground == cream ? black : cream;
                box(c, x, y, w, h, ground, false);
                c.save();
                c.clipRect(x, y, x + w, y + h);
                switch (next() % 5) {
                    case 0:  // a circle
                        box(c, x + (w - e) / 2 + e / 8, y + (h - e) / 2 + e / 8, e * 3 / 4, e * 3 / 4, fig, true);
                        break;
                    case 1: {  // a quarter
                        int q = next() % 4;
                        box(c, x + ((q & 1) != 0 ? w - e : -e), y + ((q & 2) != 0 ? h - e : -e), 2 * e, 2 * e, fig, true);
                        break;
                    }
                    case 2: {  // a half
                        boolean up = (next() & 1) != 0;
                        box(c, x + (w - e) / 2, y + (up ? -e / 2 : h - e / 2), e, e, fig, true);
                        break;
                    }
                    case 3:  // half the square
                        box(c, x, y, w / 2, h, fig, false);
                        break;
                    default:  // stripes
                        for (int i = 0; i < 3; i++) box(c, x, y + i * h / 3 + h / 12, w, Math.max(2, h / 6), fig, false);
                        break;
                }
                c.restore();
            }
        }
        double turn = (period & 0xFFFFFFFFL) * 2.39996f;
        float reach = S * 0.05f;
        float cx = W / 2f + (float) Math.cos(turn) * reach, cy = H / 2f + (float) Math.sin(turn) * reach, R = S * 0.36f;
        float ring = Math.max(2, (int) (R * 0.035f));
        box(c, cx - R, cy - R, 2 * R, 2 * R, hand, true);
        box(c, cx - R + ring, cy - R + ring, 2 * (R - ring), 2 * (R - ring), dk ? black : cream, true);
        for (int i = 0; i < 12; i++) {
            float a = i * PI / 6;
            dot(c, cx + (float) Math.sin(a) * R * 0.84f, cy - (float) Math.cos(a) * R * 0.84f, i % 3 != 0 ? R * 0.025f : R * 0.05f,
                    i % 3 != 0 ? hand : red);
        }
        int w = Math.max(6, (int) (R * 0.09f));
        float ha = hourAngle(now), ma = minuteAngle(now);
        stroke(c, cx, cy, cx + (float) Math.sin(ha) * R * 0.48f, cy - (float) Math.cos(ha) * R * 0.48f, w, hand, true);
        stroke(c, cx, cy, cx + (float) Math.sin(ma) * R * 0.72f, cy - (float) Math.cos(ma) * R * 0.72f, w * 2 / 3, hand, true);
        dot(c, cx, cy, w * 0.75f, red);
        if (f.seconds) {
            Hands h = new Hands();
            h.cx = cx; h.cy = cy; h.second = R * 0.78f; h.tail = 0; h.blade = w; h.sec = Math.max(2, w / 4);
            h.accent = red; h.face = red;
            secondHand(c, h, now.get(Calendar.SECOND));
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
