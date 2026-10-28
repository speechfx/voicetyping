package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Picks the streaming provider for each new dictation segment: the one selected in the tray
 * first, the other one as fallback.
 *
 * Within a provider, failures move between its keys: a rejected or exhausted key is frozen by
 * SpeechKeys, a stalled one is avoided for a while, so the next segment gets another key. Only
 * when a provider has no usable key left, or has failed STRIKES times running, is it set aside
 * for a few minutes and the next provider used.
 */
public final class SpeechChain {

    private static final Logger log = LoggerFactory.getLogger(SpeechChain.class);

    static final List<String> STREAMING = List.of("deepgram-flux", "deepgram-streaming", "nvidia-streaming");
    static final int STRIKES = 3;
    static final Duration SET_ASIDE = Duration.ofMinutes(5), AVOID_KEY = Duration.ofMinutes(10);

    final Supplier<String> selected;
    final Map<String, Integer> strikes = new ConcurrentHashMap<>();
    final Map<String, Instant> setAside = new ConcurrentHashMap<>();

    public SpeechChain(Supplier<String> selected) {
        this.selected = selected;
    }

    List<String> order() {
        var first = selected.get();
        var order = new ArrayList<String>();
        if (STREAMING.contains(first)) order.add(first);
        STREAMING.stream().filter(p -> !order.contains(p)).forEach(order::add);
        return order;
    }

    public StreamingTranscriptionProvider next() {
        var order = order();
        var now = Instant.now();
        var name = order.stream()
                .filter(p -> SpeechKeys.hasUsable(keyProvider(p)))
                .filter(p -> setAside.getOrDefault(p, Instant.MIN).isBefore(now))
                .findFirst()
                .or(() -> order.stream().filter(p -> SpeechKeys.hasUsable(keyProvider(p))).findFirst())
                .orElse(order.getFirst());
        if (!name.equals(order.getFirst())) log.warn("dictation using fallback provider {} (first choice {})", name, order.getFirst());
        return create(name);
    }

    public void failed(StreamingTranscriptionProvider p, SpeechFailure f) {
        var name = nameOf(p);
        if (f == SpeechFailure.STALLED || f == SpeechFailure.OTHER) SpeechKeys.avoid(p.key(), AVOID_KEY);
        var n = strikes.merge(name, 1, Integer::sum);
        if (n >= STRIKES || !SpeechKeys.hasUsable(keyProvider(name))) {
            strikes.put(name, 0);
            setAside.put(name, Instant.now().plus(SET_ASIDE));
            log.warn("{} set aside for {} min after {} ({} failures running)", name, SET_ASIDE.toMinutes(), f, n);
        }
    }

    /** The user chose this provider: forget its strikes and any set-aside, so the next segment uses it. */
    public void prefer(String name) {
        strikes.remove(name);
        setAside.remove(name);
    }

    public void succeeded(StreamingTranscriptionProvider p) {
        strikes.put(nameOf(p), 0);
    }

    public static String nameOf(StreamingTranscriptionProvider p) {
        return p instanceof DeepgramStreamingTranscriptionService d ? (d.flux ? "deepgram-flux" : "deepgram-streaming")
                : p instanceof NvidiaStreamingTranscriptionService ? "nvidia-streaming" : "other";
    }

    public static String shortName(StreamingTranscriptionProvider p) {
        return p == null ? "" : nameOf(p).replace("-streaming", "");
    }

    static String keyProvider(String name) {
        return name.startsWith("deepgram") ? "deepgram" : name.replace("-streaming", "");
    }

    static StreamingTranscriptionProvider create(String name) {
        return switch (name) {
            case "deepgram-flux" -> new DeepgramStreamingTranscriptionService(true);
            case "deepgram-streaming" -> new DeepgramStreamingTranscriptionService();
            default -> new NvidiaStreamingTranscriptionService();
        };
    }
}
