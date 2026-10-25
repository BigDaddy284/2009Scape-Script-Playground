package codeedition;

import java.awt.Color;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block scripts: what kinds of blocks exist, one block in a script, saving to and loading from a .blocks file, and
 * turning a script into the JavaScript the game backend runs (calls into blocks.js).
 */
final class BlockModel {
    private BlockModel() {}

    // ------------------------------------------------------------------ catalogue
    /** The palette tabs. Each block lives in one. */
    enum Cat {
        BASICS("Basics", new Color(0x3b6fd4)),
        BANK("Bank & Items", new Color(0x2b8fb3)),
        MAKE("Use & Make", new Color(0x8a55c9)),
        GATHER("Gathering", new Color(0x4f8f2f)),
        NATURE("Hunter & Farming", new Color(0x7c8a1f)),
        COMBAT("Combat & Magic", new Color(0xc0453b)),
        HOUSE("House", new Color(0xa0662b)),
        LOGIC("Logic", new Color(0x2f9e6e)),
        CONDITION("Conditions", new Color(0xc98a1c));
        final String title; final Color color;
        Cat(String title, Color color) { this.title = title; this.color = color; }
    }

    /**
     * What goes in a slot. Most kinds are a pick list from the game catalogue (catalog.json "editor" lists):
     * the list name is the kind's name in lower case.
     */
    enum Kind {
        NONE, PLACE, TREE, ROCK, FISH, MONSTER, NPC, ITEM, INV, FOOD, LOGS, BONES, SLOTS, SLOT, AMOUNT, SECONDS, TIMES, TEXT,
        CONDITION, OBJECT, OPTION, KEY, CHAT, PRODUCT, TARGET, SPELL, SPELLTARGET, PRAYER, COURSE, THIEVING, CREATURE, TRAP,
        SEED, PATCH, FAMILIAR, FURNITURE, ONOFF, RECHARGE, HOUSEMODE, QUEST, LEVEL, NUMBER, CODE, RUNMODE;
        /** The catalogue list this kind picks from, or null. */
        String list() {
            switch (this) {
                case PLACE: return "place"; case TREE: return "tree"; case ROCK: return "rock"; case FISH: return "fish";
                case MONSTER: return "monster"; case NPC: return "npc"; case ITEM: case INV: return "item"; case FOOD: return "food";
                case LOGS: return "logs"; case BONES: return "bones"; case OPTION: return "option"; case KEY: return "key";
                case CHAT: return "chat"; case PRODUCT: return "product"; case SPELL: return "spell"; case PRAYER: return "prayer";
                case COURSE: return "course"; case THIEVING: return "thieving"; case CREATURE: return "creature"; case TRAP: return "trap";
                case SEED: return "seed"; case PATCH: return "patch"; case FAMILIAR: return "familiar"; case FURNITURE: return "furniture";
                case ONOFF: return "onoff"; case RECHARGE: return "recharge"; case HOUSEMODE: return "housemode"; case QUEST: return "quest";
                case LEVEL: return "skill";
                default: return null;
            }
        }
        boolean numeric() { return this == AMOUNT || this == SECONDS || this == TIMES || this == SLOT || this == NUMBER; }
    }

    enum Shape { PLAIN, LOOP, IF_ELSE }

    /** One slot of a block: what it asks for, its default, and the little word shown before it on the block. */
    static final class Param {
        final String label; final Kind kind; final String def; final String word; final boolean optional;
        Param(String label, Kind kind, String def, String word, boolean optional) {
            this.label = label; this.kind = kind; this.def = def; this.word = word; this.optional = optional;
        }
    }
    static Param p(String label, Kind kind, String def) { return new Param(label, kind, def, "", false); }
    static Param p(String label, Kind kind, String def, String word) { return new Param(label, kind, def, word, false); }
    static Param opt(String label, Kind kind, String def, String word) { return new Param(label, kind, def, word, true); }

    static final class Def {
        final String type, label, help, fn; final Cat cat; final Shape shape; final Param[] params;
        /** The kind of the first slot (NONE when the block has none). */
        final Kind kind;
        Def(String type, String label, Cat cat, Shape shape, String fn, String help, Param... params) {
            this.type = type; this.label = label; this.cat = cat; this.shape = shape; this.fn = fn; this.help = help;
            this.params = params; this.kind = params.length == 0 ? Kind.NONE : params[0].kind;
        }
    }
    private static Def d(String type, String label, Cat cat, String fn, String help, Param... params) {
        return new Def(type, label, cat, Shape.PLAIN, fn, help, params);
    }

