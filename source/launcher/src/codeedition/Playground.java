package codeedition;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Script Playground: its own window beside the game. Script list, the block editor (or the JavaScript editor for
 * advanced scripts), Run/Stop, the script's log and a one-line console. Runs scripts on the logged-in character
 * through the game backend.
 */
final class Playground extends JFrame {
    private static final String NEW_SCRIPT =
        "// A new script. It keeps running until you press Stop.\n" +
        "// setInterval runs your code once per game tick (0.6 seconds).\n" +
        "set_message(\"Starting\");\n\n" +
        "setInterval(() => {\n" +
        "  if (is_busy()) return;   // wait for the last action to finish\n" +
        "  \n" +
        "}, 600);\n";

    private final Backend backend;
    private final DefaultListModel<String> files = new DefaultListModel<>();
    private final JList<String> fileList = new JList<>(files);
    private final RSyntaxTextArea editor = new RSyntaxTextArea(30, 80);
    private final BlockCanvas canvas = new BlockCanvas();
    private final CardLayout cards = new CardLayout();
    private final JPanel editorArea = new JPanel(cards);
    private TargetPicker picker;
    private JComponent palPane;
    private JSplitPane leftSplit;
    private boolean blockFile = true;       // the open script is saved as a block script (.blocks), not JavaScript (.js)
    private boolean basicView = true;       // showing blocks (Basic) rather than code (Advanced); any script shows either way
    // what the other view held when we switched, so switching back without changes gives the script back exactly
    private String switchedCode, switchedBlocks;
    private final JToggleButton basicB = viewButton("Basic"), advancedB = viewButton("Advanced");
    private final JButton cheats = Theme.button("Cheats", new Color(0x3a5ba0));
    private final JButton tools = Theme.button("Tools", null);
    private CheatsWindow cheatsWindow;
    private final JTextPane log = new JTextPane();
    private final JTextField console = new JTextField();
    private final JLabel connection = new JLabel();
    private final JLabel message = new JLabel(" ");
    private final JLabel character = new JLabel(" ");
    private final JLabel fileLabel = new JLabel();
    private final JButton run = Theme.button("▶  Run", new Color(0x2e7d32));
    private final JButton stop = Theme.button("■  Stop", new Color(0x8e2c2c));
    private String current;           // file name in scripts/, null for an unsaved script
    private boolean dirty, loading;
    private volatile String player = "";
    private volatile boolean gameRunning = true, scriptRunning;
    private boolean placed;
    private String attachedTo = "";

