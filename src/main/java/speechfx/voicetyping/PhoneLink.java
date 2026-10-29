package speechfx.voicetyping;

import speechfx.dictation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Receives notes pushed from the phone. One long-lived connection: announce this desktop is alive,
 * look whether the phone has been seen, then wait up to 30 s on the inbox, and round again. That is
 * three commands per half minute when idle, about 260k a month - inside Upstash's free tier.
 * <p>
 * The key names are the protocol and are mirrored in the Android app's PhoneLink; change both.
 * The ack is what the phone waits on: it is only written after the text is on the clipboard, so the
 * phone saying "delivered" means the text really arrived, not merely that it was queued.
 */
public final class PhoneLink {

    private static final Logger log = LoggerFactory.getLogger(PhoneLink.class);

    public enum Status { OFF, SERVER_ONLY, CONNECTED }

    static final String INBOX = "vt:inbox", DESKTOP_ALIVE = "vt:desktop:alive",
            PHONE_ALIVE = "vt:phone:alive", ACK = "vt:ack:";
    static final String WAIT_SECONDS = "30", ALIVE_TTL = "100", ACK_TTL = "120";

    final BiConsumer<Status, String> onStatus;
    final BiConsumer<String, String> onReceived;
    final ObjectMapper json = new ObjectMapper();
    volatile boolean running;
    volatile RespClient client;
    volatile String lastReport = "";
    Thread thread;

    public PhoneLink(BiConsumer<Status, String> onStatus, BiConsumer<String, String> onReceived) {
        this.onStatus = onStatus;
        this.onReceived = onReceived;
    }

    public synchronized void start() {
        stop();
        running = true;
        thread = Thread.ofPlatform().daemon().name("PhoneLink").start(this::loop);
    }

    public synchronized void stop() {
        running = false;
        closeQuietly();
        if (thread != null) thread.interrupt();
    }

    /** On exit, so the phone learns at once rather than after the alive key's TTL runs out. */
    public void retireAliveKey() {
        LinkCredentials.load().ifPresent(c -> {
            try (var r = RespClient.connect(c)) {
                r.call("DEL", DESKTOP_ALIVE);
            } catch (IOException e) {
                log.debug("could not clear alive key: {}", e.getMessage());
            }
        });
    }

    void loop() {
        var backoff = 2;
        while (running) {
            var creds = LinkCredentials.load();
            if (creds.isEmpty()) {
                report(Status.OFF, "not set up - paste the connection block (tray menu: Phone link)");
                return;
            }
            try (var redis = RespClient.connect(creds.get())) {
                client = redis;
                backoff = 2;
                while (running) cycle(redis);
            } catch (IOException | RuntimeException e) {
                if (!running) return;
                report(Status.OFF, "cannot reach the relay: " + e.getMessage());
                pause(backoff);
                backoff = Math.min(backoff * 2, 60);
            }
        }
    }

    void cycle(RespClient redis) throws IOException {
        redis.call("SET", DESKTOP_ALIVE, host(), "EX", ALIVE_TTL);
        var phone = redis.call("GET", PHONE_ALIVE);
        report(phone == null ? Status.SERVER_ONLY : Status.CONNECTED,
                phone == null ? "relay connected, phone not seen in the last 90 s" : "connected to " + phone);
        if (redis.call("BLPOP", INBOX, WAIT_SECONDS) instanceof List<?> popped && popped.size() == 2) {
            deliver(redis, String.valueOf(popped.get(1)));
        }
    }

    void deliver(RespClient redis, String raw) throws IOException {
        var node = json.readTree(raw);
        var id = node.path("id").asText("");
        var from = node.path("from").asText("phone");
        onReceived.accept(node.path("text").asText(""), from);
        if (!id.isEmpty()) redis.call("SET", ACK + id, host(), "EX", ACK_TTL);
        log.info("note {} from {} delivered to clipboard", id, from);
    }

    void report(Status s, String detail) {
        var key = s + detail;
        if (key.equals(lastReport)) return;
        lastReport = key;
        log.info("phone link: {} - {}", s, detail);
        onStatus.accept(s, detail);
    }

    void closeQuietly() {
        var c = client;
        client = null;
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }

    static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "desktop";
        }
    }

    static void pause(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
