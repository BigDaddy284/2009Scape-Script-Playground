package codebridge;

import core.game.node.entity.player.Player;
import org.graalvm.polyglot.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * One running JavaScript program attached to one character. Uses GraalJS (modern JavaScript: classes,
 * async/await, destructuring, spread). The engine is built off the game thread; the script itself only
 * ever runs on the game thread, once per tick.
 */
public final class ScriptSession {
    /** Shared engine so every script after the first starts quickly. */
    static final Engine ENGINE = Engine.newBuilder()
        .option("engine.WarnInterpreterOnly", "false")
        .build();
    /** Most JavaScript statements one tick may run before the script is stopped (catches endless loops). */
    static final long STATEMENTS_PER_TICK = Long.getLong("codebridge.statementsPerTick", 3_000_000L);

    final String name;
    final Player player;
    private Context context;
    private Value tickFn, stopFn, evalFn;
    private final Deque<String> log = new ArrayDeque<>();
    private volatile String message = "";
    private volatile boolean running;
    private int errors;
    String scriptName = "";
    /** Block scripts: the block running now, and the block and reason when a block stopped the script. */
    volatile String block = "", failedBlock = "", failReason = "";
    private volatile String stopRequest;

    ScriptSession(String name, Player player) { this.name = name; this.player = player; }

    synchronized void log(String line) {
        String stamped = "[" + core.api.ContentAPIKt.getWorldTicks() + "] " + line;
        log.addLast(stamped);
        while (log.size() > 500) log.removeFirst();
        System.out.println("[CodeBridge:" + name + "] " + line);
    }
    synchronized List<String> drainLog() { List<String> l = new ArrayList<>(log); log.clear(); return l; }
    void setMessage(String m) { message = m; }
    /** Asks for the script to stop once the current tick's code has finished (safe to call from inside the script). */
    void requestStop(String why) { stopRequest = why; }
    String message() { return message; }
    boolean running() { return running; }

    /** Builds a fresh engine with the helpers loaded. Slow-ish, so call it off the game thread. */
    static Context newContext(GameApi api) {
        ResourceLimits limits = ResourceLimits.newBuilder()
            .statementLimit(STATEMENTS_PER_TICK, null)
            .build();
        Context c = Context.newBuilder("js")
            .engine(ENGINE)
            .allowHostAccess(HostAccess.EXPLICIT)      // only GameApi methods marked @Export are visible
            .allowHostClassLookup(className -> false)  // scripts cannot reach Java classes
            .allowIO(false)                            // no files
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .resourceLimits(limits)
            .allowExperimentalOptions(true)               // needed for the unhandled-rejections option below
            .option("js.ecmascript-version", "2022")
            .option("js.unhandled-rejections", "handler")
            .build();
        c.getBindings("js").putMember("__api", api);
        c.eval(Source.newBuilder("js", resource("prelude.js"), "prelude.js").buildLiteral());
        c.eval(Source.newBuilder("js", resource("blocks.js"), "blocks.js").buildLiteral());
        c.resetLimits();
        return c;
    }

    /** Called off the game thread: prepares a new engine for the next start(). */
    Context prepare() { return newContext(new GameApi(player, this)); }

    /** Starts (or restarts) the session using a prepared engine. Must be called on the game thread. */
    String start(String scriptName, String code, Context prepared) {
        stop("restarted");
        closeContext();
        this.scriptName = scriptName;
        errors = 0;
        message = "";
        block = ""; failedBlock = ""; failReason = ""; stopRequest = null;
        context = prepared;
        Value g = context.getBindings("js");
        tickFn = g.getMember("__tick");
        stopFn = g.getMember("__stop");
        evalFn = g.getMember("__evalToString");
        try {
            g.getMember("__refresh").execute();
            running = true;
            context.eval(Source.newBuilder("js", code, scriptName).buildLiteral());
            context.resetLimits();
            log("started " + scriptName);
            return "ok";
        } catch (PolyglotException e) {
            running = false;
            String why = describe(e);
            log("error: " + why);
            return "error: " + why;
        }
    }

    /** Runs a snippet inside the running script's engine (or a fresh one) and returns its result as text. */
    String eval(String code, Context preparedIfNeeded) {
        if (context == null) {
            context = preparedIfNeeded;
            Value g = context.getBindings("js");
            tickFn = g.getMember("__tick"); stopFn = g.getMember("__stop"); evalFn = g.getMember("__evalToString");
            g.getMember("__refresh").execute();
        } else if (preparedIfNeeded != null) preparedIfNeeded.close();
        try {
            String r = evalFn.execute(code).asString();
            context.resetLimits();
            return r;
        } catch (PolyglotException e) {
            return "error: " + describe(e);
        }
    }

    boolean hasContext() { return context != null; }

    void tick() {
        if (!running) return;
        if (!player.isActive()) { stop("character logged out"); return; }
        try {
            context.resetLimits();
            tickFn.execute();
            errors = 0;
            if (stopRequest != null) { String why = stopRequest; stopRequest = null; stop(why); }
        } catch (PolyglotException e) {
            if (e.isResourceExhausted() || e.isCancelled()) {
                running = false;
                log("stopped: the script ran too long in one tick. Is there a loop that never ends? Use setInterval or await sleep() instead.");
                closeContext();
                return;
            }
            log("error: " + describe(e));
            if (++errors >= 20) stop("too many errors in a row");
        }
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        try { stopFn.execute(); } catch (Exception ignored) {}
        log("stopped (" + why + ")");
    }

    private void closeContext() {
        if (context != null) { try { context.close(true); } catch (Exception ignored) {} }
        context = null;
    }

    /** "TypeError: x is undefined (my_script.js line 12)" */
    static String describe(PolyglotException e) {
        String msg = e.getMessage();
        SourceSection at = e.getSourceLocation();
        if (at == null) {
            for (PolyglotException.StackFrame f : e.getPolyglotStackTrace())
                if (f.isGuestFrame() && f.getSourceLocation() != null && !"prelude.js".equals(f.getSourceLocation().getSource().getName()) && !"blocks.js".equals(f.getSourceLocation().getSource().getName())) { at = f.getSourceLocation(); break; }
        }
        if (at != null) msg += " (" + at.getSource().getName() + " line " + at.getStartLine() + ")";
        return msg;
    }

    static String resource(String name) {
        try (InputStream in = ScriptSession.class.getResourceAsStream("/codebridge/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
