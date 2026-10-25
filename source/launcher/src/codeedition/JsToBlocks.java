package codeedition;

import codeedition.BlockModel.Block;
import codeedition.BlockModel.Def;
import codeedition.BlockModel.Kind;
import codeedition.BlockModel.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads JavaScript back into blocks, so any script can be shown in the Basic view. Block calls
 * ({@code await blocks.chop("b2", "Oak")}), loops and ifs become their blocks; any other code becomes a JavaScript block
 * that holds that code exactly as written.
 */
final class JsToBlocks {
    private final String s;
    private int i;

    private JsToBlocks(String s) { this.s = s; }

    private static final Pattern WRAPPER = Pattern.compile("\\A\\s*(?://[^\\n]*\\n\\s*)*blocks\\.run\\(\\s*async\\s*\\(\\s*\\)\\s*=>\\s*\\{");

    static List<Block> parse(String js) {
        Matcher m = WRAPPER.matcher(js);
        if (m.find()) {
            JsToBlocks p = new JsToBlocks(js);
            int close = p.matching(m.end() - 1);
            if (close > 0 && js.substring(close + 1).trim().matches("\\)\\s*;?")) {
                return new JsToBlocks(js.substring(m.end(), close)).list();
            }
        }
        // a script without the blocks wrapper: one JavaScript block that keeps it exactly as it is
        List<Block> out = new ArrayList<>();
        if (!js.trim().isEmpty()) { Block b = new Block("jsfile"); b.set(0, js.strip()); out.add(b); }
        return out;
    }

    static Block code(String text) {
        Block b = new Block("js");
        b.set(0, text);
        return b;
    }

    // ------------------------------------------------------------------ statements
    private static final Pattern TICK = Pattern.compile("\\Gawait\\s+blocks\\.tick\\(\\s*\\)\\s*;?");
    private static final Pattern STOP = Pattern.compile("\\Gblocks\\.step\\(\\s*(\"[^\"]*\"|'[^']*')\\s*\\)\\s*;\\s*return\\s*;?");
    private static final Pattern STEP = Pattern.compile("\\Gblocks\\.step\\(\\s*(\"[^\"]*\"|'[^']*')\\s*\\)\\s*;?");
    private static final Pattern CALL = Pattern.compile("\\G(?:await\\s+)?blocks\\.(\\w+)\\(");
    private static final Pattern FOREVER = Pattern.compile("\\Gwhile\\s*\\(\\s*true\\s*\\)\\s*\\{");
    private static final Pattern REPEAT = Pattern.compile("\\Gfor\\s*\\(\\s*let\\s+(\\w+)\\s*=\\s*0\\s*;\\s*\\1\\s*<\\s*(\\d+)\\s*;\\s*\\1\\s*\\+\\+\\s*\\)\\s*\\{");
    private static final Pattern UNTIL = Pattern.compile("\\Gwhile\\s*\\(\\s*!\\s*blocks\\.check\\(");
    private static final Pattern IF = Pattern.compile("\\Gif\\s*\\(\\s*blocks\\.check\\(");
    private static final Pattern ELSE = Pattern.compile("\\G\\s*else\\s*\\{");

    private List<Block> list() {
        List<Block> out = new ArrayList<>();
        int rawStart = -1, rawEnd = -1;
        while (true) {
            skipSpace();
            if (i >= s.length()) break;
            int start = i;
            Block b = null;
            boolean skipped = false;
            try {
                if (s.startsWith("// made by the block editor", i)) { skipLine(); skipped = true; }
                else if (look(TICK)) skipped = true;
                else if (look(STOP)) b = new Block("stop");
                else if (look(STEP)) skipped = true;
                else b = statement();
            } catch (RuntimeException e) { b = null; }
            if (b == null && !skipped) {        // not a block: keep it as code
                i = start;
                rawStatement();
                if (rawStart < 0) rawStart = start;
                rawEnd = i;
                continue;
            }
            if (rawStart >= 0) { out.add(code(dedent(s.substring(rawStart, rawEnd)))); rawStart = -1; }
            if (b != null) out.add(b);
        }
        if (rawStart >= 0) out.add(code(dedent(s.substring(rawStart, rawEnd))));
        return out;
    }

