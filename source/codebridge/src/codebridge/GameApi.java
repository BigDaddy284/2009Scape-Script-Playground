package codebridge;

import core.cache.def.impl.ItemDefinition;
import core.game.bots.ScriptAPI;
import core.game.node.Node;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.GroundItem;
import core.game.node.item.GroundItemManager;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.repository.Repository;

import org.graalvm.polyglot.HostAccess;

import java.util.*;

/**
 * The Java side of the script API. Every method runs on the game thread (called from a tick).
 * Methods return plain values or JSON text; prelude.js turns them into Fantasy Land style functions.
 */
public final class GameApi {
    static final String[] SKILL_NAMES = {"attack","defence","strength","hitpoints","ranged","prayer","magic","cooking",
        "woodcutting","fletching","fishing","firemaking","crafting","smithing","mining","herblore","agility","thieving",
        "slayer","farming","runecrafting","hunter","construction","summoning"};

    private final Player p;
    private final ScriptSession session;
    private final ScriptAPI api;

    GameApi(Player p, ScriptSession session) {
        this.p = p;
        this.session = session;
        this.api = new ScriptAPI(p);
    }

    // ---------- logging ----------
    @HostAccess.Export public void log(String msg) { session.log(msg); }
    @HostAccess.Export public void message(String msg) { session.setMessage(msg); }
    @HostAccess.Export public void say(String text) { api.sendChat(text); }

    // ---------- character ----------
    @HostAccess.Export public int tick() { return core.api.ContentAPIKt.getWorldTicks(); }

    @HostAccess.Export public String character() {
        Json j = new Json();
        Skills s = p.getSkills();
        j.put("name", p.getUsername());
        j.put("x", p.getLocation().getX()); j.put("y", p.getLocation().getY()); j.put("z", p.getLocation().getZ());
        j.put("hp", s.getLifepoints()); j.put("max_hp", s.getMaximumLifepoints());
        j.put("prayer", (int) s.getPrayerPoints()); j.put("max_prayer", s.getStaticLevel(Skills.PRAYER));
        j.put("run_energy", (int) p.getSettings().getRunEnergy()); j.put("run_on", p.getSettings().isRunToggled());
        j.put("combat_level", p.getProperties().getCurrentCombatLevel());
        j.put("moving", p.getWalkingQueue().isMoving());
        j.put("in_combat", p.inCombat());
        Node t = p.getProperties().getCombatPulse().getVictim();
        j.put("target", t instanceof NPC ? t.getIndex() : -1);
        j.put("free_slots", p.getInventory().freeSlots());
        j.put("busy", busy());
        j.put("locked", p.getLocks().isInteractionLocked());          // the game won't let you click things right now
        j.put("cant_move", p.getLocks().isMovementLocked());
        core.game.component.Component open = p.getInterfaceManager().getOpened(), chat = p.getInterfaceManager().getChatbox();
        j.put("screen", open == null ? -1 : open.getId());               // id of the game screen that is open, -1 for none
        j.put("chat_screen", chat == null ? -1 : chat.getId());
        Json lv = new Json(), base = new Json(), xp = new Json();
        for (int i = 0; i < SKILL_NAMES.length; i++) {
            lv.put(SKILL_NAMES[i], s.getLevel(i));
            base.put(SKILL_NAMES[i], s.getStaticLevel(i));
            xp.put(SKILL_NAMES[i], (long) s.getExperience(i));
        }
        j.putRaw("levels", lv.toString()); j.putRaw("base_levels", base.toString()); j.putRaw("xp", xp.toString());
        return j.toString();
    }

    @HostAccess.Export public String inventory() { return items(p.getInventory().toArray()); }
    @HostAccess.Export public String equipment() { return items(p.getEquipment().toArray()); }
    @HostAccess.Export public String bank() { return items(p.getBank().toArray()); }

    private static String items(Item[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            Item it = arr[i];
            if (it == null) continue;
            if (sb.length() > 1) sb.append(',');
            Json j = new Json();
            j.put("slot", i); j.put("id", it.getId()); j.put("name", it.getName()); j.put("amount", it.getAmount());
            sb.append(j);
        }
        return sb.append(']').toString();
    }

    // ---------- movement ----------
    /** Walks toward the target in legs of up to ~49 tiles (the server pathfinder limit). Call again each tick until arrived. */
    @HostAccess.Export public boolean walkTowards(int x, int y, int z) {
        Location dest = Location.create(x, y, z);
        if (p.getLocation().getZ() != z) return false;
        if (p.getWalkingQueue().isMoving()) return true;
        core.game.world.map.path.Path direct = core.game.world.map.path.Pathfinder.find(p, dest);
        if (direct != null && direct.isSuccessful() && !direct.isMoveNear() && !direct.getPoints().isEmpty()) { direct.walk(p); return true; }
        // too far for one path, or the way goes round something: follow a route over the whole area
        Location here = p.getLocation();
        String key = x + "," + y + "," + z + "," + agility();   // a new Agility level can open a shortcut
        if (!key.equals(routeKey) || route.isEmpty() || nearestOnRoute(here) < 0) {
            route = Router.route(here.getX(), here.getY(), x, y, z, agility());
            routeKey = key;
        }
        core.game.world.map.path.Path bestPath = null, nearPath = null;
        int at = nearestOnRoute(here);
        if (at >= 0) {
            for (int ahead : new int[]{30, 20, 12, 6, 3}) {
                int[] wp = route.get(Math.min(route.size() - 1, at + ahead));
                if (wp[0] == here.getX() && wp[1] == here.getY()) continue;
                core.game.world.map.path.Path q = core.game.world.map.path.Pathfinder.find(p, Location.create(wp[0], wp[1], z));
                if (q == null || q.getPoints().isEmpty()) continue;
                if (q.isSuccessful() && !q.isMoveNear()) { bestPath = q; break; }
                nearPath = q;     // a closed door on the route: get up to it (the nearest waypoint's way), the script opens it
            }
        }
        if (bestPath != null) { bestPath.walk(p); return true; }
        if (nearPath != null) { nearPath.walk(p); return true; }
        if (direct != null && !direct.getPoints().isEmpty()) { direct.walk(p); return true; }   // as near as the way allows
        return false;
    }
    /** How close a walk on this floor can get to x,y (0 = all the way), doors counted as open. */
    @HostAccess.Export public double routeGap(int x, int y) {
        Location here = p.getLocation();
        List<int[]> r = Router.route(here.getX(), here.getY(), x, y, here.getZ(), agility());
        if (r.isEmpty()) return 9999;
        int[] end = r.get(r.size() - 1);
        return Math.hypot(end[0] - x, end[1] - y);
    }

