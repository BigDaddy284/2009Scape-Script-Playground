package codeedition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The pick lists for the block editor, read from codeedition/catalog.json ("editor" part): trees, rocks, fish,
 * products, spells... each with a note (like "Woodcutting 15") and what it needs.
 */
final class EditorCatalog {
    private EditorCatalog() {}

    static final class Entry {
        final String name, note, skill, needs, quest; final int level;
        Entry(String name, String note, String skill, int level, String needs, String quest) {
            this.name = name; this.note = note; this.skill = skill; this.level = level; this.needs = needs; this.quest = quest;
        }
    }

    private static volatile Map<String, List<Entry>> lists;
    private static volatile Map<String, List<String>> options = Map.of();

    static Path file() { return Game.ROOT.resolve("codeedition").resolve("catalog.json"); }

    /** Reads the catalogue once (in the background is fine; until then the lists are empty). */
    @SuppressWarnings("unchecked")
    static synchronized void load() {
        if (lists != null) return;
        Map<String, List<Entry>> out = new HashMap<>();
        Map<String, List<String>> opts = new HashMap<>();
        try {
            String text = new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
            // only the "editor" part is needed; skip parsing the rest
            int at = text.lastIndexOf("\"editor\":");
            Object root = new BlockModel.Parser(text.substring(at + 9, text.lastIndexOf('}'))).value();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root).entrySet()) {
                if (e.getKey().equals("options")) {
                    for (Map.Entry<String, Object> o : ((Map<String, Object>) e.getValue()).entrySet()) {
                        List<String> l = new ArrayList<>();
                        for (Object x : (List<Object>) o.getValue()) l.add(String.valueOf(x));
                        opts.put(o.getKey().toLowerCase(), l);
                    }
                    continue;
                }
                List<Entry> l = new ArrayList<>();
                for (Object r : (List<Object>) e.getValue()) {
                    List<Object> row = (List<Object>) r;
                    l.add(new Entry(s(row, 0), s(row, 1), s(row, 2), num(row, 3), s(row, 4), s(row, 5)));
                }
                out.put(e.getKey(), l);
            }
        } catch (Exception ignored) {
            // no catalogue: the editor still works with typed names and what's near the character
        }
        options = opts;
        lists = out;
    }

    private static String s(List<Object> row, int i) { return i < row.size() && row.get(i) != null ? String.valueOf(row.get(i)) : ""; }
    private static int num(List<Object> row, int i) {
        try { return (int) Double.parseDouble(s(row, i)); } catch (Exception e) { return 0; }
    }

    static List<Entry> list(String name) {
        Map<String, List<Entry>> l = lists;
        if (l == null || name == null) return List.of();
        return l.getOrDefault(name, List.of());
    }

    static Entry find(String list, String name) {
        if (name == null || name.isEmpty()) return null;
        String n = norm(name);
        for (Entry e : list(list)) if (norm(e.name).equals(n)) return e;
        return null;
    }

    /** The click options an object or person has ("Open", "Climb-up", "Pickpocket"...). */
    static List<String> optionsOf(String name) { return options.getOrDefault(name == null ? "" : name.toLowerCase(), List.of()); }

    static String norm(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]", ""); }
}
