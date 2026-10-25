package codebridge;

/** Tiny JSON object writer, enough for the values the API returns. */
final class Json {
    private final StringBuilder sb = new StringBuilder();
    private void key(String k) { sb.append(sb.length() == 0 ? "" : ",").append('"').append(k).append("\":"); }
    Json put(String k, String v) { key(k); sb.append(quote(v)); return this; }
    Json put(String k, long v) { key(k); sb.append(v); return this; }
    Json put(String k, double v) { key(k); sb.append(v); return this; }
    Json put(String k, boolean v) { key(k); sb.append(v); return this; }
    Json putRaw(String k, String raw) { key(k); sb.append(raw); return this; }
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') q.append('\\').append(c);
            else if (c < 0x20) q.append(String.format("\\u%04x", (int) c));
            else q.append(c);
        }
        return q.append('"').toString();
    }
    static String array(Iterable<String> items) {
        StringBuilder a = new StringBuilder("[");
        for (String s : items) { if (a.length() > 1) a.append(','); a.append(quote(s)); }
        return a.append(']').toString();
    }
    @Override public String toString() { return "{" + sb + "}"; }
}
