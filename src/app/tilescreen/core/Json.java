package app.tilescreen.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer. Objects become LinkedHashMap, arrays ArrayList, numbers Double,
 * plus String, Boolean and null. Kept dependency-free so the core runs on a desktop JVM for tests
 * and on Android without relying on org.json.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("null");
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    /** Parses, returning null instead of throwing. */
    public static Object tryParse(String text) {
        try { return parse(text); } catch (RuntimeException e) { return null; }
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private Object value(int depth) {
        if (depth > 64) throw new IllegalArgumentException("too deep");
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad literal at " + i);
        i += word.length();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw new IllegalArgumentException("key expected at " + i);
            String k = string();
            ws();
            if (peek() != ':') throw new IllegalArgumentException("colon expected at " + i);
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("comma expected at " + (i - 1));
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<Object>();
        i++;
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("comma expected at " + (i - 1));
        }
    }

    private char peek() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        return s.charAt(i);
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = peek();
            i++;
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = peek();
                i++;
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("bad escape at " + i);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
            else break;
        }
        if (start == i) throw new IllegalArgumentException("unexpected character at " + i);
        return Double.valueOf(s.substring(start, i));
    }

    // ----- writing -----

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
        } else if (v instanceof Number) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            Iterator<Object> it = ((Iterable<Object>) v).iterator();
            boolean first = true;
            while (it.hasNext()) {
                if (!first) b.append(',');
                first = false;
                write(b, it.next());
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            b.append('[');
            Object[] a = (Object[]) v;
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(',');
                write(b, a[k]);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ----- typed helpers over parsed values -----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    public static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        if (v instanceof String) return (String) v;
        if (v instanceof Double) return num((Double) v);
        return null;
    }

    public static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    public static Double dbl(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        if (v instanceof Double) return (Double) v;
        if (v instanceof String) {
            try { return Double.valueOf((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** An integral number, or null when absent or fractional. */
    public static Integer integer(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        if (!(v instanceof Double)) return null;
        double d = (Double) v;
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    public static Boolean bool(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : null;
    }
}
