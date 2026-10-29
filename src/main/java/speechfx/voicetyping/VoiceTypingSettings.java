package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import speechfx.dictation.DictationSettings;

/** The app's own settings in ~/speechfx/voicetyping/VoiceTypingSettings.xml: hotkeys and the on-spot bar. */
public class VoiceTypingSettings {
    static final Logger log = LoggerFactory.getLogger(VoiceTypingSettings.class);
    static final XmlMapper XML = (XmlMapper) new XmlMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final String ROOT = "<voice-typing-settings>";
    static VoiceTypingConfig cached;
    static long loadedAt;

    public static Path getAppHome() { return Path.of(System.getProperty("user.home"), "speechfx", "voicetyping"); }

    public static Path getSettingsFilePath() { return getAppHome().resolve("VoiceTypingSettings.xml"); }

    /** Recordings and their transcripts, which the history lists. */
    public static Path getTempDir() { return getAppHome().resolve("temp"); }

    /**
     * Before anything else: recordings land in this app's folder, and on the first run the engine settings
     * (provider, keys, microphones) are carried over from this app's older, all-in-one settings file.
     */
    public static void init() {
        DictationSettings.recordingsDir(getTempDir());
        DictationSettings.importFrom(getSettingsFilePath(), "voice-typing-settings", getAppHome().resolve("key-freezes.json"));
    }

    public static VoiceTypingConfig loadSettings() throws IOException {
        var file = getSettingsFilePath();
        if (!Files.exists(file)) {
            Files.createDirectories(getAppHome());
            write(new VoiceTypingConfig());
        }
        if (cached != null && System.currentTimeMillis() - loadedAt < 5000) return cached;
        try {
            var xml = Files.readString(file, StandardCharsets.UTF_8);
            cached = XML.readValue(xml.substring(xml.indexOf(ROOT)), VoiceTypingConfig.class);
            loadedAt = System.currentTimeMillis();
            return cached;
        } catch (Exception e) {
            throw new IOException("Failed to parse settings file: " + file + ". Error: " + e.getMessage(), e);
        }
    }

    /** The configured dictation-window key, or null for the platform default. */
    public static String getHotkey() {
        try { return blankToNull(loadSettings().hotkey()); } catch (IOException e) { return null; }
    }

    /** The configured dictate-into-an-app key, or null for the platform default. */
    public static String getOnSpotHotkey() {
        try { return blankToNull(loadSettings().onSpotHotkey()); } catch (IOException e) { return null; }
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    public static OnSpotSettings getOnSpotSettings() {
        try {
            var s = loadSettings().onSpot();
            return s != null ? s : new OnSpotSettings();
        } catch (IOException e) {
            return new OnSpotSettings();
        }
    }

    /** Where the floating bar was dragged to, kept for next time. */
    public static void setOnSpotBar(double x, double y) {
        change(c -> c.onSpot((c.onSpot() != null ? c.onSpot() : new OnSpotSettings()).barX(x).barY(y)));
    }

    static void change(Consumer<VoiceTypingConfig> edit) {
        try {
            var c = loadSettings();
            edit.accept(c);
            write(c);
            cached = null;
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());
        }
    }

    static void write(VoiceTypingConfig c) throws IOException {
        var body = XML.writeValueAsString(c);
        String header;
        try (var in = VoiceTypingSettings.class.getResourceAsStream("settings-header.txt")) {
            header = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(getSettingsFilePath(), header + body.substring(body.indexOf(ROOT)), StandardCharsets.UTF_8);
    }

    public static void ensureDirectoriesExist() {
        try {
            Files.createDirectories(getAppHome());
            DictationSettings.ensureDirectoriesExist();
        } catch (IOException e) {
            log.error("Failed to create directories: {}", e.getMessage());
        }
    }

    /** Opens both files: the shared dictation settings and this app's own. */
    public static void openSettingsInEditor() {
        DictationSettings.openSettingsInEditor();
        try {
            loadSettings();
            var f = getSettingsFilePath().toString();
            var os = System.getProperty("os.name").toLowerCase();
            var pb = os.contains("win") ? new ProcessBuilder("cmd", "/c", "start", "notepad", f)
                    : os.contains("mac") ? new ProcessBuilder("open", "-e", f) : new ProcessBuilder("xdg-open", f);
            pb.start();
        } catch (IOException e) {
            log.error("Failed to open settings: {}", e.getMessage());
        }
    }
}
