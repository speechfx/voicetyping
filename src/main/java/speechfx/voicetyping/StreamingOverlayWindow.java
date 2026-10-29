package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;

/**
 * Always-on-top overlay showing the live streaming transcript while a short recording runs. It must never take
 * the focus from the window being dictated into, because the result is pasted there. It is a Floating window
 * (non-activating, no taskbar entry, cannot be closed). Text is updated in memory only: no Robot, no clipboard, no key events.
 * Elsewhere (Mac) it falls back to Stage.show/hide, to be checked on the Mac.
 */
public class StreamingOverlayWindow {

    static final String TITLE = "Voice Typing overlay " + ProcessHandle.current().pid();

    Stage stage;
    TextArea text;
    Label status;
    Floating floating;

    public StreamingOverlayWindow() {
        FxThread.start();
        FxThread.run(this::build);
    }

    void build() {
        status = new Label("● Connecting…");
        status.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        text = new TextArea();
        text.setEditable(false);
        text.setFocusTraversable(false);
        text.setWrapText(true);
        text.getStyleClass().add("sfx-overlay-text");
        var keys = new Label(GlobalHotkey.DICTATION_WINDOW.shown + "  long dictation");
        keys.setStyle("-fx-text-fill: #78C8FF; -fx-font-size: 11px;");
        var root = new BorderPane(text, status, null, keys, null);
        BorderPane.setMargin(text, new Insets(6, 0, 6, 0));
        root.getStyleClass().add("sfx-overlay");
        var screen = Screen.getPrimary().getVisualBounds();
        var w = Math.min(800, screen.getWidth() * 0.6);
        var h = 215.0;
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle(TITLE);
        stage.setAlwaysOnTop(true);
        stage.setScene(Styles.apply(new Scene(root, w, h)));
        stage.setX(screen.getMinX() + (screen.getWidth() - w) / 2);
        stage.setY(screen.getMaxY() - h - 20);
        floating = new Floating(stage);
    }

    boolean native_() { return floating != null && floating.native_(); }

    /** Make the overlay visible and reset content. */
    public void showOverlay() {
        FxThread.run(() -> {
            if (stage == null) return;
            text.clear();
            setStatusNow("● Connecting…", Color.rgb(255, 165, 0));
            floating.show();
        });
    }

    /** Replace the displayed text with the latest transcript (called on every streaming update). */
    public void updateText(String s) {
        FxThread.run(() -> {
            if (text == null) return;
            text.setText(s);
            text.setScrollTop(Double.MAX_VALUE);
        });
    }

    public void setStatus(String s, java.awt.Color c) {
        FxThread.run(() -> setStatusNow(s, Color.rgb(c.getRed(), c.getGreen(), c.getBlue())));
    }

    void setStatusNow(String s, Color c) {
        if (status == null) return;
        status.setText(s);
        status.setTextFill(c);
    }

    /** Hide the overlay. */
    public void hideOverlay() {
        FxThread.run(() -> {
            if (stage == null) return;
            floating.hide();
        });
    }
}
