package app.tilescreen.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.tilescreen.core.Json;

/**
 * The energy card (firmware 0.47.0, docs/ENERGY.md, energy_card.h): Home Assistant's live power, as
 * power-flow-card-plus draws it. Solar on top, the grid at the left, the house at the right, the battery below, the
 * house's biggest devices in a column beside the house; a line between every pair that can exchange power, a dot
 * running along each line that carries it (faster for more power), and a ring round the house in the shares of what
 * feeds it. The numbers come from the add-on (energy_flow.payload, the tile's `x`), split as Home Assistant splits them.
 */
final class EnergyCard {
    interface Taps { void circle(RectF r, String entity, String name, String state, String unit); }

    // Home Assistant's energy colours (theme.h ENERGY_*).
    private static final int SOLAR = 0xFFFF9800, GRID = 0xFF488FC2, RETURN = 0xFF8353D1, BATTERY_OUT = 0xFF4DB6AC,
            BATTERY_IN = 0xFFF06292;
    private static final int[] DEVICES = {0xFFD0CC5B, 0xFF964CB5, 0xFFB54C9D, 0xFF5BD0CC};
    private static final float PI = (float) Math.PI;

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final android.content.Context context;
    /** Each line's dot: where it stands (0-1 along its line), and when it last moved. */
    private final Map<String, float[]> dots = new HashMap<String, float[]>();
    private long lastFrame;

