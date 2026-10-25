package codebridge;

import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Line commands on the local control port (127.0.0.1 only). Used by the Code window and by tests.
 *   spawn NAME X Y Z     create a test character (bot-backed) with stats and gear
 *   attach NAME PLAYER   attach a session to a logged-in player by username
 *   run NAME FILE        run scripts/FILE on that character
 *   runcode NAME CODE    run code given inline (\n escaped)
 *   stop NAME            stop the script
 *   eval NAME CODE       evaluate an expression in the script's engine
 *   status NAME          character info as JSON
 *   log NAME             new log lines since last call
 *   list                 sessions
 */
final class Control {
    static String handle(String line) throws Exception {
        String[] a = line.trim().split(" ", 3);
        String cmd = a[0];
        switch (cmd) {
            case "ping": return "pong";
            // ---- shared backend for the launcher, the game client and the Script Playground ----
            case "client": { // client X Y W H : heartbeat from the game client with its window position
                String[] c = a.length > 1 ? (a[1] + (a.length > 2 ? " " + a[2] : "")).split(" ") : new String[0];
                if (c.length == 4) CodeBridge.gameWindow = new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3])};
                CodeBridge.lastClientBeat = System.currentTimeMillis();
                int[] move = CodeBridge.moveGame;
                if (move != null) { CodeBridge.moveGame = null; return "move " + move[0] + " " + move[1] + " " + move[2] + " " + move[3]; }
                return "ok";
            }
            case "layout": { // layout X Y W H : the Playground asks the game window to make room beside it
                String[] c = (a[1] + " " + a[2]).split(" ");
                CodeBridge.moveGame = new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3])};
                return "ok";
            }
            case "orb": CodeBridge.playgroundRequests.incrementAndGet(); return "ok"; // Code orb clicked in the game
            case "grab": CodeBridge.grabbing = a.length > 1 && a[1].equals("on"); return "ok";   // grab on|off : from the Playground
            case "grabbing": return CodeBridge.grabbing ? "on" : "off";                            // the game client asks
            case "grabbed": {   // grabbed X Y Z : the tile the player clicked in the game
                String[] c = (a[1] + (a.length > 2 ? " " + a[2] : "")).split(" ");
                int n = Integer.parseInt(CodeBridge.lastGrab.split(" ")[0]) + 1;
                CodeBridge.lastGrab = n + " " + Integer.parseInt(c[0]) + " " + Integer.parseInt(c[1]) + " " + Integer.parseInt(c[2]);
                CodeBridge.grabbing = false;
                return "ok";
            }
            case "state": return CodeBridge.stateJson();
            case "me": return CodeBridge.onGameThread(CodeBridge::humanPlayer);
            case "list": return CodeBridge.SESSIONS.keySet().toString();
            case "spawn": {
                String[] c = a[2].split(" ");
                int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), z = c.length > 2 ? Integer.parseInt(c[2]) : 0;
                return CodeBridge.onGameThread(() -> spawn(a[1], Location.create(x, y, z)));
            }
            case "attach": {
                return CodeBridge.onGameThread(() -> {
                    for (Player pl : Repository.getPlayers()) if (pl != null && pl.getUsername().equalsIgnoreCase(a[2])) {
                        ScriptSession old = CodeBridge.SESSIONS.get(a[1]);
                        if (old != null && old.player == pl) return "ok";
                        if (old != null) old.stop("character changed");
                        CodeBridge.SESSIONS.put(a[1], new ScriptSession(a[1], pl));
                        return "ok";
                    }
                    return "no such player";
                });
            }
            case "prep": { // prep SESSION PLAYERNAME X Y Z : outfit a real logged-in player, finish the tutorial, attach a session
                String[] c = a[2].split(" ");
                return CodeBridge.onGameThread(() -> {
                    for (Player pl : Repository.getPlayers()) if (pl != null && pl.getUsername().equalsIgnoreCase(c[0])) {
                        pl.setAttribute("/save:tutorial:stage", 72);
                        outfit(pl);
                        pl.getProperties().setTeleportLocation(Location.create(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3])));
                        CodeBridge.SESSIONS.put(a[1], new ScriptSession(a[1], pl));
                        return "ok";
                    }
                    return "no such player";
                });
            }
            case "run": {
                ScriptSession s = session(a[1]);
                String code = new String(Files.readAllBytes(CodeBridge.scriptsDir.resolve(a[2])), StandardCharsets.UTF_8);
                org.graalvm.polyglot.Context c = s.prepare();
                return CodeBridge.onGameThread(() -> s.start(a[2], code, c));
            }
            case "runcode": {
                ScriptSession s = session(a[1]);
                String code = a[2].replace("\\n", "\n");
                org.graalvm.polyglot.Context c = s.prepare();
                return CodeBridge.onGameThread(() -> s.start("inline", code, c));
            }
            case "runb64": { // runb64 NAME SCRIPTNAME BASE64CODE : used by the Code window
                ScriptSession s = session(a[1]);
                // the script name can have spaces ("Fish Shrimp.blocks"); the code is the last word, base64 has none
                int cut = a[2].trim().lastIndexOf(' ');
                if (cut < 0) return "ERR runb64 needs a script name and code";
                String script = a[2].substring(0, cut).trim();
                String code = new String(java.util.Base64.getDecoder().decode(a[2].substring(cut + 1).trim()), StandardCharsets.UTF_8);
                org.graalvm.polyglot.Context ctx = s.prepare();
                return CodeBridge.onGameThread(() -> s.start(script, code, ctx));
            }
            case "running": return String.valueOf(session(a[1]).running());
            case "stop": { ScriptSession s = session(a[1]); return CodeBridge.onGameThread(() -> { s.stop("stop pressed"); return "ok"; }); }
            case "eval": { ScriptSession s = session(a[1]); String code = a[2].replace("\\n", "\n");
                org.graalvm.polyglot.Context c = s.hasContext() ? null : s.prepare(); return CodeBridge.onGameThread(() -> s.eval(code, c)); }
            case "status": { ScriptSession s = session(a[1]); return CodeBridge.onGameThread(() -> new GameApi(s.player, s).character()); }
            case "log": { ScriptSession s = session(a[1]); return String.join("\n", s.drainLog()); }
            case "message": return session(a[1]).message();
            case "block": { ScriptSession s = session(a[1]); // block NAME : "id|running", "id|failed|reason" or "|stopped"
                if (!s.failedBlock.isEmpty()) return s.failedBlock + "|failed|" + s.failReason;
                return (s.running() ? s.block + "|running" : "|stopped"); }
            case "nearby": { ScriptSession s = session(a[1]); return CodeBridge.onGameThread(() -> new GameApi(s.player, s).nearby()); }
            case "give": { ScriptSession s = session(a[1]); String[] c = a[2].split(" ", 2);
                return CodeBridge.onGameThread(() -> { int id = new GameApi(s.player, s).itemId(c[1]); if (id < 0) return "no item " + c[1];
                    s.player.getInventory().add(new Item(id, Integer.parseInt(c[0]))); return "ok " + id; }); }
            case "setlevel": { ScriptSession s = session(a[1]); String[] c = a[2].split(" ");
                return CodeBridge.onGameThread(() -> { int idx = Arrays.asList(GameApi.SKILL_NAMES).indexOf(c[0]);
                    setLevel(s.player, idx, Integer.parseInt(c[1])); return "ok"; }); }
            case "dbg": { ScriptSession s = session(a[1]); return CodeBridge.onGameThread(() -> { core.game.node.entity.impl.PulseManager pm = s.player.getPulseManager();
                core.game.system.task.Pulse cur = pm.getCurrent(); return "running=" + pm.hasPulseRunning() + " cur=" + (cur == null ? "-" : cur.getClass().getName() + " alive=" + cur.isRunning())
                    + " dlg=" + s.player.getDialogueInterpreter().getDialogue() + " ifc=" + s.player.getInterfaceManager().getChatbox()
                    + " active=" + s.player.scripts.getActiveScript() + " persist=" + s.player.scripts.getPersistent() + " q=" + java.util.Arrays.stream(core.game.interaction.QueueStrength.values()).filter(s.player.scripts::hasTypeInQueue).map(Enum::name).collect(java.util.stream.Collectors.joining(",")); }); }
            case "quest": { ScriptSession s = session(a[1]); return CodeBridge.onGameThread(() -> { content.data.Quests q = GameApi.questByName(a[2]); if (q == null) return "no quest";
                core.game.node.entity.player.link.quest.QuestRepository r = s.player.getQuestRepository(); r.setStageNonmonotonic(r.getQuest(q), 100); return "ok " + q.name(); }); }
            case "grow": { ScriptSession s = session(a[1]); return CodeBridge.onGameThread(() -> { int n = 0;   // test only: grow the player's nearby crops to the end
                for (content.global.skill.farming.FarmingPatch fp : content.global.skill.farming.FarmingPatch.values()) {
                    content.global.skill.farming.Patch pt = fp.getPatchFor(s.player, false);
                    if (pt == null || pt.getPlantable() == null) continue;
                    for (int i = 0; i < 12 && !pt.isGrown(); i++) { pt.setDiseased(false); pt.setNextGrowth(0); pt.update(); }
                    pt.setDiseased(false); pt.updateBit(); n++;
                } return "grew " + n; }); }
            case "slayer": { ScriptSession s = session(a[1]); String[] c = a[2].split(" ");   // test only: slayer NAME TASK AMOUNT
                return CodeBridge.onGameThread(() -> { content.global.skill.slayer.SlayerManager m = content.global.skill.slayer.SlayerManager.getInstance(s.player);
                    m.setMaster(content.global.skill.slayer.Master.TURAEL); m.setTask(content.global.skill.slayer.Tasks.valueOf(c[0])); m.setAmount(Integer.parseInt(c[1])); return "ok"; }); }
            case "house": { ScriptSession s = session(a[1]);   // test only: give the player a house in Rimmington
                return CodeBridge.onGameThread(() -> { s.player.getHouseManager().createNewHouseAt(content.global.skill.construction.HouseLocation.RIMMINGTON); return "ok"; }); }
            case "commands": {   // every :: command the game knows, for the Cheats window: [{name, usage, about, level}]
                StringBuilder sb = new StringBuilder("[");
                for (core.game.system.command.Command c : core.game.system.command.CommandMapping.INSTANCE.getCommands()) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(new Json().put("name", c.getName()).put("usage", c.getUsage()).put("about", c.getDescription())
                        .put("level", c.getPrivilege().name().toLowerCase()));
                }
                return sb.append(']').toString();
            }
            case "walkstart": {   // test only: walkstart X Y Z : a walkable tile about 30 tiles from a target, with a path to it
                String[] c = (a[1] + " " + a[2]).split(" ");
                int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), z = Integer.parseInt(c[2]);
                return CodeBridge.onGameThread(() -> walkStart(x, y, z));
            }
            case "reachcheck": {   // test only: reachcheck FILE : file lines "x y" -> 1/0 can be walked to from Lumbridge
                List<int[]> pts = new ArrayList<>();
                for (String l : Files.readAllLines(Paths.get(a[1]))) { String[] c = l.trim().split(" "); if (c.length >= 2) pts.add(new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1])}); }
                int near = a.length > 2 ? Integer.parseInt(a[2].trim()) : 8;
                return CodeBridge.onGameThread(() -> Router.reach(3222, 3218, pts.toArray(new int[0][]), near, 2048, 2496, 1920, 1728));
            }
            case "route": {   // test only: route SX SY TX TY Z [AGILITY] : the route planner's tiles
                String[] c = (a[1] + " " + a[2]).trim().split(" ");
                int[] v = new int[c.length > 5 ? 6 : 5];   // optional 6th: the Agility level (99 when left out)
                for (int i = 0; i < v.length; i++) v[i] = Integer.parseInt(c[i]);
                return CodeBridge.onGameThread(() -> {
                    StringBuilder sb = new StringBuilder();
                    for (int[] t : Router.route(v[0], v[1], v[2], v[3], v[4], v.length > 5 ? v[5] : 99)) sb.append(t[0]).append(',').append(t[1]).append(' ');
                    return sb.toString();
                });
            }
            case "scen": {   // test only: scen X Y Z R : every object the map holds near a tile, with its walking flag
                String[] c = (a[1] + " " + a[2]).trim().split(" ");
                int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), z = Integer.parseInt(c[2]), r = Integer.parseInt(c[3]);
                return CodeBridge.onGameThread(() -> {
                    StringBuilder sb = new StringBuilder();
                    for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
                        int f = core.game.world.map.RegionManager.getClippingFlag(z, x + dx, y + dy);
                        sb.append(x + dx).append(',').append(y + dy).append('=').append(Integer.toHexString(f));
                        core.game.node.scenery.Scenery o = core.game.world.map.RegionManager.getObject(core.game.world.map.Location.create(x + dx, y + dy, z));
                        if (o != null) sb.append('[').append(o.getName()).append(' ').append(o.isActive()).append(' ').append(GameApi.passageOption(o)).append(']');
                        sb.append(' ');
                    }
                    return sb.toString();
                });
            }
            case "catalog": return Catalog.dump(a[1]);    // catalog DIR : build-time dump of the game data (tools/build_catalog.py)
            default: return "unknown command " + cmd;
        }
    }

    private static String walkStart(int x, int y, int z) {
        Location goal = null;
        for (int r = 0; r <= 6 && goal == null; r++)
            for (int dx = -r; dx <= r && goal == null; dx++) for (int dy = -r; dy <= r; dy++)
                if (core.game.world.map.RegionManager.isTeleportPermitted(Location.create(x + dx, y + dy, 0))) { goal = Location.create(x + dx, y + dy, 0); break; }
        String fallback = null;
        for (int dist : new int[]{30, 22, 15, 40}) {
            for (int k = 0; k < 16; k++) {
                double ang = Math.PI * 2 * k / 16;
                int sx = x + (int) Math.round(Math.cos(ang) * dist), sy = y + (int) Math.round(Math.sin(ang) * dist);
                if (!core.game.world.map.RegionManager.isTeleportPermitted(Location.create(sx, sy, 0))) continue;
                if (fallback == null) fallback = sx + " " + sy + " 0";
                if (goal == null) continue;
                core.game.world.map.path.Path path = core.game.world.map.path.Pathfinder.find(Location.create(sx, sy, 0), goal);
                if (path != null && path.isSuccessful() && !path.isMoveNear()) return sx + " " + sy + " 0";
            }
        }
        return goal == null && fallback != null ? fallback : "none";   // a start with no path to the goal could be shut in (a pen, a house): the test picks one elsewhere
    }

    private static ScriptSession session(String name) {
        ScriptSession s = CodeBridge.SESSIONS.get(name);
        if (s == null) throw new IllegalArgumentException("no session " + name);
        return s;
    }

    static void setLevel(Player p, int skill, int level) {
        p.getSkills().setStaticLevel(skill, level);
        p.getSkills().setLevel(skill, level);
        if (skill == Skills.HITPOINTS) p.getSkills().setLifepoints(level);
        if (skill == Skills.PRAYER) p.getSkills().setPrayerPoints(level);
        p.getSkills().updateCombatLevel();
    }

    /** Test character: level 70 combat, 60 skills, rune melee gear, food and tools. */
    private static String spawn(String name, Location loc) {
        AIPlayer bot = new AIPlayer(loc);
        outfit(bot);
        CodeBridge.SESSIONS.put(name, new ScriptSession(name, bot));
        return "ok " + bot.getUsername() + " at " + loc;
    }

    /** Gives a character the test stats, gear and items. */
    static void outfit(Player bot) {
        int[] seventy = {Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS, Skills.RANGE, Skills.MAGIC, Skills.PRAYER};
        for (int i = 0; i < GameApi.SKILL_NAMES.length; i++) setLevel(bot, i, 60);
        for (int s : seventy) setLevel(bot, s, 70);
        bot.setAttribute("/save:tutorial:complete", true); // otherwise XP is capped at 175 (Tutorial Island rule)
        bot.setAttribute("/save:rules:confirmed", true);   // skips the rules screen that follows Tutorial Island
        bot.getEquipment().clear();
        bot.getInventory().clear();
        bot.getBank().clear();
        int[][] gear = {{1163, 0}, {1127, 4}, {1079, 7}, {1201, 5}, {4131, 10}, {1333, 3}, {1725, 2}, {1065, 9}, {1052, 1}};
        // rune full helm, platebody, platelegs, kiteshield, rune boots, rune scimitar, amulet of strength, green d'hide vambs, cape of legends
        for (int[] g : gear) bot.getEquipment().replace(new Item(g[0]), g[1]);
        bot.getInventory().add(new Item(1359), new Item(1275), new Item(590), new Item(379, 10), new Item(995, 5000));
        // rune axe, rune pickaxe, tinderbox, 10 lobsters, 5000 coins
        bot.getAppearance().sync();
        bot.getInventory().refresh();
        bot.getEquipment().refresh();
    }
}
