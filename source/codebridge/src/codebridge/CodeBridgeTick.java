package codebridge;

import core.api.TickListener;

/** Called by the server once per 0.6 s game tick, on the game thread. */
public class CodeBridgeTick implements TickListener {
    @Override public void tick() {
        CodeBridge.drainQueue();
        CodeBridge.keepScreensVisible();
        CodeBridge.tickSessions();
    }
}