    Playground(Backend backend, Backend.State state) {
        super("Script Playground");
        this.backend = backend;
        setIconImages(Theme.icons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (confirmClose()) setVisible(false); }
        });
        add(toolbar(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);
        bindKeys();
        firstExample();
        try { basicView = java.util.prefs.Preferences.userNodeForPackage(Playground.class).getBoolean("basicView", true); } catch (Exception ignored) {}
        setMode(basicView);
        loadFileList();
        if (files.isEmpty()) newBlocks(); else fileList.setSelectedIndex(0);
        gameState(state);
        place(state.window);

        Thread poll = new Thread(this::pollLoop, "playground-poll");
        poll.setDaemon(true);
        poll.start();
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { loadFileList(); }
        });
    }

    // ---------------------------------------------------------------- layout
    private JComponent toolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new CompoundBorderFix(new MatteBorder(0, 0, 1, 0, new Color(0x393b40)), new EmptyBorder(6, 8, 6, 10)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton newB = Theme.button("New", null), save = Theme.button("Save", null), saveAs = Theme.button("Save As", null),
            folder = Theme.button("Open Folder", null);
        JPopupMenu newMenu = new JPopupMenu();
        JMenuItem nb = new JMenuItem("Block script"), nc = new JMenuItem("JavaScript (advanced)");
        nb.addActionListener(e -> { if (confirmDiscard()) newBlocks(); });
        nc.addActionListener(e -> { if (confirmDiscard()) newScript(); });
        newMenu.add(nb);
        newMenu.add(nc);
        newB.addActionListener(e -> newMenu.show(newB, 0, newB.getHeight()));
        save.addActionListener(e -> save(false));
        saveAs.addActionListener(e -> save(true));
        folder.addActionListener(e -> { try { Desktop.getDesktop().open(Game.SCRIPTS.toFile()); } catch (Exception ignored) {} });
        run.addActionListener(e -> runScript());
        stop.addActionListener(e -> stopScript());
        run.setToolTipText("Run on your character (Ctrl+Enter)");
        stop.setToolTipText("Stop the running script (Ctrl+.)");
        newB.setToolTipText("Start a new block script (Ctrl+N) or a JavaScript one");
        save.setToolTipText("Save (Ctrl+S)");
        for (JButton b : new JButton[]{newB, save, saveAs, folder}) left.add(b);
        left.add(Box.createHorizontalStrut(10));
        left.add(run);
        left.add(stop);
        fileLabel.setForeground(Theme.MUTED);
        fileLabel.setBorder(new EmptyBorder(0, 14, 0, 8));
        fileLabel.setMinimumSize(new Dimension(0, 0));
        bar.add(left, BorderLayout.WEST);
        bar.add(fileLabel, BorderLayout.CENTER);     // shortens with "..." when the window is narrow

        // right: Cheats, then the Basic / Advanced switch
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        cheats.setToolTipText("The game's cheat codes and what they do");
        cheats.addActionListener(e -> showCheats());
        ButtonGroup g = new ButtonGroup();
        g.add(basicB);
        g.add(advancedB);
        basicB.setToolTipText("Show the script as blocks");
        advancedB.setToolTipText("Show the script as JavaScript");
        basicB.addActionListener(e -> switchView(true));
        advancedB.addActionListener(e -> switchView(false));
        JPanel seg = new JPanel(new GridLayout(1, 2, 0, 0));
        seg.add(basicB);
        seg.add(advancedB);
        tools.setToolTipText("Copy, cut and paste blocks, insert another script, or grab map coordinates from the game");
        tools.addActionListener(e -> toolsMenu().show(tools, 0, tools.getHeight()));
        right.add(cheats);
        right.add(tools);
        right.add(Box.createHorizontalStrut(4));
        right.add(seg);
        bar.add(right, BorderLayout.EAST);
        // compact buttons, so the whole bar fits beside the game in a narrow window
        for (AbstractButton b : new AbstractButton[]{newB, save, saveAs, folder, run, stop, cheats, tools, basicB, advancedB}) {
            b.putClientProperty("JComponent.minimumWidth", 0);
            b.setMargin(new Insets(2, 9, 2, 9));
        }
        return bar;
    }

    private static JToggleButton viewButton(String text) {
        JToggleButton b = new JToggleButton(text);
        b.setFocusable(false);
        return b;
    }

    // ---------------------------------------------------------------- Tools: copy, paste, insert a script
    /** The Tools menu, also the right-click menu on the blocks. In Advanced it works on the code instead. */
    private JPopupMenu toolsMenu() {
        JPopupMenu m = new JPopupMenu();
        boolean blocks = basicView;
        boolean any = blocks ? canvas.hasSelection() : editor.getSelectionEnd() > editor.getSelectionStart();
        m.add(item("Cut", "Ctrl+X", any, () -> { if (blocks) canvas.cutSelection(); else editor.cut(); }));
        m.add(item("Copy", "Ctrl+C", any, () -> { if (blocks) canvas.copySelection(); else editor.copy(); }));
        m.add(item("Paste", "Ctrl+V", blocks ? canvas.canPaste() : true, () -> { if (blocks) canvas.paste(); else editor.paste(); }));
        if (blocks) m.add(item("Duplicate", "Ctrl+D", any, canvas::duplicateSelected));
        m.add(item("Delete", "Delete", any, () -> { if (blocks) canvas.deleteSelected(); else editor.replaceSelection(""); }));
        m.addSeparator();
        m.add(item("Select All", "Ctrl+A", true, () -> { if (blocks) canvas.selectAll(); else { editor.requestFocusInWindow(); editor.selectAll(); } }));
        m.addSeparator();
        JMenu insert = new JMenu("Insert Script");
        for (String name : java.util.Collections.list(files.elements())) {
            if (name.equals(current)) continue;
            JMenuItem it = new JMenuItem(name);
            it.addActionListener(e -> insertScript(name));
            insert.add(it);
        }
        if (insert.getItemCount() == 0) { JMenuItem none = new JMenuItem("(no other scripts)"); none.setEnabled(false); insert.add(none); }
        m.add(insert);
        m.addSeparator();
        m.add(grabbing ? item("Stop Grabbing Coordinates", "Ctrl+G", true, () -> grab(null))
                       : item("Grab Coordinates", "Ctrl+G", true, () -> grab(v -> {})));
        return m;
    }

    private static JMenuItem item(String text, String keys, boolean enabled, Runnable r) {
        JMenuItem it = new JMenuItem(text);
        it.setAccelerator(KeyStroke.getKeyStroke(keys.replace("Ctrl+", "ctrl ").replace("Delete", "DELETE").toUpperCase().replace("CTRL ", "ctrl ")));
        it.setEnabled(enabled);
        it.addActionListener(e -> r.run());
        return it;
    }

    /** Puts a whole script from the list into the open one: after the selected blocks, or at the cursor in Advanced. */
    private void insertScript(String name) {
        String text;
        try { text = new String(Files.readAllBytes(Game.SCRIPTS.resolve(name)), StandardCharsets.UTF_8); }
        catch (IOException e) { append("Could not read " + name + ": " + e.getMessage(), Theme.RED); return; }
        java.util.List<BlockModel.Block> blocks = BlockCanvas.textToBlocks(text);
        if (blocks.isEmpty()) { append(name + " is empty.", Theme.AMBER); return; }
        if (basicView) {
            canvas.insertBlocks(blocks);
        } else {
            String code = BlockCanvas.innerCode(blocks);
            int at = editor.getCaretPosition();
            editor.insert(code, at);
            editor.setCaretPosition(at + code.length());
            editor.requestFocusInWindow();
        }
        append("Inserted " + name + " (" + count(blocks) + (count(blocks) == 1 ? " block)." : " blocks)."), Theme.MUTED);
    }

    private static int count(java.util.List<BlockModel.Block> l) {
        int n = 0;
        for (BlockModel.Block b : l) n += 1 + count(b.body) + count(b.elseBody);
        return n;
    }

    // ---------------------------------------------------------------- coordinate grabber
    private volatile boolean grabbing;
    private long grabSince;
    private String seenGrab;
    private java.util.function.Consumer<String> afterGrab;

    /**
     * Turns the coordinate grabber on (then: what to do with the tile, besides copying it) or off (null). While it is on,
     * the game shows the map coordinates under the mouse, and a left click copies them.
     */
    private void grab(java.util.function.Consumer<String> then) {
        if (then != null && !gameRunning) { append("Start the game first, then grab coordinates in its window.", Theme.AMBER); return; }
        boolean on = then != null;
        afterGrab = then;
        grabbing = on;
        grabSince = System.currentTimeMillis();
        new Thread(() -> backend.send("grab " + (on ? "on" : "off")), "playground-grab").start();
        if (on) append("Coordinate grabber on: click a tile in the game window to copy its coordinates (right-click or Esc cancels).", Theme.MUTED);
        else append("Coordinate grabber off.", Theme.MUTED);
    }

    /** A tile was clicked in the game: the game has copied it; pass it on and say so. */
    private void grabbed(Backend.State s) {
        if (seenGrab == null) { seenGrab = s.grab; return; }   // the first report only sets the starting point
        if (!s.grab.equals(seenGrab)) {
            seenGrab = s.grab;
            String[] c = s.grab.split(" ");
            if (c.length == 4) {
                String xy = TargetPicker.coordText(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]));
                try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(xy), null); } catch (Exception ignored) {}
                java.util.function.Consumer<String> then = afterGrab;
                afterGrab = null;
                if (then != null) then.accept(xy);
                append("Copied " + xy + ". Paste it into a Walk To (Ctrl+V on the blocks makes a Walk To).", Theme.GREEN);
            }
        }
        if (grabbing && !s.grabbing && s.backendUp && System.currentTimeMillis() - grabSince > 2000) { grabbing = false; afterGrab = null; }   // cancelled in the game
    }

    private void showCheats() {
        if (cheatsWindow == null) cheatsWindow = new CheatsWindow(this, backend);
        cheatsWindow.open();
    }

    private JComponent body() {
        // script list
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setBackground(Theme.BG);
        fileList.setFixedCellHeight(24);
        fileList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || loading) return;
            String sel = fileList.getSelectedValue();
            if (sel == null || sel.equals(current)) return;
            if (!confirmDiscard()) { loading = true; fileList.setSelectedValue(current, true); loading = false; return; }
            open(sel);
        });
        JScrollPane listPane = new JScrollPane(fileList);
        listPane.setBorder(BorderFactory.createEmptyBorder());
        JLabel listTitle = new JLabel("SCRIPTS");
        listTitle.setFont(listTitle.getFont().deriveFont(Font.BOLD, 11f));
        listTitle.setForeground(Theme.MUTED);
        listTitle.setBorder(new EmptyBorder(8, 10, 6, 10));
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.BG);
        side.add(listTitle, BorderLayout.NORTH);
        side.add(listPane, BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(190, 0));

        // editor, dark theme with JavaScript colours
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
        try {
            org.fife.ui.rsyntaxtextarea.Theme.load(getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")).apply(editor);
        } catch (IOException ignored) {}
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        editor.setTabSize(2);
        editor.setTabsEmulated(true);
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setBackground(new Color(0x1e1f22));
        editor.setCurrentLineHighlightColor(new Color(0x26282e));
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markDirty(); }
            public void removeUpdate(DocumentEvent e) { markDirty(); }
            public void changedUpdate(DocumentEvent e) {}
        });
        RTextScrollPane edPane = new RTextScrollPane(editor);
        edPane.setBorder(BorderFactory.createEmptyBorder());
        edPane.getGutter().setBackground(new Color(0x1e1f22));
        edPane.getGutter().setLineNumberColor(new Color(0x5a5d63));
        edPane.getGutter().setBorderColor(new Color(0x2b2d30));

        // log and console
        log.setEditable(false);
        log.setBackground(new Color(0x18191b));
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane logPane = new JScrollPane(log);
        logPane.setBorder(BorderFactory.createEmptyBorder());
        JLabel logTitle = new JLabel("LOG");
        logTitle.setFont(logTitle.getFont().deriveFont(Font.BOLD, 11f));
        logTitle.setForeground(Theme.MUTED);
        JButton clear = Theme.button("Clear", null);
        clear.putClientProperty("JButton.buttonType", "borderless");
        clear.addActionListener(e -> log.setText(""));
        JPanel logHead = new JPanel(new BorderLayout());
        logHead.setBorder(new EmptyBorder(4, 10, 2, 6));
        logHead.add(logTitle, BorderLayout.WEST);
        logHead.add(clear, BorderLayout.EAST);
        console.putClientProperty("JTextField.placeholderText", "Try a line of code, e.g. character.hp or get_nearest_monster({name: \"Cow\"})  (Enter)");
        console.putClientProperty("JTextField.leadingIcon", new PromptIcon());
        console.addActionListener(e -> evalLine());
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.setBorder(new MatteBorder(1, 0, 0, 0, new Color(0x393b40)));
        logBox.add(logHead, BorderLayout.NORTH);
        logBox.add(logPane, BorderLayout.CENTER);
        JPanel consoleBox = new JPanel(new BorderLayout());
        consoleBox.setBorder(new EmptyBorder(4, 6, 6, 6));
        consoleBox.add(console);
        logBox.add(consoleBox, BorderLayout.SOUTH);

        editorArea.add(blockEditor(), "blocks");
        editorArea.add(edPane, "code");
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorArea, logBox);
        right.setResizeWeight(0.75);
        right.setBorder(BorderFactory.createEmptyBorder());
        right.setDividerSize(5);
        // left column: the script list, and under it the block palette (block scripts only)
        side.setPreferredSize(new Dimension(236, 150));
        side.setMinimumSize(new Dimension(140, 60));
        leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, side, palPane);
        leftSplit.setBorder(BorderFactory.createEmptyBorder());
        leftSplit.setDividerSize(5);
        leftSplit.setDividerLocation(150);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, right);
        main.setBorder(BorderFactory.createEmptyBorder());
        main.setDividerSize(5);
        main.setDividerLocation(236);
        return main;
    }

    /** Palette on the left, the canvas in the middle, the target picker on the right. */
    private JComponent blockEditor() {
        BlockPalette palette = new BlockPalette(canvas);
        JScrollPane palScroll = new JScrollPane(palette, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        palScroll.setBorder(BorderFactory.createEmptyBorder());
        palScroll.getVerticalScrollBar().setUnitIncrement(24);
        palette.onFilter(() -> palScroll.getVerticalScrollBar().setValue(0));
        JPanel palBox = new JPanel(new BorderLayout());
        palBox.setBorder(new MatteBorder(1, 0, 0, 0, new Color(0x393b40)));
        palBox.add(palette.header(), BorderLayout.NORTH);
        palBox.add(palScroll, BorderLayout.CENTER);
        palPane = palBox;
        JScrollPane canvasPane = new JScrollPane(canvas);
        canvasPane.setBorder(BorderFactory.createEmptyBorder());
        canvasPane.getVerticalScrollBar().setUnitIncrement(24);
        canvasPane.getHorizontalScrollBar().setUnitIncrement(24);
        picker = new TargetPicker(canvas, () -> ask("nearby"), () -> ask("status"));
        picker.setBorder(new CompoundBorderFix(new MatteBorder(0, 1, 0, 0, new Color(0x393b40)), new EmptyBorder(10, 12, 10, 12)));
        canvas.onSelect(picker::show);
        canvas.onChange(this::markDirty);
        canvas.menu(this::toolsMenu);
        picker.grabber(this::grab);
        // the block panel on the right can be dragged wider or narrower, like the script list and the log
        picker.setMinimumSize(new Dimension(200, 0));
        canvasPane.setMinimumSize(new Dimension(200, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvasPane, picker);
        split.setResizeWeight(1.0);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(5);
        split.setContinuousLayout(true);
        return split;
    }

    /** A question about the character ("nearby", "status"), attaching the Playground to the character first if needed. */
    private String ask(String cmd) {
        String p = player;
        if (p.isEmpty()) return null;
        String r = backend.send(cmd + " " + p);
        if (r != null && r.contains("no session")) {
            backend.send("attach " + p + " " + p);
            r = backend.send(cmd + " " + p);
        }
        return r;
    }

    private JComponent statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(0x2b2d30));
        bar.setBorder(new EmptyBorder(4, 10, 4, 10));
        message.setForeground(Theme.TEXT);
        character.setForeground(Theme.MUTED);
        connection.setBorder(new EmptyBorder(0, 0, 0, 16));
        bar.add(connection, BorderLayout.WEST);
        bar.add(message, BorderLayout.CENTER);
        bar.add(character, BorderLayout.EAST);
        return bar;
    }

    private void bindKeys() {
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, ctrl), "run", this::runScript);
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, ctrl), "stop", this::stopScript);
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), "save", () -> save(false));
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_N, ctrl), "new", () -> { if (confirmDiscard()) newBlocks(); });
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_G, ctrl), "grab", () -> grab(grabbing ? null : v -> {}));
    }

    private static void bind(InputMap im, ActionMap am, KeyStroke k, String name, Runnable r) {
        im.put(k, name);
        am.put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    /** Opens beside the game window so it never covers the game. */
    private void place(int[] game) {
        if (placed) return;
        placed = true;
        Rectangle g = new Rectangle(game[0], game[1], game[2], game[3]);
        int minW = 640, prefW = 1060, h = 760;
        GraphicsConfiguration home = null;
        Rectangle homeArea = null;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            if (g.width > 0 && gc.getBounds().intersects(g)) { home = gc; homeArea = usable(gc); }
        }
        if (home == null) { setSize(prefW, h); setLocationRelativeTo(null); return; }
        int roomRight = homeArea.x + homeArea.width - (g.x + g.width);
        int roomLeft = g.x - homeArea.x;
        int height = Math.min(Math.max(g.height, 600), homeArea.height);
        int y = Math.max(homeArea.y, Math.min(g.y, homeArea.y + homeArea.height - height));
        if (roomRight >= minW || roomLeft >= minW) {
            boolean right = roomRight >= roomLeft;
            int w = Math.min(prefW, right ? roomRight : roomLeft);
            setBounds(right ? g.x + g.width : g.x - w, y, w, height);
            return;
        }
        // not enough room beside the game on its screen: use another screen if there is one
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            if (gc == home) continue;
            Rectangle a = usable(gc);
            setBounds(a.x + (a.width - Math.min(prefW, a.width)) / 2, a.y + (a.height - Math.min(h, a.height)) / 2,
                Math.min(prefW, a.width), Math.min(h, a.height));
            return;
        }
        // one screen without room: put the game and the Playground side by side (game left, Playground right)
        int w = Math.max(minW, Math.min(prefW, homeArea.width * 2 / 5));
        setBounds(homeArea.x + homeArea.width - w, homeArea.y, w, homeArea.height);
        int[] to = {homeArea.x, homeArea.y, homeArea.width - w, homeArea.height};
        new Thread(() -> backend.send("layout " + to[0] + " " + to[1] + " " + to[2] + " " + to[3]), "playground-layout").start();
    }

    private static Rectangle usable(GraphicsConfiguration gc) {
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
    }

    // ---------------------------------------------------------------- files
    private void loadFileList() {
        try {
            Files.createDirectories(Game.SCRIPTS);
            java.util.List<String> names;
            try (Stream<Path> s = Files.list(Game.SCRIPTS)) {
                names = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".js") || n.endsWith(".blocks")).sorted().collect(Collectors.toList());
            }
            if (names.equals(java.util.Collections.list(files.elements()))) return;
            loading = true;
            files.clear();
            names.forEach(files::addElement);
            if (current != null) fileList.setSelectedValue(current, true);
            loading = false;
        } catch (IOException e) { append("Could not read the scripts folder: " + e.getMessage(), Theme.RED); }
    }

    private void open(String name) {
        try {
            String text = new String(Files.readAllBytes(Game.SCRIPTS.resolve(name)), StandardCharsets.UTF_8);
            boolean blocksFile = name.endsWith(".blocks");
            java.util.List<BlockModel.Block> blocks = null;
            if (blocksFile) {
                try { blocks = BlockModel.fromJson(text); }
                catch (RuntimeException ex) { append("Could not read " + name + ": " + ex.getMessage(), Theme.RED); return; }
            }
            blockFile = blocksFile;
            switchedCode = switchedBlocks = null;
            setMode(basicView);
            if (basicView) {
                if (blocks == null) {
                    blocks = JsToBlocks.parse(text);
                    switchedCode = text;
                    switchedBlocks = BlockModel.toJson(blocks);
                }
                loading = true; canvas.setScript(blocks); loading = false;
            } else {
                if (blocks != null) {
                    String code = BlockModel.compile(blocks);
                    switchedBlocks = text.isEmpty() ? null : BlockModel.toJson(blocks);
                    switchedCode = code;
                    text = code;
                }
                setEditorText(text);
            }
            current = name;
            dirty = false;
            updateTitle();
        } catch (IOException e) { append("Could not open " + name + ": " + e.getMessage(), Theme.RED); }
    }

    private void setMode(boolean blocks) {
        basicView = blocks;
        basicB.setSelected(blocks);
        advancedB.setSelected(!blocks);
        cards.show(editorArea, blocks ? "blocks" : "code");
        console.setVisible(!blocks);
        if (palPane != null) {
            palPane.setVisible(blocks);
            leftSplit.setDividerLocation(blocks ? 150 : leftSplit.getHeight());
        }
    }

    /** Basic (blocks) or Advanced (JavaScript): the same script shown the other way. */
    private void switchView(boolean basic) {
        if (basic == basicView) return;
        if (basic) {
            String code = editor.getText();
            java.util.List<BlockModel.Block> blocks = code.equals(switchedCode) && switchedBlocks != null
                ? BlockModel.fromJson(switchedBlocks) : JsToBlocks.parse(code);
            switchedCode = code;
            switchedBlocks = BlockModel.toJson(blocks);
            setMode(true);
            loading = true; canvas.setScript(blocks); loading = false;
        } else {
            String now = BlockModel.toJson(canvas.script());
            String code = now.equals(switchedBlocks) && switchedCode != null ? switchedCode : BlockModel.compile(canvas.script());
            switchedBlocks = now;
            switchedCode = code;
            setMode(false);
            boolean wasDirty = dirty;
            setEditorText(code);
            dirty = wasDirty;
        }
        try { java.util.prefs.Preferences.userNodeForPackage(Playground.class).putBoolean("basicView", basic); } catch (Exception ignored) {}
        updateTitle();
    }

    /** The open script as blocks, whichever view is showing. */
    private java.util.List<BlockModel.Block> scriptBlocks() {
        if (basicView) return canvas.script();
        String code = editor.getText();
        return code.equals(switchedCode) && switchedBlocks != null ? BlockModel.fromJson(switchedBlocks) : JsToBlocks.parse(code);
    }

    /** The open script as JavaScript, whichever view is showing. */
    private String scriptCode() {
        if (!basicView) return editor.getText();
        String now = BlockModel.toJson(canvas.script());
        return now.equals(switchedBlocks) && switchedCode != null ? switchedCode : BlockModel.compile(canvas.script());
    }

    private void newBlocks() {
        blockFile = true;
        switchedCode = switchedBlocks = null;
        setMode(true);
        loading = true; canvas.setScript(new java.util.ArrayList<>()); loading = false;
        current = null;
        dirty = false;
        loading = true; fileList.clearSelection(); loading = false;
        updateTitle();
    }

    /** The first time the Playground opens, put the example block script in the scripts folder. */
    private void firstExample() {
        try {
            Path f = Game.SCRIPTS.resolve("chop_oaks_and_bank.blocks");
            Path marker = Game.SCRIPTS.resolve(".example-added");
            if (Files.exists(marker)) return;
            Files.createDirectories(Game.SCRIPTS);
            if (!Files.exists(f)) Files.write(f, BlockModel.toJson(BlockModel.example()).getBytes(StandardCharsets.UTF_8));
            Files.write(marker, new byte[0]);
        } catch (IOException ignored) {}
    }

    private void newScript() {
        blockFile = false;
        switchedCode = switchedBlocks = null;
        setMode(false);
        setEditorText(NEW_SCRIPT);
        current = null;
        dirty = false;
        loading = true; fileList.clearSelection(); loading = false;
        updateTitle();
        editor.setCaretPosition(NEW_SCRIPT.indexOf("  \n") + 2);
    }

    private void setEditorText(String text) {
        loading = true;
        editor.setText(text);
        editor.setCaretPosition(0);
        editor.discardAllEdits();
        loading = false;
    }

    private boolean save(boolean as) {
        String name = current;
        if (as || name == null) {
            String ext = blockFile ? ".blocks" : ".js";
            name = JOptionPane.showInputDialog(this, "Script name:", name == null ? "my_script" + ext : name);
            if (name == null || name.isBlank()) return false;
            name = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            if (name.endsWith(".js") || name.endsWith(".blocks")) name = name.substring(0, name.lastIndexOf('.'));
            name += ext;
            if (!name.equals(current) && Files.exists(Game.SCRIPTS.resolve(name))
                && JOptionPane.showConfirmDialog(this, name + " already exists. Replace it?", "Save As", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                return false;
        }
        try {
            String text = blockFile ? BlockModel.toJson(scriptBlocks()) : scriptCode();
            Files.write(Game.SCRIPTS.resolve(name), text.getBytes(StandardCharsets.UTF_8));
            current = name;
            dirty = false;
            loadFileList();
            loading = true; fileList.setSelectedValue(current, true); loading = false;
            updateTitle();
            return true;
        } catch (IOException e) {
            append("Could not save: " + e.getMessage(), Theme.RED);
            return false;
        }
    }

    private void markDirty() {
        if (loading || dirty) return;
        dirty = true;
        updateTitle();
    }

    private void updateTitle() {
        String n = current == null ? (blockFile ? "untitled.blocks" : "untitled.js") : current;
        fileLabel.setText((dirty ? "● " : "") + n);
        setTitle("Script Playground - " + n + (dirty ? " (not saved)" : ""));
    }

    private boolean confirmDiscard() {
        if (!dirty) return true;
        int r = JOptionPane.showConfirmDialog(this, "Save changes to " + (current == null ? "this script" : current) + "?",
            "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.YES_OPTION) return save(false);
        return r == JOptionPane.NO_OPTION;
    }

    boolean confirmClose() { return confirmDiscard(); }

    // ---------------------------------------------------------------- game
    /** Called by the launcher twice a second with what the backend reports. */
    void gameState(Backend.State s) {
        gameRunning = s.gameRunning;
        player = s.gameRunning ? s.player : "";
        if (!s.gameRunning) {
            connection.setText("●  Game closed");
            connection.setForeground(Theme.RED);
            scriptRunning = false;
        } else if (s.player.isEmpty()) {
            connection.setText("●  Log in to run scripts");
            connection.setForeground(Theme.AMBER);
        } else {
            connection.setText("●  " + s.player);
            connection.setForeground(Theme.GREEN);
        }
        run.setEnabled(!player.isEmpty());
        stop.setEnabled(!player.isEmpty() && scriptRunning);
        console.setEnabled(!player.isEmpty());
        if (player.isEmpty()) character.setText(" ");
        grabbed(s);
    }

    private String attach() {
        String p = player;
        if (p.isEmpty()) return null;
        String r = backend.send("attach " + p + " " + p);
        if (!"ok".equals(r)) { append("Could not connect to your character: " + r, Theme.RED); return null; }
        return p;
    }

    private void runScript() {
        if (player.isEmpty()) { append("Log in to the game first.", Theme.AMBER); return; }
        if (basicView && canvas.script().isEmpty()) { append("Add some blocks first: drag them from the left onto the canvas.", Theme.AMBER); return; }
        String code = basicView ? BlockModel.compile(canvas.script()) : editor.getText();
        String name = current == null ? (blockFile ? "untitled.blocks" : "untitled.js") : current;
        canvas.clearRun();
        run.setEnabled(false);
        new Thread(() -> {
            String p = attach();
            if (p != null) {
                String r = backend.send("runb64 " + p + " " + name + " " + Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
                if (r == null) append("Lost the connection to the game.", Theme.RED);
                else if (!r.equals("ok")) append(r, Theme.RED);
            }
            SwingUtilities.invokeLater(() -> run.setEnabled(!player.isEmpty()));
        }, "playground-run").start();
    }

    private void stopScript() {
        String p = player;
        if (p.isEmpty()) return;
        new Thread(() -> backend.send("stop " + p), "playground-stop").start();
    }

    private void evalLine() {
        String code = console.getText().trim();
        if (code.isEmpty() || player.isEmpty()) return;
        console.setText("");
        append("> " + code, Theme.GOLD);
        new Thread(() -> {
            String p = attach();
            if (p == null) return;
            String r = backend.send("eval " + p + " " + code.replace("\n", "\\n"));
            append(r == null ? "Lost the connection to the game." : r, r != null && r.startsWith("error") ? Theme.RED : new Color(0x9cdcfe));
        }, "playground-eval").start();
    }

    private void pollLoop() {
        int n = 0;
        while (true) {
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            String p = player;
            if (p.isEmpty() || !isVisible()) continue;
            if (!p.equals(attachedTo)) { if (attach() == null) continue; attachedTo = p; }   // pick up a script that is already running
            String running = backend.send("running " + p);
            if (running == null || running.startsWith("ERR")) continue;   // no script attached yet
            scriptRunning = running.equals("true");
            String lines = backend.send("log " + p);
            if (lines != null && !lines.isEmpty())
                for (String l : lines.split("\n")) append(l, l.contains("error") ? Theme.RED : l.contains("stopped") ? Theme.AMBER : Theme.TEXT);
            String msg = backend.send("message " + p);
            String blk = basicView ? backend.send("block " + p) : null;
            String status = (n++ % 4 == 0) ? backend.send("status " + p) : null;
            SwingUtilities.invokeLater(() -> {
                stop.setEnabled(scriptRunning);
                message.setText((scriptRunning ? "Running" : "Stopped") + (msg != null && !msg.isEmpty() ? "  |  " + msg : ""));
                message.setForeground(scriptRunning ? Theme.GREEN : Theme.MUTED);
                if (status != null) character.setText(summary(status));
                if (blk != null && !blk.startsWith("ERR")) showBlock(blk);
            });
        }
    }

    /** "b4|running", "b6|failed|reason" or "|stopped" from the backend: light up the block on the canvas. */
    private void showBlock(String state) {
        String[] parts = state.split("\\|", 3);
        BlockModel.Block b = parts[0].isEmpty() ? null : BlockModel.byRunId(canvas.script(), parts[0]);
        if (parts.length > 1 && parts[1].equals("failed")) canvas.showFailed(b, parts.length > 2 ? parts[2] : "");
        else if (parts.length > 1 && parts[1].equals("running") && b != null) canvas.showRunning(b);
        else if (!scriptRunning) canvas.clearRun();
    }

    private static String summary(String json) {
        Matcher m = Pattern.compile("\"(hp|max_hp|x|y|z|combat_level)\":(-?\\d+)").matcher(json);
        java.util.Map<String, String> v = new java.util.HashMap<>();
        while (m.find()) v.put(m.group(1), m.group(2));
        if (!v.containsKey("x")) return " ";
        return "HP " + v.get("hp") + "/" + v.get("max_hp") + "   Combat " + v.get("combat_level")
            + "   " + v.get("x") + ", " + v.get("y") + (v.getOrDefault("z", "0").equals("0") ? "" : ", floor " + v.get("z"));
    }

    private void append(String line, Color color) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = log.getStyledDocument();
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, color);
            try {
                doc.insertString(doc.getLength(), line + "\n", a);
                if (doc.getLength() > 200_000) doc.remove(0, doc.getLength() - 150_000);
            } catch (BadLocationException ignored) {}
            log.setCaretPosition(doc.getLength());
        });
    }

    /** ">" in front of the console line. */
    private static final class PromptIcon implements Icon {
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(Theme.GOLD);
            g.setFont(c.getFont().deriveFont(Font.BOLD));
            g.drawString(">", x, y + 11);
        }
        public int getIconWidth() { return 10; }
        public int getIconHeight() { return 14; }
    }

    /** A compound border (javax.swing.border.CompoundBorder with a short name). */
    private static final class CompoundBorderFix extends javax.swing.border.CompoundBorder {
        CompoundBorderFix(javax.swing.border.Border outside, javax.swing.border.Border inside) { super(outside, inside); }
    }
}