    static final List<Def> DEFS = List.of(
        // Basics
        d("walk", "Walk To", Cat.BASICS, "walk_to", "Walks to a place, to map coordinates (x, y), or to the nearest object or person with that name. Opens doors and gates on the way.",
            p("Where", Kind.PLACE, "Lumbridge")),
        d("click_object", "Click Object", Cat.BASICS, "click_object", "Clicks an option on the nearest object with that name, like Open on a door or Climb-up on a ladder.",
            p("Object", Kind.OBJECT, "Door"), p("Option", Kind.OPTION, "Open", "→")),
        d("click_npc", "Click Person", Cat.BASICS, "click_npc", "Clicks an option on the nearest person or creature with that name, like Trade or Pickpocket.",
            p("Who", Kind.NPC, "Man"), p("Option", Kind.OPTION, "Talk-to", "→")),
        d("talk", "Talk To", Cat.BASICS, "talk_to", "Talks to the nearest person with that name and waits for them to answer.",
            p("Who", Kind.NPC, "Hans")),
        d("press_key", "Press Key", Cat.BASICS, "press_key", "Presses keys in a conversation: Space to continue, 1 to 5 to pick an option, Escape to close. List several with commas.",
            p("Keys", Kind.KEY, "Space")),
        d("wait_for", "Wait For", Cat.BASICS, "wait_for", "Waits until a dialogue, options, a \"how many\" menu or a typing box shows up (or goes away).",
            p("What", Kind.CHAT, "Dialogue"), p("Up to", Kind.SECONDS, "10", "up to")),
        d("answer", "Type Answer", Cat.BASICS, "answer", "Types into an \"Enter amount\" or name box and presses Enter.",
            p("Text", Kind.TEXT, "10")),
        d("say", "Say", Cat.BASICS, "say", "Says something in the game chat.", p("Text", Kind.TEXT, "Hello!")),
        d("set_run", "Run", Cat.BASICS, "set_run", "Turns running on or off. Auto turns it back on every time your run energy gets back up to the level you set (full when left empty), for the rest of the script.",
            p("On, off or auto", Kind.RUNMODE, "On"), opt("Auto: turn on at energy %", Kind.NUMBER, "", "at")),
        // Bank & items
        d("openbank", "Open Bank", Cat.BANK, "open_bank", "Opens the nearest bank booth. Walk to a bank first."),
        d("deposit", "Deposit", Cat.BANK, "deposit", "Puts items in the bank: all of them, some inventory slots (like 3–22) or one kind of item.",
            p("What", Kind.SLOTS, "Inventory All")),
        d("withdraw", "Withdraw", Cat.BANK, "withdraw", "Takes an item out of the bank.",
            p("Item", Kind.ITEM, "Bronze axe"), p("How many", Kind.AMOUNT, "1", "×")),
        d("closebank", "Close Bank", Cat.BANK, "close_bank", "Closes the bank."),
        d("pickup", "Pick Up", Cat.BANK, "pick_up", "Picks up that item from the ground nearby, if there is one.", p("Item", Kind.ITEM, "Cowhide")),
        d("drop", "Drop", Cat.BANK, "drop", "Drops items: one kind of item, or inventory slots.", p("What", Kind.SLOTS, "Logs")),
        d("eat", "Eat", Cat.BANK, "eat", "Eats food from your inventory.", p("Food", Kind.FOOD, "Any food")),
        d("bury", "Bury Bones", Cat.BANK, "bury_bones", "Buries every bone of that kind in your inventory.", p("Bones", Kind.BONES, "Any bones")),
        // Use & make
        d("use_item_on_item", "Use Item on Item", Cat.MAKE, "use_item_on_item", "Uses one inventory item on another, like a herb on a vial of water, then picks from the \"how many\" menu if one opens.",
            p("Item", Kind.INV, "Clean guam"), p("On", Kind.INV, "Vial of water", "on"), p("How many", Kind.AMOUNT, "0", "×"), opt("Make", Kind.PRODUCT, "", "→")),
        d("use_slot_on_slot", "Use Slot on Slot", Cat.MAKE, "use_slot_on_slot", "Uses the item in one inventory slot on the item in another (slots 1 to 28).",
            p("Slot", Kind.SLOT, "1", "slot"), p("On slot", Kind.SLOT, "2", "on slot"), p("How many", Kind.AMOUNT, "0", "×")),
        d("use_item_on_target", "Use Item on Target", Cat.MAKE, "use_item_on_target", "Uses an inventory item on something nearby: an object (range, furnace, altar), a person, a farming patch or an item on the ground.",
            p("Item", Kind.INV, "Raw shrimps"), p("On", Kind.TARGET, "Range", "on"), p("How many", Kind.AMOUNT, "0", "×"), opt("Make", Kind.PRODUCT, "", "→")),
        d("use_slot_on_target", "Use Slot on Target", Cat.MAKE, "use_slot_on_target", "Uses the item in an inventory slot on something nearby.",
            p("Slot", Kind.SLOT, "1", "slot"), p("On", Kind.TARGET, "Range", "on"), p("How many", Kind.AMOUNT, "0", "×")),
        d("make_item", "Make Item", Cat.MAKE, "make_item", "Makes something: the game works out how (a recipe, a furnace, anvil, spinning wheel, tanner or obelisk). Stand next to the station it needs.",
            p("Product", Kind.PRODUCT, "Bronze bar"), p("How many", Kind.AMOUNT, "0", "×")),
        d("make", "Choose in Menu", Cat.MAKE, "make", "Picks a product in the \"how many\" menu that is already open.",
            p("Product", Kind.PRODUCT, ""), p("How many", Kind.AMOUNT, "0", "×")),
        d("light_fire", "Light Fire", Cat.MAKE, "light_fire", "Lights logs with a tinderbox, stepping aside when a fire can't go there.", p("Logs", Kind.LOGS, "Any logs")),
        // Gathering
        d("chop", "Chop Tree", Cat.GATHER, "chop", "Chops the nearest tree of that kind until it falls or your inventory is full. Needs an axe.", p("Tree", Kind.TREE, "Tree")),
        d("mine", "Mine Rock", Cat.GATHER, "mine", "Mines the nearest rock with that ore. Needs a pickaxe.", p("Ore", Kind.ROCK, "Copper")),
        d("fish", "Fish", Cat.GATHER, "fish", "Fishes at the nearest spot that has this fish, walking there if none is nearby. Needs the right tool and bait.", p("Fish", Kind.FISH, "Shrimps")),
        d("craft_runes", "Craft Runes", Cat.GATHER, "craft_runes", "Crafts your essence into runes at the altar. Enters the ruins with a talisman or tiara first."),
        d("steal", "Steal", Cat.GATHER, "steal", "Pickpockets a person, steals from a stall or searches a chest. Waits out a stun if you get caught.",
            p("From", Kind.THIEVING, "Man")),
        d("run_course", "Run Agility Course", Cat.GATHER, "run_course", "Goes over every obstacle of an agility course in order, for this many laps.",
            p("Course", Kind.COURSE, "Gnome Stronghold course"), p("Laps", Kind.TIMES, "1", "×")),
        // Hunter & farming
        d("hunt", "Hunt", Cat.NATURE, "hunt", "Lays as many traps as you're allowed for this creature, waits for a catch and collects it.",
            p("Creature", Kind.CREATURE, "Crimson swift")),
        d("lay_trap", "Lay Trap", Cat.NATURE, "lay_trap", "Lays one trap where you stand (a net trap goes on a young tree).", p("Trap", Kind.TRAP, "Bird snare")),
        d("check_traps", "Check Traps", Cat.NATURE, "check_traps", "Collects catches, resets collapsed traps and picks up fallen ones."),
        d("track", "Track Kebbit", Cat.NATURE, "track", "Follows a kebbit's tracks from a burrow and catches it. Needs a noose wand."),
        d("rake", "Rake Patch", Cat.NATURE, "rake_patch", "Clears the weeds from a farming patch with a rake.", p("Patch", Kind.PATCH, "Farming patch")),
        d("plant", "Plant", Cat.NATURE, "plant", "Plants a seed (with a seed dibber) or a sapling (with a spade). Rakes first if needed.",
            p("Seed", Kind.SEED, "Potato seed"), p("Patch", Kind.PATCH, "Allotment", "in")),
        d("water", "Water Patch", Cat.NATURE, "water_patch", "Waters a growing patch with a watering can.", p("Patch", Kind.PATCH, "Farming patch")),
        d("compost", "Compost Patch", Cat.NATURE, "compost_patch", "Puts compost on an empty, weeded patch before planting.", p("Patch", Kind.PATCH, "Farming patch")),
        d("cure", "Cure Patch", Cat.NATURE, "cure_patch", "Cures a diseased patch with plant cure.", p("Patch", Kind.PATCH, "Farming patch")),
        d("harvest", "Harvest", Cat.NATURE, "harvest_patch", "Harvests a grown patch, checks a tree's health, or clears a dead patch with a spade.", p("Patch", Kind.PATCH, "Farming patch")),
        // Combat & magic
        d("attack", "Attack", Cat.COMBAT, "attack", "Attacks the nearest monster with that name and waits until it is dead.", p("Monster", Kind.MONSTER, "Cow")),
        d("attack_task", "Attack Slayer Task", Cat.COMBAT, "attack_task", "Fights the nearest monster of your current Slayer task."),
        d("retaliate", "Auto Retaliate", Cat.COMBAT, "set_retaliate", "Turns fighting back on or off.", p("On or off", Kind.ONOFF, "On")),
        d("cast", "Cast Spell", Cat.COMBAT, "cast_spell", "Casts a spell: teleports on yourself, alchemy or enchanting on an item, combat spells on a monster.",
            p("Spell", Kind.SPELL, "Varrock teleport"), opt("On", Kind.SPELLTARGET, "", "on")),
        d("prayer", "Prayer", Cat.COMBAT, "set_prayer", "Turns a prayer on or off.", p("Prayer", Kind.PRAYER, "Protect from melee"), p("On or off", Kind.ONOFF, "On")),
        d("recharge", "Recharge", Cat.COMBAT, "recharge", "Prays at the nearest altar for prayer points, or renews Summoning points at an obelisk.",
            p("What", Kind.RECHARGE, "Prayer")),
        d("summon", "Summon", Cat.COMBAT, "summon", "Summons a familiar from its pouch.", p("Familiar", Kind.FAMILIAR, "Spirit wolf")),
        // House
        d("enter_house", "Enter House", Cat.HOUSE, "enter_house", "Goes into your house through the portal, normally or in building mode.", p("Mode", Kind.HOUSEMODE, "Building mode")),
        d("build", "Build", Cat.HOUSE, "build", "Builds furniture on the nearest empty spot that takes it. Needs building mode and a hammer.",
            p("Furniture", Kind.FURNITURE, "Crude wooden chair")),
        // Logic
        new Def("forever", "Repeat Forever", Cat.LOGIC, Shape.LOOP, "", "Runs the blocks inside again and again until you press Stop."),
        new Def("repeat", "Repeat", Cat.LOGIC, Shape.LOOP, "", "Runs the blocks inside this many times.", p("Times", Kind.TIMES, "10")),
        new Def("until", "Repeat Until", Cat.LOGIC, Shape.LOOP, "", "Runs the blocks inside until the condition is true.", p("Condition", Kind.CONDITION, "Inventory Is Full")),
        new Def("if", "If", Cat.LOGIC, Shape.LOOP, "", "Runs the blocks inside only when the condition is true.", p("Condition", Kind.CONDITION, "Inventory Is Full")),
        new Def("ifelse", "If", Cat.LOGIC, Shape.IF_ELSE, "", "Runs the first blocks when the condition is true, otherwise the blocks under Else.", p("Condition", Kind.CONDITION, "Inventory Is Full")),
        d("wait", "Wait", Cat.LOGIC, "wait", "Waits this many seconds.", p("Seconds", Kind.SECONDS, "3")),
        d("stop", "Stop Script", Cat.LOGIC, "", "Ends the script here."),
        d("js", "JavaScript", Cat.LOGIC, "", "Runs your own JavaScript code. Code from the Advanced view that isn't a block shows up here.",
            p("Code", Kind.CODE, "say(\"Hello\");")),
        // a whole JavaScript file shown in the Basic view: runs exactly as written when it is the only block
        d("jsfile", "JavaScript", Cat.LOGIC, "", "A JavaScript script, kept exactly as it was written.", p("Code", Kind.CODE, ""))
    );

