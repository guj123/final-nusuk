package com.example.nusukhelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer (objects, arrays, strings, numbers, booleans, null). No libraries needed. */
final class MiniJson {
    private final String s;
    private int p;

    private MiniJson(String s) { this.s = s; }

    static Object parse(String text) {
        MiniJson j = new MiniJson(text);
        j.ws();
        Object v = j.value();
        return v;
    }

    static String quote(String t) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private Object value() {
        ws();
        char c = s.charAt(p);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p)) { p += 4; return null; }
        int st = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        if (st == p) throw new IllegalArgumentException("bad json at " + p);
        return Double.valueOf(s.substring(st, p));
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        p++; ws();
        if (s.charAt(p) == '}') { p++; return m; }
        while (true) {
            ws();
            String k = str();
            ws();
            if (s.charAt(p++) != ':') throw new IllegalArgumentException("':' expected at " + p);
            m.put(k, value());
            ws();
            char c = s.charAt(p++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("',' expected at " + p);
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        p++; ws();
        if (s.charAt(p) == ']') { p++; return l; }
        while (true) {
            l.add(value());
            ws();
            char c = s.charAt(p++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("',' expected at " + p);
        }
    }

    private String str() {
        if (s.charAt(p) != '"') throw new IllegalArgumentException("string expected at " + p);
        p++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(p++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break;
                default: b.append(e);
            }
        }
    }
}