    private boolean look(Pattern p) {
        Matcher m = p.matcher(s);
        if (!m.find(i) || m.start() != i) return false;
        i = m.end();
        return true;
    }

    /** One block statement at i, or null (i is then anywhere). */
    private Block statement() {
        Matcher m;
        if ((m = at(FOREVER)) != null) { Block b = new Block("forever"); body(m, b.body); return b; }
        if ((m = at(REPEAT)) != null) { Block b = new Block("repeat"); b.set(0, m.group(2)); body(m, b.body); return b; }
        if ((m = at(UNTIL)) != null) {
            i = m.end();
            List<Object> a = args();
            if (a == null || a.size() < 2 || !expect("\\)\\s*\\)\\s*\\{")) return null;
            Block b = new Block("until");
            b.set(0, str(a.get(1))); b.set(1, a.size() > 2 ? str(a.get(2)) : "");
            bodyFrom(i - 1, b.body);
            return b;
        }
        if ((m = at(IF)) != null) {
            i = m.end();
            List<Object> a = args();
            if (a == null || a.size() < 2 || !expect("\\)\\s*\\)\\s*\\{")) return null;
            List<Block> yes = new ArrayList<>();
            bodyFrom(i - 1, yes);
            Block b;
            int save = i;
            if (look(ELSE)) { b = new Block("ifelse"); bodyFrom(i - 1, b.elseBody); }
            else { i = save; b = new Block("if"); }
            b.body.addAll(yes);
            b.set(0, str(a.get(1))); b.set(1, a.size() > 2 ? str(a.get(2)) : "");
            return b;
        }
        if ((m = at(CALL)) != null) {
            String fn = m.group(1);
            i = m.end();
            List<Object> a = args();
            if (a == null || !expect("\\)\\s*;?")) return null;
            Def d = byFn(fn);
            if (d == null || a.isEmpty() || a.size() - 1 > d.params.length) return null;
            Block b = new Block(d.type);
            for (int k = 1; k < a.size(); k++) {
                Param p = d.params[k - 1];
                Object v = a.get(k);
                if (p.kind.numeric() && !(v instanceof Double)) return null;     // a number slot needs a number
                b.set(k - 1, str(v));
            }
            return b;
        }
        return null;
    }

    private Matcher at(Pattern p) {
        Matcher m = p.matcher(s);
        return m.find(i) && m.start() == i ? m : null;
    }

    private boolean expect(String regex) {
        Matcher m = Pattern.compile("\\G\\s*" + regex).matcher(s);
        if (!m.find(i) || m.start() != i) return false;
        i = m.end();
        return true;
    }

    private void body(Matcher open, List<Block> into) { bodyFrom(open.end() - 1, into); }

    /** Reads the { ... } that opens at `brace` into blocks, and moves past it. */
    private void bodyFrom(int brace, List<Block> into) {
        int close = matching(brace);
        if (close < 0) throw new IllegalArgumentException("no closing brace");
        into.addAll(new JsToBlocks(s.substring(brace + 1, close)).list());
        i = close + 1;
    }

    private static Def byFn(String fn) {
        if (fn.equals("say")) return BlockModel.def("say");
        for (Def d : BlockModel.DEFS) if (!d.fn.isEmpty() && d.fn.equals(fn)) return d;
        return null;
    }

    private static String str(Object v) {
        if (v instanceof Double) { double d = (Double) v; return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
        return v == null ? "" : String.valueOf(v);
    }

    /** Literal call arguments (strings and numbers) up to the closing ")"; null when one isn't a plain value. */
    private List<Object> args() {
        List<Object> out = new ArrayList<>();
        skipSpace();
        if (i < s.length() && s.charAt(i) == ')') return out;
        while (true) {
            skipSpace();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '"' || c == '\'') out.add(string());
            else {
                Matcher m = Pattern.compile("\\G-?\\d+(?:\\.\\d+)?").matcher(s);
                if (!m.find(i) || m.start() != i) return null;
                out.add(Double.parseDouble(m.group()));
                i = m.end();
            }
            skipSpace();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            return i < s.length() && s.charAt(i) == ')' ? out : null;
        }
    }