    /** Conditions for If and Repeat Until. Some need a value (an item, a number, a place, a quest...). */
    static final List<String> CONDITIONS = List.of("Inventory Is Full", "Inventory Is Not Full", "Inventory Is Empty",
        "Has Item", "Does Not Have Item", "HP Below %", "Is In Combat", "Is Not In Combat", "Is At", "Bank Is Open",
        "Dialogue Is Open", "Options Are Showing", "Make Menu Is Open", "Quest Is Complete", "Level Is At Least",
        "Level Is", "Level Is Not", "Level Is Below",
        "Has Slayer Task", "Prayer Is On", "Prayer Points Below", "Has Familiar", "Is In House",
        "Run Is On", "Run Is Off", "Run Energy Below", "Run Energy Is At Least");

    static Kind conditionValueKind(String cond) {
        switch (cond) {
            case "Has Item": case "Does Not Have Item": return Kind.INV;
            case "HP Below %": case "Prayer Points Below": case "Run Energy Below": case "Run Energy Is At Least": return Kind.NUMBER;
            case "Is At": return Kind.PLACE;
            case "Quest Is Complete": return Kind.QUEST;
            case "Prayer Is On": return Kind.PRAYER;
            case "Level Is At Least": case "Level Is": case "Level Is Not": case "Level Is Below": return Kind.LEVEL;
            default: return Kind.NONE;
        }
    }
    static String conditionDefault(String cond) {
        switch (cond) {
            case "HP Below %": return "50";
            case "Prayer Points Below": return "10";
            case "Run Energy Below": return "20";
            case "Run Energy Is At Least": return "50";
            case "Level Is At Least": case "Level Is": case "Level Is Not": case "Level Is Below": return "Woodcutting 15";
            default: return "";
        }
    }

