package codebridge;

import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.cache.def.impl.ItemDefinition;
import core.game.world.map.RegionManager;

import content.global.skill.smithing.Bars;
import content.global.skill.smithing.SmithingPulse;
import content.global.skill.smithing.smelting.Bar;
import content.global.skill.smithing.smelting.SmeltingPulse;
import content.global.skill.crafting.TanningProduct;
import content.global.skill.crafting.armour.LeatherCrafting;
import content.global.skill.crafting.armour.SoftCraftPulse;
import content.global.skill.crafting.glass.GlassCraftingPulse;
import content.global.skill.crafting.glass.GlassProduct;
import content.global.skill.crafting.jewellery.JewelleryCrafting;
import content.global.skill.crafting.silver.SilverCraftingPulse;
import content.global.skill.crafting.silver.SilverProduct;
import content.global.skill.crafting.spinning.SpinningItem;
import content.global.skill.crafting.spinning.SpinningPulse;
import content.global.skill.summoning.SummoningCreator;
import content.global.skill.summoning.SummoningPouch;
import content.global.skill.summoning.SummoningScroll;

/**
 * Makes things that the game normally offers on a full-screen menu (smelting, smithing, jewellery, silver,
 * spinning, glassblowing, leather and tanning). The block names the product; this finds which menu makes it,
 * checks the right station is next to the character, and starts the same work the menu button would.
 */
final class Artisan {
    private Artisan() {}

    static String norm(String s) { return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", ""); }
    static String name(int id) { return ItemDefinition.forId(id).getName(); }
    static boolean named(int id, String product) { return norm(name(id)).equals(norm(product)); }

    /** A station object within reach whose name contains `word` (furnace, anvil, spinning wheel). */
    static Scenery station(Player p, String word) {
        String w = norm(word);
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) {
            Scenery s = RegionManager.getObject(p.getLocation().transform(dx, dy, 0));
            if (s == null) continue;
            Scenery shown = s.getChild(p) != null ? s.getChild(p) : s;
            if (norm(shown.getName()).contains(w)) return s;
        }
        return null;
    }

    static NPC tanner(Player p) {
        for (NPC n : RegionManager.getLocalNpcs(p, 6)) {
            String nm = norm(n.getName());
            if (nm.equals("ellis") || nm.equals("tanner") || nm.equals("sbott") || nm.equals("eodan")) return n;
        }
        return null;
    }

    static String level(Player p, int skill, int need) {
        int have = p.getSkills().getStaticLevel(skill);
        return have >= need ? null : "level:" + Skills.SKILL_NAME[skill] + ":" + need + ":" + have;
    }

    /** "no-materials:NAME" for the first item id the inventory lacks, else null. */
    static String missing(Player p, int... ids) {
        for (int id : ids) if (id > 0 && !p.getInventory().contains(id, 1)) return "no-materials:" + name(id);
        return null;
    }

    static void run(Player p, Pulse pulse) {
        p.getInterfaceManager().close();
        p.getInterfaceManager().closeChatbox();
        p.getPulseManager().run(pulse);
    }

