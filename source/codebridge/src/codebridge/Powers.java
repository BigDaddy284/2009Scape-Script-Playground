package codebridge;

import core.game.node.Node;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.SpellBookManager.SpellBook;
import core.game.node.entity.player.link.prayer.PrayerType;
import core.game.node.entity.combat.spell.MagicSpell;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.cache.def.impl.ItemDefinition;

import content.global.skill.magic.SpellListeners;
import content.global.skill.slayer.SlayerManager;
import content.global.skill.slayer.Tasks;
import content.global.skill.construction.BuildHotspot;
import content.global.skill.construction.Decoration;
import content.global.skill.construction.Hotspot;

/**
 * Magic, prayer, slayer and house building: the same calls the game makes when you click a spell, a prayer,
 * or a "Build" hotspot, so every game rule (runes, levels, quests, delays) still applies.
 */
final class Powers {
    private Powers() {}

    // spell target kinds, as the game numbers them (SpellListener)
    private static final int NPC_T = -1, OBJECT_T = -2, ITEM_T = -3, NONE_T = -5;

    static String spellbook(Player p) {
        int i = p.getSpellBookManager().getSpellBook();
        for (SpellBook b : SpellBook.values()) if (b.getInterfaceId() == i) return b.name().charAt(0) + b.name().substring(1).toLowerCase();
        return "Modern";
    }

    /**
     * Casts spell `button` from `book` on: nothing ("none"), an inventory slot ("item", a = slot),
     * an NPC ("npc", a = index) or an object ("object", a = x, b = y).
     * Returns "ok", "book:Name" (on another spellbook), "delay", "no-target" or "unknown".
     */
    static String cast(GameApi api, Player p, String book, int button, String kind, int a, int b) {
        SpellBook sb;
        try { sb = SpellBook.valueOf(book.toUpperCase()); } catch (IllegalArgumentException e) { return "unknown"; }
        if (p.getSpellBookManager().getSpellBook() != sb.getInterfaceId()) return "book:" + spellbook(p);
        if (p.getAttribute("magic:delay", -1) > GameWorld.getTicks() || p.getAttribute("magic-delay", 0) > GameWorld.getTicks()) return "delay";
        String name = sb.name().toLowerCase();
        api.closeForClick();
        Node target;
        int type;
        switch (kind) {
            case "item": target = a >= 0 && a < 28 ? p.getInventory().get(a) : null; type = ITEM_T; break;
            case "npc": target = npc(a); type = NPC_T; break;
            case "object": target = RegionManager.getObject(Location.create(a, b, p.getLocation().getZ())); type = OBJECT_T; break;
            default:
                SpellListeners.run(button, NONE_T, name, p, null);
                MagicSpell.castSpell(p, sb, button, p);
                return "ok";
        }
        if (target == null) return "no-target";
        SpellListeners.run(button, type, name, p, target);
        MagicSpell.castSpell(p, sb, button, target);
        return "ok";
    }

    private static NPC npc(int index) {
        for (NPC n : core.game.world.repository.Repository.getNpcs()) if (n != null && n.getIndex() == index) return n;
        return null;
    }

    // ---------- prayer ----------
    static PrayerType prayer(String name) {
        String n = Artisan.norm(name);
        for (PrayerType t : PrayerType.values()) if (Artisan.norm(t.name()).equals(n)) return t;
        for (PrayerType t : PrayerType.values()) if (Artisan.norm(t.name()).startsWith(n)) return t;
        return null;
    }

    /** Turns a prayer on or off. "ok", "already", "unknown", "level:N" or "refused" (no points, or a quest is needed). */
    static String setPrayer(Player p, String name, boolean on) {
        PrayerType t = prayer(name);
        if (t == null) return "unknown";
        boolean active = p.getPrayer().getActive().contains(t);
        if (active == on) return "already";
        if (on && p.getSkills().getStaticLevel(core.game.node.entity.skill.Skills.PRAYER) < t.getLevel()) return "level:" + t.getLevel();
        if (on && p.getSkills().getPrayerPoints() < 1) return "refused";
        return p.getPrayer().toggle(t) ? "ok" : "refused";
    }

    static String activePrayers(Player p) {
        StringBuilder sb = new StringBuilder("[");
        for (PrayerType t : p.getPrayer().getActive()) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(nice(t.name())).append('"');
        }
        return sb.append(']').toString();
    }

    static String nice(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------- slayer ----------
    static String slayerTask(Player p) {
        SlayerManager m = SlayerManager.getInstance(p);
        Json j = new Json();
        Tasks t = m.getActiveTask();
        if (t == null) { j.put("task", ""); j.put("amount", 0); return j.toString(); }
        j.put("task", nice(t.name()));
        j.put("amount", m.getAmount());
        j.put("master", m.getMaster() == null ? "" : nice(m.getMaster().name()));
        StringBuilder ids = new StringBuilder("[");
        for (int id : t.getNpcs()) { if (ids.length() > 1) ids.append(','); ids.append(id); }
        j.putRaw("ids", ids.append(']').toString());
        j.put("level", t.levelReq);
        return j.toString();
    }

    // ---------- construction ----------
    /**
     * Empty furniture spots ("Build" hotspots) near the character in their own house, with what each can take:
     * [{x, y, spot, distance, options: [{name, level, slot}]}]. `slot` is the button on the build menu.
     */
    static String hotspots(GameApi api, Player p, int radius) {
        StringBuilder sb = new StringBuilder("[");
        if (p.getHouseManager() == null) return "[]";
        for (Scenery s : api.objectsAround()) {
            if (s == null || !s.isActive() || !s.getLocation().withinDistance(p.getLocation(), radius)) continue;
            if (!s.getDefinition().hasAction("build")) continue;
            Hotspot h;
            try { h = p.getHouseManager().getHotspot(s); } catch (Throwable e) { h = null; }
            if (h == null) continue;
            BuildHotspot bh = h.getHotspot();
            BuildHotspot[] linked = BuildHotspot.getLinkedHotspots(bh);
            if (linked != null) bh = linked[0];
            Decoration[] decos = bh.getDecorations();
            if (sb.length() > 1) sb.append(',');
            Json j = new Json();
            j.put("x", s.getLocation().getX()); j.put("y", s.getLocation().getY());
            j.put("spot", nice(bh.name()));
            j.put("distance", Math.round(p.getLocation().getDistance(s.getLocation()) * 10) / 10.0);
            StringBuilder os = new StringBuilder("[");
            for (int i = 0; i < decos.length && i < 7; i++) {
                Decoration d = decos[i];
                if (d == null || d.isInvisibleNode()) continue;
                if (os.length() > 1) os.append(',');
                Json o = new Json();
                o.put("name", ItemDefinition.forId(d.getInterfaceItem()).getName());
                o.put("level", d.getLevel());
                o.put("slot", i < 4 ? i * 2 : (i - 4) * 2 + 1);     // the menu lists them in two columns
                StringBuilder need = new StringBuilder("[");
                for (Item it : d.getItems()) { if (need.length() > 1) need.append(','); need.append(Json.quote(it.getAmount() + " x " + it.getName())); }
                o.putRaw("needs", need.append(']').toString());
                os.append(o);
            }
            j.putRaw("options", os.append(']').toString());
            sb.append(j);
        }
        return sb.append(']').toString();
    }

    static boolean buildingMode(Player p) { return p.getHouseManager() != null && p.getHouseManager().isBuildingMode(); }
    static boolean inHouse(Player p) {
        try { return p.getHouseManager() != null && p.getHouseManager().isInHouse(p); } catch (Throwable e) { return false; }
    }
}