    static Def def(String type) {
        for (Def d : DEFS) if (d.type.equals(type)) return d;
        return null;
    }

    /** Blocks from older files that were renamed. */
    private static String rename(String type) {
        switch (type) {
            default: return type;
        }
    }

    // ------------------------------------------------------------------ one block
    static final class Block {
        String type;
        /** One value per slot of the block (a condition block holds the condition and its value). */
        final String[] args;
        final List<Block> body = new ArrayList<>();
        final List<Block> elseBody = new ArrayList<>();
        transient String runId = "";

        Block(String type) {
            this.type = type;
            Def d = BlockModel.def(type);
            int n = d == null ? 0 : d.kind == Kind.CONDITION ? 2 : d.params.length;
            args = new String[n];
            for (int i = 0; i < n; i++) args[i] = d.kind == Kind.CONDITION ? (i == 0 ? d.params[0].def : "") : d.params[i].def;
        }

        Def def() { return BlockModel.def(type); }
        String arg(int i) { return i < args.length && args[i] != null ? args[i] : ""; }
        void set(int i, String v) { if (i < args.length) args[i] = v == null ? "" : v; }
        /** First slot (a place, a tree, the condition...). */
        String target() { return arg(0); }
        /** Second slot (a condition's value). */
        String value() { return arg(1); }