    /** Steps on foot from here to (x, y) on this floor, or -1 when you can't get within `near` tiles of it. */
    @HostAccess.Export public int routeLength(int x, int y, int near) {
        Location here = p.getLocation();
        List<int[]> r = Router.route(here.getX(), here.getY(), x, y, here.getZ(), agility());
        if (r.isEmpty()) return -1;
        int[] end = r.get(r.size() - 1);
        return Math.hypot(end[0] - x, end[1] - y) <= near ? r.size() : -1;
    }

    private List<int[]> route = new ArrayList<>();
    private String routeKey = "";

    /** Index of the route tile nearest the character (within 6 tiles), or -1 when it has left the route. */
    private int nearestOnRoute(Location here) {
        int best = -1; double bd = 6.5;
        for (int i = 0; i < route.size(); i++) {
            double d = Math.hypot(route.get(i)[0] - here.getX(), route.get(i)[1] - here.getY());
            if (d <= bd) { bd = d; best = i; }
        }
        return best;
    }

    @HostAccess.Export public boolean moving() { return p.getWalkingQueue().isMoving(); }
    @HostAccess.Export public void stopMoving() { p.getWalkingQueue().reset(); p.getPulseManager().clear(); }
    @HostAccess.Export public void teleport(int x, int y, int z) { p.getProperties().setTeleportLocation(Location.create(x, y, z)); }
    @HostAccess.Export public double distanceTo(int x, int y) { return p.getLocation().getDistance(Location.create(x, y, p.getLocation().getZ())); }

    // ---------- NPCs and combat ----------
    @HostAccess.Export public String npcs(String name, int radius) {
        StringBuilder sb = new StringBuilder("[");
        for (NPC n : RegionManager.getLocalNpcs(p, radius)) {
            if (n == null || !n.isActive() || n.isHidden(p)) continue;
            if (name != null && !name.isEmpty() && !n.getName().equalsIgnoreCase(name)) continue;
            if (sb.length() > 1) sb.append(',');
            Json j = new Json();
            j.put("index", n.getIndex()); j.put("id", n.getId()); j.put("name", n.getName());
            j.put("level", n.getDefinition().getCombatLevel());
            j.put("x", n.getLocation().getX()); j.put("y", n.getLocation().getY()); j.put("z", n.getLocation().getZ());
            j.put("hp", n.getSkills().getLifepoints()); j.put("max_hp", n.getSkills().getMaximumLifepoints());
            j.put("in_combat", n.inCombat());
            j.put("attackable", n.getDefinition().hasAction("attack"));
            j.put("distance", Math.round(p.getLocation().getDistance(n.getLocation()) * 10) / 10.0);
            sb.append(j);
        }
        return sb.append(']').toString();
    }

    private NPC npc(int index) {
        for (NPC n : Repository.getNpcs()) if (n != null && n.getIndex() == index) return n;
        return null;
    }

    @HostAccess.Export public boolean attack(int index) {
        NPC n = npc(index);
        if (n == null || !n.isActive()) return false;
        p.attack(n);
        return true;
    }

    @HostAccess.Export public boolean npcAction(int index, String option) {
        NPC n = npc(index);
        if (n == null) return false;
        return clickNpc(n, option).equals("ok");
    }

    // ---------- items ----------
    @HostAccess.Export public int itemId(String name) {
        for (ItemDefinition d : ItemDefinition.getDefinitions().values()) {
            if (d != null && d.getName() != null && d.getName().equalsIgnoreCase(name) && d.isUnnoted()) return d.getId();
        }
        return -1;
    }

    /** Clicks an option ("Eat", "Drink", "Bury", "Wield"...) on the first inventory item with that name. */
    @HostAccess.Export public String itemAction(String name, String option) {
        Item[] inv = p.getInventory().toArray();
        for (int i = 0; i < inv.length; i++) {
            Item it = inv[i];
            if (it == null || !it.getName().equalsIgnoreCase(name)) continue;
            Item real = p.getInventory().get(i);
            boolean listener = core.game.interaction.InteractionListeners.run(real.getId(), core.game.interaction.IntType.ITEM, option, p, real);
            if (listener) return "ok:listener";
            core.game.interaction.Option opt = null;
            for (core.game.interaction.Option o : real.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) opt = o;
            if (opt == null) return "no-option";
            if (opt.getHandler() == null) opt.setHandler(core.game.interaction.Option.defaultHandler(real, real.getId(), opt.getName()));
            real.getInteraction().handleItemOption(p, opt, p.getInventory());    // the game's own path for an inventory click
            return "ok:handler";
        }
        return "no-item";
    }

    @HostAccess.Export public String groundItems(int radius) {
        StringBuilder sb = new StringBuilder("[");
        for (GroundItem g : GroundItemManager.getItems()) {
            if (g == null || !g.isActive()) continue;
            if (g.getLocation().getZ() != p.getLocation().getZ()) continue;
            if (!g.getLocation().withinDistance(p.getLocation(), radius)) continue;
            if (g.isPrivate() && !g.droppedBy(p)) continue;
            if (sb.length() > 1) sb.append(',');
            Json j = new Json();
            j.put("id", g.getId()); j.put("name", g.getName()); j.put("amount", g.getAmount());
            j.put("x", g.getLocation().getX()); j.put("y", g.getLocation().getY());
            j.put("value", ItemDefinition.forId(g.getId()).getValue());
            sb.append(j);
        }
        return sb.append(']').toString();
    }

    @HostAccess.Export public boolean pickUp(int id, int x, int y) {
        GroundItem g = GroundItemManager.get(id, Location.create(x, y, p.getLocation().getZ()), p);
        if (g == null) return false;
        core.game.interaction.Option take = null;
        for (core.game.interaction.Option o : g.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase("take")) take = o;
        if (take == null) return false;
        g.getInteraction().handle(p, take);
        return true;
    }

