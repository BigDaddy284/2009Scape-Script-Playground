package codeedition;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connection to the game backend: the script add-on inside the game server, listening on 127.0.0.1 only.
 * The launcher, the game client (Code orb) and the Script Playground all talk to it.
 */
final class Backend {
    static final int PORT = Integer.getInteger("codebridge.port", 43610);

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    /** Sends one command and returns the one-line answer, or null when the backend isn't running. */
    synchronized String send(String line) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null) {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                    // with nothing listening, a local connect can join itself; that would hold the port the game needs
                    if (socket.getLocalPort() == PORT) { socket.close(); socket = null; throw new IOException("no game yet"); }
                    socket.setSoTimeout(15000);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                }
                out.println(line);
                String r = in.readLine();
                if (r != null) return r.replace("\\n", "\n");
            } catch (IOException ignored) {}
            close();
        }
        return null;
    }

    synchronized void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
    }

    /** What the backend knows about the game right now. */
    static final class State {
        boolean backendUp;     // game world (server) is running
        boolean gameRunning;   // game window is open and sending heartbeats
        String player = "";    // logged-in character, "" at the login screen
        int[] window = {0, 0, 0, 0};
        int orbClicks;
        boolean grabbing;      // the game is waiting for a tile click (coordinate grabber)
        String grab = "0";     // the last tile clicked: "count x y z"
    }

    State state() {
        State s = new State();
        String json = send("state");
        if (json == null || !json.startsWith("{")) return s;
        s.backendUp = true;
        s.gameRunning = json.contains("\"game\":true");
        Matcher m = Pattern.compile("\"player\":\"([^\"]*)\"").matcher(json);
        if (m.find()) s.player = m.group(1);
        m = Pattern.compile("\"window\":\\[(-?\\d+),(-?\\d+),(\\d+),(\\d+)]").matcher(json);
        if (m.find()) for (int i = 0; i < 4; i++) s.window[i] = Integer.parseInt(m.group(i + 1));
        m = Pattern.compile("\"orb\":(\\d+)").matcher(json);
        if (m.find()) s.orbClicks = Integer.parseInt(m.group(1));
        s.grabbing = json.contains("\"grabbing\":true");
        m = Pattern.compile("\"grab\":\"([^\"]*)\"").matcher(json);
        if (m.find()) s.grab = m.group(1);
        return s;
    }
}
