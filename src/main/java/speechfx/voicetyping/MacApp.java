package speechfx.voicetyping;

import speechfx.dictation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;

/**
 * The macOS-only parts: starting at login (a per-user LaunchAgent, no admin rights) and updating
 * from the public releases repository, whose install.sh both installs and updates the app.
 */
public final class MacApp {

    private static final Logger log = LoggerFactory.getLogger(MacApp.class);

    public static final String RELEASES_REPO = "speechfx/voicetyping-releases";
    public static final String INSTALL_COMMAND = "curl -fsSL https://raw.githubusercontent.com/" + RELEASES_REPO + "/main/install.sh | bash";
    static final String AGENT_LABEL = "io.github.speechfx.voicetyping";
    static final String APP_NAME = "Voice Typing";

    private MacApp() {}

    static Path agentFile() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", AGENT_LABEL + ".plist");
    }

    public static boolean startsAtLogin() {
        return Files.exists(agentFile());
    }

    public static void startAtLogin(boolean on) {
        try {
            if (!on) { Files.deleteIfExists(agentFile()); log.info("start at login: off"); return; }
            Files.createDirectories(agentFile().getParent());
            var plist = String.join("\n",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
                    "<plist version=\"1.0\"><dict>",
                    "  <key>Label</key><string>" + AGENT_LABEL + "</string>",
                    "  <key>ProgramArguments</key><array><string>/usr/bin/open</string><string>-a</string><string>" + APP_NAME + "</string></array>",
                    "  <key>RunAtLoad</key><true/>",
                    "</dict></plist>", "");
            Files.writeString(agentFile(), plist);
            log.info("start at login: on ({})", agentFile());
        } catch (IOException e) {
            log.error("could not change start at login: {}", e.getMessage());
        }
    }

    public static String version() {
        try (var in = MacApp.class.getResourceAsStream("/vt-version.properties")) {
            var p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("version", "0");
        } catch (IOException e) {
            return "0";
        }
    }

    /** The newer release's version, if the latest published release is newer than this build. */
    public static Optional<String> newerRelease() {
        try {
            var req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + RELEASES_REPO + "/releases/latest"))
                    .header("Accept", "application/vnd.github+json").timeout(Duration.ofSeconds(15)).build();
            var res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return Optional.empty();
            var latest = new ObjectMapper().readTree(res.body()).path("tag_name").asText("").replaceFirst("^v", "");
            return newer(latest, version()) ? Optional.of(latest) : Optional.empty();
        } catch (Exception e) {
            log.warn("update check failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static boolean newer(String a, String b) {
        var x = a.split("\\D+");
        var y = b.split("\\D+");
        for (var i = 0; i < Math.max(x.length, y.length); i++) {
            var p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            var q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    /** Runs the installer detached; it quits this app, replaces it and opens the new one. */
    public static void update() {
        try {
            var logFile = VoiceTypingSettings.getAppHome().resolve("update.log").toFile();
            new ProcessBuilder("/bin/bash", "-c", "nohup bash -c '" + INSTALL_COMMAND + "' >/dev/null 2>&1 &")
                    .redirectErrorStream(true).redirectOutput(logFile).start();
            log.info("update started: {}", INSTALL_COMMAND);
        } catch (IOException e) {
            log.error("could not start the update: {}", e.getMessage());
        }
    }
}
