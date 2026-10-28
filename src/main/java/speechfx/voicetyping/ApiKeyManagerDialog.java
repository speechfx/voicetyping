package speechfx.voicetyping;

import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.*;

/**
 * Manages the Gemini API keys. F2 add, F3 edit, F4 delete, F5 open the Deepgram keys file, Esc close.
 * The active provider is shown, not switched here: the tray menu lists every provider (the old Gemini/Deepgram
 * toggle would have turned deepgram-streaming into batch Deepgram).
 * Disabled keys show "No" in the Enabled column.
 */
public final class ApiKeyManagerDialog {

    static final Logger log = LoggerFactory.getLogger(ApiKeyManagerDialog.class);
    static final Path DEEPGRAM_KEYS = Path.of(System.getProperty("user.home"), "speechfx", "keys", "deepgram_keys.xml");

    final RowTable list = new RowTable(new String[] {"Name", "Key (masked)", "Enabled"}, 200, 330, 80);
    final Label provider = new Label();
    final Label status = new Label("");
    List<ApiKey> keys;
    Stage stage;

    /** Callable from any thread. */
    public static void open() {
        FxThread.start();
        FxThread.run(() -> new ApiKeyManagerDialog().show());
    }

    void show() {
        var legend = new KeyLegend()
                .key(KeyCode.F2, "Add", this::add)
                .key(KeyCode.F3, "Edit", this::edit)
                .key(KeyCode.F4, "Delete", this::delete)
                .key(KeyCode.F5, "Deepgram keys file", this::openDeepgramKeys)
                .key(KeyCode.ESCAPE, "Close", () -> stage.close());
        provider.getStyleClass().add("vt-heading");
        status.getStyleClass().add("vt-status");
        var foot = new VBox(6, status, legend.bar());
        foot.getStyleClass().add("vt-foot");
        var root = new BorderPane(list.view, provider, null, foot, null);
        root.getStyleClass().add("vt-window");
        reload(0);
        showProvider();
        stage = FxWindows.show("Voice Typing — Gemini API keys", root, 700, 440, legend);
        list.view.requestFocus();
    }

    void reload(int select) {
        keys = VoiceTypingSettings.getApiKeys();
        list.fill(keys.stream().map(k -> List.of(k.name(), mask(k.value()), k.isEnabled() ? "Yes" : "No")).toList(), select);
    }

    static String mask(String key) {
        return key == null || key.length() < 15 ? "***" : key.substring(0, 10) + "..." + key.substring(key.length() - 4);
    }

    void showProvider() {
        var p = VoiceTypingSettings.getProvider();
        provider.setText("Active provider: " + p + "   (change it in the tray menu)");
    }

    void add() {
        ApiKeyForm.ask(stage, "Add Gemini API key", null).ifPresent(k -> {
            if (k.name().isEmpty()) k.name("Key " + (keys.size() + 1));
            keys.add(k);
            VoiceTypingSettings.saveSettings();
            log.info("Added API key: {}", k.name());
            reload(keys.size() - 1);
            status.setText("Added " + k.name() + ".");
        });
    }

    void edit() {
        var i = list.selected();
        if (i < 0) return;
        var k = keys.get(i);
        ApiKeyForm.ask(stage, "Edit Gemini API key", k).ifPresent(e -> {
            k.name(e.name().isEmpty() ? k.name() : e.name());
            k.value(e.value());
            k.enabled(e.enabled());
            VoiceTypingSettings.saveSettings();
            log.info("Updated API key: {}", k.name());
            reload(i);
            status.setText("Saved " + k.name() + ".");
        });
    }

    void delete() {
        var i = list.selected();
        if (i < 0) return;
        var k = keys.get(i);
        if (!Dialogs.confirm("Delete API key", "Delete the API key '" + k.name() + "'? This cannot be undone.")) return;
        keys.remove(i);
        VoiceTypingSettings.saveSettings();
        log.info("Deleted API key: {}", k.name());
        reload(i);
        status.setText("Deleted " + k.name() + ".");
    }

    void openDeepgramKeys() {
        try {
            java.awt.Desktop.getDesktop().open(DEEPGRAM_KEYS.toFile());
        } catch (Exception e) {
            status.setText("Cannot open " + DEEPGRAM_KEYS + ": " + e.getMessage());
        }
    }
}