    // ---------- objects ----------
    /** Scenery in the character's region and the eight regions around it (a region is 64x64 tiles). */
    List<Scenery> objectsAround() {
        List<Scenery> out = new ArrayList<>();
        int rx = p.getLocation().getRegionX() >> 3, ry = p.getLocation().getRegionY() >> 3, z = p.getLocation().getZ();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            core.game.world.map.Region r = RegionManager.forId(((rx + dx) << 8) | (ry + dy));
            if (r == null) continue;
            core.game.world.map.RegionPlane plane = r.getPlanes()[z];
            core.game.world.map.RegionChunk[][] chunks = plane.getChunks();
            if (chunks == null || !(r instanceof core.game.world.map.build.DynamicRegion)) { out.addAll(plane.getObjectList()); continue; }
            // read the chunks: a built house (a copied region) keeps its objects only there
            for (core.game.world.map.RegionChunk[] row : chunks) for (core.game.world.map.RegionChunk c : row) {
                if (c == null) continue;
                for (int ox = 0; ox < 8; ox++) for (int oy = 0; oy < 8; oy++) {
                    Scenery[] here = c.getObjects(ox, oy);
                    if (here != null) for (Scenery o : here) if (o != null && o.isRenderable()) out.add(o);
                }
            }
        }
        return out;
    }

    /** What this player sees: farming patches and similar objects change with the player's own progress. */
    Scenery shown(Scenery s) {
        try { Scenery c = s.getChild(p); if (c != null) return c; } catch (Throwable ignored) {}
        return s;
    }
    private String shownName(Scenery s) {
        String n = shown(s).getName();
        if (n == null || n.equals("null")) n = s.getName();
        return n == null || n.equals("null") ? "" : n;
    }

    // ---------- the Use engine: the same packets the game client sends, so every game rule applies ----------

    private static final int INVENTORY = 149;

    private Item slot(int slot) { return slot < 0 || slot >= 28 ? null : p.getInventory().get(slot); }

    /**
     * The game's own "use X with Y" path, in the same order the server follows for a click from the game client.
     * Returns who handled it, or "nothing" when the game has no use for that pair ("Nothing interesting happens").
     */
    private String useWith(Item item, Node node, core.game.interaction.IntType type, Node child) {
        if (p.getLocks().isInteractionLocked() || p.getLocks().isMovementLocked()) return "busy";
        p.scripts.reset();
        if (p.getZoneMonitor().useWith(item, node)) return "zone";
        if (core.game.interaction.InteractionListeners.run(item, node, type, p)) return "listener";
        if (child != null && child.getId() != node.getId() && core.game.interaction.InteractionListeners.run(item, child, type, p)) return "listener";
        boolean flipped = type == core.game.interaction.IntType.ITEM && item.getId() < node.getId();
        core.game.interaction.NodeUsageEvent ev = flipped ? new core.game.interaction.NodeUsageEvent(p, 0, node, item)
                                                          : new core.game.interaction.NodeUsageEvent(p, 0, item, child != null ? child : node);
        if (core.game.interaction.PluginInteractionManager.handle(p, ev)) return "plugin";
        core.game.interaction.UseWithHandler.run(ev);
        return "handler";
    }

    /** Use the item in inventory slot a on the item in slot b (0-based). */
    @HostAccess.Export public String useSlotOnSlot(int a, int b) {
        Item x = slot(a), y = slot(b);
        if (x == null || y == null) return "no-item";
        if (a == b) return "same-slot";
        return useWith(x, y, core.game.interaction.IntType.ITEM, null);
    }

    /** Use the item in a slot on the object at x,y (walks there first, like a click). */
    @HostAccess.Export public String useSlotOnObjectAt(int slot, int x, int y) {
        closeForClick();
        Item it = slot(slot);
        if (it == null) return "no-item";
        Scenery s = RegionManager.getObject(Location.create(x, y, p.getLocation().getZ()));
        if (s == null) return "no-object";
        Scenery c = shown(s);
        return useWith(it, s, core.game.interaction.IntType.SCENERY, c != s ? c : null);
    }

    /** Use the item in a slot on an NPC (by its index). */
    @HostAccess.Export public String useSlotOnNpc(int slot, int index) {
        closeForClick();
        Item it = slot(slot);
        NPC n = npc(index);
        if (it == null) return "no-item";
        if (n == null) return "no-npc";
        NPC shownNpc = n.getShownNPC(p);
        return useWith(it, n, core.game.interaction.IntType.NPC, shownNpc != n ? shownNpc : null);
    }

    /** Use the item in a slot on an item lying on the ground. */
    @HostAccess.Export public String useSlotOnGround(int slot, String groundName) {
        closeForClick();
        Item it = slot(slot);
        if (it == null) return "no-item";
        GroundItem best = null; double bd = 1e9;
        for (GroundItem g : GroundItemManager.getItems()) {
            if (g == null || !g.isActive() || !g.getName().equalsIgnoreCase(groundName)) continue;
            double d = g.getLocation().getDistance(p.getLocation());
            if (g.getLocation().getZ() == p.getLocation().getZ() && d < bd) { bd = d; best = g; }
        }
        if (best == null) return "no-ground-item";
        return useWith(it, best, core.game.interaction.IntType.GROUNDITEM, null);
    }

    /** Make a product from a full-screen menu (smelt, smith, jewellery, silver, spin, glass, leather, tan). */
    @HostAccess.Export public String craft(String product, int amount) { return Artisan.make(p, product, amount); }

    /** Working on something: a skill pulse, or a repeating skill action (fishing, mining and woodcutting run as scripts). */
    boolean busy() {
        if (p.getPulseManager().hasPulseRunning()) return true;
        core.game.interaction.ScriptProcessor sp = p.scripts;
        if (sp.getPersistent()) return true;    // a repeating action (fishing, cutting, mining) still going
        for (core.game.interaction.QueueStrength q : core.game.interaction.QueueStrength.values()) if (sp.hasTypeInQueue(q)) return true;
        return false;
    }

    // ---------- hunter and farming ----------
    @HostAccess.Export public String myTraps() { return Nature.traps(p); }
    @HostAccess.Export public String trapCount() { return Nature.trapCount(p); }
    @HostAccess.Export public String trackNext() { return Nature.trackNext(p); }
    @HostAccess.Export public String patches(int radius) { return Nature.patches(this, p, radius); }

    // ---------- magic, prayer, slayer, combat settings, house building ----------
    @HostAccess.Export public String spellbook() { return Powers.spellbook(p); }
    @HostAccess.Export public String castSpell(String book, int button, String kind, int a, int b) { return Powers.cast(this, p, book, button, kind, a, b); }
    @HostAccess.Export public String setPrayer(String name, boolean on) { return Powers.setPrayer(p, name, on); }
    @HostAccess.Export public String activePrayers() { return Powers.activePrayers(p); }
    @HostAccess.Export public String slayerTask() { return Powers.slayerTask(p); }
    @HostAccess.Export public boolean retaliate() { return p.getProperties().isRetaliating(); }
    /** Run on or off (the run orb), and how much run energy is left (0 to 100). */
    @HostAccess.Export public void setRun(boolean on) { p.getSettings().setRunToggled(on); }
    @HostAccess.Export public boolean runOn() { return p.getSettings().isRunToggled(); }
    @HostAccess.Export public int runEnergy() { return (int) p.getSettings().getRunEnergy(); }
    @HostAccess.Export public void setRetaliate(boolean on) {
        p.getProperties().setRetaliating(on);
        core.api.ContentAPIKt.setVarp(p, 172, on ? 0 : 1, false);    // the button on the combat tab
    }
    @HostAccess.Export public String hotspots(int radius) { return Powers.hotspots(this, p, radius); }
    @HostAccess.Export public boolean buildingMode() { return Powers.buildingMode(p); }
    @HostAccess.Export public boolean inHouse() { return Powers.inHouse(p); }
    @HostAccess.Export public boolean hasFamiliar() { return p.getFamiliarManager().hasFamiliar(); }

    private static volatile String catalogJson;
    /** The game catalogue (catalog.json next to codebridge.jar), read once. */
    @HostAccess.Export public String catalog() {
        if (catalogJson == null) {
            try {
                java.nio.file.Path jar = java.nio.file.Paths.get(GameApi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                catalogJson = new String(java.nio.file.Files.readAllBytes(jar.resolveSibling("catalog.json")), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) { catalogJson = "{}"; }
        }
        return catalogJson;
    }

    static content.data.Quests questByName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        for (content.data.Quests q : content.data.Quests.values())
            if (q.name().replace("_", "").toLowerCase().equals(n) || q.getQuestName().replaceAll("[^A-Za-z0-9]", "").toLowerCase().equals(n)) return q;
        return null;
    }

    /** "done", "started", "not-started" or "unknown" for a quest by its name. */
    @HostAccess.Export public String questState(String name) {
        content.data.Quests q = questByName(name);
        if (q == null) return "unknown";
        if (p.getQuestRepository().isComplete(q)) return "done";
        return p.getQuestRepository().hasStarted(q) ? "started" : "not-started";
    }

    /** 0-based slots holding an item with this name, in order. */
    @HostAccess.Export public String slotsOf(String name) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 28; i++) {
            Item it = p.getInventory().get(i);
            if (it != null && it.getName().equalsIgnoreCase(name)) { if (sb.length() > 1) sb.append(','); sb.append(i); }
        }
        return sb.append(']').toString();
    }

    /** Item name in a 0-based slot, or "" */
    @HostAccess.Export public String slotName(int slot) { Item it = slot(slot); return it == null ? "" : it.getName(); }

    // ---------- dialogue, menus and keys ----------

    /** What the chat box shows: {"id", "kind": none|continue|options|make|input, "count", "make": [names]} */
    @HostAccess.Export public String chatbox() {
        Json j = new Json();
        core.game.component.Component c = p.getInterfaceManager().getChatbox();
        int id = c == null ? -1 : c.getId();
        j.put("id", id);
        String kind = "none";
        core.game.dialogue.DialoguePlugin d = p.getDialogueInterpreter().getDialogue();
        if (d instanceof core.game.dialogue.SkillDialoguePlugin) {
            kind = "make";
            core.game.dialogue.SkillDialogueHandler h = makeHandler();
            List<String> names = new ArrayList<>();
            if (h != null) for (Item it : h.getItems()) names.add(it.getName());
            j.putRaw("make", Json.array(names));
        } else if (id == 307 || id == 309) {
            kind = "make";    // a one-product "how many?" menu run by a dialogue file (cooking and others)
            j.putRaw("make", "[]");
        } else if (id >= 228 && id <= 235 && (id - 224) % 2 == 0) {
            kind = "options";
            j.put("count", (id - 224) / 2);
        } else if (id == 389 || id == 390 || id == 392) {
            kind = "input";
        } else if (id != -1 && id != 137) {
            kind = "continue";
        }
        if (p.getAttribute("runscript", null) != null && kind.equals("none")) kind = "input";
        j.put("kind", kind);
        j.put("screen", p.getInterfaceManager().getOpened() == null ? -1 : p.getInterfaceManager().getOpened().getId());
        return j.toString();
    }

    private core.game.dialogue.SkillDialogueHandler makeHandler() {
        core.game.dialogue.DialoguePlugin d = p.getDialogueInterpreter().getDialogue();
        if (!(d instanceof core.game.dialogue.SkillDialoguePlugin)) return null;
        try {
            java.lang.reflect.Field f = core.game.dialogue.SkillDialoguePlugin.class.getDeclaredField("handler");
            f.setAccessible(true);
            return (core.game.dialogue.SkillDialogueHandler) f.get(d);
        } catch (Throwable t) { return null; }
    }

    /** the child of the open chat box that says "Click here to continue" */
    private int continueChild(int iface) {
        for (int c = 0; c < 40; c++) {
            try {
                core.cache.def.impl.IfaceDefinition d = core.cache.def.impl.IfaceDefinition.forId(iface, c);
                if (d == null) continue;
                String t = d.text == null ? "" : d.text.toLowerCase();
                if (t.contains("continue")) return c;
            } catch (Throwable ignored) { break; }
        }
        return 2;
    }

    /**
     * A key press in the chat box: "space"/"enter" continues a dialogue, "1".."5" picks an option,
     * "escape" closes what is open. Returns what happened.
     */
    @HostAccess.Export public String pressKey(String key) {
        String k = key.trim().toLowerCase();
        core.game.component.Component c = p.getInterfaceManager().getChatbox();
        int id = c == null ? -1 : c.getId();
        if (k.equals("escape") || k.equals("esc")) {
            p.getInterfaceManager().close();
            p.getInterfaceManager().closeChatbox();
            p.getDialogueInterpreter().close();
            if (p.getAttribute("runscript", null) != null) { p.removeAttribute("runscript"); p.removeAttribute("parseamount"); p.getPacketDispatch().sendRunScript(101, ""); }
            return "closed";
        }
        if (id == -1 || id == 137) return "nothing-open";
        boolean options = id >= 228 && id <= 235 && (id - 224) % 2 == 0;
        if (k.matches("[1-5]")) {
            int n = Integer.parseInt(k);
            if (!options) return "no-options";
            int count = (id - 224) / 2;
            if (n > count) return "only-" + count;
            continueOption(id, n + 1, 0);
            return "ok";
        }
        if (k.equals("space") || k.equals("enter") || k.equals("return") || k.equals("continue")) {
            if (options) return "choose-option";
            if (p.getDialogueInterpreter().getDialogue() instanceof core.game.dialogue.SkillDialoguePlugin) return "make-menu";
            continueOption(id, continueChild(id), 0);
            return "ok";
        }
        return "unknown-key";
    }

    /** An open screen by id, including the chatbox and its overlay. */
    private core.game.component.Component component(int iface) {
        core.game.node.entity.player.link.InterfaceManager m = p.getInterfaceManager();
        core.game.component.Component c = m.getComponent(iface);
        if (c == null && m.getChatbox() != null && m.getChatbox().getId() == iface) c = m.getChatbox();
        if (c == null && m.getOpened() != null && m.getOpened().getId() == iface) c = m.getOpened();
        if (c == null && m.getOverlay() != null && m.getOverlay().getId() == iface) c = m.getOverlay();
        return c;
    }

    /** What the server does when a dialogue line is continued or an option chosen. */
    private void continueOption(int iface, int child, int slot) {
        if (p.getDialogueInterpreter().getDialogue() == null) {
            p.getInterfaceManager().closeChatbox();
            List<core.game.dialogue.DialogueAction> acts = p.getDialogueInterpreter().getActions();
            if (!acts.isEmpty()) acts.remove(0).handle(p, child);
            core.game.component.Component c = component(iface);
            if (c == null) return;
            if (!core.game.interaction.InterfaceListeners.run(p, c, 0, child, slot, -1) && c.getPlugin() != null) c.getPlugin().handle(p, c, 0, child, slot, -1);
            return;
        }
        p.getDialogueInterpreter().handle(iface, child);
    }

    /** Pick a product in a "how many do you want to make?" menu. amount 0 = All. */
    @HostAccess.Export public String makeChoice(String product, int amount) {
        core.game.dialogue.SkillDialogueHandler h = makeHandler();
        if (h == null) {
            // one-product menu with buttons: 5 = make 1, 4 = make 5, 3 = make X, 2 = make All
            core.game.component.Component cb = p.getInterfaceManager().getChatbox();
            int id = cb == null ? -1 : cb.getId();
            if (id != 307 && id != 309) return "no-menu";
            int button = amount <= 0 ? 2 : amount == 1 ? 5 : amount == 5 ? 4 : 3;
            continueOption(id, button + 1, 0);    // the dialogue sees the client's button number minus one
            if (button == 3) return answerInput(String.valueOf(amount));
            return "ok";
        }
        Item[] items = h.getItems();
        int index = -1;
        String want = Artisan.norm(product);
        for (int i = 0; i < items.length; i++) if (items[i] != null && Artisan.norm(items[i].getName()).equals(want)) index = i;
        if (index == -1 && want.isEmpty()) index = 0;
        // "Oak longbow (u)" picks the menu's "Oak longbow"; "Attack potion" picks "Attack potion(3)"
        if (index == -1) for (int i = 0; i < items.length; i++) { String n = items[i] == null ? "" : Artisan.norm(items[i].getName());
            if (!n.isEmpty() && (n.startsWith(want) || want.startsWith(n))) { index = i; break; } }
        if (index == -1) {
            List<String> names = new ArrayList<>();
            for (Item it : items) if (it != null) names.add(it.getName());
            return "not-in-menu:" + String.join(", ", names);
        }
        int n = amount <= 0 ? h.getAll(index) : amount;
        p.getDialogueInterpreter().getDialogue().end();
        h.create(Math.max(1, n), index);
        return "ok";
    }

    /** Answer an "Enter amount" or "Enter name" box. */
    @HostAccess.Export public String answerInput(String value) {
        Object v = value.matches("\\d+") ? (Object) Integer.parseInt(value) : value;
        @SuppressWarnings("unchecked") kotlin.jvm.functions.Function1<Object, Boolean> script = (kotlin.jvm.functions.Function1<Object, Boolean>) p.getAttribute("runscript", null);
        if (script == null) return "no-input-box";
        if (p.getLocks().isInteractionLocked()) return "busy";
        try { core.net.packet.in.RunScript.INSTANCE.processInput(p, v, script); }
        finally { p.removeAttribute("parseamount"); p.removeAttribute("runscript"); p.getPacketDispatch().sendRunScript(101, ""); }    // 101 closes the typing box on screen
        return "ok";
    }

    /** Click a button on a game screen: interface, child, option number (1-based), slot, item id. */
    @HostAccess.Export public String clickButton(int iface, int child, int option, int slot, int itemId) {
        int[] opcodes = {155, 196, 124, 199, 234, 168, 166, 64, 53, 9};
        int op = opcodes[Math.max(0, Math.min(opcodes.length - 1, option - 1))];
        // chatbox dialogue buttons (make menus, options) come in as "continue" from the real client
        core.game.component.Component cb = p.getInterfaceManager().getChatbox();
        if (cb != null && cb.getId() == iface && p.getDialogueInterpreter().getDialogue() != null) { continueOption(iface, child, slot); return "ok"; }
        // same steps as the server does for a real click
        if (p.getDialogueInterpreter().getDialogue() != null && op != 132 && iface != 64 && iface != 746) p.getDialogueInterpreter().close();
        if (p.getLocks().isComponentLocked()) return "busy";
        if (p.getZoneMonitor().clickButton(iface, child, slot, itemId, op)) return "ok";
        core.game.component.Component c = component(iface);
        if (c == null || c.isHidden()) return "not-open";
        p.dispatch(new core.game.event.ButtonClickEvent(c.getId(), child));
        if (!core.game.interaction.InterfaceListeners.run(p, c, op, child, slot, itemId) && c.getPlugin() != null) c.getPlugin().handle(p, c, op, child, slot, itemId);
        return "ok";
    }

    private Scenery nearestObject(String name, String option) {
        Scenery best = null; double bd = Double.MAX_VALUE;
        for (Scenery s : objectsAround()) {
            if (s == null || !s.isActive() || !(name.isEmpty() || shownName(s).equalsIgnoreCase(name))) continue;
            if (option != null && !hasOption(shown(s), option)) continue;
            double d = p.getLocation().getDistance(s.getLocation());
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    @HostAccess.Export public String objects(String name, int radius) {
        StringBuilder sb = new StringBuilder("[");
        for (Scenery s : objectsAround()) {
            if (s == null || !s.isActive()) continue;
            String shownName = shownName(s);
            if (shownName.isEmpty() || !(name.isEmpty() || shownName.equalsIgnoreCase(name))) continue;
            if (!s.getLocation().withinDistance(p.getLocation(), radius)) continue;
            if (sb.length() > 1) sb.append(',');
            Json j = new Json();
            j.put("id", s.getId()); j.put("name", shownName);
            List<String> opts = new ArrayList<>();
            for (core.game.interaction.Option o : shown(s).getInteraction().getOptions()) if (o != null && o.getName() != null && !o.getName().equalsIgnoreCase("null")) opts.add(o.getName());
            j.put("shown_id", shown(s).getId());
            j.putRaw("options", Json.array(opts));
            j.put("x", s.getLocation().getX()); j.put("y", s.getLocation().getY());
            j.put("distance", Math.round(p.getLocation().getDistance(s.getLocation()) * 10) / 10.0);
            try { // rocks are all called "Rocks"; say which ore they give
                content.global.skill.gather.mining.MiningNode mn = content.global.skill.gather.mining.MiningNode.forId(s.getId());
                if (mn != null) { j.put("ore", ItemDefinition.forId(mn.getReward()).getName()); j.put("level", mn.getLevel()); }
            } catch (Throwable ignored) {}
            sb.append(j);
        }
        return sb.append(']').toString();
    }

    /**
     * Checks the walk to x,y (or to NPC npcIndex when it is 0 or more). When the way is blocked, also names the
     * closed door or gate where the path stops, so the script can open it.
     * {"reachable":false,"end_x":..,"end_y":..,"door":{"name":"Gate","x":..,"y":..,"distance":..}}
     */
    @HostAccess.Export public String pathCheck(int x, int y, int npcIndex) {
        core.game.node.Node target = npcIndex >= 0 ? npc(npcIndex) : Location.create(x, y, p.getLocation().getZ());
        if (target == null) return "null";
        core.game.world.map.path.Path path = core.game.world.map.path.Pathfinder.find(p, target);
        boolean ok = path != null && path.isSuccessful() && !path.isMoveNear();
        Location end = p.getLocation();
        if (path != null && !path.getPoints().isEmpty()) {
            core.game.world.map.Point last = path.getPoints().peekLast();
            end = Location.create(last.getX(), last.getY(), p.getLocation().getZ());
        }
        Json j = new Json().put("reachable", ok).put("end_x", end.getX()).put("end_y", end.getY());
        if (!ok) {
            Scenery door = closedDoorNear(end, target.getLocation());
            if (door == null) door = doorOnRoute(target.getLocation());
            if (door != null) j.putRaw("door", new Json().put("name", door.getName()).put("option", passageOption(door, agility())).put("x", door.getLocation().getX())
                .put("y", door.getLocation().getY()).put("distance", Math.round(p.getLocation().getDistance(door.getLocation()) * 10) / 10.0).toString());
            else j.putRaw("door", "null");
        } else j.putRaw("door", "null");
        return j.toString();
    }

    /**
     * The first door or gate on the planned route, if it is close. The game's own path can stop somewhere useless
     * (a garden on the far side of a wall, nearest to the goal as the crow flies), so its end isn't always beside the door.
     */
    private Scenery doorOnRoute(Location goal) {
        Location here = p.getLocation();
        List<int[]> r = Router.route(here.getX(), here.getY(), goal.getX(), goal.getY(), here.getZ(), agility());
        for (int i = 0; i < r.size() && i < 25; i++) {
            Scenery s = core.game.world.map.RegionManager.getObject(Location.create(r.get(i)[0], r.get(i)[1], here.getZ()));
            if (s != null && s.isActive() && passageOption(s, agility()) != null) return s;
        }
        return null;
    }

    /** A closed door or gate (anything with an "Open" option) within 3 tiles of where the path stops. */
    private Scenery closedDoorNear(Location end, Location goal) {
        Scenery best = null; double bd = Double.MAX_VALUE;
        for (Scenery s : objectsAround()) {
            if (s == null || !s.isActive() || s.getName() == null) continue;
            if (passageOption(s, agility()) == null) continue;
            Location l = s.getLocation();
            if (l.getZ() != end.getZ()) continue;
            int cheb = Math.max(Math.abs(l.getX() - end.getX()), Math.abs(l.getY() - end.getY()));
            if (cheb > 3) continue;
            double d = cheb * 100 + l.getDistance(goal);   // closest to where we got stuck, then toward the goal
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Ways through on foot: doors and gates, stiles, ditches, passes and the like. */
    private static final String[] PASSAGE = {"Open", "Cross", "Climb-over", "Go-through", "Squeeze-through", "Jump", "Jump-over", "Pass", "Walk-through", "Pay-toll(10gp)"};

    /** The option that gets through this object on foot, or null if it isn't a way through. */
    static String passageOption(Scenery s) {
        if (s == null || s.getName() == null) return null;
        String n = s.getName().toLowerCase();
        for (String o : PASSAGE) {
            if (!hasOption(s, o)) continue;
            if (o.equals("Open") && !(n.contains("door") || n.contains("gate"))) continue;   // not chests and wardrobes
            return o;
        }
        return null;
    }

    /** The way through, but only when the player has the Agility level for it (a shortcut above it is a wall). */
    static String passageOption(Scenery s, int agility) {
        String o = passageOption(s);
        return o == null || shortcutLevel(s, o) > agility ? null : o;
    }

    /** The Agility level an object's option needs (crumbling walls, log balances, squeeze-throughs...), 0 when none. */
    static int shortcutLevel(Scenery s, String option) {
        try {
            Object h = core.cache.def.impl.SceneryDefinition.forId(s.getId()).getHandlers().get("option:" + option.toLowerCase());
            if (h instanceof content.global.skill.agility.AgilityShortcut) return ((content.global.skill.agility.AgilityShortcut) h).getLevel();
        } catch (Throwable ignored) {}
        return 0;
    }

    int agility() { return p.getSkills().getLevel(core.game.node.entity.skill.Skills.AGILITY); }

    /** Clicks an option on the object standing at x,y (for when several objects share a name). */
    @HostAccess.Export public String objectActionAt(int x, int y, String option) {
        Scenery s = core.game.world.map.RegionManager.getObject(Location.create(x, y, p.getLocation().getZ()));
        if (s == null) return "no-object";
        return clickObject(s, option);
    }

    /** What the server does for a real click on an object option, using what this player sees there. */
    /** Clicking out in the world closes open dialogues and screens first, as walking does in the real client. */
    void closeForClick() {
        if (p.getDialogueInterpreter().getDialogue() != null) p.getDialogueInterpreter().close();
        p.getInterfaceManager().close();
        p.getInterfaceManager().closeChatbox();
    }

    String clickObject(Scenery s, String option) {
        closeForClick();
        Scenery c = shown(s);
        core.game.interaction.Option opt = null;
        for (core.game.interaction.Option o : c.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) { opt = o; break; }
        if (opt == null) return "no-option";
        p.scripts.reset();
        if (core.game.interaction.InteractionListeners.run(c.getId(), core.game.interaction.IntType.SCENERY, opt.getName(), p, c)) return "ok";
        if (core.game.interaction.PluginInteractionManager.handle(p, c)) return "ok";
        c.getInteraction().handle(p, opt);
        return "ok";
    }

    static boolean hasOption(Scenery c, String option) {
        for (core.game.interaction.Option o : c.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) return true;
        return false;
    }

    /** The same for an NPC option. */
    String clickNpc(NPC n, String option) {
        closeForClick();
        NPC c = n.getShownNPC(p);
        core.game.interaction.Option opt = null;
        for (core.game.interaction.Option o : c.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) { opt = o; break; }
        if (opt == null) return "no-option";
        p.scripts.reset();
        if (core.game.interaction.InteractionListeners.run(c.getId(), core.game.interaction.IntType.NPC, opt.getName(), p, n)) return "ok";
        if (core.game.interaction.PluginInteractionManager.handle(p, c, opt)) return "ok";
        n.getInteraction().handle(p, opt);
        return "ok";
    }

    @HostAccess.Export public String objectAction(String name, String option) {
        Scenery s = nearestObject(name, option);
        if (s == null) return "no-object";
        return clickObject(s, option);
    }

    @HostAccess.Export public String useItemOnObject(String itemName, String objectName) {
        int id = -1;
        for (Item it : p.getInventory().toArray()) if (it != null && it.getName().equalsIgnoreCase(itemName)) { id = it.getId(); break; }
        if (id == -1) return "no-item";
        Scenery s = nearestObject(objectName, null);
        if (s == null) return "no-object";
        api.useWith(p, id, s);
        return "ok";
    }

    // ---------- bank (uses the built-in bot helpers) ----------
    @HostAccess.Export public void bankDepositAll() { api.depositAtBank(); }
    @HostAccess.Export public void bankWithdraw(String name, int amount) { int id = itemId(name); if (id != -1) api.withdraw(id, amount); }

    // ---------- interfaces ----------
    @HostAccess.Export public boolean interfaceOpen() { return p.getInterfaceManager().isOpened() || p.getInterfaceManager().getChatbox() != null && p.getInterfaceManager().getChatbox().getId() != 137; }
    @HostAccess.Export public void closeInterfaces() { p.getInterfaceManager().close(); p.getInterfaceManager().closeChatbox(); p.getDialogueInterpreter().close(); }

    // ---------- block scripts ----------
    /** The block that is running now (the Playground lights it up). */
    @HostAccess.Export public void block(String id) { session.block = id; }

    /** A block could not do its job: say why in the game chat, light the block up red and stop the script. */
    @HostAccess.Export public void fail(String id, String reason) {
        session.failedBlock = id;
        session.failReason = reason;
        p.sendMessage("<col=a00000>Script stopped:</col> " + reason);
        session.log("stopped: " + reason);
        session.requestStop("block could not continue");
    }

    /** The block script reached its last block. */
    @HostAccess.Export public void done() {
        session.block = "";
        p.sendMessage("<col=0040a0>Script finished.</col>");
        session.log("finished");
        session.requestStop("finished");
    }

    /** A message in the game chat from the script. */
    @HostAccess.Export public void chat(String msg) { p.sendMessage("<col=0040a0>Script:</col> " + msg); }

    /** Why this tree can't be chopped right now ("" when it can). */
    @HostAccess.Export public String chopCheck(int x, int y) {
        Scenery s = RegionManager.getObject(Location.create(x, y, p.getLocation().getZ()));
        if (s == null) return "that tree is gone.";
        String name = s.getName();
        if (content.data.skill.SkillingTool.getHatchet(p) == null) return "you need an axe to chop the " + name + ". Carry one or wear one.";
        content.global.skill.gather.woodcutting.WoodcuttingNode node = content.global.skill.gather.woodcutting.WoodcuttingNode.forId(s.getId());
        if (node != null && p.getSkills().getLevel(Skills.WOODCUTTING) < node.getLevel())
            return "you need Woodcutting level " + node.getLevel() + " to chop the " + name + " (yours is " + p.getSkills().getLevel(Skills.WOODCUTTING) + ").";
        if (p.getInventory().freeSlots() == 0) return "your inventory is full, so there is no room for logs. Add an If \u2192 Inventory Is Full block to bank or drop them.";
        return "";
    }

    /** Why this rock can't be mined right now ("" when it can). */
    @HostAccess.Export public String mineCheck(int x, int y) {
        Scenery s = RegionManager.getObject(Location.create(x, y, p.getLocation().getZ()));
        if (s == null) return "that rock is gone.";
        if (content.data.skill.SkillingTool.getPickaxe(p) == null) return "you need a pickaxe to mine. Carry one or wear one.";
        content.global.skill.gather.mining.MiningNode node = content.global.skill.gather.mining.MiningNode.forId(s.getId());
        if (node != null && p.getSkills().getLevel(Skills.MINING) < node.getLevel())
            return "you need Mining level " + node.getLevel() + " to mine " + ItemDefinition.forId(node.getReward()).getName().toLowerCase()
                + " (yours is " + p.getSkills().getLevel(Skills.MINING) + ").";
        if (p.getInventory().freeSlots() == 0) return "your inventory is full, so there is no room for ore. Add an If \u2192 Inventory Is Full block to bank or drop it.";
        return "";
    }

    /** Why this fishing spot can't be fished with this option ("net", "bait", "lure", "cage", "harpoon"); "" when it can. */
    @HostAccess.Export public String fishCheck(int npcIndex, String option) {
        NPC n = npc(npcIndex);
        if (n == null) return "that fishing spot has moved away.";
        content.global.skill.fishing.FishingSpot spot = content.global.skill.fishing.FishingSpot.Companion.forId(n.getId());
        if (spot == null) return "that is not a fishing spot.";
        content.global.skill.fishing.FishingOption opt = spot.getOptionByName(option.toLowerCase());
        if (opt == null) return "you can't " + option.toLowerCase() + " at this fishing spot. Try a different spot.";
        if (!p.getInventory().containsItem(new Item(opt.getTool())) && !p.getEquipment().containsItem(new Item(opt.getTool())))
            return "you need a " + ItemDefinition.forId(opt.getTool()).getName().toLowerCase() + " to fish here.";
        if (opt.getBait() != null && !opt.hasBait(p)) return "you have no " + opt.getBaitName().toLowerCase() + " left.";
        if (p.getSkills().getLevel(Skills.FISHING) < opt.getLevel())
            return "you need Fishing level " + opt.getLevel() + " to fish here (yours is " + p.getSkills().getLevel(Skills.FISHING) + ").";
        if (p.getInventory().freeSlots() == 0) return "your inventory is full, so there is no room for fish. Add an If \u2192 Inventory Is Full block to bank or drop them.";
        return "";
    }

    /** The fishing method ("Lobster cage", "Small net"...) as the game's enum, or null. */
    private static content.global.skill.fishing.FishingOption fishingMethod(String method) {
        try { return content.global.skill.fishing.FishingOption.valueOf(method.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_")); }
        catch (Exception e) { return null; }
    }

    /** Like fishCheck, but for one exact method, so a pike spot isn't mistaken for a sardine spot (both are "Bait"). */
    @HostAccess.Export public String fishCheckMethod(int npcIndex, String method) {
        content.global.skill.fishing.FishingOption opt = fishingMethod(method);
        if (opt == null) return fishCheck(npcIndex, method);
        NPC n = npc(npcIndex);
        if (n == null) return "that fishing spot has moved away.";
        content.global.skill.fishing.FishingSpot spot = content.global.skill.fishing.FishingSpot.Companion.forId(n.getId());
        if (spot == null) return "that is not a fishing spot.";
        if (!Arrays.asList(spot.getOptions()).contains(opt)) return "you can't catch that at this fishing spot.";
        return fishCheck(npcIndex, opt.getOption());
    }

    /** Every fishing spot in the world that offers this method, as [[x, y, z], ...]. Spots move about a little, so these are where they are now. */
    @HostAccess.Export public String fishingSpots(String method) {
        content.global.skill.fishing.FishingOption opt = fishingMethod(method);
        StringBuilder sb = new StringBuilder("[");
        if (opt != null) for (NPC n : Repository.getNpcs()) {
            if (n == null || !n.isActive()) continue;
            content.global.skill.fishing.FishingSpot spot = content.global.skill.fishing.FishingSpot.Companion.forId(n.getId());
            if (spot == null || !Arrays.asList(spot.getOptions()).contains(opt)) continue;
            Location l = n.getLocation();
            if (sb.length() > 1) sb.append(',');
            sb.append('[').append(l.getX()).append(',').append(l.getY()).append(',').append(l.getZ()).append(']');
        }
        return sb.append(']').toString();
    }

    @HostAccess.Export public boolean bankOpen() { return p.getBank().isOpen(); }

    /** Walks to the nearest bank booth (or banker) and opens the bank. "ok", or "no-bank" when none is near. */
    @HostAccess.Export public String openBank() {
        Scenery best = null; double bd = Double.MAX_VALUE;
        for (Scenery s : objectsAround()) {
            if (s == null || !s.isActive() || s.getName() == null || !s.getName().equalsIgnoreCase("Bank booth")) continue;
            if (!s.getDefinition().hasAction("Use-quickly") && !s.getDefinition().hasAction("Bank")) continue;
            double d = p.getLocation().getDistance(s.getLocation());
            if (d < bd) { bd = d; best = s; }
        }
        if (best != null && bd <= 25) {
            api.interact(p, best, best.getDefinition().hasAction("Use-quickly") ? "Use-quickly" : "Bank");
            return "ok";
        }
        for (NPC n : RegionManager.getLocalNpcs(p, 20)) {
            if (n != null && n.isActive() && n.getDefinition().hasAction("Bank")) { api.interact(p, n, "Bank"); return "ok"; }
        }
        return "no-bank";
    }

    @HostAccess.Export public void closeBank() { if (p.getBank().isOpen()) p.getInterfaceManager().close(); }

    /** Puts inventory slots from..to (1 to 28, as you see them) in the bank. Returns how many slots were banked, or -1 if the bank is full. */
    @HostAccess.Export public int depositSlots(int from, int to) {
        int done = 0;
        for (int slot = Math.max(1, from); slot <= Math.min(28, to); slot++) {
            Item it = p.getInventory().get(slot - 1);
            if (it == null) continue;
            if (!p.getBank().canAdd(it)) return -1;
            p.getBank().addItem(slot - 1, p.getInventory().getAmount(it));
            done++;
        }
        return done;
    }

    /** Puts every item with this name in the bank. Returns how many slots were banked, or -1 if the bank is full. */
    @HostAccess.Export public int depositItem(String name) {
        int done = 0;
        for (int i = 0; i < 28; i++) {
            Item it = p.getInventory().get(i);
            if (it == null || !it.getName().equalsIgnoreCase(name)) continue;
            if (!p.getBank().canAdd(it)) return -1;
            p.getBank().addItem(i, p.getInventory().getAmount(it));
            done++;
        }
        return done;
    }

    /** Takes items out of the bank. amount 0 means all of them. "ok", "not-in-bank" or "inventory-full". */
    @HostAccess.Export public String withdrawItem(String name, int amount) {
        Item[] bank = p.getBank().toArray();
        for (int i = 0; i < bank.length; i++) {
            Item it = bank[i];
            if (it == null || !it.getName().equalsIgnoreCase(name)) continue;
            if (p.getInventory().freeSlots() == 0 && !(it.getDefinition().isStackable() && p.getInventory().contains(it.getId(), 1))) return "inventory-full";
            p.getBank().takeItem(i, amount <= 0 ? it.getAmount() : Math.min(amount, it.getAmount()));
            return "ok";
        }
        return "not-in-bank";
    }

    /** Drops inventory slots from..to (1 to 28), or every item with this name when name isn't empty. Returns how many were dropped. */
    @HostAccess.Export public int drop(String name, int from, int to) {
        int done = 0;
        for (int slot = Math.max(1, from); slot <= Math.min(28, to); slot++) {
            Item it = p.getInventory().get(slot - 1);
            if (it == null || (!name.isEmpty() && !it.getName().equalsIgnoreCase(name))) continue;
            Item real = p.getInventory().get(slot - 1);
            core.game.interaction.Option opt = null;
            for (core.game.interaction.Option o : real.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase("drop")) opt = o;
            if (opt == null) continue;
            if (core.game.interaction.InteractionListeners.run(real.getId(), core.game.interaction.IntType.ITEM, "drop", p, real)) { done++; continue; }
            real.getInteraction().handle(p, opt);
            done++;
        }
        return done;
    }

    /** The first item in the inventory that can be eaten, or "" when there is none. */
    @HostAccess.Export public String foodName() {
        for (Item it : p.getInventory().toArray()) if (it != null && it.getDefinition().hasAction("eat")) return it.getName();
        return "";
    }

    /** Names of things near the character, for the Playground's target picker. */
    @HostAccess.Export public String nearby() {
        Set<String> monsters = new TreeSet<>(), people = new TreeSet<>(), objects = new TreeSet<>(), items = new TreeSet<>(), inv = new TreeSet<>();
        for (NPC n : RegionManager.getLocalNpcs(p, 24)) {
            if (n == null || !n.isActive() || n.getName() == null || n.getName().equals("null")) continue;
            if (n.getDefinition().hasAction("attack")) monsters.add(n.getName());
            people.add(n.getName());
        }
        for (Scenery s : objectsAround()) {
            if (s == null || !s.isActive() || !s.getLocation().withinDistance(p.getLocation(), 24)) continue;
            String name = shownName(s);
            if (name.isEmpty()) continue;
            boolean useful = false;
            for (core.game.interaction.Option o : shown(s).getInteraction().getOptions())
                if (o != null && o.getName() != null && !o.getName().equalsIgnoreCase("examine") && !o.getName().equalsIgnoreCase("null")) useful = true;
            if (useful) objects.add(name);
        }
        for (GroundItem g : GroundItemManager.getItems())
            if (g != null && g.isActive() && g.getLocation().getZ() == p.getLocation().getZ() && g.getLocation().withinDistance(p.getLocation(), 16)) items.add(g.getName());
        for (Item it : p.getInventory().toArray()) if (it != null) inv.add(it.getName());
        return "{\"monsters\":" + Json.array(monsters) + ",\"people\":" + Json.array(people) + ",\"objects\":" + Json.array(objects)
            + ",\"ground\":" + Json.array(items) + ",\"inventory\":" + Json.array(inv) + "}";
    }
}
