package speechfx.voicetyping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speech API keys: read from the shared ~/speechfx/keys/&lt;provider&gt;_keys.xml (enabled="n" switches one
 * off for good), with temporary freezes kept in vt's own key-freezes.json, so other tools reading
 * the shared files never see them. A frozen key comes back by itself once its time is up.
 */
public final class SpeechKeys {

    private static final Logger log = LoggerFactory.getLogger(SpeechKeys.class);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    public static final List<String> PROVIDERS = List.of("deepgram", "nvidia");
    static final Random random = new Random();

    private SpeechKeys() {}

    static Path keysFile(String provider) {
        return Path.of(System.getProperty("user.home"), "speechfx", "keys", provider + "_keys.xml");
    }

    static Path freezesFile() {
        return VoiceTypingSettings.getAppHome().resolve("key-freezes.json");
    }

    public static List<SpeechKey> all(String provider) {
        var out = new ArrayList<SpeechKey>();
        var file = keysFile(provider);
        if (!Files.exists(file)) return out;
        try {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            var nodes = doc.getElementsByTagName(provider + "_key");
            for (var i = 0; i < nodes.getLength(); i++) {
                var node = (Element) nodes.item(i);
                var value = node.getTextContent().strip();
                if ("n".equalsIgnoreCase(node.getAttribute("enabled")) || value.isEmpty()) continue;
                var key = new SpeechKey();
                key.provider(provider);
                key.account(node.getAttribute("account"));
                key.value(value);
                key.id(shortHash(value));
                out.add(key);
            }
        } catch (Exception e) {
            log.error("could not read {}: {}", file, e.getMessage());
        }
        return out;
    }

    public static synchronized SpeechKey pick(String provider) {
        var usable = all(provider).stream().filter(k -> frozenUntil(k) == null).toList();
        var pinned = VoiceTypingSettings.getSpeechSettings().pinned(provider);
        if (!pinned.isEmpty()) {
            var chosen = usable.stream().filter(k -> k.id().equals(pinned)).findFirst();
            if (chosen.isPresent()) {
                log.info("using {} (chosen account)", chosen.get().label());
                return chosen.get();
            }
            log.warn("chosen {} key #{} is frozen or gone; rotating among the others", provider, pinned);
        }
        if (usable.isEmpty()) {
            log.warn("no usable {} key (all disabled or frozen)", provider);
            return null;
        }
        var now = Instant.now();
        var fresh = usable.stream().filter(k -> avoided.getOrDefault(k.id(), Instant.MIN).isBefore(now)).toList();
        var from = fresh.isEmpty() ? usable : fresh;
        var key = from.get(random.nextInt(from.size()));
        log.info("using {} ({} of {} keys usable)", key.label(), usable.size(), all(provider).size());
        return key;
    }

    /** In memory only: prefer other keys for a while after this one stalled, without freezing it. */
    public static void avoid(SpeechKey key, Duration d) {
        if (key != null) avoided.put(key.id(), Instant.now().plus(d));
    }

    static final Map<String, Instant> avoided = new ConcurrentHashMap<>();

    public static boolean hasUsable(String provider) {
        return all(provider).stream().anyMatch(k -> frozenUntil(k) == null);
    }

    public static synchronized Instant frozenUntil(SpeechKey key) {
        var f = freezes().get(key.id());
        if (f == null) return null;
        var until = Instant.parse(f.until());
        return until.isAfter(Instant.now()) ? until : null;
    }

    public static synchronized KeyFreeze freezeOf(SpeechKey key) {
        return frozenUntil(key) == null ? null : freezes().get(key.id());
    }

    public static synchronized void freeze(SpeechKey key, Duration d, String reason) {
        var map = freezes();
        var f = new KeyFreeze();
        f.until(Instant.now().plus(d).toString());
        f.reason(reason);
        f.account(key.account());
        map.put(key.id(), f);
        save(map);
        log.warn("froze {} for {} h: {}", key.label(), d.toMinutes() / 60.0, reason);
    }

    public static synchronized void unfreeze(SpeechKey key) {
        var map = freezes();
        if (map.remove(key.id()) != null) save(map);
        log.info("unfroze {}", key.label());
    }

    /** Called by a provider when its stream fails; freezes the key when the failure is the key's fault. */
    public static void report(SpeechKey key, SpeechFailure failure, String message) {
        if (key != null && !failure.freeze.isZero()) freeze(key, failure.freeze, failure + ": " + message);
    }

    static Map<String, KeyFreeze> freezes() {
        var file = freezesFile();
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            var map = JSON.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, KeyFreeze>>() {});
            map.values().removeIf(f -> f.until() == null || Instant.parse(f.until()).isBefore(Instant.now()));
            return map;
        } catch (Exception e) {
            log.warn("ignoring unreadable {}: {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    static void save(Map<String, KeyFreeze> map) {
        try {
            Files.createDirectories(freezesFile().getParent());
            JSON.writeValue(freezesFile().toFile(), map);
        } catch (Exception e) {
            log.error("could not save key freezes: {}", e.getMessage());
        }
    }

    static String shortHash(String value) {
        try {
            var d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
