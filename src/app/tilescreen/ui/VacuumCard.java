package app.tilescreen.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.tilescreen.core.Json;
import app.tilescreen.core.Tessera;

/**
 * A robot vacuum's card, after Tessera's (module_vacuum_card.cpp, vacuum_model.h, docs/VACUUM.md): the robot's state and
 * battery over its map, its keys as Home Assistant's own dialog gives them, cleaning per room (vacuum.clean_area: rooms
 * picked in the order of the taps), suction and water, and the robot's routines. What the glass has no room for becomes
 * a key that opens its view of the card; what the robot doesn't have isn't there. Everything beyond the robot's own state
 * comes from the add-on (vacuum_card.py, the tile's `x.vc`; core.vacuum_extras, `x.mode`, `x.fan`, `x.water`).
 */
final class VacuumCard {
    interface Host {
        void tap(RectF r, Runnable run);
        void call(String service, Map<String, String> data, Map<String, String> templates);
        /** The map's picture at exactly w×h on the colour `bg`, or null while it is on its way. */
        Bitmap map(String entity, String mark, int w, int h, int bg, boolean dark);
        /** Whether the add-on said there is no map to be had for the last ask. */
        boolean mapMissing();
    }

    // VacuumEntityFeature (tile_catalogue.h).
    static final int TURN_ON = 1, TURN_OFF = 2, PAUSE = 4, STOP = 8, RETURN_HOME = 16, LOCATE = 512, START = 8192,
            CLEAN_AREA = 16384;
    static final int MAX_ROOMS = 24;

    static final String ICON_ROOMS = "F0FE6", ICON_ROUTINES = "F0411", ICON_HOW = "F0210", ICON_LOCATE = "F034E",
            ICON_PLAY = "F040A", ICON_PAUSE = "F03E4", ICON_STOP = "F04DB", ICON_DOCK = "F05F8", ICON_CHEVRON = "F0142",
            ICON_BACK = "F0141", ICON_ROBOT = "F070D", ICON_WATER = "F058C", ICON_HOME_DOCK = "F02DC";
    private static final int TEAL = 0xFF009688, ACCENT = 0xFF03A9F4, ERROR = 0xFFDB4437;

    // ---- the data (vacuum_model::Data) ----
    static final class Room { String area = "", name = ""; int icon, floor; }
    static final class Routine { String entity = "", name = ""; }
    static final class Choice {
        String kind = "", entity = "", current = "";
        final List<String> values = new ArrayList<String>(), labels = new ArrayList<String>();
        String roles = "";
    }
    static final class Data {
        String mapEntity = "", mapMark = "", error = "";
        final List<Room> rooms = new ArrayList<Room>();
        final List<String> floors = new ArrayList<String>();
        final List<Routine> routines = new ArrayList<Routine>();
        boolean unmapped;
        int progress = -1;
        double area = Double.NaN;
        boolean hasMap() { return !mapEntity.isEmpty() && !mapMark.isEmpty(); }
        boolean byRoom() { return !rooms.isEmpty() || unmapped; }
    }

    static Data parse(Tessera.Tile t) {
        Map<String, Object> vc = Json.obj(t.extra.get("vc"));
        if (vc == null) return null;
        Data d = new Data();
        Map<String, Object> map = Json.obj(vc.get("map"));
        if (map != null) {
            d.mapEntity = nz(Json.str(map, "e"));
            d.mapMark = nz(Json.str(map, "m"));
        }
        List<Object> rooms = Json.arr(vc.get("rooms"));
        if (rooms != null) {
            for (Object o : rooms) {
                List<Object> r = Json.arr(o);
                if (r == null || r.size() < 2 || !(r.get(0) instanceof String) || d.rooms.size() >= MAX_ROOMS) continue;
                Room room = new Room();
                room.area = (String) r.get(0);
                room.name = r.get(1) instanceof String ? (String) r.get(1) : room.area;
                if (r.size() > 2 && r.get(2) instanceof String && !((String) r.get(2)).isEmpty()) {
                    // A codepoint in hex, as every icon travels (tile_icon::codepoint).
                    try { room.icon = Integer.parseInt(((String) r.get(2)).trim(), 16); } catch (NumberFormatException ignored) {}
                }
                if (r.size() > 3 && r.get(3) instanceof Double) room.floor = ((Double) r.get(3)).intValue();
                d.rooms.add(room);
            }
        }
        List<Object> floors = Json.arr(vc.get("floors"));
        if (floors != null) for (Object o : floors) if (o instanceof String) d.floors.add((String) o);
        d.unmapped = Json.integer(vc, "unmapped") != null && Json.integer(vc, "unmapped") != 0;
        List<Object> rt = Json.arr(vc.get("rt"));
        if (rt != null) {
            for (Object o : rt) {
                List<Object> r = Json.arr(o);
                if (r == null || r.size() < 2 || !(r.get(0) instanceof String)) continue;
                Routine routine = new Routine();
                routine.entity = (String) r.get(0);
                routine.name = r.get(1) instanceof String ? (String) r.get(1) : routine.entity;
                d.routines.add(routine);
            }
        }
        d.error = nz(Json.str(vc, "err"));
        Integer pg = Json.integer(vc, "pg");
        if (pg != null) d.progress = pg;
        Double ar = Json.dbl(vc, "ar");
        if (ar != null) d.area = ar;
        return d;
    }