        Block copy() {
            Block b = new Block(type);
            System.arraycopy(args, 0, b.args, 0, args.length);
            for (Block c : body) b.body.add(c.copy());
            for (Block c : elseBody) b.elseBody.add(c.copy());
            return b;
        }

        /** The text in the orange slot, e.g. "Oak", "Clean guam on Vial of water × All", "HP Below 50%". */
        String slotText() {
            Def d = def();
            if (d == null || d.params.length == 0) return "";
            if (d.kind == Kind.CONDITION) {
                String c = target();
                Kind vk = conditionValueKind(c);
                if (vk == Kind.NONE) return c;
                String v = value().isEmpty() ? "?" : value();
                if (c.equals("HP Below %")) return "HP Below " + v + "%";
                if (c.startsWith("Run Energy")) return c + " " + v + "%";
                if (vk == Kind.LEVEL) {   // "Woodcutting Level Is Not 15"
                    Matcher m = Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(v);
                    if (m.find()) return m.group(1) + " " + c + " " + m.group(2);
                }
                return c + " " + v;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.params.length; i++) {
                Param p = d.params[i];
                String v = arg(i);
                if (v.isEmpty() && p.optional) continue;
                String shown = show(p.kind, v);
                if (sb.length() > 0) sb.append(' ');
                if (!p.word.isEmpty() && i > 0) sb.append(p.word).append(p.word.equals("×") ? "" : " ");
                else if (!p.word.isEmpty() && (p.kind == Kind.SLOT)) sb.append(p.word).append(' ');
                sb.append(shown);
            }
            return sb.toString();
        }
    }

