package codeedition;

import codeedition.BlockModel.Block;
import codeedition.BlockModel.Def;
import codeedition.BlockModel.Kind;
import codeedition.BlockModel.Param;
import codeedition.EditorCatalog.Entry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The panel on the right of the block editor: what goes in each slot of the selected block. Pick lists come from the
 * game catalogue (with the level each one needs) and from what is near your character right now. Underneath,
 * "What it needs" checks the levels, items and quests against your character.
 */
final class TargetPicker extends JPanel {
    private final BlockCanvas canvas;
    private final Supplier<String> nearbySource, statusSource;
    private final JLabel title = new JLabel(" ");
    private final JTextArea help = new JTextArea();
    private final JPanel slots = new JPanel();
    private final JPanel body = new JPanel(new BorderLayout());
    private final JTextArea needs = new JTextArea();
    private Block block;
    private int slot;
    private volatile Map<String, List<String>> nearby = Map.of();
    private volatile Map<String, Integer> levels = Map.of();

    TargetPicker(BlockCanvas canvas, Supplier<String> nearbySource, Supplier<String> statusSource) {
        super(new BorderLayout(0, 6));
        this.canvas = canvas;
        this.nearbySource = nearbySource;
        this.statusSource = statusSource;
        setBackground(Theme.BG);
        setBorder(new EmptyBorder(10, 12, 10, 12));
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        JLabel t = new JLabel("BLOCK");
        t.setFont(t.getFont().deriveFont(Font.BOLD, 11f));
        t.setForeground(Theme.MUTED);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(Theme.TEXT);
        style(help);
        help.setBorder(new EmptyBorder(0, 0, 6, 0));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(t, BorderLayout.NORTH);
        top.add(title, BorderLayout.CENTER);
        top.add(help, BorderLayout.SOUTH);
        slots.setLayout(new BoxLayout(slots, BoxLayout.Y_AXIS));
        slots.setOpaque(false);
        head.add(top, BorderLayout.NORTH);
        head.add(slots, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        body.setOpaque(false);
        add(body, BorderLayout.CENTER);
        style(needs);
        needs.setForeground(Theme.TEXT);
        needs.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x393b40)), new EmptyBorder(8, 0, 0, 0)));
        add(needs, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(255, 0));
        new Thread(EditorCatalog::load, "catalogue").start();
        show(null);
    }

    private static void style(JTextArea a) {
        a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); a.setOpaque(false);
        a.setForeground(Theme.MUTED);
        a.setFont(a.getFont().deriveFont(12f));
    }

    /** Shows the slots of this block (null: nothing selected). */
    void show(Block b) {
        if (b != block) slot = 0;
        block = b;
        build();
        if (b != null) refresh(b);
    }

    private void build() {
        Block b = block;
        body.removeAll();
        slots.removeAll();
        if (b == null) {
            title.setText("Nothing selected");
            help.setText("Click a block on the canvas to choose what it works on.");
            needs.setText("");
        } else {
            Def d = b.def();
            title.setText(d.label);
            help.setText(d.help);
            Param[] ps = d.kind == Kind.CONDITION ? new Param[0] : d.params;
            if (ps.length > 1) {
                ButtonGroup g = new ButtonGroup();
                for (int i = 0; i < ps.length; i++) {
                    int idx = i;
                    String v = b.arg(i);
                    JToggleButton tb = new JToggleButton("<html><span style='color:#8c8f94'>" + esc(ps[i].label) + ":</span> " + esc(v.isEmpty() ? (ps[i].optional ? "(any)" : "?") : shown(ps[i].kind, v)) + "</html>");
                    tb.setHorizontalAlignment(SwingConstants.LEFT);
                    tb.setFocusable(false);
                    tb.setSelected(i == slot);
                    tb.setAlignmentX(LEFT_ALIGNMENT);
                    tb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                    tb.addActionListener(e -> { slot = idx; build(); });
                    g.add(tb);
                    slots.add(tb);
                    slots.add(Box.createVerticalStrut(3));
                }
            }
            if (d.kind == Kind.CONDITION) body.add(conditionEditor(b), BorderLayout.CENTER);
            else if (ps.length == 0) body.add(note("This block needs nothing chosen."), BorderLayout.CENTER);
            else {
                if (slot >= ps.length) slot = 0;
                body.add(editorFor(b, slot, ps[slot]), BorderLayout.CENTER);
            }
            needs.setText(needsText(b));
        }
        slots.revalidate();
        body.revalidate();
        repaint();
    }

    private static String shown(Kind k, String v) {
        if (k == Kind.AMOUNT && (v.equals("0") || v.isEmpty())) return "All";
        return v;
    }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

    /** Asks the game what is near the character and the character's levels, then refreshes the lists. */
    private void refresh(Block b) {
        new Thread(() -> {
            String json = nearbySource.get();
            String status = statusSource.get();
            Map<String, List<String>> m = new HashMap<>();
            if (json != null && json.startsWith("{"))
                for (String key : new String[]{"monsters", "people", "objects", "ground", "inventory"}) m.put(key, array(json, key));
            Map<String, Integer> lv = new HashMap<>();
            if (status != null) {
                Matcher mm = Pattern.compile("\"base_levels\":\\{(.*?)}").matcher(status);
                if (mm.find()) {
                    Matcher e = Pattern.compile("\"(\\w+)\":(\\d+)").matcher(mm.group(1));
                    while (e.find()) lv.put(e.group(1), Integer.parseInt(e.group(2)));
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (m.equals(nearby) && lv.equals(levels)) return;
                nearby = m;
                levels = lv;
                if (block == b) build();
            });
        }, "picker-nearby").start();
    }

    private static List<String> array(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\\[(.*?)]").matcher(json);
        List<String> out = new ArrayList<>();
        if (!m.find()) return out;
        Matcher s = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(m.group(1));
        while (s.find()) out.add(s.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        return out;
    }

    // ------------------------------------------------------------------ what it needs
    /** Levels, items and quests the block's choices need, checked against the character when the game is running. */
    private String needsText(Block b) {
        Def d = b.def();
        if (d.kind == Kind.CONDITION || d.params.length == 0) return fixedNeeds(d.type);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < d.params.length; i++) {
            Param p = d.params[i];
            String list = p.kind.list();
            if (list == null || p.kind == Kind.OPTION || p.kind == Kind.KEY || p.kind == Kind.CHAT || p.kind == Kind.ONOFF
                || p.kind == Kind.HOUSEMODE || p.kind == Kind.RECHARGE || p.kind == Kind.PLACE) continue;
            Entry e = EditorCatalog.find(list, b.arg(i));
            if (e == null) continue;
            if (!e.skill.isEmpty() && e.level > 0) {
                String sk = e.skill.substring(0, 1).toUpperCase() + e.skill.substring(1);
                Integer have = levels.get(e.skill);
                lines.add(have == null ? "• " + sk + " level " + e.level
                    : (have >= e.level ? "✓ " : "✗ ") + sk + " level " + e.level + " (you have " + have + ")");
            }
            if (!e.needs.isEmpty()) lines.add("• Needs " + e.needs);
            if (!e.quest.isEmpty()) lines.add("• Quest: " + e.quest);
        }
        // "use X on Y" with no product chosen: say what the pair makes
        if ((b.type.equals("use_item_on_item") || b.type.equals("use_item_on_target")) && b.arg(3).isEmpty()) {
            String x = b.arg(0).toLowerCase(), y = b.arg(1).toLowerCase();
            List<String> makes = new ArrayList<>();
            for (Entry e : EditorCatalog.list("product")) {
                String n = e.note.toLowerCase();
                if (n.endsWith(x + " on " + y) || n.endsWith(y + " on " + x)) makes.add(e.name + (e.level > 0 ? " (" + cap(e.skill) + " " + e.level + ")" : ""));
            }
            if (!makes.isEmpty()) lines.add("• Makes " + String.join(", ", makes.subList(0, Math.min(4, makes.size()))) + (makes.size() > 4 ? "…" : ""));
            for (Entry e : EditorCatalog.list("product")) {
                String n = e.note.toLowerCase();
                if (!(n.endsWith(x + " on " + y) || n.endsWith(y + " on " + x))) continue;
                Integer have = levels.get(e.skill);
                if (e.level > 0 && have != null) lines.add((have >= e.level ? "✓ " : "✗ ") + cap(e.skill) + " level " + e.level + " (you have " + have + ")");
                if (!e.quest.isEmpty()) lines.add("• Quest: " + e.quest);
                break;
            }
        }
        String extra = fixedNeeds(d.type);
        if (!extra.isEmpty()) lines.add(extra);
        return lines.isEmpty() ? "" : "WHAT IT NEEDS\n" + String.join("\n", lines);
    }

    private static String cap(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1); }

    private static String fixedNeeds(String type) {
        switch (type) {
            case "craft_runes": return "• Rune or pure essence, and the altar's talisman or tiara\n• Quest: Rune Mysteries";
            case "track": return "• A noose wand";
            case "rake": return "• A rake";
            case "water": return "• A watering can with water in it";
            case "compost": return "• Compost or supercompost";
            case "cure": return "• Plant cure";
            case "harvest": return "• A spade for allotments and hops";
            case "build": return "• A hammer and a saw, building mode in your own house";
            case "enter_house": return "• A house (from an estate agent), next to its portal";
            case "summon": return "• Quest: Wolf Whistle";
            case "attack_task": return "• A Slayer task from a Slayer master";
            case "openbank": return "• Stand near a bank booth";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ editors
    private JComponent editorFor(Block b, int i, Param p) {
        Kind k = p.kind;
        switch (k) {
            case SECONDS: return numberEditor(b, i, "Seconds", 1, 3600);
            case TIMES: return numberEditor(b, i, "Times", 1, 100000);
            case NUMBER:
                if (b.type.equals("set_run")) return numberEditor(b, i, "Auto turns run back on when energy reaches this % (0 to 100)", 0, 100, 99);
                return numberEditor(b, i, "Number", 0, 100000);
            case SLOT: return numberEditor(b, i, "Inventory slot (1 to 28)", 1, 28);
            case AMOUNT: return numberEditor(b, i, "How many (0 = all of them)", 0, 1000000);
            case TEXT: return textEditor(b, i, p.label);
            case CODE: return codeEditor(b, i);
            case SLOTS: return slotsEditor(b, i);
            case PLACE: {
                JPanel p2 = new JPanel(new BorderLayout(0, 4));
                p2.setOpaque(false);
                JPanel top = column();
                top.add(coordBox(b.arg(i), v -> set(b, i, v)));
                top.add(label("Or pick a place or a name"));
                p2.add(top, BorderLayout.NORTH);
                p2.add(listEditor(sectionsFor(k, p.optional), b.arg(i), v -> set(b, i, v)), BorderLayout.CENTER);
                return p2;
            }
            case OPTION: {
                Map<String, List<Entry>> s = new LinkedHashMap<>();
                String who = b.arg(0);
                List<Entry> own = new ArrayList<>();
                for (String o : EditorCatalog.optionsOf(who)) own.add(new Entry(o, "", "", 0, "", ""));
                if (!own.isEmpty()) s.put("Options of " + who, own);
                s.put("Common options", EditorCatalog.list("option"));
                return listEditor(s, b.arg(i), v -> set(b, i, v));
            }
            default: return listEditor(sectionsFor(k, p.optional), b.arg(i), v -> set(b, i, v));
        }
    }

    private void set(Block b, int i, String v) { canvas.setArg(b, i, v); build(); }

    private static List<Entry> plain(List<String> names) {
        List<Entry> l = new ArrayList<>();
        for (String n : names) l.add(new Entry(n, "", "", 0, "", ""));
        return l;
    }

    private Map<String, List<Entry>> sectionsFor(Kind k, boolean optional) {
        Map<String, List<Entry>> s = new LinkedHashMap<>();
        if (optional) s.put("Leave empty", List.of(new Entry("", "(any: let the game choose)", "", 0, "", "")));
        List<Entry> monsters = plain(nearby.getOrDefault("monsters", List.of())), people = plain(nearby.getOrDefault("people", List.of())),
            objects = plain(nearby.getOrDefault("objects", List.of())), ground = plain(nearby.getOrDefault("ground", List.of())),
            inv = plain(nearby.getOrDefault("inventory", List.of()));
        switch (k) {
            case PLACE:
                s.put("Places", EditorCatalog.list("place"));
                s.put("Fishing spots (the easiest one to walk to)", fishSpots());
                s.put("Objects near you", objects);
                s.put("People and monsters near you", people);
                s.put("People", EditorCatalog.list("npc"));       // Walk To also goes to a person or monster by name
                s.put("Monsters", EditorCatalog.list("monster"));
                break;
            case RUNMODE:
                s.put("Run", List.of(new Entry("On", "", "", 0, "", ""), new Entry("Off", "", "", 0, "", ""),
                    new Entry("Auto", "on again whenever energy is full", "", 0, "", "")));
                break;
            case OBJECT:
                s.put("Objects near you", objects);
                s.put("All objects", EditorCatalog.list("object"));
                break;
            case NPC:
                s.put("People near you", people);
                s.put("People", EditorCatalog.list("npc"));
                break;
            case MONSTER:
                s.put("Monsters near you", monsters);
                s.put("Monsters", EditorCatalog.list("monster"));
                break;
            case ITEM:
                s.put("On the ground near you", ground);
                s.put("In your inventory", inv);
                s.put("Items", EditorCatalog.list("item"));
                break;
            case INV:
                s.put("In your inventory", inv);
                s.put("Used in recipes", EditorCatalog.list("ingredient"));
                s.put("All items", EditorCatalog.list("item"));
                break;
            case TARGET:
                s.put("Objects near you", objects);
                s.put("People near you", people);
                s.put("On the ground near you", ground);
                s.put("Farming patches", EditorCatalog.list("patch"));
                s.put("All objects", EditorCatalog.list("object"));
                s.put("People", EditorCatalog.list("npc"));
                break;
            case SPELLTARGET:
                s.put("Items in your inventory (alchemy, enchanting)", inv);
                s.put("Monsters near you (combat spells)", monsters);
                s.put("Monsters", EditorCatalog.list("monster"));
                break;
            case FOOD:
                s.put("In your inventory", inv);
                s.put("Food", EditorCatalog.list("food"));
                break;
            default: {
                String list = k.list();
                if (list != null) s.put(title(list), EditorCatalog.list(list));
            }
        }
        return s;
    }

    /** The fish list, as Walk To destinations: "Lobster" walks to a lobster spot. */
    private static List<Entry> fishSpots() {
        List<Entry> l = new ArrayList<>();
        for (Entry f : EditorCatalog.list("fish")) l.add(new Entry(f.name, f.note, "", 0, "", ""));
        return l;
    }

    private static String title(String list) {
        switch (list) {
            case "product": return "Things you can make";
            case "thieving": return "People, stalls and chests";
            case "creature": return "Creatures";
            case "furniture": return "Furniture";
            case "course": return "Agility courses";
            default: return list.substring(0, 1).toUpperCase() + list.substring(1) + (list.endsWith("s") ? "" : "s");
        }
    }

    private interface Setter { void set(String v); }

    /** A search box over a sectioned list. Enter in the box uses the typed name as it is. */
    private JComponent listEditor(Map<String, List<Entry>> sections, String current, Setter set) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search or type a name, then Enter");
        DefaultListModel<Object> model = new DefaultListModel<>();
        JList<Object> list = new JList<>(model);
        list.setBackground(new Color(0x1a1b1e));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                if (v instanceof String) {
                    JLabel h = new JLabel(((String) v).toUpperCase());
                    h.setFont(h.getFont().deriveFont(Font.BOLD, 10.5f));
                    h.setForeground(Theme.MUTED);
                    h.setBorder(new EmptyBorder(8, 6, 3, 6));
                    return h;
                }
                Entry e = (Entry) v;
                String note = e.note.isEmpty() ? "" : " <span style='color:#8c8f94'>" + esc(e.note) + "</span>";
                Integer have = e.skill.isEmpty() ? null : levels.get(e.skill);
                String warn = have != null && e.level > have ? " <span style='color:#ff7b72'>(you have " + have + ")</span>" : "";
                JLabel c = (JLabel) super.getListCellRendererComponent(l, "<html>" + esc(e.name.isEmpty() ? "(empty)" : e.name) + note + warn + "</html>", i, sel, f);
                c.setBorder(new EmptyBorder(3, 12, 3, 6));
                if (e.name.equalsIgnoreCase(current)) c.setForeground(BlockCanvas.SLOT_TEXT);
                return c;
            }
        });
        Runnable fill = () -> {
            model.clear();
            String q = search.getText().trim().toLowerCase();
            for (Map.Entry<String, List<Entry>> e : sections.entrySet()) {
                List<Entry> hits = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Entry x : e.getValue()) {
                    if (hits.size() >= 400) break;      // long lists: search narrows them
                    if ((q.isEmpty() || x.name.toLowerCase().contains(q) || x.note.toLowerCase().contains(q)) && seen.add(x.name.toLowerCase())) hits.add(x);
                }
                if (hits.isEmpty()) continue;
                model.addElement(e.getKey());
                hits.forEach(model::addElement);
            }
        };
        fill.run();
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        search.addActionListener(e -> {
            String t = search.getText().trim();
            if (!t.isEmpty()) set.set(t);
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                Object v = list.getSelectedValue();
                if (v instanceof Entry) set.set(((Entry) v).name);
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(new Color(0x393b40)));
        p.add(search, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JComponent numberEditor(Block b, int i, String what, int min, int max) { return numberEditor(b, i, what, min, max, min); }
    private JComponent numberEditor(Block b, int i, String what, int min, int max, int empty) {
        JPanel p = column();
        int v = empty;
        try { v = Integer.parseInt(b.arg(i).trim()); } catch (Exception ignored) {}
        JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, v)), min, max, 1));
        sp.addChangeListener(e -> { canvas.setArg(b, i, String.valueOf(sp.getValue())); refreshSlotsOnly(); });
        sp.setAlignmentX(LEFT_ALIGNMENT);
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.add(label(what));
        p.add(sp);
        return wrapTop(p);
    }

    /** Updates the slot buttons' text without rebuilding the editor (keeps spinner focus). */
    private void refreshSlotsOnly() {
        Block b = block;
        if (b == null) return;
        Param[] ps = b.def().params;
        for (int i = 0, c = 0; i < slots.getComponentCount(); i++) {
            if (!(slots.getComponent(i) instanceof JToggleButton)) continue;
            String v = b.arg(c);
            ((JToggleButton) slots.getComponent(i)).setText("<html><span style='color:#8c8f94'>" + esc(ps[c].label) + ":</span> " + esc(v.isEmpty() ? "?" : shown(ps[c].kind, v)) + "</html>");
            c++;
        }
    }

    /** Several lines of JavaScript. Changes apply when you click away or press Apply. */
    private JComponent codeEditor(Block b, int i) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        JTextArea area = new JTextArea(b.arg(i), 14, 20);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(2);
        area.setBackground(new Color(0x1e1f22));
        area.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent e) { if (!area.getText().equals(b.arg(i))) canvas.setArg(b, i, area.getText()); } });
        JButton apply = new JButton("Apply");
        apply.setFocusable(false);
        apply.addActionListener(e -> canvas.setArg(b, i, area.getText()));
        p.add(label("JavaScript (runs as written; the Advanced view shows it in place)"), BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.setOpaque(false);
        south.add(apply);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent textEditor(Block b, int i, String what) {
        JPanel p = column();
        JTextField f = new JTextField(b.arg(i));
        f.setAlignmentX(LEFT_ALIGNMENT);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        f.addActionListener(e -> set(b, i, f.getText()));
        f.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent e) { if (!f.getText().equals(b.arg(i))) canvas.setArg(b, i, f.getText()); } });
        p.add(label(what));
        p.add(f);
        return wrapTop(p);
    }

    private JComponent conditionEditor(Block b) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        Map<String, List<Entry>> secs = new LinkedHashMap<>();
        secs.put("Conditions", plain(BlockModel.CONDITIONS));
        JComponent conds = listEditor(secs, b.target(), v -> {
            canvas.setTarget(b, v, BlockModel.conditionDefault(v));
            build();
        });
        Kind vk = BlockModel.conditionValueKind(b.target());
        if (vk == Kind.NONE) { p.add(conds, BorderLayout.CENTER); return p; }
        JPanel split = new JPanel(new GridLayout(2, 1, 0, 8));
        split.setOpaque(false);
        split.add(conds);
        JPanel valueBox = new JPanel(new BorderLayout(0, 4));
        valueBox.setOpaque(false);
        if (vk == Kind.NUMBER) {
            String c = b.target();
            boolean pct = c.equals("HP Below %") || c.startsWith("Run Energy");
            valueBox.add(label(c.equals("HP Below %") ? "HP below what %?" : c.equals("Run Energy Below") ? "Run energy below what %?"
                : c.equals("Run Energy Is At Least") ? "Run energy at least what %?" : "Below how many points?"), BorderLayout.NORTH);
            int v = Integer.parseInt(BlockModel.conditionDefault(c).isEmpty() ? "10" : BlockModel.conditionDefault(c));
            try { v = Integer.parseInt(b.value().trim()); } catch (Exception ignored) {}
            int max = c.startsWith("Run Energy") ? 100 : 99;
            JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.max(1, Math.min(max, v)), 1, max, pct ? 5 : 1));
            sp.addChangeListener(e -> canvas.setTarget(b, null, String.valueOf(sp.getValue())));
            valueBox.add(wrapTop(sp), BorderLayout.CENTER);
        } else if (vk == Kind.LEVEL) {
            Matcher m = Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(b.value());
            String skill = m.find() ? m.group(1) : "Woodcutting";
            int lvl = 15;
            try { lvl = Integer.parseInt(m.group(2)); } catch (Exception ignored) {}
            JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.max(1, Math.min(99, lvl)), 1, 99, 1));
            String sk = skill;
            sp.addChangeListener(e -> canvas.setTarget(b, null, sk + " " + sp.getValue()));
            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setOpaque(false);
            top.add(label("Level"), BorderLayout.WEST);
            top.add(sp, BorderLayout.CENTER);
            valueBox.add(top, BorderLayout.NORTH);
            Map<String, List<Entry>> sk2 = new LinkedHashMap<>();
            sk2.put("Skills", EditorCatalog.list("skill"));
            valueBox.add(listEditor(sk2, skill, v -> { canvas.setTarget(b, null, v + " " + sp.getValue()); build(); }), BorderLayout.CENTER);
        } else {
            JComponent what = label(vk == Kind.PLACE ? "Which place?" : vk == Kind.QUEST ? "Which quest?" : vk == Kind.PRAYER ? "Which prayer?" : "Which item?");
            if (vk == Kind.PLACE) {
                JPanel top = column();
                top.add(coordBox(b.value(), v -> { canvas.setTarget(b, null, v); build(); }));
                top.add(label("Or a place"));
                what = top;
            }
            valueBox.add(what, BorderLayout.NORTH);
            Map<String, List<Entry>> vs = sectionsFor(vk, false);
            if (vk == Kind.PLACE) vs.keySet().retainAll(List.of("Places"));   // Is At only knows places
            valueBox.add(listEditor(vs, b.value(), v -> { canvas.setTarget(b, null, v); build(); }), BorderLayout.CENTER);
        }
        split.add(valueBox);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JComponent slotsEditor(Block b, int i) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        JPanel top = column();
        top.add(label("Inventory slots (1 to 28)"));
        JButton all = Theme.button("All slots", null);
        all.addActionListener(e -> set(b, i, "Inventory All"));
        int[] cur = parseRange(b.arg(i));
        JSpinner from = new JSpinner(new SpinnerNumberModel(cur[0], 1, 28, 1)), to = new JSpinner(new SpinnerNumberModel(cur[1], 1, 28, 1));
        JButton apply = Theme.button("Use these slots", null);
        apply.addActionListener(e -> {
            int a = (Integer) from.getValue(), z = (Integer) to.getValue();
            if (z < a) { int t = a; a = z; z = t; }
            set(b, i, a == z ? "Inventory " + a : "Inventory " + a + "–" + z);
        });
        JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        range.setOpaque(false);
        range.setAlignmentX(LEFT_ALIGNMENT);
        range.add(new JLabel("From")); range.add(from); range.add(new JLabel("to")); range.add(to);
        top.add(all);
        top.add(range);
        top.add(apply);
        top.add(label("Or one kind of item"));
        p.add(top, BorderLayout.NORTH);
        p.add(listEditor(sectionsFor(Kind.INV, false), b.arg(i), v -> set(b, i, v)), BorderLayout.CENTER);
        return p;
    }

    /** Asks the game for the tile the player clicks next (the Playground's coordinate grabber), then hands it over. */
    private java.util.function.Consumer<java.util.function.Consumer<String>> grabber = done -> {};
    void grabber(java.util.function.Consumer<java.util.function.Consumer<String>> g) { grabber = g; }

    /** Map coordinates for Walk To and Is At, like the slot range picker: type them, use where you stand, or grab a tile. */
    private JComponent coordBox(String current, Setter set) {
        JPanel box = column();
        box.add(label("Map coordinates"));
        int[] c = parseCoords(current);
        JSpinner x = coordSpinner(c[0], 16383), y = coordSpinner(c[1], 16383), z = coordSpinner(c[2], 3);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(new JLabel("X")); row.add(x); row.add(new JLabel("Y")); row.add(y);
        JButton use = Theme.button("Use these", null);
        use.setToolTipText("Put these coordinates in the block");
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        row2.setOpaque(false);
        row2.setAlignmentX(LEFT_ALIGNMENT);
        row2.add(new JLabel("Floor")); row2.add(z); row2.add(Box.createHorizontalStrut(6)); row2.add(use);
        use.addActionListener(e -> set.set(coordText((Integer) x.getValue(), (Integer) y.getValue(), (Integer) z.getValue())));
        JButton here = Theme.button("Where I am", null);
        here.setToolTipText("Your character's tile right now");
        here.addActionListener(e -> new Thread(() -> {
            String st = statusSource.get();
            Matcher m = Pattern.compile("\"x\":(\\d+),\"y\":(\\d+)(?:,\"z\":(\\d+))?").matcher(st == null ? "" : st);
            if (m.find()) {
                String v = coordText(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
                SwingUtilities.invokeLater(() -> set.set(v));
            }
        }, "where-am-i").start());
        JButton grab = Theme.button("Grab from game", null);
        grab.setToolTipText("Then click a tile in the game window");
        grab.addActionListener(e -> grabber.accept(set::set));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.add(here);
        buttons.add(grab);
        for (JButton bt : new JButton[]{use, here, grab}) { bt.setMargin(new Insets(2, 7, 2, 7)); bt.setAlignmentX(LEFT_ALIGNMENT); }
        box.add(row);
        box.add(Box.createVerticalStrut(4));
        box.add(row2);
        box.add(Box.createVerticalStrut(4));
        box.add(buttons);
        return box;
    }

    private static JSpinner coordSpinner(int v, int max) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.max(0, Math.min(max, v)), 0, max, 1));
        sp.setEditor(new JSpinner.NumberEditor(sp, "#"));
        ((JSpinner.DefaultEditor) sp.getEditor()).getTextField().setColumns(max > 9 ? 4 : 1);
        return sp;
    }

    /** "3222, 3218" on the ground floor, "3222, 3218, 1" upstairs. */
    static String coordText(int x, int y, int z) { return x + ", " + y + (z == 0 ? "" : ", " + z); }

    /** x, y, floor from "3222, 3218" or "3222 3218 1"; Lumbridge when the text isn't coordinates. */
    static int[] parseCoords(String t) {
        Matcher m = Pattern.compile("^\\(?\\s*(\\d{3,5})\\s*[,\\s]\\s*(\\d{3,5})(?:\\s*[,\\s]\\s*(\\d))?\\s*\\)?$").matcher(t == null ? "" : t.trim());
        if (!m.find()) return new int[]{3222, 3218, 0};
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    static int[] parseRange(String t) {
        Matcher m = Pattern.compile("(\\d+)\\s*(?:[-–—]\\s*(\\d+))?").matcher(t == null ? "" : t);
        if (!m.find()) return new int[]{1, 28};
        int a = Integer.parseInt(m.group(1)), z = m.group(2) == null ? a : Integer.parseInt(m.group(2));
        return new int[]{Math.max(1, Math.min(28, a)), Math.max(1, Math.min(28, z))};
    }

    // ------------------------------------------------------------------ bits
    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }
    private static JComponent wrapTop(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(c, BorderLayout.NORTH);
        return p;
    }
    private static JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(Theme.MUTED);
        l.setBorder(new EmptyBorder(6, 0, 4, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }
    private static JComponent note(String s) {
        JLabel l = label(s);
        return wrapTop(l);
    }
}