    /** The rows of suction and water: `m` the mode select, `s` suction (the robot's fan speed), `w` the water select. */
    static Choice choice(Tessera.Tile t, char kind) {
        Map<String, Object> row = Json.obj(t.extra.get(kind == 'm' ? "mode" : kind == 's' ? "fan" : "water"));
        if (row == null) return null;
        List<Object> values = Json.arr(row.get("o")), labels = Json.arr(row.get("l"));
        if (values == null || values.isEmpty()) return null;
        Choice c = new Choice();
        c.kind = String.valueOf(kind);
        c.entity = nz(Json.str(row, "e"));
        c.current = kind == 's' ? nz(t.text("fan_speed")) : nz(Json.str(row, "s"));
        c.roles = nz(Json.str(row, "r"));
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i) instanceof String ? (String) values.get(i) : String.valueOf(values.get(i));
            String l = labels != null && i < labels.size() && labels.get(i) instanceof String ? (String) labels.get(i) : Style.words(v);
            c.values.add(v);
            c.labels.add(l);
        }
        return c;
    }

    // ---- what the robot can do (vacuum_model::can) and its two keys ----
    static final class Can { boolean start, pause, stop, dock, locate, byArea; }

    static int supported(Tessera.Tile t) {
        Double f = t.number("supported_features");
        return f == null ? 0 : f.intValue();
    }

    static Can can(int f) {
        Can c = new Can();
        if (f == 0) { c.start = true; c.pause = true; c.dock = true; return c; }
        c.start = (f & (START | PAUSE)) != 0;
        c.pause = (f & PAUSE) != 0;
        c.stop = (f & STOP) != 0;
        c.dock = (f & RETURN_HOME) != 0;
        c.locate = (f & LOCATE) != 0;
        c.byArea = (f & CLEAN_AREA) != 0;
        return c;
    }

    static final int NONE = 0, K_START = 1, K_RESUME = 2, K_PAUSE = 3, K_STOP = 4, K_DOCK = 5;

    static int firstKey(String state, Can c) {
        if ("cleaning".equals(state)) return c.pause ? K_PAUSE : c.stop ? K_STOP : c.dock ? K_DOCK : NONE;
        if ("paused".equals(state)) return K_RESUME;
        return c.start ? K_START : c.dock ? K_DOCK : NONE;
    }

    static int secondKey(String state, Can c) {
        int first = firstKey(state, c);
        if ("returning".equals(state)) return c.stop ? K_STOP : NONE;
        if (c.dock && first != K_DOCK) return K_DOCK;
        if (!c.dock && c.stop && first != K_STOP && ("cleaning".equals(state) || "paused".equals(state))) return K_STOP;
        return NONE;
    }

    static String service(int k) {
        switch (k) {
            case K_START: case K_RESUME: return "vacuum.start";
            case K_PAUSE: return "vacuum.pause";
            case K_STOP: return "vacuum.stop";
            case K_DOCK: return "vacuum.return_to_base";
            default: return "";
        }
    }

    static String keyIcon(int k) {
        switch (k) {
            case K_START: case K_RESUME: return ICON_PLAY;
            case K_PAUSE: return ICON_PAUSE;
            case K_STOP: return ICON_STOP;
            case K_DOCK: return ICON_DOCK;
            default: return "";
        }
    }

    static String keyWords(int k) {
        switch (k) {
            case K_START: return "Clean";
            case K_RESUME: return "Resume";
            case K_PAUSE: return "Pause";
            case K_STOP: return "Stop";
            case K_DOCK: return "Dock";
            default: return "";
        }
    }

    /**
     * The keys on the tile (tile_controls::keys_for): the first follows what it does - Pause while it cleans, Stop for a
     * robot that can only be turned off, else Play - then Stop and Dock where it can. {icon, service} each.
     */
    static List<String[]> tileKeys(Tessera.Tile t) {
        int f = supported(t);
        List<String[]> out = new ArrayList<String[]>();
        boolean cleaning = "cleaning".equals(t.state);
        if (cleaning && (f & PAUSE) != 0) out.add(new String[]{ICON_PAUSE, "vacuum.pause"});
        else if (cleaning && (f & TURN_OFF) != 0 && (f & START) == 0) out.add(new String[]{ICON_PAUSE, "vacuum.turn_off"});
        else if ((f & (START | TURN_ON)) != 0) out.add(new String[]{ICON_PLAY, (f & START) != 0 ? "vacuum.start" : "vacuum.turn_on"});
        if ((f & STOP) != 0) out.add(new String[]{ICON_STOP, (f & STOP) != 0 ? "vacuum.stop" : "vacuum.turn_off"});
        if ((f & RETURN_HOME) != 0) out.add(new String[]{ICON_DOCK, "vacuum.return_to_base"});
        if (out.isEmpty() && f == 0) { // a robot that reports nothing keeps the keys it may have
            out.add(new String[]{cleaning ? ICON_PAUSE : ICON_PLAY, cleaning ? "vacuum.pause" : "vacuum.start"});
            out.add(new String[]{ICON_DOCK, "vacuum.return_to_base"});
        }
        return out;
    }

    /** The state as Home Assistant names it. */
    static String stateWord(String s) {
        if (s == null) return "";
        if (s.equals("docked")) return "Docked";
        if (s.equals("cleaning")) return "Cleaning";
        if (s.equals("paused")) return "Paused";
        if (s.equals("returning")) return "Returning to dock";
        if (s.equals("idle")) return "Idle";
        if (s.equals("error")) return "Error";
        return Style.words(s);
    }

    /** The battery, from the robot's own attribute or the battery sensor on its device (core.device_power); NaN without. */
    static double battery(Tessera.Tile t) {
        Double b = t.number("battery_level");
        if (b == null) b = Json.dbl(t.extra, "bat");
        return b == null ? Double.NaN : b;
    }

    static boolean charging(Tessera.Tile t) {
        Integer c = Json.integer(t.extra, "chg");
        return c != null && c != 0;
    }

    // ---- the card's own state: its view, what is picked, the floor and page of the rooms view ----
    static final int MAIN = 0, ROOMS = 1, ROUTINES = 2, HOW = 3;
    int view = MAIN;
    private String entity = "";
    final List<Integer> picks = new ArrayList<Integer>();
    private int floor, page;
    /** A chip just tapped, shown until Home Assistant says so too (docs/OPTIMISTIC.md). */
    private final Map<String, Object[]> wishes = new HashMap<String, Object[]>();
    private static final long WISH_MS = 6000;
    /** Which keys the top bar should carry: suction and water, Locate. */
    boolean barHow, barLocate;

    void follow(Tessera.Tile t) {
        if (t.entity.equals(entity)) return;
        entity = t.entity;
        reset();
    }

    void reset() {
        view = MAIN;
        picks.clear();
        floor = 0;
        page = 0;
        wishes.clear();
    }

    /** The back key: a view goes back to the card (true); the card itself closes (false). */
    boolean back() {
        if (view == MAIN) return false;
        view = MAIN;
        page = 0;
        return true;
    }

    String viewTitle() {
        return view == ROOMS ? "By area" : view == ROUTINES ? "Routines" : view == HOW ? "Suction & mop" : "";
    }

    void open(int v) {
        view = v;
        page = 0;
    }

    /** Whether the card takes the whole glass: landscape glass, with a map to show. */
    static boolean wide(Tessera.Tile t, int w, int h) {
        Data d = parse(t);
        return w * 10 >= h * 14 && d != null && d.hasMap();
    }

    // ---- drawing ----
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Tessera.Tile t;
    private Data d;
    private Can c;
    private Host host;
    private float u, fs;
    private boolean avail;
    // Sizes (overlay_card metrics), set per frame from u.
    private float gap, in, actionH, keyH, chipH, rowH, radius;

    private final android.content.Context context;

    VacuumCard(android.content.Context context, TextPaint glyphs) {
        this.context = context;
        this.glyphs = glyphs;
    }

    void draw(Canvas canvas, Tessera.Tile tile, RectF body, float unit, Host h) {
        follow(tile);
        t = tile;
        d = parse(tile);
        c = can(supported(tile));
        host = h;
        u = unit;
        fs = 4.2f * u;
        avail = !Style.unavailable(tile);
        gap = 2 * u;
        in = 3 * u;
        actionH = 9 * u;
        keyH = 9 * u;
        chipH = 7.5f * u;
        rowH = 8.5f * u;
        radius = 2.5f * u;
        barHow = false;
        barLocate = false;
        if (d != null) {
            for (int i = picks.size() - 1; i >= 0; i--) if (picks.get(i) >= d.rooms.size()) picks.remove(i);
        }
        if (view == ROOMS) { roomsView(canvas, body); return; }
        if (view == ROUTINES) { routinesView(canvas, body); return; }
        if (view == HOW) {
            float hw = Math.min(body.width(), 110 * u), hh = howH();
            how(canvas, new RectF(body.centerX() - hw / 2, body.top + Math.max(0, (body.height() - hh) / 2),
                    body.centerX() + hw / 2, body.top + Math.max(0, (body.height() - hh) / 2) + hh));
            return;
        }
        boolean landscape = body.width() * 10 >= body.height() * 14;
        if (landscape && showsMap()) twoColumns(canvas, body);
        else oneColumn(canvas, body);
    }

    // ---- what is there ----
    private boolean showsMap() { return d != null && d.hasMap(); }
    private boolean byRoom() { return d != null && c.byArea && d.byRoom(); }
    private boolean hasRoutines() { return d != null && !d.routines.isEmpty(); }
    private boolean hasHow() { return choice(t, 'm') != null || choice(t, 's') != null || choice(t, 'w') != null; }
    private boolean cleaning() { return "cleaning".equals(t.state); }
    private boolean hasReason() { return "error".equals(t.state) && d != null && !d.error.isEmpty(); }

    private String headline() {
        if (!avail) return Style.words(t.state);
        return stateWord(t.state);
    }

    private String detailLine(float room) {
        if (hasReason()) return d.error;
        if (!cleaning()) return "";
        String[] parts = {nz(Json.str(t.extra, "room")), d != null && d.progress >= 0 ? d.progress + " %" : "",
                d != null && !Double.isNaN(d.area) ? Math.round(d.area) + " m²" : ""};
        text.setTypeface(regular);
        text.setTextSize(fs);
        for (int n = 3; n > 0; n--) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) if (!parts[i].isEmpty()) b.append(b.length() == 0 ? "" : "  ·  ").append(parts[i]);
            if (text.measureText(b.toString()) <= room || n == 1) return b.toString();
        }
        return "";
    }

    private boolean hasLine() { return !detailLine(100000).isEmpty(); }
    private boolean hasBar() { return cleaning() && d != null && d.progress >= 0; }
    private float big() { return fs * 1.45f; }

    private float headerH(boolean stacked) {
        return big() * 1.25f + (stacked && !Double.isNaN(battery(t)) ? u + fs * 1.3f : 0) + (hasLine() ? u + fs * 1.3f : 0)
                + (hasBar() ? 3 * u : 0);
    }

    private float batteryW() {
        double b = battery(t);
        if (Double.isNaN(b)) return 0;
        text.setTypeface(regular);
        text.setTextSize(fs);
        return fs * 1.4f + text.measureText(Math.round(b) + " %");
    }

    private void drawBattery(Canvas canvas, float x, float base) {
        double b = battery(t);
        if (Double.isNaN(b)) return;
        int level = (int) Math.max(0, Math.min(100, Math.round(b / 10.0) * 10));
        String name = charging(t) ? (level >= 100 ? "battery-charging-100" : level <= 0 ? "battery-charging-outline"
                : "battery-charging-" + level) : level >= 100 ? "battery" : level <= 0 ? "battery-outline" : "battery-" + level;
        glyphs.setTextSize(fs * 1.2f);
        glyphs.setColor(b <= 15 ? ERROR : Style.DIM);
        String g = Icons.named(context, name);
        canvas.drawText(g == null ? Icons.sure(context, "battery") : g, x + fs * 0.55f, base + fs * 0.1f, glyphs);
        text.setTypeface(regular);
        text.setTextSize(fs);
        text.setColor(Style.DIM);
        canvas.drawText(Math.round(b) + " %", x + fs * 1.3f, base, text);
    }

    private void header(Canvas canvas, float x, float y, float w, boolean stacked) {
        float dot = 2 * u;
        int colour = "error".equals(t.state) ? ERROR : !avail ? Style.FAINT : cleaning() || "returning".equals(t.state) ? TEAL : Style.DIM;
        fill.setColor(colour);
        float bigH = big() * 1.25f;
        canvas.drawCircle(x + dot / 2, y + bigH / 2, dot / 2, fill);
        float tx = x + dot + 2.5f * u, tw = x + w - tx;
        float bw = batteryW();
        text.setTypeface(medium);
        text.setTextSize(big());
        text.setColor(Style.TEXT);
        canvas.drawText(fit(headline(), stacked || bw == 0 ? tw : tw - bw - 3 * u), tx, y + bigH * 0.78f, text);
        float ly = y + bigH;
        if (bw > 0) {
            if (stacked) {
                ly += u;
                drawBattery(canvas, tx, ly + fs);
                ly += fs * 1.3f;
            } else {
                drawBattery(canvas, x + w - bw, y + bigH * 0.72f);
            }
        }
        if (hasLine()) {
            ly += u;
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(hasReason() ? ERROR : Style.DIM);
            canvas.drawText(fit(detailLine(tw), tw), tx, ly + fs, text);
            ly += fs * 1.3f;
        }
        if (hasBar()) {
            ly += 2 * u;
            float bar = u;
            fill.setColor(Style.LINE);
            canvas.drawRoundRect(new RectF(tx, ly, tx + tw, ly + bar), bar / 2, bar / 2, fill);
            fill.setColor(TEAL);
            canvas.drawRoundRect(new RectF(tx, ly, tx + Math.max(bar, tw * Math.max(0, d.progress) / 100f), ly + bar), bar / 2, bar / 2, fill);
        }
    }

    private float headerNeed() {
        text.setTypeface(medium);
        text.setTextSize(big());
        float words = text.measureText(headline());
        for (String s : new String[]{"Cleaning", "Returning to dock", "Docked"}) words = Math.max(words, text.measureText(s));
        return 2 * in + 5 * u + words + 3 * u + batteryW();
    }

    // The hero: the header over the map, or the drawn robot beside the header.
    private float robotSize() { return 14 * u; }
    private float heroNatural(float w) {
        if (!showsMap()) return 2 * in + Math.max(robotSize(), headerH(true));
        return 2 * in + big() * 1.25f + 3 * u + (w - 2 * in) * 10 / 22;
    }
    private float heroLeast() {
        if (!showsMap()) return 2 * in + Math.max(12 * u, headerH(true));
        return 2 * in + big() * 1.25f + 3 * u + 16 * u;
    }

    private void hero(Canvas canvas, RectF r) {
        fill.setColor(Style.RAISED);
        canvas.drawRoundRect(r, radius, radius, fill);
        float iw = r.width() - 2 * in, ih = r.height() - 2 * in;
        if (!showsMap()) {
            float ds = Math.min(robotSize(), ih);
            robot(canvas, r.left + in, r.top + in + (ih - ds) / 2, ds);
            float hx = r.left + in + ds + 4 * u, hh = headerH(true);
            header(canvas, hx, r.top + in + (ih - hh) / 2, r.right - in - hx, true);
            return;
        }
        header(canvas, r.left + in, r.top + in, iw, false);
        float my = r.top + in + headerH(false) + 3 * u;
        RectF box = new RectF(r.left + in, my, r.right - in, r.bottom - in);
        if (box.width() < 16 || box.height() < 16) return;
        Bitmap b = host.map(d.mapEntity, d.mapMark, Math.round(box.width()), Math.round(box.height()), Style.RAISED, Style.isDark());
        if (b != null) {
            float scale = Math.min(box.width() / b.getWidth(), box.height() / b.getHeight());
            float pw = b.getWidth() * scale, ph = b.getHeight() * scale;
            RectF dst = new RectF(box.centerX() - pw / 2, box.centerY() - ph / 2, box.centerX() + pw / 2, box.centerY() + ph / 2);
            canvas.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, null);
        } else if (!host.mapMissing()) {
            // The spinner in the map's place until it is here.
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(u);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(Style.FAINT);
            float s = 6 * u;
            float spin = (SystemClock.uptimeMillis() % 1200) / 1200f * 360;
            canvas.drawArc(new RectF(box.centerX() - s / 2, box.centerY() - s / 2, box.centerX() + s / 2, box.centerY() + s / 2),
                    spin, 270, false, ring);
        } else {
            robot(canvas, box.centerX() - Math.min(robotSize(), box.height()) / 2, box.centerY() - Math.min(robotSize(), box.height()) / 2,
                    Math.min(robotSize(), box.height()));
        }
    }

    /** The lite card's drawn robot: a disc with its bumper and its eye, in the state's colour. */
    private void robot(Canvas canvas, float x, float y, float s) {
        float cx = x + s / 2, cy = y + s / 2;
        int accent = "error".equals(t.state) ? ERROR : cleaning() ? TEAL : Style.DIM;
        fill.setColor(withAlpha(accent, 50));
        canvas.drawCircle(cx, cy, s / 2, fill);
        glyphs.setColor(accent);
        glyphs.setTextSize(s * 0.62f);
        canvas.drawText(Icons.glyph(ICON_ROBOT), cx, cy + s * 0.22f, glyphs);
    }

    // ---- the keys under the robot ----
    private String startWords(boolean counted) {
        int k = firstKey(t.state, c);
        if (k == K_START && counted && !picks.isEmpty()) return "Clean (" + picks.size() + ")";
        return keyWords(k);
    }

    private float keyNeed(String words) {
        text.setTypeface(medium);
        text.setTextSize(fs);
        return fs * 1.3f + 2 * u + text.measureText(words) + 7 * u;
    }

    private float keysSideNeed(boolean counted) {
        int second = secondKey(t.state, c);
        return keyNeed(startWords(counted)) + (second != NONE ? gap + keyNeed(keyWords(second)) : 0);
    }

    private boolean locateInRow(float w, boolean counted) {
        return c.locate && w >= keysSideNeed(counted) + gap + actionH;
    }

    private float keysLeast(boolean counted) {
        return keyNeed(startWords(counted)) + (secondKey(t.state, c) != NONE ? gap + actionH : 0);
    }

    private void commandKey(Canvas canvas, RectF r, String icon, String words, boolean primary, final Runnable run) {
        fill.setColor(!avail ? withAlpha(primary ? ACCENT : Style.RAISED, 110) : primary ? ACCENT : Style.CARD);
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        if (!primary) {
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, u * 0.25f));
            edge.setColor(Style.LINE);
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, edge);
        }
        int fg = primary ? 0xFFFFFFFF : Style.TEXT;
        text.setTypeface(medium);
        text.setTextSize(fs);
        float iw = fs * 1.3f, ww = words == null || words.isEmpty() ? 0 : text.measureText(words);
        float total = iw + (ww > 0 ? 2 * u + ww : 0), x = r.centerX() - total / 2;
        glyphs.setColor(fg);
        glyphs.setTextSize(fs * 1.3f);
        canvas.drawText(Icons.glyph(icon), x + iw / 2, r.centerY() + fs * 0.45f, glyphs);
        if (ww > 0) {
            text.setColor(fg);
            canvas.drawText(fit(words, r.width() - iw - 6 * u), x + iw + 2 * u, r.centerY() + fs * 0.36f, text);
        }
        if (avail) host.tap(r, run);
    }

    private void keys(Canvas canvas, float x, float y, float w, final boolean counted) {
        final int first = firstKey(t.state, c), second = secondKey(t.state, c);
        if (locateInRow(w, counted)) {
            RectF k = new RectF(x + w - actionH, y, x + w, y + actionH);
            fill.setColor(Style.RAISED);
            canvas.drawOval(k, fill);
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(fs * 1.3f);
            canvas.drawText(Icons.glyph(ICON_LOCATE), k.centerX(), k.centerY() + fs * 0.45f, glyphs);
            if (avail) host.tap(k, locate());
            w -= actionH + gap;
        }
        if (first == NONE) return;
        Runnable one = new Runnable() {
            public void run() { press(first, counted); }
        };
        if (second == NONE) {
            commandKey(canvas, new RectF(x, y, x + w, y + actionH), keyIcon(first), startWords(counted), true, one);
            return;
        }
        String bw = keyWords(second);
        float fw = Math.max((w - gap) * 3 / 5, keyNeed(startWords(counted)));
        if (w - fw - gap < keyNeed(bw)) fw = Math.max(keyNeed(startWords(counted)), w - gap - keyNeed(bw));
        boolean words = w - fw - gap >= keyNeed(bw);
        if (!words) fw = w - gap - actionH;
        commandKey(canvas, new RectF(x, y, x + fw, y + actionH), keyIcon(first), startWords(counted), true, one);
        commandKey(canvas, new RectF(x + fw + gap, y, x + w, y + actionH), keyIcon(second), words ? bw : "", false, new Runnable() {
            public void run() { press(second, false); }
        });
    }

    private Runnable locate() {
        return new Runnable() {
            public void run() { call("vacuum.locate", null, null); }
        };
    }

    /** A key under the robot: Start with rooms picked cleans those rooms, in the order of the taps. */
    private void press(int k, boolean counted) {
        if (k == K_START && d != null && !picks.isEmpty() && c.byArea) {
            cleanRooms();
            return;
        }
        call(service(k), null, null);
    }

    void cleanRooms() {
        if (d == null || picks.isEmpty()) return;
        String areas = areasTemplate(d, picks);
        if (areas.isEmpty()) return;
        Map<String, String> templates = new LinkedHashMap<String, String>();
        templates.put("cleaning_area_id", areas);
        call("vacuum.clean_area", null, templates);
        picks.clear();
        if (view == ROOMS) view = MAIN;
    }

    private void call(String service, Map<String, String> extra, Map<String, String> templates) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("entity_id", t.entity);
        if (extra != null) data.putAll(extra);
        host.call(service, data, templates);
    }

    /** vacuum.clean_area's cleaning_area_id, as Home Assistant renders it from a template into a list (areas_template). */
    static String areasTemplate(Data d, List<Integer> picked) {
        StringBuilder out = new StringBuilder("{{ [");
        boolean first = true;
        for (int r : picked) {
            if (r < 0 || r >= d.rooms.size() || !areaId(d.rooms.get(r).area)) continue;
            out.append(first ? "'" : ", '").append(d.rooms.get(r).area).append("'");
            first = false;
        }
        return first ? "" : out.append("] }}").toString();
    }

    static boolean areaId(String id) {
        if (id == null || id.isEmpty() || id.length() > 40) return false;
        for (char ch : id.toCharArray()) {
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) return false;
        }
        return true;
    }

    /** A tap on a room picks it, at the end of the order; a tap on a picked one takes it out. */
    void toggle(int room) {
        int at = picks.indexOf(room);
        if (at >= 0) picks.remove(at);
        else if (picks.size() < MAX_ROOMS) picks.add(room);
    }

    // ---- a key that opens a view: the icon, the words, a chevron ----
    private float pageKeyNeed(String words) {
        text.setTypeface(regular);
        text.setTextSize(fs);
        return 8 * u + fs * 1.3f + 3 * u + text.measureText(words) + 2.5f * u + fs;
    }

    private void pageKey(Canvas canvas, RectF r, String icon, String words, final int target) {
        fill.setColor(Style.CARD);
        canvas.drawRoundRect(r, radius, radius, fill);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, u * 0.25f));
        edge.setColor(Style.LINE);
        canvas.drawRoundRect(r, radius, radius, edge);
        float e = 4 * u;
        glyphs.setColor(Style.DIM);
        glyphs.setTextSize(fs * 1.3f);
        canvas.drawText(Icons.glyph(icon), r.left + e + fs * 0.65f, r.centerY() + fs * 0.45f, glyphs);
        text.setTypeface(regular);
        text.setTextSize(fs);
        text.setColor(Style.TEXT);
        canvas.drawText(fit(words, r.width() - 2 * e - fs * 3), r.left + e + fs * 1.3f + 3 * u, r.centerY() + fs * 0.36f, text);
        glyphs.setColor(Style.FAINT);
        canvas.drawText(Icons.glyph(ICON_CHEVRON), r.right - e - fs * 0.5f, r.centerY() + fs * 0.45f, glyphs);
        host.tap(r, new Runnable() {
            public void run() { open(target); }
        });
    }

    // ---- suction and water: the rows the chosen mode uses ----
    private String shown(Choice row) {
        Object[] w = wishes.get(row.kind);
        if (w != null) {
            if (SystemClock.uptimeMillis() < (Long) w[1] && !w[0].equals(row.current)) return (String) w[0];
            wishes.remove(row.kind);
        }
        return row.current;
    }

    private char role() {
        Choice mode = choice(t, 'm');
        if (mode == null) return 'b';
        String value = shown(mode);
        for (int i = 0; i < mode.values.size() && i < mode.roles.length(); i++) if (mode.values.get(i).equals(value)) return mode.roles.charAt(i);
        return 'b';
    }

    private float levelH() { return 7 * u; }

    private float howH() {
        Choice mode = choice(t, 'm');
        char r = role();
        boolean suction = r != 'm' && r != 'a' && choice(t, 's') != null, water = r != 'v' && r != 'a' && choice(t, 'w') != null;
        float h = 2 * 3 * u;
        int n = 0;
        if (mode != null) { h += chipH; n++; }
        if (suction) { h += levelH(); n++; }
        if (water) { h += levelH(); n++; }
        if (mode != null && r == 'a') { h += fs * 1.4f; n++; }
        return h + Math.max(0, n - 1) * 2 * u;
    }

    private float segmentsNeed(Choice row) {
        if (row == null) return 0;
        text.setTypeface(medium);
        text.setTextSize(fs * 0.85f);
        float w = 0;
        for (String l : row.labels) w += text.measureText(l) + 3.5f * u;
        return w;
    }

    private float howNeed() {
        char r = role();
        float iconW = 7 * u;
        Choice s = r != 'm' && r != 'a' ? choice(t, 's') : null, w = r != 'v' && r != 'a' ? choice(t, 'w') : null;
        return 6 * u + Math.max(segmentsNeed(choice(t, 'm')), Math.max(s != null ? iconW + segmentsNeed(s) : 0, w != null ? iconW + segmentsNeed(w) : 0));
    }

    private void how(Canvas canvas, RectF r) {
        fill.setColor(Style.RAISED);
        canvas.drawRoundRect(r, radius, radius, fill);
        Choice mode = choice(t, 'm');
        char ro = role();
        Choice suction = ro != 'm' && ro != 'a' ? choice(t, 's') : null, water = ro != 'v' && ro != 'a' ? choice(t, 'w') : null;
        float e = 3 * u, step = 2 * u, iconW = 7 * u;
        float cx = r.left + e, cw = r.width() - 2 * e, cy = r.top + e;
        if (mode != null) {
            segments(canvas, mode, new RectF(cx, cy, cx + cw, cy + chipH));
            cy += chipH + step;
        }
        if (suction != null) {
            level(canvas, suction, ICON_HOW, cx, cy, cw, iconW);
            cy += levelH() + step;
        }
        if (water != null) {
            level(canvas, water, ICON_WATER, cx, cy, cw, iconW);
            cy += levelH() + step;
        }
        if (mode != null && ro == 'a') {
            boolean smart = shown(mode).contains("smart");
            text.setTypeface(regular);
            text.setTextSize(fs * 0.9f);
            text.setColor(Style.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(smart ? "The robot chooses suction and water" : "Suction and water as set per room", r.centerX(), cy + fs, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void level(Canvas canvas, Choice row, String icon, float x, float y, float w, float iconW) {
        glyphs.setColor(Style.DIM);
        glyphs.setTextSize(fs * 1.25f);
        canvas.drawText(Icons.glyph(icon), x + iconW * 0.4f, y + levelH() / 2 + fs * 0.45f, glyphs);
        segments(canvas, row, new RectF(x + iconW, y, x + w, y + levelH()));
    }

    /** One track with a segment per option, the chosen one filled (the lite card's chips). */
    private void segments(Canvas canvas, final Choice row, RectF r) {
        fill.setColor(Style.isDark() ? 0xFF22262E : 0xFFDDE1E7);
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        int n = row.values.size();
        if (n == 0) return;
        float sw = r.width() / n;
        String current = shown(row);
        text.setTypeface(medium);
        text.setTextSize(Math.min(fs * 0.85f, r.height() * 0.42f));
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            RectF s = new RectF(r.left + i * sw, r.top, r.left + (i + 1) * sw, r.bottom);
            boolean on = row.values.get(i).equals(current);
            if (on) {
                fill.setColor(ACCENT);
                RectF k = new RectF(s.left + 0.6f * u, s.top + 0.6f * u, s.right - 0.6f * u, s.bottom - 0.6f * u);
                canvas.drawRoundRect(k, k.height() / 2, k.height() / 2, fill);
            }
            text.setColor(on ? 0xFFFFFFFF : Style.TEXT);
            canvas.drawText(fit(row.labels.get(i), sw - 2 * u), s.centerX(), s.centerY() + text.getTextSize() * 0.36f, text);
            final String value = row.values.get(i);
            if (avail) host.tap(s, new Runnable() {
                public void run() { pick(row, value); }
            });
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** The service call behind a chip (tile_controls::choice_action): the robot's fan speed, or a select on its device. */
    private void pick(Choice row, String value) {
        wishes.put(row.kind, new Object[]{value, SystemClock.uptimeMillis() + WISH_MS});
        if (row.kind.equals("s")) {
            Map<String, String> data = new LinkedHashMap<String, String>();
            data.put("fan_speed", value);
            call("vacuum.set_fan_speed", data, null);
        } else if (!row.entity.isEmpty()) {
            Map<String, String> data = new LinkedHashMap<String, String>();
            data.put("entity_id", row.entity);
            data.put("option", value);
            host.call("select.select_option", data, null);
        }
    }

    // ---- rooms as chips on the card: the icon and name; a picked one blue, with its place in the order ----
    private int chipCols(float w) {
        text.setTypeface(regular);
        text.setTextSize(fs);
        float widest = 0;
        for (Room r : d.rooms) widest = Math.max(widest, text.measureText(r.name));
        float need = widest + fs * 1.3f + 2 * u + 7 * u;
        for (int cols = d.rooms.size(); cols > 1; cols--) if (cols != 3 && (w - (cols - 1) * 2 * u) / cols >= need) return cols;
        return 1;
    }

    private int chipsRows(float w) { int cols = chipCols(w); return (d.rooms.size() + cols - 1) / cols; }
    private float chipsH(float w) { int r = chipsRows(w); return r * chipH + (r - 1) * 2 * u; }
    private float headingH() { return fs * 1.2f + 2 * u; }

    private void heading(Canvas canvas, float x, float y, String words) {
        text.setTypeface(medium);
        text.setTextSize(fs * 0.85f);
        text.setColor(Style.DIM);
        canvas.drawText(words, x + u, y + fs * 0.95f, text);
    }

    private String roomGlyph(int icon) {
        String g = icon > 0 ? Icons.glyph(Integer.toHexString(icon).toUpperCase()) : null;
        return g != null ? g : Icons.glyph(ICON_ROOMS);
    }

    private void chips(Canvas canvas, float x, float y, float w) {
        int cols = chipCols(w);
        float g = 2 * u, cw = (w - (cols - 1) * g) / cols;
        for (int i = 0; i < d.rooms.size(); i++) {
            RectF r = new RectF(x + (i % cols) * (cw + g), y + (i / cols) * (chipH + g), 0, 0);
            r.right = r.left + cw;
            r.bottom = r.top + chipH;
            int at = picks.indexOf(i) + 1;
            fill.setColor(at > 0 ? ACCENT : Style.isDark() ? 0xFF22262E : 0xFFDDE1E7);
            canvas.drawRoundRect(r, chipH / 2, chipH / 2, fill);
            float iw = fs * 1.3f, ix = r.left + 3.5f * u;
            if (at > 0) {
                fill.setColor(0xFFFFFFFF);
                canvas.drawCircle(ix + iw / 2, r.centerY(), iw / 2, fill);
                text.setTypeface(medium);
                text.setTextSize(fs * 0.8f);
                text.setColor(ACCENT);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(at), ix + iw / 2, r.centerY() + fs * 0.3f, text);
                text.setTextAlign(Paint.Align.LEFT);
            } else {
                glyphs.setColor(Style.DIM);
                glyphs.setTextSize(iw);
                canvas.drawText(roomGlyph(d.rooms.get(i).icon), ix + iw / 2, r.centerY() + iw * 0.36f, glyphs);
            }
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(at > 0 ? 0xFFFFFFFF : Style.TEXT);
            canvas.drawText(fit(d.rooms.get(i).name, r.right - ix - iw - 2 * u - 2.5f * u), ix + iw + 2 * u, r.centerY() + fs * 0.36f, text);
            final int room = i;
            host.tap(r, new Runnable() {
                public void run() { toggle(room); }
            });
        }
    }

    // ---- the routines as rows: a play disc and the name; a tap runs one ----
    private float routinesH(int count, int cols) {
        int rows = (count + cols - 1) / Math.max(1, cols);
        return rows * (rowH + 2 * u) - 2 * u;
    }

    private void routines(Canvas canvas, float x, float y, float w, int first, int count, int cols) {
        int end = Math.min(d.routines.size(), first + count);
        float g = 2 * u, cw = (w - (cols - 1) * gap) / Math.max(1, cols);
        for (int i = first; i < end; i++) {
            int slot = i - first;
            RectF r = new RectF(x + (slot % cols) * (cw + gap), y + (slot / cols) * (rowH + g), 0, 0);
            r.right = r.left + cw;
            r.bottom = r.top + rowH;
            fill.setColor(Style.CARD);
            canvas.drawRoundRect(r, radius, radius, fill);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, u * 0.25f));
            edge.setColor(Style.LINE);
            canvas.drawRoundRect(r, radius, radius, edge);
            float disc = 6 * u, e = 3 * u;
            fill.setColor(withAlpha(TEAL, 46));
            canvas.drawCircle(r.left + e + disc / 2, r.centerY(), disc / 2, fill);
            glyphs.setColor(TEAL);
            glyphs.setTextSize(disc * 0.6f);
            canvas.drawText(Icons.glyph(ICON_PLAY), r.left + e + disc / 2, r.centerY() + disc * 0.21f, glyphs);
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.TEXT);
            canvas.drawText(fit(d.routines.get(i).name, cw - 2 * e - disc - 3 * u), r.left + e + disc + 3 * u, r.centerY() + fs * 0.36f, text);
            final String button = d.routines.get(i).entity;
            if (avail) host.tap(r, new Runnable() {
                public void run() {
                    Map<String, String> data = new LinkedHashMap<String, String>();
                    data.put("entity_id", button);
                    host.call("button.press", data, null);
                }
            });
        }
    }

    // ---- the plan: the blocks under the hero, in order of importance, each on the card or as a key ----
    private interface Draw { void at(Canvas canvas, float x, float y, float w); }
    private static final class Block {
        final float h, minW;
        final Draw draw;
        Block(float h, float minW, Draw draw) { this.h = h; this.minW = minW; this.draw = draw; }
    }

    private List<Block> blocks(final boolean rooms, final boolean how, final boolean rts, float w, boolean withKeys) {
        List<Block> out = new ArrayList<Block>();
        if (withKeys) out.add(new Block(actionH, keysLeast(rooms), new Draw() {
            public void at(Canvas canvas, float x, float y, float bw) { keys(canvas, x, y, bw, rooms); }
        }));
        if (rooms && d != null) {
            boolean fits = chipsRows(w) <= 2 && d.floors.size() < 2 && !d.rooms.isEmpty();
            out.add(new Block(fits ? headingH() + chipsH(w) : 1 << 20, 0, new Draw() {
                public void at(Canvas canvas, float x, float y, float bw) {
                    heading(canvas, x, y, "By area");
                    chips(canvas, x, y + headingH(), bw);
                }
            }));
        }
        if (how) out.add(new Block(howH(), howNeed(), new Draw() {
            public void at(Canvas canvas, float x, float y, float bw) { how(canvas, new RectF(x, y, x + bw, y + howH())); }
        }));
        if (rts && d != null) out.add(new Block(headingH() + routinesH(d.routines.size(), 1), 0, new Draw() {
            public void at(Canvas canvas, float x, float y, float bw) {
                heading(canvas, x, y, "Routines");
                routines(canvas, x, y + headingH(), bw, 0, d.routines.size(), 1);
            }
        }));
        // The keys to the views of what is not on the card: side by side when both fit, else one above the other.
        final List<Object[]> k = new ArrayList<Object[]>();
        if (byRoom() && !rooms) k.add(new Object[]{ICON_ROOMS, "By area", ROOMS});
        if (hasRoutines() && !rts) k.add(new Object[]{ICON_ROUTINES, "Routines", ROUTINES});
        float need = 0;
        for (Object[] e : k) need = Math.max(need, pageKeyNeed((String) e[1]));
        if (k.size() == 1 || (k.size() == 2 && w >= 2 * need + gap)) {
            if (!k.isEmpty()) out.add(new Block(keyH, need, new Draw() {
                public void at(Canvas canvas, float x, float y, float bw) {
                    int n = k.size();
                    float kw = (bw - (n - 1) * gap) / n;
                    for (int i = 0; i < n; i++) {
                        pageKey(canvas, new RectF(x + i * (kw + gap), y, x + i * (kw + gap) + kw, y + keyH),
                                (String) k.get(i)[0], (String) k.get(i)[1], (Integer) k.get(i)[2]);
                    }
                }
            }));
        } else {
            for (final Object[] e : k) out.add(new Block(keyH, need, new Draw() {
                public void at(Canvas canvas, float x, float y, float bw) {
                    pageKey(canvas, new RectF(x, y, x + bw, y + keyH), (String) e[0], (String) e[1], (Integer) e[2]);
                }
            }));
        }
        return out;
    }

    private float stackH(List<Block> v) {
        float h = 0;
        for (Block b : v) h += b.h + gap;
        return v.isEmpty() ? 0 : h - gap;
    }

    private static boolean fitsW(List<Block> v, float w) {
        for (Block b : v) if (b.minW > w) return false;
        return true;
    }

    /** From the richest plan to the plainest, only with what the robot has: {rooms, how, routines}. */
    private List<boolean[]> plans() {
        boolean[][] all = {{true, true, true}, {true, true, false}, {true, false, true}, {true, false, false},
                {false, true, true}, {false, true, false}, {false, false, true}, {false, false, false}};
        List<boolean[]> out = new ArrayList<boolean[]>();
        for (boolean[] p : all) {
            boolean[] q = {p[0] && byRoom() && !d.rooms.isEmpty(), p[1] && hasHow(), p[2] && hasRoutines()};
            boolean seen = false;
            for (boolean[] o : out) seen |= o[0] == q[0] && o[1] == q[1] && o[2] == q[2];
            if (!seen) out.add(q);
        }
        return out;
    }

    /** Square and standing glass, or no map: one column; the map keeps its natural height or half the room. */
    private void oneColumn(Canvas canvas, RectF body) {
        float x = body.left, y = body.top, w = body.width(), h = body.height();
        float keep = showsMap() ? Math.max(heroLeast(), Math.min(heroNatural(w), h / 2)) : heroLeast();
        for (boolean[] p : plans()) {
            List<Block> v = blocks(p[0], p[1], p[2], w, true);
            float rest = stackH(v) + gap;
            if (h - rest < keep || !fitsW(v, w)) continue;
            float hh = Math.min(heroNatural(w), h - rest), total = hh + rest;
            float cy = y + Math.max(0, (h - total) / 2);
            hero(canvas, new RectF(x, cy, x + w, cy + hh));
            cy += hh + gap;
            for (Block b : v) {
                b.draw.at(canvas, x, cy, w);
                cy += b.h + gap;
            }
            barLocate = c.locate && !locateInRow(w, p[0]);
            barHow = hasHow() && !p[1];
            return;
        }
        hero(canvas, new RectF(x, y, x + w, y + Math.max(heroLeast(), h - actionH - gap)));
        keys(canvas, x, y + h - actionH, w, false);
        barLocate = c.locate && !locateInRow(w, false);
        barHow = hasHow();
    }

    /** Landscape glass with a map: the hero at the left, the blocks at the right, a block that doesn't fit there under the hero. */
    private void twoColumns(Canvas canvas, RectF body) {
        float x = body.left, y = body.top, w = body.width(), h = body.height();
        float g = 4 * u;
        boolean bottom = w * 0.42f < keysSideNeed(true);
        float colh = h - (bottom ? actionH + gap : 0);
        float want = (colh - 2 * in - big() * 1.25f - 3 * u) * 22 / 10 + 2 * in;
        float lw = Math.max(Math.max(w * 0.45f, Math.min(want, w * 0.62f)), headerNeed());
        float need = bottom ? 0 : keysLeast(true);
        if (byRoom()) need = Math.max(need, pageKeyNeed("By area"));
        if (hasRoutines()) need = Math.max(need, pageKeyNeed("Routines"));
        lw = Math.max(w * 0.40f, Math.min(lw, w - g - need));
        float rw = Math.min(w - lw - g, 110 * u);
        lw = w - rw - g;
        float rx = x + lw + g, hh = Math.min(colh, heroNatural(lw));
        for (boolean[] p : plans()) {
            List<Block> v = blocks(p[0], p[1], p[2], rw, !bottom);
            float ry = 0, ly = hh + gap;
            boolean ok = true;
            List<float[]> at = new ArrayList<float[]>();
            for (int i = 0; i < v.size(); i++) {
                Block b = v.get(i);
                if (ry + b.h <= colh && b.minW <= rw) { at.add(new float[]{i, rx, ry, rw}); ry += b.h + gap; }
                else if (ly + b.h <= colh && b.minW <= lw) { at.add(new float[]{i, x, ly, lw}); ly += b.h + gap; }
                else { ok = false; break; }
            }
            if (!ok) continue;
            float used = Math.max(Math.max(ry - gap, hh), ly - gap), dy = y + (colh - used) / 2;
            hero(canvas, new RectF(x, dy, x + lw, dy + hh));
            for (float[] a : at) v.get((int) a[0]).draw.at(canvas, a[1], dy + a[2], a[3]);
            if (bottom) keys(canvas, x, y + h - actionH, w, p[0]);
            barLocate = c.locate && !locateInRow(bottom ? w : rw, p[0]);
            barHow = hasHow() && !p[1];
            return;
        }
        hero(canvas, new RectF(x, y, x + lw, y + colh));
        float ky = y;
        if (!bottom) { keys(canvas, rx, ky, rw, false); ky += actionH + gap; }
        else keys(canvas, x, y + h - actionH, w, false);
        for (Block b : blocks(false, false, false, rw, false)) {
            if (ky + b.h <= y + colh) { b.draw.at(canvas, rx, ky, rw); ky += b.h + gap; }
        }
        barLocate = c.locate;
        barHow = hasHow();
    }

    // ---- the rooms view: Home Assistant's clean by area ----
    private void roomsView(Canvas canvas, RectF body) {
        float x = body.left, y = body.top, w = body.width(), h = body.height();
        if (d == null || d.rooms.isEmpty()) {
            float disc = 12 * u, cw = Math.min(w, 110 * u);
            float total = disc + 4 * u + big() * 1.3f + 2.5f * u + 2 * fs * 1.35f;
            float cy = y + Math.max(0, (h - total) / 2);
            fill.setColor(Style.RAISED);
            canvas.drawCircle(x + w / 2, cy + disc / 2, disc / 2, fill);
            glyphs.setColor(Style.DIM);
            glyphs.setTextSize(disc * 0.5f);
            canvas.drawText(Icons.glyph(ICON_ROOMS), x + w / 2, cy + disc / 2 + disc * 0.18f, glyphs);
            cy += disc + 4 * u;
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(big());
            text.setColor(Style.TEXT);
            canvas.drawText("No areas mapped", x + w / 2, cy + big(), text);
            cy += big() * 1.3f + 2.5f * u;
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.DIM);
            String[] lines = wrap("Map the robot's segments to areas in Home Assistant, in the vacuum's settings.", cw, 2);
            for (String line : lines) {
                canvas.drawText(line, x + w / 2, cy + fs, text);
                cy += fs * 1.35f;
            }
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float g = gap, cw = Math.min(w, 150 * u);
        boolean floors = d.floors.size() > 1;
        floor = Math.max(0, Math.min(floor, Math.max(0, d.floors.size() - 1)));
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < d.rooms.size(); i++) if (d.floors.size() < 2 || d.rooms.get(i).floor == floor) list.add(i);
        int n = list.size();
        float fh = floors ? chipH + g : 0;
        text.setTypeface(regular);
        text.setTextSize(fs);
        float widest = 0;
        for (int i : list) widest = Math.max(widest, text.measureText(d.rooms.get(i).name));
        float disc = 9 * u, tall = 4 * u + disc + 2.5f * u + fs * 1.3f + 4 * u;
        boolean tight = h < 60 * u;
        float hint = tight ? 0 : fs * 1.1f + 3 * u, below = 2.5f * u + hint + actionH;
        float pagerH = tight ? 0 : 7.5f * u;
        // Tiles standing (a disc over the name) while they all fit, else lying (the disc beside it).
        float[] gr = lay(true, n, cw, widest, tall, h - fh - below, pagerH, tight);
        if (gr[5] > 0 || gr[4] < Math.min(n, 4)) gr = lay(false, n, cw, widest, tall, h - fh - below, pagerH, tight);
        boolean up = gr[0] > 0, paged = gr[5] > 0;
        int cols = (int) gr[1], rows = (int) gr[3], per = (int) gr[4];
        float tw = gr[2], th = up ? tall : keyH;
        float gw = cols * tw + (cols - 1) * g, gh = rows * th + (rows - 1) * g;
        float total = fh + gh + (paged && !tight ? g + pagerH : 0) + below;
        float cy = y + Math.max(0, (h - total) / 2);
        float x0 = x + (w - gw) / 2;
        if (floors) {
            float fw = Math.max(gw, Math.min(cw, 105 * u));
            Choice row = new Choice();
            row.kind = "f";
            for (int i = 0; i < d.floors.size(); i++) {
                row.values.add(String.valueOf(i));
                row.labels.add(d.floors.get(i).isEmpty() ? "?" : d.floors.get(i));
            }
            floorSegments(canvas, row, new RectF(x + (w - fw) / 2, cy, x + (w + fw) / 2, cy + chipH));
            cy += fh;
        }
        int pages = per <= 0 || n <= 0 ? 1 : (n + per - 1) / per;
        page = Math.max(0, Math.min(page, pages - 1));
        int first = page * per;
        for (int k = first; k < Math.min(n, first + per); k++) {
            final int i = list.get(k);
            int slot = k - first;
            RectF r = new RectF(x0 + (slot % cols) * (tw + g), cy + (slot / cols) * (th + g), 0, 0);
            r.right = r.left + tw;
            r.bottom = r.top + th;
            int at = picks.indexOf(i) + 1;
            fill.setColor(at > 0 ? withAlpha(ACCENT, 46) : Style.CARD);
            canvas.drawRoundRect(r, radius, radius, fill);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(at > 0 ? 0.5f * u : Math.max(1, u * 0.25f));
            edge.setColor(at > 0 ? ACCENT : Style.LINE);
            canvas.drawRoundRect(r, radius, radius, edge);
            float dd = up ? disc : 7 * u;
            float dcx = up ? r.centerX() : r.left + 3 * u + dd / 2, dcy = up ? r.top + 4 * u + dd / 2 : r.centerY();
            fill.setColor(at > 0 ? ACCENT : Style.RAISED);
            canvas.drawCircle(dcx, dcy, dd / 2, fill);
            if (at > 0) {
                text.setTypeface(medium);
                text.setTextSize(dd * 0.5f);
                text.setColor(0xFFFFFFFF);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(at), dcx, dcy + dd * 0.18f, text);
                text.setTextAlign(Paint.Align.LEFT);
            } else {
                glyphs.setColor(Style.DIM);
                glyphs.setTextSize(dd * 0.55f);
                canvas.drawText(roomGlyph(d.rooms.get(i).icon), dcx, dcy + dd * 0.2f, glyphs);
            }
            text.setTypeface(regular);
            text.setTextSize(fs);
            text.setColor(Style.TEXT);
            if (up) {
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(fit(d.rooms.get(i).name, tw - 3 * u), r.centerX(), r.top + 4 * u + dd + 2.5f * u + fs, text);
                text.setTextAlign(Paint.Align.LEFT);
            } else {
                canvas.drawText(fit(d.rooms.get(i).name, tw - dd - 8 * u), r.left + 3 * u + dd + 2.5f * u, r.centerY() + fs * 0.36f, text);
            }
            host.tap(r, new Runnable() {
                public void run() { toggle(i); }
            });
        }
        cy += gh;
        float bw = Math.min(Math.max(gw, 75 * u), Math.min(w, 110 * u));
        float bx = x + (w - bw) / 2;
        if (paged && !tight) {
            cy += g;
            pager(canvas, x0, cy, gw, pagerH, page, pages);
            cy += pagerH;
        }
        cy += 2.5f * u;
        if (!tight) {
            text.setTypeface(regular);
            text.setTextSize(fs * 0.85f);
            text.setColor(Style.FAINT);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(picks.isEmpty() ? "Pick one or more areas" : "Not every robot keeps this order", x + w / 2, cy + fs * 0.9f, text);
            text.setTextAlign(Paint.Align.LEFT);
            cy += hint;
        }
        if (paged && tight) {
            pagerKey(canvas, new RectF(bx, cy, bx + actionH, cy + actionH), false, page == 0);
            pagerKey(canvas, new RectF(bx + bw - actionH, cy, bx + bw, cy + actionH), true, page >= pages - 1);
            bx += actionH + g;
            bw -= 2 * (actionH + g);
        }
        boolean none = picks.isEmpty() || !avail;
        RectF start = new RectF(bx, cy, bx + bw, cy + actionH);
        fill.setColor(none ? withAlpha(ACCENT, 90) : ACCENT);
        canvas.drawRoundRect(start, actionH / 2, actionH / 2, fill);
        String words = picks.isEmpty() ? "Clean" : "Clean (" + picks.size() + ")";
        text.setTypeface(medium);
        text.setTextSize(fs);
        float iw = fs * 1.3f, total2 = iw + 2 * u + text.measureText(words), sx = start.centerX() - total2 / 2;
        glyphs.setColor(0xFFFFFFFF);
        glyphs.setTextSize(fs * 1.3f);
        canvas.drawText(Icons.glyph(ICON_PLAY), sx + iw / 2, start.centerY() + fs * 0.45f, glyphs);
        text.setColor(0xFFFFFFFF);
        canvas.drawText(words, sx + iw + 2 * u, start.centerY() + fs * 0.36f, text);
        if (!none) host.tap(start, new Runnable() {
            public void run() { cleanRooms(); }
        });
    }

    /** {up, cols, tileW, rows, per, paged} for the rooms view's grid. */
    private float[] lay(boolean up, int n, float cw, float widest, float tall, float room, float pagerH, boolean tight) {
        float g = gap, th = up ? tall : keyH;
        int fit = up ? Math.min(4, (int) ((cw + g) / (Math.max(widest + 6 * u, 30 * u) + g)))
                : Math.min(3, (int) ((cw + g) / (widest + 21 * u + g)));
        int cols = evenColumns(n, Math.max(1, fit));
        float tw = Math.min(up ? 50 * u : 75 * u, (cw - (cols - 1) * g) / cols);
        int rows = Math.max(1, (int) ((room + g) / (th + g)));
        boolean paged = false;
        if (rows * cols < n) {
            paged = true;
            room -= pagerH + (tight ? 0 : g);
            rows = Math.max(1, (int) ((room + g) / (th + g)));
        }
        rows = Math.min(rows, (n + cols - 1) / cols);
        return new float[]{up ? 1 : 0, cols, tw, rows, rows * cols, paged ? 1 : 0};
    }

    /** Columns for `count` tiles where at most `fit` stand side by side: even rows (four as two and two). */
    static int evenColumns(int count, int fit) {
        if (count <= 0 || fit <= 0) return 1;
        int cols = Math.min(count, fit);
        int rows = (count + cols - 1) / cols;
        return (count + rows - 1) / rows;
    }

    private void floorSegments(Canvas canvas, Choice row, RectF r) {
        fill.setColor(Style.isDark() ? 0xFF22262E : 0xFFDDE1E7);
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        int n = row.values.size();
        float sw = r.width() / n;
        text.setTypeface(medium);
        text.setTextSize(fs * 0.85f);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            RectF s = new RectF(r.left + i * sw, r.top, r.left + (i + 1) * sw, r.bottom);
            boolean on = i == floor;
            if (on) {
                fill.setColor(ACCENT);
                RectF k = new RectF(s.left + 0.6f * u, s.top + 0.6f * u, s.right - 0.6f * u, s.bottom - 0.6f * u);
                canvas.drawRoundRect(k, k.height() / 2, k.height() / 2, fill);
            }
            text.setColor(on ? 0xFFFFFFFF : Style.TEXT);
            canvas.drawText(fit(row.labels.get(i), sw - 2 * u), s.centerX(), s.centerY() + fs * 0.3f, text);
            final int f = i;
            host.tap(s, new Runnable() {
                public void run() {
                    if (f != floor) { floor = f; page = 0; }
                }
            });
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void pagerKey(Canvas canvas, RectF k, final boolean forward, boolean off) {
        fill.setColor(Style.RAISED);
        canvas.drawOval(k, fill);
        glyphs.setColor(off ? Style.FAINT : Style.TEXT);
        glyphs.setTextSize(k.height() * 0.5f);
        canvas.drawText(Icons.glyph(forward ? ICON_CHEVRON : ICON_BACK), k.centerX(), k.centerY() + k.height() * 0.18f, glyphs);
        if (!off) host.tap(k, new Runnable() {
            public void run() { page = Math.max(0, page + (forward ? 1 : -1)); }
        });
    }

    private void pager(Canvas canvas, float x, float y, float w, float k, int now, int pages) {
        pagerKey(canvas, new RectF(x, y, x + k, y + k), false, now == 0);
        pagerKey(canvas, new RectF(x + w - k, y, x + w, y + k), true, now >= pages - 1);
        float dot = 2 * u, dg = 2.5f * u, dw = pages * dot + (pages - 1) * dg;
        for (int i = 0; i < pages; i++) {
            fill.setColor(i == now ? Style.TEXT : Style.FAINT);
            canvas.drawCircle(x + w / 2 - dw / 2 + i * (dot + dg) + dot / 2, y + k / 2, dot / 2, fill);
        }
    }

    // ---- the routines view: every routine as a row, in two columns where there is room, in pages where they don't fit ----
    private void routinesView(Canvas canvas, RectF body) {
        if (d == null) return;
        float x = body.left, y = body.top, w = body.width(), h = body.height();
        int n = d.routines.size();
        text.setTypeface(regular);
        text.setTextSize(fs);
        float widest = 0;
        for (Routine r : d.routines) widest = Math.max(widest, text.measureText(r.name));
        float rowNeed = 6 * u + 6 * u + 3 * u + widest + 2 * u;
        float cw = Math.min(w, 150 * u);
        int cols = n > 4 && cw >= 2 * Math.max(rowNeed, 55 * u) + gap ? 2 : 1;
        float gw = cols == 2 ? cw : Math.min(w, 110 * u);
        float note = 3 * u + fs, pagerH = 7.5f * u;
        int rows = Math.max(1, (int) ((h - note + 2 * u) / (rowH + 2 * u)));
        boolean paged = rows * cols < n;
        if (paged) rows = Math.max(1, (int) ((h - note - pagerH - gap + 2 * u) / (rowH + 2 * u)));
        int per = rows * cols, pages = per <= 0 || n <= 0 ? 1 : (n + per - 1) / per;
        page = Math.max(0, Math.min(page, pages - 1));
        int shown = Math.min(per, n - page * per);
        float listH = routinesH(paged ? per : shown, cols);
        float total = listH + (paged ? gap + pagerH : 0) + note;
        float cy = y + Math.max(0, (h - total) / 2), cx = x + (w - gw) / 2;
        routines(canvas, cx, cy, gw, page * per, per, cols);
        cy += listH;
        if (paged) {
            cy += gap;
            pager(canvas, cx, cy, gw, pagerH, page, pages);
            cy += pagerH;
        }
        text.setTypeface(regular);
        text.setTextSize(fs * 0.85f);
        text.setColor(Style.FAINT);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Made in the robot's own app", cx + gw / 2, cy + 3 * u + fs * 0.85f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ---- helpers ----
    private String fit(String s, float width) {
        if (s == null) return "";
        if (width <= 0) return "";
        return TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
    }

    private String[] wrap(String s, float width, int most) {
        List<String> out = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (text.measureText(next) > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        while (out.size() > most) {
            String last = out.remove(out.size() - 1);
            out.set(out.size() - 1, out.get(out.size() - 1) + " " + last);
        }
        return out.toArray(new String[0]);
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    private static String nz(String s) { return s == null ? "" : s; }

}
