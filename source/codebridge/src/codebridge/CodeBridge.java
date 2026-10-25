package codebridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shared state for the script runner: running sessions, game-thread work queue and the local control port. */
public final class CodeBridge {
    public static final int CONTROL_PORT = Integer.getInteger("codebridge.port", 43610);
    public static final Map<String, ScriptSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    public static volatile Path scriptsDir = Paths.get(System.getProperty("codebridge.scripts", "../scripts"));

    /** Set by the game client's heartbeat; the Script Playground needs a running game. */
    public static volatile long lastClientBeat;
    public static volatile int[] gameWindow = {0, 0, 0, 0};
    /** Where the Playground asked the game window to move, picked up by the next heartbeat. */
    public static volatile int[] moveGame;
    /** Counts Code orb clicks, so the launcher can open or bring forward the Playground. */
    public static final java.util.concurrent.atomic.AtomicInteger playgroundRequests = new java.util.concurrent.atomic.AtomicInteger();
    /** Coordinate grabber: on while the Playground waits for a tile click in the game; the last tile picked, with a count. */
    public static volatile boolean grabbing;
    public static volatile String lastGrab = "0";

    private CodeBridge() {}

    static boolean clientRunning() { return System.currentTimeMillis() - lastClientBeat < 4000; }

    /** Username of the logged-in (non-bot) player, or "" when nobody is logged in. Game thread only. */
    static String humanPlayer() {
        for (core.game.node.entity.player.Player p : core.game.world.repository.Repository.getPlayers())
            if (p != null && !p.isArtificial() && p.isActive()) return p.getUsername();
        return "";
    }
    private static volatile String lastHuman = "";

    static String stateJson() {
        int[] w = gameWindow;
        return "{\"game\":" + clientRunning() + ",\"player\":" + Json.quote(lastHuman)
            + ",\"window\":[" + w[0] + "," + w[1] + "," + w[2] + "," + w[3] + "],\"orb\":" + playgroundRequests.get() + ",\"grabbing\":" + grabbing + ",\"grab\":" + Json.quote(lastGrab) + "}";
    }

    /** Runs work on the game thread at the next tick and waits for the answer. */
    public static <T> T onGameThread(Supplier<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        QUEUE.add(() -> { try { f.complete(work.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get(10, TimeUnit.SECONDS);
    }

    static void drainQueue() {
        Runnable r;
        while ((r = QUEUE.poll()) != null) r.run();
    }

    private static final Map<String, Integer> WINDOW_MODES = new HashMap<>();

    /**
     * When the game switches between fixed and resizable (the Code orb's auto-resize does this after login), a screen
     * that was open, such as the rules screen after Tutorial Island, stays open in the game world but stops showing,
     * leaving the character stuck. Closing it lets the game open it again in the new layout.
     */
    static void keepScreensVisible() {
        Set<String> online = new HashSet<>();
        for (core.game.node.entity.player.Player p : core.game.world.repository.Repository.getPlayers()) {
            if (p == null || p.isArtificial() || !p.isActive() || p.getSession() == null) continue;
            online.add(p.getUsername());
            boolean justLoggedIn = LOGGED_IN.add(p.getUsername());
            if (justLoggedIn) onLogin(p);
            NewPlayers.check(p, justLoggedIn);
            int mode = p.getSession().getClientInfo().getWindowMode();
            Integer before = WINDOW_MODES.put(p.getUsername(), mode);
            if (before == null || before == mode) continue;
            core.game.component.Component open = p.getInterfaceManager().getOpened();
            if (open == null || open.getId() == 378) continue;   // 378 is the welcome screen
            if (open.getId() == 771) {   // character design can't normally be closed, so close and reopen it by hand
                p.setAttribute("char-design:accepted", true);
                p.getInterfaceManager().close();
                content.region.misc.tutisland.handlers.iface.CharacterDesign.reopen(p);
            } else p.getInterfaceManager().close();
        }
        LOGGED_IN.retainAll(online);
        NewPlayers.loggedOut(online);
        WINDOW_MODES.keySet().retainAll(online);
    }
    private static final Set<String> LOGGED_IN = new HashSet<>();

    /**
     * Once per login: left-click does the top option and right-click opens the menu. The game's "one mouse button"
     * option makes every left-click open the menu instead, so it is switched back to two buttons.
     */
    private static void onLogin(core.game.node.entity.player.Player p) {
        if (p.getSettings().isSingleMouseButton()) {
            p.getSettings().toggleMouseButton();
            p.sendMessage("Code Edition: left-click now does the top option. Right-click opens the menu.");
        }
    }

    static void tickSessions() {
        lastHuman = humanPlayer();
        for (ScriptSession s : SESSIONS.values()) s.tick();
    }

    static void startControlServer() {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(CONTROL_PORT, 50, InetAddress.getLoopbackAddress())) {
                System.out.println("[CodeBridge] control port listening on 127.0.0.1:" + CONTROL_PORT);
                while (true) {
                    Socket s = server.accept();
                    Thread h = new Thread(() -> handle(s), "codebridge-client");
                    h.setDaemon(true);
                    h.start();
                }
            } catch (IOException e) {
                System.out.println("[CodeBridge] control port failed: " + e);
            }
        }, "codebridge-control");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                String reply;
                try { reply = Control.handle(line); } catch (Throwable e) { reply = "ERR " + e; }
                out.println(reply.replace("\n", "\\n"));
            }
        } catch (IOException ignored) {}
    }
}