    private String string() {
        char q = s.charAt(i++);
        StringBuilder o = new StringBuilder();
        while (i < s.length() && s.charAt(i) != q) {
            char c = s.charAt(i++);
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': o.append('\n'); break;
                    case 't': o.append('\t'); break;
                    case 'u': o.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: o.append(e);
                }
            } else o.append(c);
        }
        i++;
        return o.toString();
    }

    // ------------------------------------------------------------------ scanning plain code
    private void skipSpace() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private void skipLine() { while (i < s.length() && s.charAt(i) != '\n') i++; }

    /** Moves past one statement of any JavaScript: to a ";" or the end of a line, outside brackets, strings and comments. */
    private void rawStatement() {
        int depth = 0;
        boolean any = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { skipLine(); if (depth == 0 && !any) return; continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { int e = s.indexOf("*/", i + 2); i = e < 0 ? s.length() : e + 2; continue; }
            if (c == '"' || c == '\'' || c == '`') { skipString(c); any = true; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth <= 0 && c == '}') {
                    i++;
                    // a block statement ends at its "}" unless the code carries on (else, catch, a ")" or a ".")
                    int j = i;
                    while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                    if (depth == 0 && !s.startsWith("else", j) && !s.startsWith("catch", j) && !s.startsWith("finally", j)
                        && (j >= s.length() || ")].,;".indexOf(s.charAt(j)) < 0)) return;
                    if (depth < 0) depth = 0;
                    continue;
                }
            } else if (c == ';' && depth == 0) { i++; return; }
            else if (c == '\n' && depth == 0 && any && !continues()) { return; }
            if (!Character.isWhitespace(c)) any = true;
            i++;
        }
    }

    /** At a line end outside brackets: does the statement go on (the line ends with an operator, or the next starts with one)? */
    private boolean continues() {
        int b = i - 1;
        while (b >= 0 && (s.charAt(b) == ' ' || s.charAt(b) == '\t' || s.charAt(b) == '\r')) b--;
        if (b >= 0 && "+-*/%=&|^!?:,.<>({[".indexOf(s.charAt(b)) >= 0) return true;
        int j = i + 1;
        while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
        return j < s.length() && ".+-*/%=&|^?:)]".indexOf(s.charAt(j)) >= 0 && !s.startsWith("//", j) && !s.startsWith("/*", j);
    }

    private void skipString(char q) {
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '\\') { i++; continue; }
            if (c == q) return;
            if (q == '`' && c == '$' && i < s.length() && s.charAt(i) == '{') { int e = matching(i); i = e < 0 ? s.length() : e + 1; }
        }
    }

    /** The index of the bracket that closes the one at `open`, skipping strings and comments; -1 if none. */
    private int matching(int open) {
        int save = i, depth = 0;
        i = open;
        try {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { skipLine(); continue; }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { int e = s.indexOf("*/", i + 2); i = e < 0 ? s.length() : e + 2; continue; }
                if (c == '"' || c == '\'' || c == '`') { skipString(c); continue; }
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') { if (--depth == 0) return i; }
                i++;
            }
            return -1;
        } finally { i = save; }
    }

    /** Removes the indent the code had inside its loop or if, so it reads cleanly on its own. */
    static String dedent(String code) {
        String[] lines = code.replace("\r", "").split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (int k = 0; k < lines.length; k++) {
            String l = lines[k];
            if (l.trim().isEmpty() || k == 0) continue;
            int n = 0; while (n < l.length() && l.charAt(n) == ' ') n++;
            min = Math.min(min, n);
        }
        if (min == Integer.MAX_VALUE) min = 0;
        StringBuilder o = new StringBuilder(lines[0].trim());
        for (int k = 1; k < lines.length; k++) o.append('\n').append(lines[k].length() >= min ? lines[k].substring(min) : lines[k].trim());
        return o.toString().strip();
    }
}
