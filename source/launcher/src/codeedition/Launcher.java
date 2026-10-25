package codeedition;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * First screen: Launch Game and Launch Script Playground.
 * The game runs on its own and never needs the Playground. The Playground only opens while the game is running.
 * Both talk to the same backend (the script add-on in the game world), which also passes Code orb clicks along.
 */
public final class Launcher extends JFrame {
    private final Backend backend = new Backend();
    private final Game game = new Game();
    private final JButton launchGame = Theme.button("Launch Game", new Color(0x2e7d32));
    private final JButton launchPlayground = Theme.button("Launch Script Playground", new Color(0x3a5ba0));
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel playgroundHint = new JLabel(" ", SwingConstants.CENTER);
    private Playground playground;
    private volatile boolean starting;
    private volatile Backend.State last = new Backend.State();
    private int seenOrbClicks = -1;

    /** Port the running launcher listens on (127.0.0.1 only) so a second copy can hand over to it and close. */
    private static final int SINGLE_PORT = Integer.getInteger("codeedition.single", 43611);
    private static java.net.ServerSocket single;
    private static volatile Launcher instance;

    public static void main(String[] args) {
        if (!firstCopy()) System.exit(0);
        Theme.install();
        SwingUtilities.invokeLater(() -> { instance = new Launcher(); instance.setVisible(true); });
    }

    /**
     * Only one launcher (and so one Playground) runs at a time. If one is already running, this asks it to come to the
     * front and returns false.
     */
    private static boolean firstCopy() {
        try {
            single = new java.net.ServerSocket();
            single.setReuseAddress(false);
            single.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), SINGLE_PORT));
        } catch (java.io.IOException taken) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), SINGLE_PORT), 1000);
                s.getOutputStream().write("show\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
            } catch (java.io.IOException ignored) {}
            return false;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try (java.net.Socket s = single.accept()) {
                    s.setSoTimeout(2000);
                    String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)).readLine();
                    if ("show".equals(line)) SwingUtilities.invokeLater(() -> { if (instance != null) instance.showAgain(); });
                } catch (java.io.IOException ignored) {}
            }
        }, "launcher-single");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Someone started the launcher again: show this one (and the Playground, if it is open) instead. */
    private void showAgain() {
        Theme.bringToFront(this);
        if (playground != null && playground.isVisible()) Theme.bringToFront(playground);
    }

    private Launcher() {
        super("2009 OSRS Code Edition");
        setIconImages(Theme.icons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });

        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(new EmptyBorder(26, 40, 22, 40));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

        JLabel logo = new JLabel(new ImageIcon(Theme.orb(72)));
        JLabel title = new JLabel("2009 OSRS", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));
        title.setForeground(Theme.GOLD);
        JLabel sub = new JLabel("Code Edition", SwingConstants.CENTER);
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 16f));
        sub.setForeground(Theme.MUTED);

        for (JButton b : new JButton[]{launchGame, launchPlayground}) {
            b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
            b.setPreferredSize(new Dimension(300, 46));
        }
        launchGame.addActionListener(e -> launchGame());
        launchPlayground.addActionListener(e -> openPlayground());
        status.setForeground(Theme.TEXT);
        playgroundHint.setForeground(Theme.MUTED);
        playgroundHint.setFont(playgroundHint.getFont().deriveFont(12f));

        c.insets = new Insets(0, 0, 6, 0); root.add(logo, c);
        c.insets = new Insets(0, 0, 0, 0); root.add(title, c);
        c.insets = new Insets(0, 0, 24, 0); root.add(sub, c);
        c.insets = new Insets(0, 0, 12, 0); root.add(launchGame, c);
        c.insets = new Insets(0, 0, 4, 0); root.add(launchPlayground, c);
        c.insets = new Insets(0, 0, 18, 0); root.add(playgroundHint, c);
        c.insets = new Insets(0, 0, 0, 0); root.add(status, c);
        setContentPane(root);
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        refresh();

        Thread poll = new Thread(this::pollLoop, "launcher-poll");
        poll.setDaemon(true);
        poll.start();
    }

    private void launchGame() {
        if (starting || game.clientAlive()) return;
        starting = true;
        refresh();
        new Thread(() -> {
            try {
                game.launch(backend, this::setStatus);
                setStatus("Game is starting. Log in to begin.");
                // wait for the game window to close, then save and stop the game world
                while (game.clientAlive()) Thread.sleep(1000);
                setStatus(" ");
                game.stopWorld(this::setStatus);
                setStatus("Game closed.");
            } catch (Exception ex) {
                setStatus(ex.getMessage());
            } finally {
                starting = false;
                SwingUtilities.invokeLater(this::refresh);
            }
        }, "launcher-game").start();
        // starting stays true until the game window is up; pollLoop clears the status once it reports in
    }

    /** Opens the Playground, or brings it to the front if it is already open. Only while the game is running. */
    private void openPlayground() {
        if (!last.gameRunning) {
            JOptionPane.showMessageDialog(this, "Start the game first. The Script Playground needs the game running.",
                "Game not running", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean first = playground == null;
        if (first) {
            playground = new Playground(new Backend(), last);
            playground.setVisible(true);
        }
        Theme.bringToFront(playground);
        if (first) {   // the game window may still be moving aside; make sure the Playground ends up active
            Timer again = new Timer(700, e -> Theme.bringToFront(playground));
            again.setRepeats(false);
            again.start();
        }
    }

    private void pollLoop() {
        while (true) {
            Backend.State s = backend.state();
            last = s;
            SwingUtilities.invokeLater(() -> {
                if (seenOrbClicks >= 0 && s.orbClicks > seenOrbClicks && s.gameRunning) openPlayground();
                if (s.backendUp) seenOrbClicks = s.orbClicks;
                if (playground != null) playground.gameState(s);
                if (s.gameRunning && status.getText().startsWith("Game is starting")) status.setText("Game running.");
                refresh();
            });
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
    }

    private void refresh() {
        boolean running = last.gameRunning || game.clientAlive();
        launchGame.setEnabled(!starting && !running);
        launchGame.setText(running ? "Game running" : "Launch Game");
        launchPlayground.setEnabled(last.gameRunning);
        playgroundHint.setText(last.gameRunning
            ? (last.player.isEmpty() ? "Log in to the game to run scripts." : "Logged in as " + last.player + ".")
            : "Available once the game is running.");
    }

    private void setStatus(String text) { SwingUtilities.invokeLater(() -> status.setText(text == null ? " " : text)); }

    private void onClose() {
        if (game.clientAlive() || game.serverAlive()) {
            // the launcher looks after the game world, so it stays in the taskbar while the game is open
            setExtendedState(ICONIFIED);
            setStatus("The launcher stays open while the game is running.");
            return;
        }
        if (playground != null && !playground.confirmClose()) return;
        System.exit(0);
    }
}