    EnergyCard(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** power-flow-card-plus's numbers: W below 1000 without decimals, kW with one, ".0" dropped. */
    static String power(double w) {
        w = Math.abs(w);
        if (Math.round(w) < 1000) return Math.round(w) + " W";
        String v = String.format(Locale.US, "%.1f", Math.round(w / 100) / 10.0);
        if (v.endsWith(".0")) v = v.substring(0, v.length() - 2);
        return v + " kW";
    }

    /** The seconds a dot takes along its line: 6 s for almost nothing, 0.75 s from 2 kW. */
    static float duration(double w) {
        return w > 2000 ? 0.75f : (float) ((w - 0.01) * (0.75 - 6) / (2000 - 0.01) + 6);
    }

    private static double at(List<Object> list, int i) {
        if (list == null || i >= list.size() || !(list.get(i) instanceof Double)) return 0;
        return Math.max(0, (Double) list.get(i));
    }

    /** A line: its points, its colour, its power and its key (so its dot stays its own). */
    private static final class Flow {
        final List<float[]> points = new ArrayList<float[]>();
        int color;
        double watts;
        String key;
        boolean reverse;

        Flow to(float x, float y) {
            points.add(new float[]{x, y});
            return this;
        }

        /** A quarter turn from the last point: round a corner whose centre is (cx, cy), from angle a0 to a1. */
        Flow turn(float cx, float cy, float r, float a0, float a1) {
            for (int i = 1; i <= 8; i++) {
                float a = a0 + (a1 - a0) * i / 8f;
                points.add(new float[]{cx + r * (float) Math.cos(a), cy + r * (float) Math.sin(a)});
            }
            return this;
        }

        float length() {
            float l = 0;
            for (int i = 1; i < points.size(); i++) l += dist(points.get(i - 1), points.get(i));
            return l;
        }

        float[] along(float t) {
            float want = t * length();
            for (int i = 1; i < points.size(); i++) {
                float[] a = points.get(i - 1), b = points.get(i);
                float d = dist(a, b);
                if (want <= d || i == points.size() - 1) {
                    float u = d > 0 ? Math.min(1, want / d) : 0;
                    return new float[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u};
                }
                want -= d;
            }
            return points.get(0);
        }

        private static float dist(float[] a, float[] b) { return (float) Math.hypot(b[0] - a[0], b[1] - a[1]); }
    }

    /** One circle: where, its colour, its icon and the words in and under it. */
    private static final class Circle {
        float x, y, r;
        int color;
        String icon, name, entity = "", state = "", unit = "";
        List<String> lines = new ArrayList<String>();
        List<Integer> lineColors = new ArrayList<Integer>();
        float[] ring;  // the house's ring: shares of {solar, grid, battery}
        boolean above; // the name over the circle (solar, on top)
    }

    /**
     * Draws the card into `box` and says whether a dot moves (so the view draws again soon). `calm`: the tile's
     * "lines" choice, a thicker line and no dot.
     */
    boolean draw(Canvas c, Map<String, Object> x, RectF box, float fs, int ink, int muted, int idle, int card, boolean calm, Taps taps) {
        Integer has = x == null ? null : Json.integer(x, "h");
        if (has == null || has == 0) {
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(muted);
            text.setTextAlign(Paint.Align.CENTER);
            String[] words = {"Add power sensors in", "Home Assistant's Energy settings"};
            for (int i = 0; i < 2; i++) c.drawText(words[i], box.centerX(), box.centerY() + (i - 0.3f) * fs * 1.2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return false;
        }
        boolean solar = (has & 1) != 0, grid = (has & 2) != 0, battery = (has & 4) != 0;
        List<Object> p = Json.arr(x.get("p")), f = Json.arr(x.get("f")), ent = Json.arr(x.get("e")), rd = Json.arr(x.get("u"));
        double solarW = at(p, 0), fromGrid = at(p, 1), toGrid = at(p, 2), fromBattery = at(p, 3), toBattery = at(p, 4), home = at(p, 5);
        double s2h = at(f, 0), s2g = at(f, 1), s2b = at(f, 2), g2h = at(f, 3), g2b = at(f, 4), b2h = at(f, 5), b2g = at(f, 6);
        Double soc = Json.dbl(x, "c");

        // The devices, biggest kept: up to four places, the smallest shared as Other, and what no device measures as
        // Untracked (Home Assistant's sankey).
        List<Object[]> devices = new ArrayList<Object[]>(); // {name, entity, watts, icon, state, unit, rest}
        List<Object> sent = Json.arr(x.get("d"));
        double tracked = 0;
        if (sent != null) for (Object o : sent) {
            Map<String, Object> d = Json.obj(o);
            if (d == null) continue;
            double w = Json.dbl(d, "w") == null ? 0 : Json.dbl(d, "w");
            tracked += w;
            devices.add(new Object[]{nz(Json.str(d, "n")), nz(Json.str(d, "e")), w, Json.str(d, "i"), nz(Json.str(d, "s")), nz(Json.str(d, "u")), false});
        }
        double other = Json.dbl(x, "o") == null ? 0 : Json.dbl(x, "o");
        double untracked = devices.isEmpty() ? 0 : home - tracked - other;
        int places = 4;
        if (devices.size() + (other > 0 ? 1 : 0) + (untracked >= 1 ? 1 : 0) > places) {
            // The biggest keep a place, in the order of the settings; the rest join Other.
            List<Object[]> bySize = new ArrayList<Object[]>(devices);
            java.util.Collections.sort(bySize, new java.util.Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) { return Double.compare((Double) b[2], (Double) a[2]); }
            });
            int keep = Math.max(1, places - 1 - (untracked >= 1 ? 1 : 0));
            List<Object[]> kept = new ArrayList<Object[]>();
            for (Object[] d : devices) if (bySize.indexOf(d) < keep) kept.add(d); else other += (Double) d[2];
            devices = kept;
        }
        if (other > 0) devices.add(new Object[]{"Other", "", other, null, "", "", true});
        if (untracked >= 1) devices.add(new Object[]{"Untracked", "", untracked, null, "", "", true});

        // ---- where everything goes: the sources and the house in a square, the devices in a column beside it.
        float W = box.width(), H = box.height();
        boolean withDevices = !devices.isEmpty() && W > H * 1.3f;
        float mainW = withDevices ? W * 0.72f : W;
        float rows = (solar ? 1 : 0) + 1 + (battery ? 1 : 0);
        float nameH = fs * 1.1f;
        float r = Math.min(mainW / 7.2f, (H - (rows + 1) * nameH) / (rows * 2.5f));
        r = Math.max(fs * 1.6f, Math.min(r, fs * 3.6f));
        float midY = box.top + (solar && !battery ? H * 0.62f : !solar && battery ? H * 0.38f : H * 0.5f);
        float gx = box.left + r + 2, hx = box.left + mainW - r - 2, sx = (gx + hx) / 2;
        float sy = box.top + nameH + r + 2, by = box.bottom - r - nameH - 2;
        float lane = r * 0.45f, bend = r * 0.6f;

        List<Circle> circles = new ArrayList<Circle>();
        Circle house = circle(hx, midY, r, ink, "home", "Home");
        house.lines.add(power(home));
        house.lineColors.add(ink);
        double feed = s2h + g2h + b2h;
        house.ring = feed > 0 ? new float[]{(float) (s2h / feed), (float) (g2h / feed), (float) (b2h / feed)} : null;
        circles.add(house);
        Circle sun = null, net = null, store = null;
        if (solar) {
            sun = circle(sx, sy, r, SOLAR, "solar-power", "Solar");
            sun.above = true;
            sun.lines.add(power(solarW));
            sun.lineColors.add(ink);
            reading(sun, ent, rd, 0);
            circles.add(sun);
        }
        if (grid) {
            net = circle(gx, midY, r, GRID, "transmission-tower", "Grid");
            // Both directions of the grid, the way each one flows.
            if (toGrid > 0) {
                net.lines.add("← " + power(toGrid));
                net.lineColors.add(RETURN);
            }
            if (fromGrid > 0 || toGrid <= 0) {
                net.lines.add("→ " + power(fromGrid));
                net.lineColors.add(GRID);
            }
            reading(net, ent, rd, 1);
            circles.add(net);
        }
        if (battery) {
            store = circle(sx, by, r, BATTERY_OUT, batteryIcon(soc), "Battery");
            if (toBattery > 0) {
                store.lines.add("↓ " + power(toBattery));
                store.lineColors.add(BATTERY_IN);
            }
            if (fromBattery > 0 || toBattery <= 0) {
                store.lines.add("↑ " + power(fromBattery));
                store.lineColors.add(BATTERY_OUT);
            }
            if (soc != null) store.name = Math.round(soc) + "%";
            reading(store, ent, rd, 2);
            circles.add(store);
        }

        // ---- the lines: a straight one where two circles face each other, a quarter turn where a line bends.
        List<Flow> flows = new ArrayList<Flow>();
        if (grid) {
            Flow fl = new Flow().to(gx + r, midY).to(hx - r, midY);
            fl.color = GRID;
            fl.watts = g2h;
            fl.key = "g2h";
            flows.add(fl);
        }
        if (solar) {
            float x0 = sx + lane, y0 = sy + (float) Math.sqrt(Math.max(0, r * r - lane * lane));
            float yTurn = midY - lane;
            Flow fl = new Flow().to(x0, y0).to(x0, yTurn - bend).turn(x0 + bend, yTurn - bend, bend, PI, PI / 2).to(edgeX(hx, midY, yTurn, r, -1), yTurn);
            fl.color = SOLAR;
            fl.watts = s2h;
            fl.key = "s2h";
            flows.add(fl);
            if (grid) {
                float x1 = sx - lane;
                Flow fg = new Flow().to(x1, y0).to(x1, yTurn - bend).turn(x1 - bend, yTurn - bend, bend, 0, PI / 2).to(edgeX(gx, midY, yTurn, r, 1), yTurn);
                fg.color = RETURN;
                fg.watts = s2g;
                fg.key = "s2g";
                flows.add(fg);
            }
            if (battery) {
                Flow fb = new Flow().to(sx, sy + r).to(sx, by - r);
                fb.color = BATTERY_IN;
                fb.watts = s2b;
                fb.key = "s2b";
                flows.add(fb);
            }
        }
        if (battery) {
            float x0 = sx + lane, y0 = by - (float) Math.sqrt(Math.max(0, r * r - lane * lane));
            float yTurn = midY + lane;
            Flow fh = new Flow().to(x0, y0).to(x0, yTurn + bend).turn(x0 + bend, yTurn + bend, bend, PI, PI * 1.5f).to(edgeX(hx, midY, yTurn, r, -1), yTurn);
            fh.color = BATTERY_OUT;
            fh.watts = b2h;
            fh.key = "b2h";
            flows.add(fh);
            if (grid) {
                float x1 = sx - lane;
                // One line between the grid and the battery, its dot running the way the power goes.
                Flow fg = new Flow().to(x1, y0).to(x1, yTurn + bend).turn(x1 - bend, yTurn + bend, bend, 0, -PI / 2).to(edgeX(gx, midY, yTurn, r, 1), yTurn);
                fg.color = b2g > 0 ? RETURN : BATTERY_IN;
                fg.watts = Math.max(b2g, g2b);
                fg.reverse = g2b > b2g;
                fg.key = "bg";
                flows.add(fg);
            }
        }
        // The devices: a column beside the house, a line from the house to each.
        if (withDevices) {
            int n = devices.size();
            float dr = Math.min(r * 0.8f, (H - n * nameH) / (n * 2.3f));
            dr = Math.max(fs * 1.2f, dr);
            float dx = box.right - dr - 2, step = H / n;
            for (int i = 0; i < n; i++) {
                Object[] d = devices.get(i);
                boolean rest = (Boolean) d[6];
                int color = rest ? (Style.BG < 0xFF808080 ? 0xFF707070 : 0xFFBDBDBD) : DEVICES[Math.min(i, 3)];
                float dy = box.top + step * i + (step - nameH) / 2;
                String icon = d[3] != null ? Icons.glyph((String) d[3]) : null;
                Circle dc = circle(dx, dy, dr, color, null, (String) d[0]);
                dc.icon = icon != null ? icon : Icons.sure(context, rest ? ("Untracked".equals(d[0]) ? "home-outline" : "dots-horizontal") : "flash");
                dc.lines.add(power((Double) d[2]));
                dc.lineColors.add(ink);
                dc.entity = (String) d[1];
                dc.state = (String) d[4];
                dc.unit = (String) d[5];
                circles.add(dc);
                float ang = (float) Math.atan2(dy - midY, dx - hx);
                Flow fl = new Flow().to(hx + r * (float) Math.cos(ang), midY + r * (float) Math.sin(ang))
                        .to(dx - dr * (float) Math.cos(ang), dy - dr * (float) Math.sin(ang));
                fl.color = color;
                fl.watts = (Double) d[2];
                fl.key = "d" + i;
                flows.add(fl);
            }
        }

        // ---- paint: the lines, their dots, then the circles over them.
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.5f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean moving = false;
        float thin = Math.max(1.5f, r * 0.035f);
        for (Flow fl : flows) {
            boolean on = fl.watts > 0.5;
            Path path = new Path();
            for (int i = 0; i < fl.points.size(); i++) {
                float[] q = fl.points.get(i);
                if (i == 0) path.moveTo(q[0], q[1]); else path.lineTo(q[0], q[1]);
            }
            line.setColor(on ? fl.color : idle);
            // Calm: a line that carries power grows thicker in two steps up to 2 kW.
            line.setStrokeWidth(calm && on ? thin * (fl.watts >= 1000 ? 3 : 2) : thin);
            c.drawPath(path, line);
            if (!on || calm) {
                dots.remove(fl.key);
                continue;
            }
            float[] dot = dots.get(fl.key);
            if (dot == null) {
                dot = new float[]{0};
                dots.put(fl.key, dot);
            }
            dot[0] = (dot[0] + dt / duration(fl.watts)) % 1f;
            float[] q = fl.along(fl.reverse ? 1 - dot[0] : dot[0]);
            fill.setColor(fl.color);
            c.drawCircle(q[0], q[1], Math.max(2.5f, r * 0.08f), fill);
            moving = true;
        }
        for (final Circle ci : circles) drawCircle(c, ci, fs, ink, muted, card, taps);
        return moving;
    }

