package speechfx.voicetyping;

import java.util.concurrent.*;

/**
 * The JavaFX toolkit, started once next to the AWT tray, and the one place that hands work to its thread.
 * Windows hide rather than close, so the toolkit must outlive its last window (implicit exit off).
 * javafx.application.Platform is spelled out: this package has its own Platform (OS detection).
 */
public final class FxThread {

    static volatile boolean started;

    /** Starts the toolkit and waits for it; safe to call more than once. */
    public static synchronized void start() {
        if (started) return;
        var ready = new CountDownLatch(1);
        javafx.application.Platform.startup(ready::countDown);
        try {
            if (!ready.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start in 20 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        javafx.application.Platform.setImplicitExit(false);
        started = true;
    }

    /** Runs on the FX thread: now if already there, otherwise as soon as it is free. */
    public static void run(Runnable r) {
        if (javafx.application.Platform.isFxApplicationThread()) r.run();
        else javafx.application.Platform.runLater(r);
    }

    /** Runs on the FX thread and waits for the answer. Never call it while holding up the FX thread itself. */
    public static <T> T call(Callable<T> c) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }
        var f = new CompletableFuture<T>();
        javafx.application.Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f.join();
    }

    private FxThread() {}
}
