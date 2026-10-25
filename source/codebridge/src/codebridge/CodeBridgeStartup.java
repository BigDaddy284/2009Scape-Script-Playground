package codebridge;

import core.api.StartupListener;

/** Found by the server's class scanner; starts the local control port once the world is up. */
public class CodeBridgeStartup implements StartupListener {
    @Override public void startup() {
        CodeBridge.startControlServer();
        Thread warm = new Thread(() -> {   // load the JavaScript engine now so the first Run is quick
            try { ScriptSession.newContext(null).close(); System.out.println("[CodeBridge] JavaScript engine ready"); }
            catch (Throwable t) { System.out.println("[CodeBridge] JavaScript engine failed to load: " + t); }
        }, "codebridge-warmup");
        warm.setDaemon(true);
        warm.start();
    }
}