    /** Where a horizontal line at y meets the edge of a circle at (cx, cy): side -1 its left, 1 its right. */
    private static float edgeX(float cx, float cy, float y, float r, int side) {
        float dy = Math.abs(y - cy);
        return cx + side * (float) Math.sqrt(Math.max(0, r * r - dy * dy));
    }

    private Circle circle(float x, float y, float r, int color, String icon, String name) {
        Circle ci = new Circle();
        ci.x = x;
        ci.y = y;
        ci.r = r;
        ci.color = color;
        ci.icon = icon == null ? null : Icons.sure(context, icon);
        ci.name = name;
        return ci;
    }

    private static void reading(Circle ci, List<Object> ent, List<Object> rd, int i) {
        if (ent != null && i < ent.size() && ent.get(i) instanceof String) ci.entity = (String) ent.get(i);
        List<Object> r = rd == null || i >= rd.size() ? null : Json.arr(rd.get(i));
        if (r != null && r.size() >= 2) {
            ci.state = String.valueOf(r.get(0));
            ci.unit = String.valueOf(r.get(1));
        }
    }

    private String batteryIcon(Double soc) {
        if (soc == null) return "battery";
        int step = (int) ((soc + 5) / 10);
        if (step <= 0) return "battery-outline";
        if (step >= 10) return "battery";
        return "battery-" + step * 10;
    }

