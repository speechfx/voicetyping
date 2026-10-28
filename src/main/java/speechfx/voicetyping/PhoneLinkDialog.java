package speechfx.voicetyping;

import java.io.IOException;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * Paste the Upstash block, press F2, and the link wires itself. No chords: F2 saves, Esc closes.
 * The box takes typing, so the keys are F2 and Esc only.
 */
public final class PhoneLinkDialog {

    Stage stage;

    /** Callable from any thread. */
    public static void open(String currentStatus, Runnable onSaved) {
        FxThread.start();
        FxThread.run(() -> new PhoneLinkDialog().show(currentStatus, onSaved));
    }

    static VBox foot(Label status, KeyLegend legend) {
        var foot = new VBox(8, status, legend.bar());
        foot.getStyleClass().add("vt-foot");
        return foot;
    }

    void show(String currentStatus, Runnable onSaved) {
        var box = new TextArea();
        box.setPrefRowCount(6);
        box.setWrapText(true);
        box.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        var configured = LinkCredentials.load();
        var status = new Label(currentStatus + (configured.isPresent()
                ? "\nConfigured for " + configured.get().host + ". Paste a new block to replace it." : ""));
        status.setWrapText(true);
        Runnable save = () -> {
            var parsed = LinkCredentials.parse(box.getText());
            if (parsed.isEmpty()) {
                status.setText("Could not find REDIS_URL, or UPSTASH_REDIS_REST_URL with UPSTASH_REDIS_REST_TOKEN, in that text.");
                return;
            }
            try {
                parsed.get().save();
                onSaved.run();
                stage.close();
            } catch (IOException e) {
                status.setText("Could not save: " + e.getMessage());
            }
        };
        var legend = new KeyLegend()
                .key(KeyCode.F2, "Save and connect", save)
                .key(KeyCode.ESCAPE, "Close", () -> stage.close());
        var root = new BorderPane(box, new Label("Paste the Upstash connection block (the same one goes into the phone app):"),
                null, foot(status, legend), null);
        root.getStyleClass().add("vt-window");
        stage = FxWindows.show("Phone link", root, 620, 300, legend);
        stage.setAlwaysOnTop(true);
        box.requestFocus();
    }
}
