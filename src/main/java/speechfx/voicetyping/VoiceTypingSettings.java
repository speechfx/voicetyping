package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class VoiceTypingSettings {

    private static final Logger log = LoggerFactory.getLogger(VoiceTypingSettings.class);

    private static final XmlMapper XML_MAPPER = new XmlMapper();
    private static VoiceTypingConfig cachedConfig;
    private static long lastLoadTime;

    static {
        XML_MAPPER.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
    }

    public static Path getAppHome() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                userHome = System.getenv("USERPROFILE");
                if (userHome == null) userHome = System.getenv("HOMEDRIVE") + System.getenv("HOMEPATH");
            } else {
                userHome = System.getenv("HOME");
            }
        }
        return Paths.get(userHome, "speechfx", "voicetyping");
    }

    public static Path getSettingsFilePath() {
        return getAppHome().resolve("VoiceTypingSettings.xml");
    }

    public static Path getTempDir() {
        return getAppHome().resolve("temp");
    }

    public static Path getTodaysTempDir() {
        String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return getTempDir().resolve(dateFolder);
    }

    public static VoiceTypingConfig loadSettings() throws IOException {
        Path settingsFile = getSettingsFilePath();

        if (!Files.exists(settingsFile)) {
            createDefaultSettingsFile(settingsFile);
            log.info("Created default settings file at: {}", settingsFile);
            log.info("Please edit this file with your Gemini API keys.");
        }

        if (cachedConfig != null && System.currentTimeMillis() - lastLoadTime < 5000) {
            return cachedConfig;
        }

        try {
            String xmlContent = Files.readString(settingsFile, StandardCharsets.UTF_8);
            cachedConfig = XML_MAPPER.readValue(xmlContent, VoiceTypingConfig.class);
            lastLoadTime = System.currentTimeMillis();
            return cachedConfig;
        } catch (Exception e) {
            throw new IOException("Failed to parse settings file: " + settingsFile + ". Error: " + e.getMessage(), e);
        }
    }

    public static String getModel() {
        try {
            var config = loadSettings();
            return config.model() != null ? config.model() : "gemini-2.5-flash";
        } catch (IOException e) {
            log.error("Error loading model setting: {}", e.getMessage());
            return "gemini-2.5-flash";
        }
    }

    public static String getProvider() {
        try {
            var config = loadSettings();
            return config.provider() != null ? config.provider() : "gemini";
        } catch (IOException e) {
            return "gemini";
        }
    }

    public static void setProvider(String provider) {
        try {
            var config = loadSettings();
            config.provider(provider);
            cachedConfig = config;
            saveSettings();
        } catch (IOException e) {
            log.error("Failed to set provider: {}", e.getMessage());
        }
    }

    public static String getDeepgramModel() {
        try {
            var config = loadSettings();
            return config.deepgramModel() != null ? config.deepgramModel() : "nova-3";
        } catch (IOException e) {
            return "nova-3";
        }
    }

    public static String getNvidiaFunctionId() {
        try {
            var config = loadSettings();
            return config.nvidiaFunctionId() != null ? config.nvidiaFunctionId() : "bb0837de-8c7b-481f-9ec8-ef5663e9c1fa";
        } catch (IOException e) {
            return "bb0837de-8c7b-481f-9ec8-ef5663e9c1fa";
        }
    }

    public static String getNvidiaLanguage() {
        try {
            var config = loadSettings();
            return config.nvidiaLanguage() != null ? config.nvidiaLanguage() : "en-US";
        } catch (IOException e) {
            return "en-US";
        }
    }

    /** The configured dictation-window key, or null for the platform default. */
    public static String getHotkey() {
        try {
            return blankToNull(loadSettings().hotkey());
        } catch (IOException e) {
            return null;
        }
    }

    /** The configured dictate-into-an-app key, or null for the platform default. */
    public static String getOnSpotHotkey() {
        try {
            return blankToNull(loadSettings().onSpotHotkey());
        } catch (IOException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public static String getRandomApiKey() {
        try {
            List<ApiKey> enabledKeys = loadEnabledApiKeys();
            if (enabledKeys.isEmpty()) {
                log.error("No enabled API keys found in settings!");
                return null;
            }
            Collections.shuffle(enabledKeys);
            ApiKey selected = enabledKeys.get(0);
            log.info("Using API key: {}", selected.name());
            return selected.value();
        } catch (IOException e) {
            log.error("Error loading API keys: {}", e.getMessage());
            return null;
        }
    }

    public static List<ApiKey> loadEnabledApiKeys() throws IOException {
        var config = loadSettings();
        if (config.apiKeys() == null || config.apiKeys().keys() == null) {
            return new ArrayList<>();
        }
        return config.apiKeys().keys().stream()
                .filter(ApiKey::isEnabled)
                .filter(key -> key.value() != null && !key.value().isBlank())
                .filter(key -> !key.value().startsWith("your-"))
                .collect(Collectors.toList());
    }

    public static RecordingSettings getRecordingSettings() {
        try {
            var config = loadSettings();
            return config.recording() != null ? config.recording() : new RecordingSettings();
        } catch (IOException e) {
            return new RecordingSettings();
        }
    }

    public static LongRecordingSettings getLongRecordingSettings() {
        try {
            var config = loadSettings();
            return config.longRecording() != null ? config.longRecording() : new LongRecordingSettings();
        } catch (IOException e) {
            return new LongRecordingSettings();
        }
    }

    public static SpeechSettings getSpeechSettings() {
        try {
            var config = loadSettings();
            return config.speech() != null ? config.speech() : new SpeechSettings();
        } catch (IOException e) {
            return new SpeechSettings();
        }
    }

    /** provider: "deepgram" or "nvidia"; id: a SpeechKey id, or empty to rotate. */
    public static void setSpeechKey(String provider, String id) {
        try {
            var config = loadSettings();
            var s = config.speech() != null ? config.speech() : new SpeechSettings();
            s.pin(provider, id == null ? "" : id);
            config.speech(s);
            cachedConfig = config;
            saveSettings();
        } catch (IOException e) {
            log.error("Failed to save the speech account: {}", e.getMessage());
        }
    }

    public static OnSpotSettings getOnSpotSettings() {
        try {
            var config = loadSettings();
            return config.onSpot() != null ? config.onSpot() : new OnSpotSettings();
        } catch (IOException e) {
            return new OnSpotSettings();
        }
    }

    /** Where the floating bar was dragged to, kept for next time. */
    public static void setOnSpotBar(double x, double y) {
        try {
            var config = loadSettings();
            var s = config.onSpot() != null ? config.onSpot() : new OnSpotSettings();
            s.barX(x).barY(y);
            config.onSpot(s);
            cachedConfig = config;
            saveSettings();
        } catch (IOException e) {
            log.error("Failed to save the bar position: {}", e.getMessage());
        }
    }

    public static MicrophoneSettings getMicrophoneSettings() {
        try {
            var config = loadSettings();
            return config.microphones() != null ? config.microphones() : new MicrophoneSettings();
        } catch (IOException e) {
            return new MicrophoneSettings();
        }
    }

    public static void setPreferredMicrophones(List<String> preferred) {
        try {
            var config = loadSettings();
            var mics = config.microphones() != null ? config.microphones() : new MicrophoneSettings();
            mics.preferred(new ArrayList<>(preferred));
            config.microphones(mics);
            cachedConfig = config;
            saveSettings();
        } catch (IOException e) {
            log.error("Failed to save preferred microphones: {}", e.getMessage());
        }
    }

    public static List<ApiKey> getApiKeys() {
        try {
            var config = loadSettings();
            if (config.apiKeys() == null || config.apiKeys().keys() == null) {
                return new ArrayList<>();
            }
            return config.apiKeys().keys();
        } catch (IOException e) {
            log.error("Error loading API keys: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public static void saveSettings() {
        try {
            if (cachedConfig == null) {
                cachedConfig = loadSettings();
            }

            String xmlOutput = XML_MAPPER.writeValueAsString(cachedConfig);
            Path settingsFile = getSettingsFilePath();

            String existingContent = Files.readString(settingsFile, StandardCharsets.UTF_8);
            String header;
            if (existingContent.contains("<!--")) {
                header = existingContent.substring(0, existingContent.indexOf("<voice-typing-settings>"));
            } else {
                header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
            }

            String finalXml = header + xmlOutput.substring(xmlOutput.indexOf("<voice-typing-settings>"));
            Files.writeString(settingsFile, finalXml, StandardCharsets.UTF_8);

            cachedConfig = null;
            log.info("Settings saved successfully");
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage());
        }
    }

    private static void createDefaultSettingsFile(Path settingsFile) throws IOException {
        Files.createDirectories(settingsFile.getParent());

        var config = new VoiceTypingConfig();
        config.provider("deepgram-streaming");
        config.model("gemini-2.5-flash");
        config.deepgramModel("nova-3");

        var recording = new RecordingSettings();
        recording.maxDurationSeconds(120);
        recording.sampleRate(44100);
        config.recording(recording);
        config.longRecording(new LongRecordingSettings());

        var apiKeys = new ApiKeys();
        List<ApiKey> keyList = new ArrayList<>();
        keyList.add(new ApiKey("example@gmail.com", "your-gemini-api-key-here", "y"));
        apiKeys.keys(keyList);
        config.apiKeys(apiKeys);

        String xmlOutput = XML_MAPPER.writeValueAsString(config);

        String header = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!--
                Voice Typing Settings

                provider: Active transcription provider (gemini or deepgram)
                model: Gemini model (gemini-2.5-flash recommended)
                deepgram-model: Deepgram model (nova-3 recommended)
                hotkey: the key that opens the dictation window, e.g. Ctrl+Z (default Win+Y,
                  or Ctrl+Option+Y on a Mac); read at start, so restart after changing it
                on-spot-hotkey: the key that dictates into the app in front (default Win+J,
                  or Ctrl+Option+J on a Mac); read at start

                Deepgram keys: stored in ~/speechfx/keys/deepgram_keys.xml

                Recording Settings (the short press-record-paste flow):
                  - maxDurationSeconds: Maximum recording duration (default: 120)
                  - sampleRate: Audio sample rate (default: 44100)

                Long Recording Settings (the dictation window, no time limit):
                  - sampleRate: 16000 is what the recogniser wants; lower memory and bandwidth
                  - segmentSeconds: close and reopen the stream about this often (default 240)
                  - segmentHardMaxSeconds: cut even mid-speech past this (default 330)
                  - silenceGapMillis / silenceRmsThreshold: what counts as a gap to cut in
                  - safetyStopMinutes: stop a forgotten session (default 90, 0 disables)
                  - autosaveSeconds: how often the note is written to disk while running
                  - keepAudio: keep the raw PCM alongside the note (default true)

                API Keys: Gemini API keys
                  - enabled="y" to enable, "n" to disable
                  - Multiple keys are rotated to avoid rate limits
                -->
                """;

        String finalXml = header + xmlOutput.substring(xmlOutput.indexOf("<voice-typing-settings>"));
        Files.writeString(settingsFile, finalXml, StandardCharsets.UTF_8);
    }

    public static void ensureDirectoriesExist() {
        try {
            Files.createDirectories(getAppHome());
            Files.createDirectories(getTempDir());
            Files.createDirectories(getTodaysTempDir());
        } catch (IOException e) {
            log.error("Failed to create directories: {}", e.getMessage());
        }
    }

    public static void openSettingsInEditor() {
        try {
            Path settingsFile = getSettingsFilePath();
            if (!Files.exists(settingsFile)) {
                createDefaultSettingsFile(settingsFile);
            }

            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "notepad", settingsFile.toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", "-e", settingsFile.toString());
            } else {
                pb = new ProcessBuilder("xdg-open", settingsFile.toString());
            }
            pb.start();
        } catch (IOException e) {
            log.error("Failed to open settings: {}", e.getMessage());
        }
    }
}