    private void drawCircle(Canvas c, final Circle ci, float fs, int ink, int muted, int card, Taps taps) {
        fill.setColor(card);
        c.drawCircle(ci.x, ci.y, ci.r, fill);
        float ringW = Math.max(2, ci.r * 0.07f);
        line.setStrokeWidth(ringW);
        RectF oval = new RectF(ci.x - ci.r, ci.y - ci.r, ci.x + ci.r, ci.y + ci.r);
        if (ci.ring != null) {
            // The house's ring in the shares of what feeds it, from the top, clockwise.
            int[] colors = {SOLAR, GRID, BATTERY_OUT};
            float start = -90;
            for (int i = 0; i < 3; i++) {
                float sweep = ci.ring[i] * 360;
                if (sweep <= 0) continue;
                line.setColor(colors[i]);
                arc(c, oval, start, sweep);
                start += sweep;
            }
        } else {
            line.setColor(ci.color);
            c.drawCircle(ci.x, ci.y, ci.r, line);
        }
        // The icon, then the numbers, inside; the name under the circle.
        int n = ci.lines.size();
        float size = Math.min(fs, ci.r * (n > 1 ? 0.36f : 0.42f));
        float icon = ci.r * (n > 1 ? 0.42f : 0.5f);
        float block = icon + n * size * 1.1f, top = ci.y - block / 2;
        if (ci.icon != null) {
            glyphs.setColor(ci.ring != null ? ink : ci.color);
            glyphs.setTextSize(icon);
            c.drawText(ci.icon, ci.x, top + icon * 0.85f, glyphs);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(medium);
        // A number is never cut: the size shrinks until the widest line fits inside the circle.
        text.setTextSize(size);
        float widest = 0;
        for (String l : ci.lines) widest = Math.max(widest, text.measureText(l));
        if (widest > ci.r * 1.7f) size *= ci.r * 1.7f / widest;
        text.setTextSize(size);
        for (int i = 0; i < n; i++) {
            text.setColor(ci.lineColors.get(i) == ink ? ink : ci.lineColors.get(i));
            c.drawText(ci.lines.get(i), ci.x, top + icon + (i + 0.85f) * size * 1.1f, text);
        }
        text.setTypeface(regular);
        text.setTextSize(Math.min(fs * 0.85f, ci.r * 0.42f));
        text.setColor(muted);
        float nameY = ci.above ? ci.y - ci.r - text.getTextSize() * 0.4f : ci.y + ci.r + text.getTextSize() * 1.05f;
        c.drawText(fit(ci.name, ci.r * 3.2f), ci.x, nameY, text);
        text.setTextAlign(Paint.Align.LEFT);
        // A circle with a sensor behind it opens its history, as a circle of Home Assistant's live view opens it.
        if (!ci.entity.isEmpty() && taps != null) {
            taps.circle(new RectF(ci.x - ci.r, ci.y - ci.r, ci.x + ci.r, ci.y + ci.r), ci.entity, ci.name, ci.state, ci.unit);
        }
    }

    /** An arc as a path of short lines (the desktop stand-in has no drawArc). */
    private void arc(Canvas c, RectF oval, float start, float sweep) {
        Path p = new Path();
        int steps = Math.max(4, (int) (sweep / 6));
        float cx = oval.centerX(), cy = oval.centerY(), r = oval.width() / 2;
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(start + sweep * i / steps);
            float x = cx + r * (float) Math.cos(a), y = cy + r * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        c.drawPath(p, line);
    }

    private String fit(String s, float w) {
        return TextUtils.ellipsize(s == null ? "" : s, text, Math.max(1, w), TextUtils.TruncateAt.END).toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
