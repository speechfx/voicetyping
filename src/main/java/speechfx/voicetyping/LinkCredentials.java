package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import datapotter.datahelper.Data;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the phone link's Upstash database is, taken from whatever block the user pasted - either
 * REDIS_URL, or the two UPSTASH_REDIS_REST_* lines (Upstash's REST token is also the Redis password).
 * Kept in its own file beside settings.xml rather than inside it, so the settings file can be copied
 * or shown around without carrying the secret.
 */
@Data
public final class LinkCredentials extends LinkCredentials_A {

    String host;
    String password;

    static Path file() {
        return VoiceTypingSettings.getAppHome().resolve("phonelink.env");
    }

    public static Optional<LinkCredentials> parse(String text) {
        var kv = new HashMap<String, String>();
        for (var raw : text.split("\\R")) {
            var line = raw.strip();
            var eq = line.indexOf('=');
            if (line.startsWith("#") || eq <= 0) continue;
            kv.put(line.substring(0, eq).strip().toUpperCase(Locale.ROOT), unquote(line.substring(eq + 1).strip()));
        }
        var c = new LinkCredentials();
        var redis = kv.get("REDIS_URL");
        var rest = kv.get("UPSTASH_REDIS_REST_URL");
        var token = kv.get("UPSTASH_REDIS_REST_TOKEN");
        try {
            if (redis != null && URI.create(redis).getUserInfo() != null) {
                var u = URI.create(redis);
                var info = u.getUserInfo();
                c.host = u.getHost();
                c.password = info.substring(info.indexOf(':') + 1);
            } else if (rest != null && token != null) {
                c.host = URI.create(rest).getHost();
                c.password = token;
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return c.host == null || c.password == null || c.password.isBlank() ? Optional.empty() : Optional.of(c);
    }

    public static Optional<LinkCredentials> load() {
        try {
            return Files.exists(file()) ? parse(Files.readString(file())) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void save() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "REDIS_URL=rediss://default:" + password + "@" + host + ":6379\n");
    }

    static String unquote(String v) {
        return v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))
                ? v.substring(1, v.length() - 1) : v;
    }
}
