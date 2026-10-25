package codeedition;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Starts and stops the game: the game world (server.jar plus the script add-on) and the game window (client.jar).
 * server.jar, client.jar and the cache are used exactly as shipped; the add-on is only added to the classpath.
 */
final class Game {
    static final Path ROOT = findRoot();
    static final Path GAME = ROOT.resolve("game");
    static final Path SCRIPTS = ROOT.resolve("scripts");
    static final Path LOGS = ROOT.resolve("logs");
    static final Path BACKUPS = ROOT.resolve("backups");
    private static final int GAME_PORT = 43595;

    private Process server, client;
    private PrintWriter serverInput;

    /** The folder that holds game/, jre/ and codeedition/ (the jar lives in codeedition/). */
    private static Path findRoot() {
        String forced = System.getProperty("codeedition.root");
        if (forced != null) return Paths.get(forced).toAbsolutePath();
        try {
            Path jar = Paths.get(Game.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            if (dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("codeedition")) dir = dir.getParent();
            return dir.toAbsolutePath();
        } catch (Exception e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    static String java(boolean console) {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        Path javaw = bin.resolve("javaw.exe"), javaExe = bin.resolve("java.exe");
        if (!console && Files.exists(javaw)) return javaw.toString();
        if (Files.exists(javaExe)) return javaExe.toString();
        return bin.resolve("java").toString();
    }

    boolean clientAlive() { return client != null && client.isAlive(); }
    boolean serverAlive() { return server != null && server.isAlive(); }

    static boolean portOpen(int port) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 300); return s.getLocalPort() != port; }
        catch (IOException e) { return false; }
    }

    /** Starts the game world if needed, then the game window. Runs on a background thread; progress goes to status. */
    void launch(Backend backend, Consumer<String> status) throws Exception {
        Files.createDirectories(LOGS);
        Files.createDirectories(SCRIPTS);
        boolean worldUp = portOpen(GAME_PORT);
        if (worldUp && backend.send("ping") == null)
            throw new IllegalStateException("Another copy of the game world is already running without Code Edition. Close it first.");
        if (!worldUp) {
            status.accept("Backing up your characters...");
            backupPlayers();
            status.accept("Starting the game world...");
            // overrides.jar first: Code Edition's replacements for single game classes (server.jar is never edited)
            String cp = String.join(File.pathSeparator,
                ROOT.resolve("codeedition").resolve("overrides.jar").toString(), "server.jar",
                ROOT.resolve("codeedition").resolve("codebridge.jar").toString(),
                ROOT.resolve("codeedition").resolve("lib").toString() + File.separator + "*");
            ProcessBuilder pb = new ProcessBuilder(java(false), "-Dsun.net.useExclusiveBind=false", "-Xmx2G", "-Xms2G",
                "-Dcodebridge.scripts=" + SCRIPTS, "-cp", cp, "core.Server");
            pb.directory(GAME.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(LOGS.resolve("game-world.log").toFile()));
            pb.environment().remove("_JAVA_OPTIONS");
            server = pb.start();
            serverInput = new PrintWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8), true);
            long start = System.currentTimeMillis();
            int dots = 0;
            while (!(portOpen(GAME_PORT) && backend.send("ping") != null)) {
                if (!server.isAlive()) throw new IllegalStateException("The game world stopped while starting. See logs/game-world.log.");
                if (System.currentTimeMillis() - start > 180_000) throw new IllegalStateException("The game world took too long to start. See logs/game-world.log.");
                status.accept("Starting the game world" + ".".repeat(1 + dots++ % 3));
                Thread.sleep(1000);
            }
        }
        status.accept("Opening the game...");
        ProcessBuilder pb = new ProcessBuilder(java(false), "-Xmx1G", "-Xms1G", "-Dsun.java2d.opengl=true", "-jar", "client.jar");
        pb.directory(GAME.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(LOGS.resolve("game-window.log").toFile()));
        pb.environment().remove("_JAVA_OPTIONS");
        client = pb.start();
    }

    /** When the game window closes, the game world saves and stops (only if this launcher started it). */
    void stopWorld(Consumer<String> status) {
        if (!serverAlive()) return;
        status.accept("Saving and closing the game world...");
        serverInput.println("stop");          // the server saves every character on the way out
        try {
            if (!server.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly();
        } catch (InterruptedException ignored) {}
        server = null;
    }

    /** Copies game/data/players to backups/players-<date>.zip and keeps the newest 20 backups. */
    static void backupPlayers() throws IOException {
        Path players = GAME.resolve("data").resolve("players");
        if (!Files.isDirectory(players)) return;
        List<Path> saves;
        try (Stream<Path> s = Files.list(players)) { saves = s.filter(Files::isRegularFile).collect(Collectors.toList()); }
        if (saves.isEmpty()) return;
        Files.createDirectories(BACKUPS);
        Path zip = BACKUPS.resolve("players-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path p : saves) {
                z.putNextEntry(new ZipEntry("players/" + p.getFileName()));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
        List<Path> old;
        try (Stream<Path> s = Files.list(BACKUPS)) {
            old = s.filter(p -> p.getFileName().toString().startsWith("players-")).sorted(Comparator.reverseOrder()).skip(20).collect(Collectors.toList());
        }
        for (Path p : old) Files.deleteIfExists(p);
    }
}
