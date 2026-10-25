package codebridge;

import core.cache.Cache;
import core.cache.CacheFileManager;
import core.cache.def.impl.ItemDefinition;
import core.cache.def.impl.NPCDefinition;
import core.cache.def.impl.SceneryDefinition;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Region;
import core.game.world.map.RegionChunk;
import core.game.world.map.RegionManager;

import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Build-time dump of the game's own data, used to make the block catalogue (see tools/build_catalog.py).
 * Runs inside a started server so every table is loaded exactly as the game uses it.
 * Not used while playing.
 */
final class Catalog {
    private Catalog() {}

    /** catalog DIR : writes enums.json, defs.json, map.json and labels.json into DIR */
    static String dump(String dir) throws Exception {
        File d = new File(dir);
        d.mkdirs();
        StringBuilder report = new StringBuilder();
        report.append(write(new File(d, "enums.json"), enums())).append(' ');
        report.append(write(new File(d, "defs.json"), defs())).append(' ');
        report.append(write(new File(d, "labels.json"), labels())).append(' ');
        report.append(write(new File(d, "ifaces.json"), ifaces(192, 193, 430))).append(' ');
        report.append(write(new File(d, "map.json"), map()));
        return report.toString();
    }

    private static String write(File f, String json) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(json); }
        return f.getName() + "=" + json.length();
    }

    // ---------------- skill tables: every enum in the skill and data packages, by reflection ----------------

    private static final String[] PACKAGES = {"content/global/skill/", "content/data/", "content/global/activity/"};

    static String enums() throws Exception {
        String jar = new File(Item.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        List<String> names = new ArrayList<>();
        try (JarFile jf = new JarFile(jar)) {
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class")) continue;
                for (String p : PACKAGES) if (n.startsWith(p)) { names.add(n.substring(0, n.length() - 6).replace('/', '.')); break; }
            }
        }
        names.add("core.game.node.entity.player.link.SpellBookManager$SpellBook");
        Collections.sort(names);
        for (String cn : names) {
            Class<?> c;
            try { c = Class.forName(cn, false, Catalog.class.getClassLoader()); } catch (Throwable t) { continue; }
            if (cn.contains(".spellconsts.")) {      // Kotlin objects of named constants (spell button ids)
                StringBuilder e = new StringBuilder("{");
                boolean f1 = true;
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    f.setAccessible(true);
                    if (!f1) e.append(',');
                    f1 = false;
                    e.append(str(f.getName())).append(':').append(f.getInt(null));
                }
                if (!first) sb.append(',');
                first = false;
                sb.append('\n').append(str(cn)).append(':').append(e).append('}');
                continue;
            }
            if (!c.isEnum()) continue;
            Object[] constants;
            try { constants = c.getEnumConstants(); } catch (Throwable t) { continue; }
            if (constants == null || constants.length == 0) continue;
            StringBuilder e = new StringBuilder("[");
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) e.append(',');
                try { e.append(object(constants[i], 0, true)); }
                catch (Throwable t) { e.append("{\"_name\":").append(str(((Enum<?>) constants[i]).name())).append(",\"_error\":").append(str(String.valueOf(t))).append('}'); }
            }
            e.append(']');
            if (!first) sb.append(',');
            first = false;
            sb.append('\n').append(str(cn)).append(':').append(e);
        }
        return sb.append("\n}").toString();
    }

    private static final int MAX_DEPTH = 5;

    private static String object(Object o, int depth, boolean top) throws Exception {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (o instanceof String) return str((String) o);
        if (o instanceof Number || o instanceof Boolean) return numberOrBool(o);
        if (o instanceof Character) return str(o.toString());
        if (o instanceof Enum && !top) return str(((Enum<?>) o).name());
        if (o instanceof Item) {
            Item it = (Item) o;
            return "{\"item\":" + it.getId() + ",\"amount\":" + it.getAmount() + ",\"name\":" + str(itemName(it.getId())) + "}";
        }
        if (o instanceof core.game.world.map.Location) {
            core.game.world.map.Location l = (core.game.world.map.Location) o;
            return "{\"x\":" + l.getX() + ",\"y\":" + l.getY() + ",\"z\":" + l.getZ() + "}";
        }
        if (c.isArray()) {
            int n = Array.getLength(o);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(n, 400); i++) { if (i > 0) sb.append(','); sb.append(depth >= MAX_DEPTH ? "null" : object(Array.get(o, i), depth + 1, false)); }
            return sb.append(']').toString();
        }
        if (o instanceof Collection) {
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (Object x : (Collection<?>) o) { if (i++ > 0) sb.append(','); if (i > 400) break; sb.append(depth >= MAX_DEPTH ? "null" : object(x, depth + 1, false)); }
            return sb.append(']').toString();
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<?, ?> x : ((Map<?, ?>) o).entrySet()) {
                if (i++ > 0) sb.append(','); if (i > 400) break;
                sb.append(str(String.valueOf(x.getKey()))).append(':').append(depth >= MAX_DEPTH ? "null" : object(x.getValue(), depth + 1, false));
            }
            return sb.append('}').toString();
        }
        if (depth >= MAX_DEPTH || c.getName().startsWith("java.") || c.getName().startsWith("kotlin.jvm")) return str(String.valueOf(o));
        // a plain object or the enum constant itself: its instance fields
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (o instanceof Enum) { sb.append("\"_name\":").append(str(((Enum<?>) o).name())); first = false; }
        else { sb.append("\"_class\":").append(str(c.getSimpleName())); first = false; }
        for (Class<?> k = c; k != null && k != Object.class && k != Enum.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(o); } catch (Throwable t) { continue; }
                if (v != null && v.getClass().getName().contains("Lambda")) continue;
                if (v instanceof core.game.node.entity.player.Player) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(str(f.getName())).append(':').append(object(v, depth + 1, false));
            }
        }
        return sb.append('}').toString();
    }

    private static String numberOrBool(Object o) {
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        }
        return o.toString();
    }

    private static String itemName(int id) {
        try { ItemDefinition d = ItemDefinition.forId(id); return d == null ? "" : d.getName(); } catch (Throwable t) { return ""; }
    }

    // ---------------- items, NPCs, objects from the cache ----------------

    static String defs() {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        int n = 0;
        for (int id = 0; id < 15000; id++) {
            ItemDefinition d;
            try { d = ItemDefinition.forId(id); } catch (Throwable t) { continue; }
            if (d == null || d.getName() == null || d.getName().equals("null")) continue;
            if (n++ > 0) sb.append(',');
            sb.append("\n{\"id\":").append(id).append(",\"name\":").append(str(d.getName()))
              .append(",\"noted\":").append(d.isUnnoted() ? "false" : "true")
              .append(",\"stackable\":").append(d.isStackable())
              .append(",\"members\":").append(d.isMembersOnly())
              .append(",\"options\":").append(strs(d.getInventoryOptions()))
              .append(",\"ground\":").append(strs(d.getGroundOptions()))
              .append('}');
        }
        sb.append("],\"npcs\":[");
        n = 0;
        for (int id = 0; id < 10000; id++) {
            NPCDefinition d;
            try { d = NPCDefinition.forId(id); } catch (Throwable t) { continue; }
            if (d == null || d.getName() == null || d.getName().equals("null")) continue;
            if (n++ > 0) sb.append(',');
            sb.append("\n{\"id\":").append(id).append(",\"name\":").append(str(d.getName()))
              .append(",\"combat\":").append(d.getCombatLevel())
              .append(",\"size\":").append(d.getSize())
              .append(",\"options\":").append(strs(d.getOptions()))
              .append(",\"children\":").append(ints(d.getChildNPCIds()))
              .append('}');
        }
        sb.append("],\"objects\":[");
        n = 0;
        for (int id = 0; id < 60000; id++) {
            SceneryDefinition d;
            try { d = SceneryDefinition.forId(id); } catch (Throwable t) { continue; }
            if (d == null) continue;
            String name = d.getName();
            int[] kids = d.getChildrenIds();
            boolean named = name != null && !name.equals("null");
            if (!named && (kids == null || kids.length == 0)) continue;
            if (n++ > 0) sb.append(',');
            sb.append("\n{\"id\":").append(id).append(",\"name\":").append(str(named ? name : ""))
              .append(",\"options\":").append(strs(d.getOptions()))
              .append(",\"w\":").append(d.getSizeX()).append(",\"h\":").append(d.getSizeY())
              .append(",\"icon\":").append(d.getMapIcon())
              .append(",\"children\":").append(ints(kids))
              .append(",\"varbit\":").append(d.getVarbitID()).append(",\"varp\":").append(d.getConfigId())
              .append('}');
        }
        return sb.append("]}").toString();
    }

    // ---------------- interface texts (spell names on the spellbooks) ----------------

    static String ifaces(int... ids) {
        StringBuilder sb = new StringBuilder("{");
        for (int k = 0; k < ids.length; k++) {
            if (k > 0) sb.append(',');
            sb.append('"').append(ids[k]).append("\":[");
            int n = 0;
            for (int c = 0; c < 400; c++) {
                core.cache.def.impl.IfaceDefinition d;
                try { d = core.cache.def.impl.IfaceDefinition.forId(ids[k], c); } catch (Throwable t) { break; }
                if (d == null) continue;
                if (n++ > 0) sb.append(',');
                sb.append("\n{\"c\":").append(c).append(",\"text\":").append(str(d.text)).append(",\"suffix\":").append(str(d.opSuffix))
                  .append(",\"option\":").append(str(d.option)).append(",\"ops\":").append(strs(d.ops)).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    // ---------------- world map place names (cache index 23, the world map's own labels) ----------------

    static String labels() {
        StringBuilder sb = new StringBuilder("[");
        try {
            CacheFileManager wm = Cache.getIndexes()[23];
            int n = 0;
            for (String group : new String[]{"main_labels"}) {
                int gid = wm.getArchiveId(group);
                if (gid < 0) continue;
                for (int fid : wm.getFileIds(gid)) {
                    byte[] data = wm.getFileData(gid, fid);
                    if (data == null) continue;
                    ByteBuffer b = ByteBuffer.wrap(data);
                    StringBuilder name = new StringBuilder();
                    for (byte ch; (ch = b.get()) != 0; ) name.append((char) (ch & 0xFF));
                    int flags = b.get();
                    int x = b.getShort() & 0xFFFF, y = b.getShort() & 0xFFFF;
                    int colour = b.getInt();
                    if (n++ > 0) sb.append(',');
                    sb.append("\n{\"name\":").append(str(name.toString())).append(",\"x\":").append(x).append(",\"y\":").append(y)
                      .append(",\"size\":").append(flags & 3).append(",\"flags\":").append(flags).append(",\"colour\":").append(colour).append('}');
                }
            }
        } catch (Throwable t) {
            return "{\"error\":" + str(String.valueOf(t)) + "}";
        }
        return sb.append("]").toString();
    }

    // ---------------- every object you can click, on every map square ----------------

    static String map() {
        // objectId -> list of x,y,z ; only objects with at least one option or a map icon
        StringBuilder sb = new StringBuilder("{");
        Map<Integer, StringBuilder> where = new TreeMap<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int rx = 0; rx < 256; rx++) {
            for (int ry = 0; ry < 256; ry++) {
                int regionId = rx << 8 | ry;
                int land;
                try { land = Cache.getIndexes()[5].getArchiveId("l" + rx + "_" + ry); } catch (Throwable t) { continue; }
                if (land < 0) continue;
                Region r;
                try { r = RegionManager.forId(regionId); Region.load(r); } catch (Throwable t) { continue; }
                for (int z = 0; z < 4; z++) {
                    for (RegionChunk[] col : r.getPlanes()[z].getChunks()) {
                        if (col == null) continue;
                        for (RegionChunk ch : col) {
                            if (ch == null) continue;
                            for (int ox = 0; ox < 8; ox++) for (int oy = 0; oy < 8; oy++) {
                              Scenery[] tile;
                              try { tile = ch.getObjects(ox, oy); } catch (Throwable t) { continue; }
                              if (tile == null) continue;
                              for (Scenery s : tile) {
                                if (s == null) continue;
                                SceneryDefinition d = s.getDefinition();
                                if (d == null) continue;
                                if (!clickable(d)) continue;
                                int cnt = counts.merge(s.getId(), 1, Integer::sum);
                                if (cnt > 4000) continue;   // walls and fences: enough to know what they are
                                StringBuilder w = where.computeIfAbsent(s.getId(), k -> new StringBuilder());
                                if (w.length() > 0) w.append(',');
                                w.append(s.getLocation().getX()).append(',').append(s.getLocation().getY()).append(',').append(s.getLocation().getZ());
                              }
                            }
                        }
                    }
                }
            }
        }
        boolean first = true;
        for (Map.Entry<Integer, StringBuilder> e : where.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('\n').append('"').append(e.getKey()).append("\":{\"n\":").append(counts.get(e.getKey())).append(",\"at\":[").append(e.getValue()).append("]}");
        }
        return sb.append("\n}").toString();
    }

    private static boolean clickable(SceneryDefinition d) {
        if (d.getMapIcon() > 0) return true;
        if (d.getChildrenIds() != null && d.getChildrenIds().length > 0) return true;
        // every named object: many (anvils, ranges, spinning wheels) are only ever used with an item, so they have no options
        String n = d.getName();
        return n != null && !n.isEmpty() && !n.equals("null");
    }

    // ---------------- json helpers ----------------

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch);
            }
        }
        return b.append('"').toString();
    }
    private static String strs(String[] a) {
        if (a == null) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(a[i] == null || a[i].equals("null") ? "null" : str(a[i])); }
        return b.append(']').toString();
    }
    private static String ints(int[] a) {
        if (a == null) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(a[i]); }
        return b.append(']').toString();
    }
}
