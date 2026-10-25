package codebridge;

import java.util.List;

import core.game.node.entity.player.Player;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;

import content.global.skill.farming.FarmingPatch;
import content.global.skill.farming.Patch;
import content.global.skill.hunter.HunterManager;
import content.global.skill.hunter.TrapWrapper;
import content.global.skill.hunter.tracking.TrailDefinition;

/** What the Hunter and Farming blocks need to see: the player's traps, kebbit trails and farming patches. */
final class Nature {
    private Nature() {}

    /** The player's laid traps: where they are and whether they caught something or collapsed. */
    static String traps(Player p) {
        StringBuilder sb = new StringBuilder("[");
        HunterManager m = HunterManager.getInstance(p);
        for (TrapWrapper w : m.getTraps()) {
            if (sb.length() > 1) sb.append(',');
            Scenery o = w.getObject();
            sb.append(new Json().put("trap", w.getType().name().toLowerCase().replace('_', ' '))
                .put("x", o.getLocation().getX()).put("y", o.getLocation().getY())
                .put("caught", w.isCaught()).put("failed", w.isFailed()).put("busy", w.isBusy()).put("baited", w.isBaited())
                .put("name", o.getName()));
        }
        return sb.append("]").toString();
    }

    static String trapCount(Player p) {
        HunterManager m = HunterManager.getInstance(p);
        return new Json().put("laid", m.getTrapAmount()).put("max", m.getMaximumTraps()).toString();
    }

    /**
     * The next spot on the kebbit trail the player is following, as the footprints on the ground show it:
     * {"state":"none"} with no trail, {"state":"next",x,y} for the next tracks to inspect, {"state":"end",x,y} where the kebbit hides.
     */
    @SuppressWarnings("unchecked")
    static String trackNext(Player p) {
        String[][] kinds = {{"hunter:tracking:commontrail", "hunter:tracking:commonIndex"}, {"hunter:tracking:polartrail", "hunter:tracking:polarindex"}};
        for (String[] k : kinds) {
            List<TrailDefinition> trail = (List<TrailDefinition>) p.getAttribute(k[0], null);
            if (trail == null || trail.isEmpty()) continue;
            int i = p.getAttribute(k[1], 0);
            boolean last = i >= trail.size() - 1;
            TrailDefinition t = last ? trail.get(trail.size() - 1) : trail.get(i + 1);
            Location l = last ? t.getEndLocation() : t.getTriggerObjectLocation();
            return new Json().put("state", last ? "end" : "next").put("x", l.getX()).put("y", l.getY()).toString();
        }
        return "{\"state\":\"none\"}";
    }

    /** Farming patches near the player, with what this player has growing in each. */
    static String patches(GameApi api, Player p, int radius) {
        StringBuilder sb = new StringBuilder("[");
        for (Scenery s : api.objectsAround()) {
            if (s == null || !s.isActive() || !s.getLocation().withinDistance(p.getLocation(), radius)) continue;
            FarmingPatch fp = FarmingPatch.Companion.forObject(s);
            if (fp == null) continue;
            Patch pt = fp.getPatchFor(p, true);
            String state;
            if (pt.isDead()) state = "dead";
            else if (pt.isDiseased()) state = "diseased";
            else if (pt.isWeedy()) state = "weeds";
            else if (pt.getPlantable() == null || pt.isEmptyAndWeeded()) state = "empty";
            else if (pt.isChoppedFruitTree()) state = "stump";
            else if (pt.isGrown()) state = "grown";
            else state = "growing";
            if (sb.length() > 1) sb.append(',');
            Scenery shown = api.shown(s);
            sb.append(new Json().put("patch", fp.name().toLowerCase().replace('_', ' ')).put("type", fp.getType().name().toLowerCase().replace('_', ' '))
                .put("name", shown.getName()).put("x", s.getLocation().getX()).put("y", s.getLocation().getY())
                .put("distance", Math.round(p.getLocation().getDistance(s.getLocation()) * 10) / 10.0)
                .put("state", state).put("plant", pt.getPlantable() == null ? "" : pt.getPlantable().getDisplayName())
                .put("stage", pt.getCurrentGrowthStage()).put("watered", pt.isWatered())
                .put("compost", pt.getCompost().name().toLowerCase()).put("check_health", pt.isCheckHealth())
                .put("protected", pt.getProtectionPaid()));
        }
        return sb.append("]").toString();
    }
}
