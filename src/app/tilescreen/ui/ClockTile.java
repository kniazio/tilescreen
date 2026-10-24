package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The clock card's displays, after runtime_tiles.h (render_clock, render_calm_dial, render_flip): "dial", a filled
 * disc with four strokes and eight dots (Tessera's default for a new clock); "analog", the classic dial with the time
 * and date or a calendar block beside it; "flip", hours and minutes on two blocks; and "digital", the time over the
 * date. Each follows the card's shape the way the firmware does. Letters are sized to the room, as the firmware picks
 * the largest of its font steps that fits.
 */
final class ClockTile {
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final float PI = (float) Math.PI;
    /** The second hand's red (theme ALARM's foreground). */
    private static final int ALARM = 0xFFE53935;

    ClockTile() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    /** What the card is: its content box, its colours and its shape on the grid. */
    static final class Card {
        RectF box;
        int ink, muted, accent, card, block;
        float fs;
        boolean clock24, wide, full, awake;
        int rows;
    }

    void draw(Canvas c, String display, Card k) {
        Calendar now = Calendar.getInstance();
        if (display.equals("dial")) dial(c, k, now);
        else if (display.equals("flip")) flip(c, k, now);
        else if (display.equals("analog")) analog(c, k, now);
        else digital(c, k, now);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ---- words
    private String time(Calendar now, boolean clock24) {
        return new SimpleDateFormat(clock24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
    }

    private String ampm(Calendar now, boolean clock24) {
        return clock24 ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(now.getTime());
    }

    private static String weekday(Calendar now) { return new SimpleDateFormat("EEEE", Locale.getDefault()).format(now.getTime()); }

    private static String month(Calendar now) { return new SimpleDateFormat("MMM", Locale.getDefault()).format(now.getTime()); }

    private static String dayMonth(Calendar now) { return now.get(Calendar.DAY_OF_MONTH) + " " + month(now); }

    private static String longDate(Calendar now) { return new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.getTime()); }

    // ---- sizes: digits stand about 0.71 of the text size, a line about 1.17
    private static float digits(float size) { return size * 0.71f; }

    private float width(String s, float size, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        return text.measureText(s);
    }

    /** The largest size whose digits are at most h tall and whose `s` is at most w wide. */
    private float fit(String s, float w, float h, Typeface face) {
        float size = Math.max(1, h / 0.71f);
        float tw = width(s, size, face);
        if (tw > w) size *= w / tw;
        return size;
    }

    /** Text whose digits' top stands at y. */
    private void put(Canvas c, String s, float x, float digitsTop, float size, int color, Typeface face, Paint.Align align) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(color);
        c.drawText(s, x, digitsTop + digits(size), text);
    }

    private float px(Card k, float n) { return n * k.fs / 16f; }

    // ---- the time over the date (face_text, place_face_text): the largest time that fits, the date only under a time
    // at least twice its height, the long date first, then the short one.
    private static final class Words {
        float size, small, w, h, gap;
        String date = "";
        boolean ok;
    }

    private Words words(Card k, Calendar now, float roomW, float roomH, boolean wantDate) {
        Words o = new Words();
        String t = time(now, k.clock24), ap = ampm(now, k.clock24);
        o.small = k.fs;
        o.gap = px(k, 9);
        float smallH = digits(o.small), apW = ap.isEmpty() ? 0 : px(k, 4) + width(ap, o.small, regular);
        String[] dates = {longDate(now), dayMonth(now)};
        for (int with = wantDate ? 1 : 0; with >= 0; with--) {
            float h = roomH - (with == 1 ? o.gap + smallH + o.small * 0.25f : 0);
            if (h <= 0) continue;
            // No larger than the board's clock step (the firmware's largest font for a clock card's time).
            float size = Math.min(fit(t, Math.max(1, roomW - apW), h, light), k.fs * 4.6f);
            o.gap = Math.max(px(k, 9), size * 0.12f);
            if (with == 1 && digits(size) + o.gap + smallH + o.small * 0.25f > roomH) {
                size = Math.min(size, (roomH - o.gap - smallH - o.small * 0.25f) / 0.71f);
            }
            if (with == 1 && digits(size) < 2 * smallH) continue;
            if (digits(size) < smallH * 0.9f) continue;
            o.size = size;
            o.h = digits(size) + (with == 1 ? o.gap + smallH : 0);
            o.w = width(t, size, light) + apW;
            o.ok = true;
            if (with == 0) return o;
            for (String d : dates) {
                if (width(d, o.small, regular) <= roomW) {
                    o.date = d;
                    o.w = Math.max(o.w, width(d, o.small, regular));
                    return o;
                }
            }
            // No date fits: the time alone, larger.
            o.ok = false;
        }
        return o;
    }

    private void placeWords(Canvas c, Card k, Words o, Calendar now, float x, float y, float roomW, boolean centred) {
        String t = time(now, k.clock24), ap = ampm(now, k.clock24);
        float tw = width(t, o.size, light), aw = ap.isEmpty() ? 0 : px(k, 4) + width(ap, o.small, regular);
        float tx = centred ? x + (roomW - (tw + aw)) / 2 : x;
        put(c, t, tx, y, o.size, k.ink, light, Paint.Align.LEFT);
        // AM/PM sits on the time's baseline.
        if (!ap.isEmpty()) put(c, ap, tx + tw + px(k, 4), y + digits(o.size) - digits(o.small), o.small, k.muted, regular, Paint.Align.LEFT);
        if (!o.date.isEmpty()) {
            float dw = width(o.date, o.small, regular);
            put(c, o.date, centred ? x + (roomW - dw) / 2 : x, y + digits(o.size) + o.gap, o.small, k.muted, regular, Paint.Align.LEFT);
        }
    }

    // ---- digital
    private void digital(Canvas c, Card k, Calendar now) {
        RectF b = k.box;
        Words o = words(k, now, b.width(), b.height(), true);
        if (!o.ok) return;
        placeWords(c, k, o, now, b.left, b.top + (b.height() - o.h) / 2, b.width(), true);
    }

    // ---- hands
    private static float hourAngle(Calendar t) { return (t.get(Calendar.HOUR) + t.get(Calendar.MINUTE) / 60f) * PI / 6; }

    private static float minuteAngle(Calendar t) { return t.get(Calendar.MINUTE) * PI / 30; }

    private void stroke(Canvas c, float x1, float y1, float x2, float y2, float w, int color) {
        line.setStrokeWidth(Math.max(1, w));
        line.setColor(color);
        c.drawLine(x1, y1, x2, y2, line);
    }

    private void dot(Canvas c, float x, float y, float d, int color) {
        fill.setColor(color);
        c.drawCircle(x, y, Math.max(0.5f, d / 2), fill);
    }

    private void hand(Canvas c, float cx, float cy, float a, float len, float w, int color) {
        stroke(c, cx, cy, cx + len * (float) Math.sin(a), cy - len * (float) Math.cos(a), w, color);
    }

    /** The thin red second hand with a short tail (second_hand), only while the screen is awake. */
    private void secondHand(Canvas c, Card k, Calendar now, float cx, float cy, float r, float w) {
        if (!k.awake) return;
        float a = now.get(Calendar.SECOND) * PI / 30, sx = (float) Math.sin(a), sy = (float) Math.cos(a);
        stroke(c, cx - r * 0.2f * sx, cy + r * 0.2f * sy, cx + r * 0.92f * sx, cy - r * 0.92f * sy, w, ALARM);
    }

    // ---- "dial": the calm face, beside the time on a card wider than tall, above it on a tall card or a page.
    private void dial(Canvas c, Card k, Calendar now) {
        RectF b = k.box;
        float W = b.width(), H = b.height(), gap = px(k, 8), side = Math.min(W, H);
        boolean big = k.full || k.rows > 1;
        float d0 = big ? H * 7 / 8 : H;
        // Plans: {dial, cx, cy, tx, ty, room, centred, words}
        Words beside = null, above = null;
        float[] pb = null, pa = null;
        Words wb = words(k, now, W - d0 - gap, H, true);
        if (wb.ok) {
            float cx = d0 / 2, tx = d0 + gap, room = W - tx;
            float spare = Math.min(Math.max(0, room - wb.w) / 2, gap);
            cx += spare;
            tx += 2 * spare;
            room -= 2 * spare;
            pb = new float[]{d0, cx, H / 2, tx, (H - wb.h) / 2, room, 0};
            beside = wb;
        }
        Words wa = words(k, now, W, H - side * 0.6f - gap, true);
        if (wa.ok) {
            float below = wa.date.isEmpty() ? 0 : wa.small * 0.25f;
            float d = Math.min(W, H - wa.h - below - gap);
            if (big) d = d * 7 / 8;
            float top = (H - d - gap - wa.h - below) / 2;
            pa = new float[]{d, W / 2, top + d / 2, 0, top + d + gap, W, 1};
            above = wa;
        }
        float[] plan;
        Words o;
        if (k.full || H > W * 0.9f) { plan = pa; o = above; }
        else if (k.rows <= 1) {
            plan = pb; o = beside;
            if (plan == null) { plan = pa; o = above; }
        } else {
            boolean first = beside != null && (above == null || beside.size > above.size
                    || (beside.size == above.size && pb[0] >= pa[0]));
            plan = first ? pb : pa;
            o = first ? beside : above;
        }
        float dial, cx, cy;
        if (plan == null || o == null || !o.ok) {
            dial = big ? side * 7 / 8 : side;
            cx = W / 2;
            cy = H / 2;
        } else {
            dial = plan[0];
            cx = plan[1];
            cy = plan[2];
            placeWords(c, k, o, now, b.left + plan[3], b.top + plan[4], plan[5], plan[6] != 0);
        }
        cx += b.left;
        cy += b.top;
        float R = dial / 2f - 1;
        if (R < 4) return;
        // The disc in the ink's colour; its strokes and hour hand in the card's, so it stands out of the card.
        dot(c, cx, cy, 2 * R, k.ink);
        float inset = Math.max(2, R * 0.09f), strokeLen = Math.max(3, R * 0.22f);
        float strokeW = Math.max(2, R * 0.08f), dotD = Math.max(2, R * 0.075f);
        for (int i = 0; i < 12; i++) {
            float a = i * PI / 6, sx = (float) Math.sin(a), sy = (float) Math.cos(a);
            if (i % 3 == 0) {
                stroke(c, cx + (R - inset) * sx, cy - (R - inset) * sy, cx + (R - inset - strokeLen) * sx,
                        cy - (R - inset - strokeLen) * sy, strokeW, k.card);
            } else {
                float r = R - inset - strokeLen * 0.3f;
                dot(c, cx + r * sx, cy - r * sy, dotD, k.muted);
            }
        }
        hand(c, cx, cy, hourAngle(now), R * 0.5f, Math.max(3, R * 0.16f), k.card);
        hand(c, cx, cy, minuteAngle(now), R * 0.78f, Math.max(2, R * 0.11f), k.accent);
        secondHand(c, k, now, cx, cy, R * 0.9f, Math.max(1, R * 0.045f));
        dot(c, cx, cy, Math.max(5, R * 0.26f), k.accent);
    }

    // ---- "analog": the classic dial. A page centres it; a wide card puts the time and date beside it; a single card
    // a calendar block (the weekday over a big day and the short month), or the day and month on a compact card.
    private void analog(Canvas c, Card k, Calendar now) {
        RectF b = k.box;
        float W = b.width(), H = b.height();
        boolean large = k.fs >= 14;
        float dial = Math.min(W, H);
        String t = time(now, k.clock24);
        float timeSize = Math.min(fit(t, W, H * 0.45f, light), k.fs * 2.6f), small = k.fs;
        boolean withDate = timeSize * 1.17f + 2 + small * 1.17f <= H;
        boolean dateFits = true;
        if (!k.full && k.wide) {
            float room = W - dial - px(k, large ? 16 : 8);
            timeSize = Math.min(timeSize, fit(t, Math.max(1, room), H * (withDate ? 0.5f : 0.7f), light));
            float need = Math.max(width(t, timeSize, light), withDate ? width(longDate(now), small, regular) : 0);
            dateFits = room >= need && digits(timeSize) >= small * 0.9f;
            if (!dateFits && room > 0) {
                // The short date instead of the long one before giving up.
                dateFits = room >= Math.max(width(t, timeSize, light), width(dayMonth(now), small, regular)) && digits(timeSize) >= small * 0.9f;
            }
        }
        float calRoom = W - dial - px(k, large ? 10 : 6);
        if (!k.full && !k.wide) {
            float need = large ? Math.max(width(weekday(now), k.fs, regular),
                    width(Integer.toString(now.get(Calendar.DAY_OF_MONTH)), k.fs * 2.2f, light) + 6 + width(month(now), small, regular))
                    : width(dayMonth(now), k.fs * 1.3f, regular);
            dateFits = calRoom >= need;
        }
        float cx = b.left + ((k.full || !dateFits) ? (W - dial) / 2 : 0) + dial / 2, cy = b.top + H / 2;
        float outer = dial / 2 - 1, radius = dial / 2 - px(k, large ? 4 : 2);
        float numeralBox = k.fs * 1.17f + 4, ring = outer - 11 * k.fs / 16;
        boolean numerals = large && ring >= numeralBox + px(k, 2);
        for (int i = 0; i < 12; i++) {
            float a = i * PI / 6, sx = (float) Math.sin(a), sy = (float) Math.cos(a);
            boolean cardinal = i % 3 == 0;
            if (cardinal && numerals) {
                text.setTypeface(regular);
                text.setTextSize(k.fs);
                text.setTextAlign(Paint.Align.CENTER);
                text.setColor(k.ink);
                c.drawText(i == 0 ? "12" : Integer.toString(i), cx + ring * sx, cy - ring * sy + digits(k.fs) / 2, text);
                continue;
            }
            float len = cardinal ? px(k, large ? 9 : 5) : px(k, large ? 5 : 3);
            line.setStrokeCap(Paint.Cap.BUTT);
            stroke(c, cx + outer * sx, cy - outer * sy, cx + (outer - len) * sx, cy - (outer - len) * sy,
                    cardinal ? px(k, large ? 3 : 2) : px(k, large ? 2 : 1), cardinal ? k.ink : k.muted);
            line.setStrokeCap(Paint.Cap.ROUND);
        }
        hand(c, cx, cy, hourAngle(now), radius * 0.52f, px(k, large ? 5 : 3), k.ink);
        hand(c, cx, cy, minuteAngle(now), radius * 0.82f, px(k, large ? 3 : 2), k.ink);
        secondHand(c, k, now, cx, cy, radius, px(k, large ? 2 : 1));
        dot(c, cx, cy, px(k, large ? 8 : 4), k.accent);
        if (k.full || !dateFits) return;
        if (k.wide) {
            float x = b.left + dial + px(k, large ? 16 : 8), room = b.right - x;
            String date = width(longDate(now), small, regular) <= room ? longDate(now) : dayMonth(now);
            float th = timeSize * 1.17f + (withDate ? 2 + small * 1.17f : 0), y = b.top + Math.max(0, (H - th) / 2);
            put(c, t, x + room / 2, y + timeSize * 0.23f, timeSize, k.ink, light, Paint.Align.CENTER);
            if (withDate) put(c, date, x + room / 2, y + timeSize * 1.17f + 2 + small * 0.23f, small, k.muted, regular, Paint.Align.CENTER);
            return;
        }
        float x = b.left + dial + px(k, large ? 10 : 6), room = Math.max(1, b.right - x);
        if (!large) {
            float size = k.fs * 1.3f;
            put(c, dayMonth(now), x + room / 2, b.top + (H - digits(size)) / 2, size, k.ink, regular, Paint.Align.CENTER);
            return;
        }
        // The calendar block: the weekday over a big day with the short month beside it; the weekday goes first when
        // the card is too low for both.
        float daySize = Math.min(k.fs * 2.2f, H * 0.6f), weekH = k.fs * 1.17f;
        boolean withWeekday = weekH + daySize * 1.17f <= H;
        if (!withWeekday) weekH = 0;
        float top = b.top + Math.max(0, (H - weekH - daySize * 1.17f) / 2);
        if (withWeekday) put(c, weekday(now), x + room / 2, top + k.fs * 0.23f, k.fs, k.muted, regular, Paint.Align.CENTER);
        String day = Integer.toString(now.get(Calendar.DAY_OF_MONTH)), mon = month(now);
        float dw = width(day, daySize, light), mw = width(mon, small, regular), sx = x + Math.max(0, (room - (dw + 6 + mw)) / 2);
        float dayTop = top + weekH + daySize * 0.23f;
        put(c, day, sx, dayTop, daySize, k.ink, light, Paint.Align.LEFT);
        // Both on one baseline.
        put(c, mon, sx + dw + 6, dayTop + digits(daySize) - digits(small), small, k.muted, regular, Paint.Align.LEFT);
    }

    // ---- "flip": hours and minutes on two blocks with a seam across each.
    private void flip(Canvas c, Card k, Calendar now) {
        RectF b = k.box;
        float W = b.width(), H = b.height();
        boolean large = k.fs >= 14;
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String hh = k.clock24 ? String.format(Locale.US, "%02d", hour) : Integer.toString(hour % 12 == 0 ? 12 : hour % 12);
        String mm = String.format(Locale.US, "%02d", now.get(Calendar.MINUTE));
        String ap = ampm(now, k.clock24);
        float small = k.fs, sh = digits(small), aw = ap.isEmpty() ? 0 : width(ap, small, regular);
        String weekday = weekday(now), date = dayMonth(now);
        if (k.full || (k.wide && k.rows >= 2)) {
            // The two blocks share the width; one line under them: the day at the left, AM or PM at the right.
            float lineGap = px(k, large ? 12 : 6), gb = Math.max(px(k, 6), W / 36), bw = (W - gb) / 2, air = Math.max(px(k, 6), H / 20);
            float bh = Math.min(H - 2 * air - lineGap - sh, bw * 0.92f);
            if (bh < 10) return;
            float size = fit("88", bw * 0.88f, bh * 0.72f, regular);
            float by = b.top + (H - (bh + lineGap + sh)) / 2;
            for (int i = 0; i < 2; i++) block(c, k, b.left + i * (bw + gb), by, bw, bh, i == 0 ? hh : mm, size, Math.max(px(k, 4), bh / 12), Math.max(1, bh / 60));
            put(c, weekday + " " + date, b.left, by + bh + lineGap, small, k.muted, regular, Paint.Align.LEFT);
            if (!ap.isEmpty()) put(c, ap, b.right, by + bh + lineGap, small, k.muted, regular, Paint.Align.RIGHT);
            return;
        }
        // Side by side or stacked, whichever gives the bigger blocks; a page never stacks.
        float[] row = blocks(k, false, W, H, aw), column = k.full ? null : blocks(k, true, W, H, aw);
        boolean stacked = column != null && (row == null || column[1] > row[1] * 1.1f);
        float[] ch = stacked ? column : row;
        if (ch == null) return;
        float size = ch[0], bh = ch[1], bw = ch[2], gb = ch[3];
        float radius = Math.max(px(k, 4), bh / 9), seam = Math.max(1, bh / 45), gap = px(k, large ? 16 : 8);
        float groupW = (stacked ? bw : 2 * bw + gb) + (aw > 0 ? px(k, 6) + aw : 0), groupH = stacked ? 2 * bh + gb : bh;
        float bold = k.fs * 1.05f, colW = Math.max(width(weekday, bold, medium), width(date, small, regular));
        float colH = digits(bold) + px(k, large ? 9 : 5) + sh;
        boolean side = !stacked && !k.full && groupW + gap + colW <= W && colH <= H;
        String underLine = stacked ? date : weekday + " " + date;
        boolean under = !side && (stacked || k.full) && groupH + gap + sh <= H && width(underLine, small, regular) <= W;
        float bx, by;
        if (side) {
            float spare = Math.max(0, W - (groupW + gap + colW)) / 2;
            bx = b.left + Math.min(spare, gap);
            by = b.top + (H - bh) / 2;
            float tx = bx + groupW + gap + Math.min(spare, gap), ty = b.top + (H - colH) / 2;
            put(c, weekday, tx, ty, bold, k.ink, medium, Paint.Align.LEFT);
            put(c, date, tx, ty + digits(bold) + px(k, large ? 9 : 5), small, k.muted, regular, Paint.Align.LEFT);
        } else {
            float total = groupH + (under ? gap + sh : 0);
            bx = b.left + (W - groupW) / 2;
            by = b.top + (H - total) / 2;
            if (under) put(c, underLine, b.centerX(), by + groupH + gap, small, k.muted, regular, Paint.Align.CENTER);
        }
        for (int i = 0; i < 2; i++) {
            float x = stacked ? bx : bx + i * (bw + gb), y = stacked ? by + i * (bh + gb) : by;
            block(c, k, x, y, bw, bh, i == 0 ? hh : mm, size, radius, seam);
        }
        if (!ap.isEmpty()) {
            float ax = stacked ? bx + bw + px(k, 6) : bx + 2 * bw + gb + px(k, 6);
            put(c, ap, ax, by + (bh - digits(size)) / 2 + digits(size) - sh, small, k.muted, regular, Paint.Align.LEFT);
        }
    }

    /** {size, block height, block width, gap} for blocks side by side or stacked, or null when none fit. */
    private float[] blocks(Card k, boolean stack, float W, float H, float aw) {
        float wantH = stack ? H : k.full ? H * 0.55f : H;
        float h = wantH;
        float g = Math.max(px(k, 3), h / 12);
        if (stack) h = Math.min(h, (H - g) / 2);
        // A block a little wider than tall, its digits at most 72 % of it.
        for (int step = 0; step < 40; step++) {
            float size = h * 0.72f / 0.71f;
            float bw = Math.max(width("88", size, regular) + digits(size) * 0.6f, h * 1.08f);
            float need = (stack ? bw : 2 * bw + g) + (aw > 0 ? px(k, 6) + aw : 0);
            if (need <= W) return new float[]{size, h, bw, g};
            h *= 0.93f;
            g = Math.max(px(k, 3), h / 12);
            if (h < k.fs) return null;
        }
        return null;
    }

    private void block(Canvas c, Card k, float x, float y, float w, float h, String digitsText, float size, float radius, float seam) {
        fill.setColor(k.block);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), radius, radius, fill);
        put(c, digitsText, x + w / 2, y + (h - digits(size)) / 2, size, k.ink, regular, Paint.Align.CENTER);
        fill.setColor(k.card);
        c.drawRect(x, y + h / 2 - seam / 2, x + w, y + h / 2 + seam / 2, fill);
    }
}
