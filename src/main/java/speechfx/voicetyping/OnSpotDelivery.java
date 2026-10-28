package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Holds finished text and hands it to the target window whenever that window is in front, from one background
 * thread ticking ten times a second. While the user is in another program the text waits here and is never sent
 * anywhere else; it goes in when they come back. Started without a target (from the tray), the first ordinary
 * window to come to the front becomes the target.
 */
final class OnSpotDelivery {

    private static final Logger log = LoggerFactory.getLogger(OnSpotDelivery.class);

    final StringBuilder pending = new StringBuilder();
    final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "vt-onspot-delivery");
        t.setDaemon(true);
        return t;
    });
    volatile TargetWindow target;
    volatile boolean gone;
    boolean anyDelivered;
    int delivered;

    OnSpotDelivery(TargetWindow target) {
        this.target = target;
        ticker.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** A finished piece of speech; a space goes between pieces. */
    synchronized void add(String text) {
        if (text == null || text.isBlank()) return;
        if (anyDelivered || !pending.isEmpty()) pending.append(' ');
        pending.append(text.strip());
    }

    synchronized String pending() { return pending.toString(); }

    synchronized int pendingWords() {
        var s = pending.toString().strip();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    synchronized void clear() { pending.setLength(0); }

    boolean waitingForTarget() { return target == null; }

    boolean away() {
        var t = target;
        return t != null && !gone && !t.inFront();
    }

    void shutdown() { ticker.shutdownNow(); }

    void tick() {
        try {
            var t = target;
            if (t == null) {
                target = t = TargetWindow.inFrontNow();
                if (t == null) return;
                log.info("on-spot target picked: {}", t);
            }
            if (gone) return;
            if (!t.alive()) {
                gone = true;
                log.info("on-spot target closed: {}", t);
                return;
            }
            var text = pending();
            if (text.isEmpty() || !t.inFront() || Win32Input.userHolding()) return;
            var sent = deliver(t, text);
            synchronized (this) {
                pending.delete(0, sent);
                if (sent > 0) { anyDelivered = true; delivered += sent; }
            }
        } catch (Throwable e) {
            log.error("on-spot delivery failed: {}", e.toString());
        }
    }

    static int deliver(TargetWindow t, String text) {
        var cfg = VoiceTypingSettings.getOnSpotSettings();
        if (cfg.pastes(t.app, text.length()) && TextInjector.paste(text, t::inFront)) return text.length();
        return TextInjector.type(text, () -> t.inFront() && !Win32Input.userHolding());
    }
}
