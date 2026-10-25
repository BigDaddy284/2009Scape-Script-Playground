package codebridge;

import content.region.misc.tutisland.handlers.*;
import core.ServerConstants;
import core.api.ContentAPIKt;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * New characters skip Tutorial Island: once the character design is accepted they are finished off exactly the way
 * the Magic Instructor finishes them ("Yes, I'm ready"), and land in Lumbridge with the normal starter gear.
 * No Ironman choice is ever offered.
 */
final class NewPlayers {
    private NewPlayers() {}

    /** The same starter items the Magic Instructor hands out. */
    private static final int[][] STARTER_PACK = {
        {1351, 1}, {590, 1}, {303, 1}, {315, 1}, {1925, 1}, {1931, 1}, {2309, 1}, {1265, 1}, {1205, 1},
        {1277, 1}, {1171, 1}, {841, 1}, {882, 25}, {556, 25}, {558, 15}, {555, 6}, {557, 4}, {559, 2}
    };
    private static final Location LUMBRIDGE = Location.create(3233, 3230, 0);

    /** Called every tick for each online player. Stage 1 means the character design was just accepted. */
    static void check(Player p, boolean justLoggedIn) {
        if (justLoggedIn) ONLINE_SINCE.put(p.getUsername(), ContentAPIKt.getWorldTicks());
        if (p.getAttribute("tutorial:complete", false)) return;
        if (p.getAttribute("tutorial:stage", 0) < 1) return;
        // wait until the game has finished loading the tutorial at login, or it would undo this
        Integer since = ONLINE_SINCE.get(p.getUsername());
        if (since == null || ContentAPIKt.getWorldTicks() - since < 5) return;
        finishTutorial(p);
    }
    static void loggedOut(java.util.Set<String> online) { ONLINE_SINCE.keySet().retainAll(online); }
    private static final java.util.Map<String, Integer> ONLINE_SINCE = new java.util.HashMap<>();

    private static void finishTutorial(Player p) {
        // stop the tutorial reacting first, so closing its dialog does not bring it back
        p.unhook(TutorialKillReceiver.INSTANCE);
        p.unhook(TutorialFireReceiver.INSTANCE);
        p.unhook(TutorialResourceReceiver.INSTANCE);
        p.unhook(TutorialUseWithReceiver.INSTANCE);
        p.unhook(TutorialInteractionReceiver.INSTANCE);
        p.unhook(TutorialButtonReceiver.INSTANCE);
        p.unhook(TutorialDialogPreserver.INSTANCE);
        p.getDialogueInterpreter().close();
        p.getInterfaceManager().closeChatbox();

        ContentAPIKt.setAttribute(p, "/save:tutorial:stage", 71);
        ContentAPIKt.setAttribute(p, "/save:tutorial:complete", true);
        ContentAPIKt.setVarbit(p, 3756, 0);
        ContentAPIKt.setVarp(p, 281, 1000, true);
        ContentAPIKt.closeOverlay(p);
        p.getProperties().setTeleportLocation(LUMBRIDGE);

        p.getInventory().clear();
        p.getBank().clear();
        p.getEquipment().clear();
        p.getInterfaceManager().restoreTabs();
        p.getInterfaceManager().setViewedTab(3);
        for (int[] it : STARTER_PACK) p.getInventory().add(new Item(it[0], it[1]));
        p.getBank().add(new Item(995, 25));
        TutorialStage.INSTANCE.removeHintIcon(p);
        p.unlock();

        // no default clan chat: single-player has no such channel, so joining only printed "does not exist"

        p.getDialogueInterpreter().sendDialogue(
            "Welcome to Lumbridge! To get more help, simply click on the",
            "Lumbridge Guide or one of the Tutors - these can be found by looking",
            "for the question mark icon on your mini-map. If you find you are lost",
            "at any time, look for a signpost or use the Lumbridge Home Port Spell.");
        // the online server's rules screen (no autoclickers, type a PIN) does not apply to this single-player edition
        ContentAPIKt.setAttribute(p, "/save:rules:confirmed", true);
        System.out.println("[CodeBridge] " + p.getUsername() + " skipped Tutorial Island to Lumbridge");
    }
}