    /**
     * Returns "ok:SKILL", "unknown", "station:NAME" (stand next to one first), "level:Skill:need:have",
     * or "no-materials:NAME".
     */
    static String make(Player p, String product, int amount) {
        int n = amount <= 0 ? 28 : amount;
        String last = "unknown";
        for (Bar b : Bar.values()) if (named(b.getProduct().getId(), product)) {
            if (station(p, "furnace") == null) return "station:Furnace";
            String l = level(p, Skills.SMITHING, b.getLevel()); if (l != null) return l;
            for (Item ore : b.getOres()) if (!p.getInventory().contains(ore.getId(), ore.getAmount())) return "no-materials:" + ore.getName();
            run(p, new SmeltingPulse(p, null, b, n));
            return "ok:smithing";
        }
        for (Bars b : Bars.values()) if (named(b.getProduct(), product)) {
            if (station(p, "anvil") == null) return "station:Anvil";
            String l = level(p, Skills.SMITHING, b.getLevel()); if (l != null) return l;
            l = missing(p, 2347, b.getBarType().getBarType()); if (l != null) return l;
            run(p, new SmithingPulse(p, new Item(b.getProduct(), n), b, n));
            return "ok:smithing";
        }
        for (JewelleryCrafting.JewelleryItem j : JewelleryCrafting.JewelleryItem.values()) if (named(j.getSendItem(), product)) {
            if (station(p, "furnace") == null) return "station:Furnace";
            String l = level(p, Skills.CRAFTING, j.getLevel()); if (l != null) return l;
            l = missing(p, j.getItems()); if (l == null) l = missing(p, JewelleryCrafting.mouldFor(name(j.getSendItem()))); if (l != null) return l;
            p.getInterfaceManager().close();
            JewelleryCrafting.make(p, j, n);
            return "ok:crafting";
        }
        for (SilverProduct s : SilverProduct.values()) if (named(s.getProducedItemId(), product)) {
            Scenery f = station(p, "furnace");
            if (f == null) return "station:Furnace";
            String l = level(p, Skills.CRAFTING, s.getMinimumLevel()); if (l != null) return l;
            l = missing(p, 2355, s.getRequiredItemId()); if (l != null) return l;
            p.getInterfaceManager().close();
            core.api.ContentAPIKt.submitIndividualPulse(p, new SilverCraftingPulse(p, s, f, n));
            return "ok:crafting";
        }
        for (SpinningItem s : SpinningItem.values()) if (named(s.getProduct(), product)) {
            if (station(p, "spinning") == null) return "station:Spinning wheel";
            String l = level(p, Skills.CRAFTING, s.getLevel()); if (l != null) return l;
            if (!p.getInventory().contains(s.getNeed(), 1)) { last = "no-materials:" + name(s.getNeed()); continue; }    // several spin into one product
            run(p, new SpinningPulse(p, new Item(s.getNeed(), 1), n, s));
            return "ok:crafting";
        }
        for (GlassProduct g : GlassProduct.values()) if (named(g.getProducedItemId(), product)) {
            String l = level(p, Skills.CRAFTING, g.getMinimumLevel()); if (l != null) return l;
            l = missing(p, 1785, 1775); if (l != null) return l;
            p.getInterfaceManager().close();
            core.api.ContentAPIKt.submitIndividualPulse(p, new GlassCraftingPulse(p, g, n));
            return "ok:crafting";
        }
        for (LeatherCrafting.SoftLeather s : LeatherCrafting.SoftLeather.values()) if (named(s.getProduct().getId(), product)) {
            String l = level(p, Skills.CRAFTING, s.getLevel()); if (l != null) return l;
            l = missing(p, 1733, 1734, 1741); if (l != null) return l;
            run(p, new SoftCraftPulse(p, null, s, n));
            return "ok:crafting";
        }
        for (TanningProduct t : TanningProduct.values()) if (named(t.getProduct(), product)) {
            if (tanner(p) == null) return "station:Tanner";
            if (!p.getInventory().contains(t.getItem(), 1)) return "no-materials:" + name(t.getItem());
            p.getInterfaceManager().close();
            TanningProduct.tan(p, Math.min(n, p.getInventory().getAmount(t.getItem())), t);
            return "ok:crafting";
        }
        for (SummoningPouch sp : SummoningPouch.values()) if (named(sp.getPouchId(), product)) {
            if (station(p, "obelisk") == null) return "station:Obelisk";
            String l = level(p, Skills.SUMMONING, sp.getLevelRequired()); if (l != null) return l;
            for (Item it : sp.getItems()) if (!p.getInventory().containsItem(it)) return "no-materials:" + it.getName();
            p.getInterfaceManager().close();
            SummoningCreator.create(p, n, sp);
            return "ok:summoning";
        }
        for (SummoningScroll sc : SummoningScroll.values()) if (named(sc.getItemId(), product)) {
            if (station(p, "obelisk") == null) return "station:Obelisk";
            String l = level(p, Skills.SUMMONING, sc.getLevel()); if (l != null) return l;
            l = missing(p, sc.getItems()); if (l != null) return l;
            p.getInterfaceManager().close();
            SummoningCreator.create(p, n, sc);
            return "ok:summoning";
        }
        return last;
    }
}
