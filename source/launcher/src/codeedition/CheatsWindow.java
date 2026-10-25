package codeedition;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * The Cheats window: every cheat code (game command) with what it does, how to type it, and a search box.
 * The list is read from codeedition/cheats.json; codes the game knows that the list doesn't describe are added
 * from the game itself, with the game's own short description.
 */
final class CheatsWindow extends JFrame {
    private final Backend backend;
    private final List<String[]> rows = new ArrayList<>();   // {code, what it does, group}
    private final Model model = new Model();
    private final JTable table = new JTable(model) {
        @Override public String getToolTipText(java.awt.event.MouseEvent e) {     // the whole text of a cut-off cell
            int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
            if (r < 0 || c < 0) return null;
            Object v = getValueAt(r, c);
            return v == null ? null : "<html><div style='width:420px'>" + v.toString().replace("<", "&lt;") + "<br><i>Double-click to copy the code</i></div></html>";
        }
    };
    private final TableRowSorter<Model> sorter = new TableRowSorter<>(model);
    private final JTextField search = new JTextField();
    private boolean loaded;

    CheatsWindow(JFrame owner, Backend backend) {
        super("Cheats");
        this.backend = backend;
        setIconImages(Theme.icons());
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        JLabel how = new JLabel("<html><div style='width:600px'>"
            + "<b style='font-size:13px'>How to use cheats</b><br><br>"
            + "Click in the game's chat box and type the code with two colons in front, then press Enter. "
            + "For example, type <b>::bank</b> to open your bank anywhere, or <b>::item 379 10</b> to get 10 lobsters (379 is the lobster's item number: <b>::itemsearch lobster</b> finds it).<br>"
            + "Words in &lt;angle brackets&gt; are what you fill in (leave the brackets out). Words in [square brackets] "
            + "are optional.<br>"
            + "Cheats work on your character in this single-player game. Some change your save for good (levels, quests, items), "
            + "so make a backup first if you want to undo them: the launcher keeps backups in the backups folder."
            + "</div></html>");
        how.setBorder(new EmptyBorder(12, 14, 10, 14));

        search.putClientProperty("JTextField.placeholderText", "Search cheats (a code or a word like teleport, level, item)");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });
        JPanel top = new JPanel(new BorderLayout());
        top.add(how, BorderLayout.CENTER);
        JPanel sp = new JPanel(new BorderLayout());
        sp.setBorder(new EmptyBorder(0, 14, 8, 14));
        sp.add(search);
        top.add(sp, BorderLayout.SOUTH);

        ToolTipManager.sharedInstance().registerComponent(table);
        table.setRowSorter(sorter);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(520);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0) return;
                String code = (String) model.getValueAt(table.convertRowIndexToModel(r), 0);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(code), null);
                status.setText("Copied " + code + ". Click the game, press Ctrl+V to paste it into the chat box, then Enter.");
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        status.setBorder(new EmptyBorder(6, 14, 8, 14));
        status.setForeground(Theme.MUTED);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        setSize(900, 680);
        setLocationRelativeTo(owner);
    }

    private final JLabel status = new JLabel(" ");

    void open() {
        if (!loaded) load();
        Theme.bringToFront(this);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        rows.clear();
        Set<String> seen = new HashSet<>();
        try {
            String text = new String(Files.readAllBytes(Game.ROOT.resolve("codeedition").resolve("cheats.json")), StandardCharsets.UTF_8);
            for (Object o : (List<Object>) new BlockModel.Parser(text).value()) {
                List<Object> r = (List<Object>) o;
                rows.add(new String[]{"::" + r.get(0), String.valueOf(r.get(2)), String.valueOf(r.get(1))});
                seen.add(String.valueOf(r.get(0)).split(" ")[0].toLowerCase());
            }
        } catch (Exception ignored) {}
        // anything the game knows that the list doesn't describe, with the game's own words
        String live = backend.send("commands");
        int extra = 0;
        if (live != null && live.startsWith("[")) {
            try {
                for (Object o : (List<Object>) new BlockModel.Parser(live).value()) {
                    Map<String, Object> c = (Map<String, Object>) o;
                    String name = String.valueOf(c.get("name"));
                    if (seen.contains(name.toLowerCase())) continue;
                    String usage = tidy(String.valueOf(c.getOrDefault("usage", "")));
                    String about = tidy(String.valueOf(c.getOrDefault("about", "")));
                    String code = usage.isEmpty() || usage.equals("null") ? name : usage.startsWith(name) ? usage : name + " " + usage;
                    rows.add(new String[]{"::" + code.replace("::", ""), about.isEmpty() || about.equals("null") ? "(no description from the game)" : about, "Developer and server tools"});
                    extra++;
                }
            } catch (Exception ignored) {}
        }
        loaded = live != null;
        model.fireTableDataChanged();
        status.setText(rows.size() + " cheats" + (live == null ? ". Start the game to also list the game's other codes." : "")
            + "   Double-click a row to copy its code, then press Ctrl+V in the game.");
    }

    /** The game writes its notes for its own chat: "&lt;lt&gt;" for "<", line breaks as <br>. */
    private static String tidy(String s) {
        if (s == null || s.trim().equals("null")) return "";
        return s.replace("<lt>", "<").replace("<gt>", ">").replace("<br>", " ").trim();
    }

    private void filter() {
        String q = search.getText().trim().toLowerCase();
        if (q.isEmpty()) { sorter.setRowFilter(null); return; }
        sorter.setRowFilter(new RowFilter<Model, Integer>() {
            @Override public boolean include(Entry<? extends Model, ? extends Integer> e) {
                for (String w : q.split("\\s+")) {
                    boolean hit = false;
                    for (int c = 0; c < 3; c++) if (e.getStringValue(c).toLowerCase().contains(w)) hit = true;
                    if (!hit) return false;
                }
                return true;
            }
        });
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Code", "Group", "What it does"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) { String[] row = rows.get(r); return c == 0 ? row[0] : c == 1 ? row[2] : row[1]; }
    }
}