    private static String show(Kind k, String v) {
        switch (k) {
            case SECONDS: return v + (v.equals("1") ? " second" : " seconds");
            case TIMES: return v + (v.equals("1") ? " time" : " times");
            case AMOUNT: return v.isEmpty() || v.equals("0") ? "All" : v;
            case TEXT: return "“" + v + "”";
            case PRODUCT: return v.isEmpty() ? "any" : v;
            case CODE: {
                String[] lines = v.strip().split("\n");
                String first = lines[0].strip();
                if (first.length() > 40) first = first.substring(0, 38) + "…";
                return lines.length > 1 ? first + "  (+" + (lines.length - 1) + " lines)" : first.isEmpty() ? "?" : first;
            }
            default: return v.isEmpty() ? "?" : v;
        }
    }

    // ------------------------------------------------------------------ files
    static String toJson(List<Block> script) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"format\": \"code-edition-blocks\",\n  \"version\": 2,\n  \"blocks\": ");
        list(sb, script, 2);
        return sb.append("\n}\n").toString();
    }

    private static void list(StringBuilder sb, List<Block> l, int indent) {
        if (l.isEmpty()) { sb.append("[]"); return; }
        sb.append("[\n");
        for (int i = 0; i < l.size(); i++) {
            Block b = l.get(i);
            String pad = " ".repeat(indent + 2);
            sb.append(pad).append("{\"type\": ").append(q(b.type)).append(", \"args\": [");
            for (int a = 0; a < b.args.length; a++) sb.append(a > 0 ? ", " : "").append(q(b.arg(a)));
            sb.append(']');
            Shape shape = b.def() == null ? Shape.PLAIN : b.def().shape;
            if (shape != Shape.PLAIN) { sb.append(", \"body\": "); list(sb, b.body, indent + 2); }
            if (shape == Shape.IF_ELSE) { sb.append(", \"else\": "); list(sb, b.elseBody, indent + 2); }
            sb.append('}').append(i < l.size() - 1 ? ",\n" : "\n");
        }
        sb.append(" ".repeat(indent)).append(']');
    }

    static String q(String s) {
        StringBuilder o = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') o.append('\\').append(c);
            else if (c == '\n') o.append("\\n");
            else if (c < 0x20 || c == ' ' || c == ' ') o.append(String.format("\\u%04x", (int) c));
            else o.append(c);
        }
        return o.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    static List<Block> fromJson(String text) {
        Object root = new Parser(text).value();
        List<Block> out = new ArrayList<>();
        if (root instanceof Map) readList((List<Object>) ((Map<String, Object>) root).getOrDefault("blocks", List.of()), out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void readList(List<Object> in, List<Block> out) {
        for (Object o : in) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String type = rename(String.valueOf(m.get("type")));
            if (def(type) == null) continue;
            Block b = new Block(type);
            if (m.get("args") instanceof List) {
                List<Object> a = (List<Object>) m.get("args");
                for (int i = 0; i < a.size() && i < b.args.length; i++) b.set(i, a.get(i) == null ? "" : String.valueOf(a.get(i)));
            } else {    // version 1 files: target and value
                if (m.containsKey("target")) b.set(0, String.valueOf(m.get("target")));
                if (m.containsKey("value")) b.set(1, String.valueOf(m.get("value")));
            }
            if (m.get("body") instanceof List) readList((List<Object>) m.get("body"), b.body);
            if (m.get("else") instanceof List) readList((List<Object>) m.get("else"), b.elseBody);
            out.add(b);
        }
    }

    /** Just enough JSON reading for .blocks files and the catalogue. */
    static final class Parser {
        private final String s; private int i;
        Parser(String s) { this.s = s; }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("the file is empty");
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = str(); ws(); expect(':'); m.put(k, value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>(); i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']'); return l;
                }
            }
            if (c == '"') return str();
            int start = i;
            while (i < s.length() && "{}[],: \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String word = s.substring(start, i);
            if (word.equals("true")) return true;
            if (word.equals("false")) return false;
            if (word.equals("null")) return null;
            return word;
        }
        private String str() {
            expect('"');
            StringBuilder o = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
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
        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("unexpected text at character " + i);
            i++;
        }
    }

    // ------------------------------------------------------------------ JavaScript
    /** Turns a block script into JavaScript for the game. Gives every block a run id ("b1", "b2"...) so it can light up. */
    static String compile(List<Block> script) {
        // a plain JavaScript file on its own: give it back exactly as it was written
        if (script.size() == 1 && script.get(0).type.equals("jsfile")) { script.get(0).runId = ""; return script.get(0).arg(0).strip() + "\n"; }
        StringBuilder sb = new StringBuilder("// made by the block editor\nblocks.run(async () => {\n");
        int[] n = {0};
        emit(sb, script, 1, n);
        return sb.append("});\n").toString();
    }

    private static void emit(StringBuilder sb, List<Block> l, int depth, int[] n) {
        String pad = "  ".repeat(depth);
        for (Block b : l) {
            String id = "b" + (++n[0]);
            b.runId = id;
            String I = q(id);
            Def d = b.def();
            if (d == null) continue;
            switch (b.type) {
                case "stop": sb.append(pad).append("blocks.step(").append(I).append("); return;\n"); continue;
                case "js": case "jsfile":
                    sb.append(pad).append("blocks.step(").append(I).append(");\n");
                    for (String line : b.arg(0).strip().split("\n", -1)) sb.append(line.isBlank() ? "" : pad + line).append('\n');
                    continue;
                case "say": sb.append(pad).append("blocks.say(").append(I).append(", ").append(q(b.arg(0))).append(");\n"); continue;
                case "forever":
                    sb.append(pad).append("while (true) {\n");
                    emit(sb, b.body, depth + 1, n);
                    sb.append(pad).append("  await blocks.tick();\n").append(pad).append("}\n");
                    continue;
                case "repeat": {
                    String v = "i" + n[0];
                    sb.append(pad).append("for (let ").append(v).append(" = 0; ").append(v).append(" < ").append(num(b.arg(0), 1)).append("; ").append(v).append("++) {\n");
                    emit(sb, b.body, depth + 1, n);
                    sb.append(pad).append("  await blocks.tick();\n").append(pad).append("}\n");
                    continue;
                }
                case "until":
                    sb.append(pad).append("while (!blocks.check(").append(I).append(", ").append(q(b.target())).append(", ").append(q(b.value())).append(")) {\n");
                    emit(sb, b.body, depth + 1, n);
                    sb.append(pad).append("  await blocks.tick();\n").append(pad).append("}\n");
                    continue;
                case "if":
                case "ifelse":
                    sb.append(pad).append("if (blocks.check(").append(I).append(", ").append(q(b.target())).append(", ").append(q(b.value())).append(")) {\n");
                    emit(sb, b.body, depth + 1, n);
                    if (b.type.equals("ifelse")) {
                        sb.append(pad).append("} else {\n");
                        emit(sb, b.elseBody, depth + 1, n);
                    }
                    sb.append(pad).append("}\n");
                    continue;
                default: break;
            }
            // an action block: await blocks.fn(id, arg1, arg2...)
            sb.append(pad).append("await blocks.").append(d.fn).append('(').append(I);
            for (int i = 0; i < d.params.length; i++) {
                Param p = d.params[i];
                String v = b.arg(i);
                sb.append(", ");
                if (p.kind == Kind.SLOT) sb.append(num(v, 1));
                else if (p.kind.numeric()) sb.append(num(v, p.kind == Kind.AMOUNT ? 0 : 1));
                else sb.append(q(v));
            }
            sb.append(");\n");
        }
    }

    private static String num(String s, double fallback) {
        try { double d = Double.parseDouble(s.trim()); return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
        catch (Exception e) { return String.valueOf((long) fallback); }
    }

    /** Finds the block that had this run id in the last compile. */
    static Block byRunId(List<Block> script, String id) {
        for (Block b : script) {
            if (id.equals(b.runId)) return b;
            Block in = byRunId(b.body, id);
            if (in == null) in = byRunId(b.elseBody, id);
            if (in != null) return in;
        }
        return null;
    }

    /** A new script: chop oaks, bank at Draynor, keep the axe. */
    static List<Block> example() {
        List<Block> l = new ArrayList<>();
        Block loop = new Block("forever");
        Block w = new Block("walk"); w.set(0, "Oak");
        Block c = new Block("chop"); c.set(0, "Oak");
        Block iff = new Block("if"); iff.set(0, "Inventory Is Full");
        Block wb = new Block("walk"); wb.set(0, "Draynor Bank");
        Block ob = new Block("openbank");
        Block d = new Block("deposit"); d.set(0, "Inventory 3–22");
        Block cb = new Block("closebank");
        iff.body.addAll(List.of(wb, ob, d, cb));
        loop.body.addAll(List.of(w, c, iff));
        l.add(loop);
        return l;
    }
}
