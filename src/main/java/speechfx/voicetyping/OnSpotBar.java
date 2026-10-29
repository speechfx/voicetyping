package speechfx.voicetyping;

import speechfx.dictation.*;

import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import prosemirrorfx.view.ToolbarIcons;

/**
 * The small floating bar of an on-spot dictation: what it is typing into, the words still being recognised, and
 * icon buttons for the microphone, the speech service and account, Pause, Stop, carry on in the long dictation, and Copy. Every
 * button and the status line explain themselves on hover, so the bar stays small. It must never take the focus
 * from the app being typed into: it is a Floating window (no taskbar entry, cannot be closed, shown and hidden
 * through Win32) and its buttons take no focus. Dragged anywhere, it stays where it was put.
 */
final class OnSpotBar {

    static final String TITLE = "Voice Typing on-spot " + ProcessHandle.current().pid();
    static final double W = 540, H = 62;
    static final Map<String, String> ICONS = ToolbarIcons.load(OnSpotBar.class, "app-icons.tsv");

    final Runnable onPause, onStop, onCopy, onLong, onMenuClosed;
    final Consumer<String> onMic;
    Stage stage;
    Label status, interim;
    Button pause, stop, copy, long_, speech, mic;
    Floating floating;
    double dragX, dragY;

    OnSpotBar(Runnable onPause, Runnable onStop, Runnable onCopy, Runnable onLong, Runnable onMenuClosed, Consumer<String> onMic) {
        this.onMic = onMic;
        this.onPause = onPause;
        this.onStop = onStop;
        this.onCopy = onCopy;
        this.onLong = onLong;
        this.onMenuClosed = onMenuClosed;
        FxThread.start();
        FxThread.run(this::build);
    }

    void build() {
        status = new Label();
        status.getStyleClass().add("vt-dictation-state");
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        status.setTooltip(new Tooltip());
        interim = new Label();
        interim.getStyleClass().add("vt-onspot-interim");
        interim.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        interim.setMaxWidth(Double.MAX_VALUE);
        pause = button("pause", "Pause", onPause);
        stop = button("stop", "Stop (" + GlobalHotkey.ON_SPOT.shown + ")", onStop);
        long_ = button("open_in_new", "Stop typing here and carry on in the long dictation window, with all the text ("
                + GlobalHotkey.DICTATION_WINDOW.shown + ")", onLong);
        copy = button("content_copy", "Copy the text still waiting to be typed, or all of this dictation", onCopy);
        speech = button("record_voice_over", "", this::speechMenu);
        speech.getTooltip().setOnShowing(e -> speech.getTooltip().setText(
                "Speech: " + SpeechMenu.describe() + ". Click to change, even while recording"));
        mic = button("mic", "", this::micMenu);
        mic.getTooltip().setOnShowing(e -> mic.getTooltip().setText("Microphone: "
                + MicMenu.label(Microphones.inUse(), BluetoothBattery.levels()) + ". Click to change, even while recording"));
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(status, Priority.SOMETIMES);
        var top = new HBox(6, status, spacer, mic, speech, pause, stop, long_, copy);
        top.setAlignment(Pos.CENTER_LEFT);
        var root = new VBox(3, top, interim);
        root.getStyleClass().add("vt-onspot");
        root.setOnMousePressed(e -> { dragX = e.getScreenX() - stage.getX(); dragY = e.getScreenY() - stage.getY(); });
        root.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - dragX); stage.setY(e.getScreenY() - dragY); });
        root.setOnMouseReleased(e -> { if (e.isStillSincePress()) return; VoiceTypingSettings.setOnSpotBar(stage.getX(), stage.getY()); });
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle(TITLE);
        stage.setAlwaysOnTop(true);
        stage.setScene(Styles.apply(new Scene(root, W, H)));
        place();
        floating = new Floating(stage);
    }

    static Button button(String icon, String tip, Runnable action) {
        var b = new Button();
        b.setGraphic(ToolbarIcons.draw(ICONS.get(icon)));
        b.setMnemonicParsing(false);
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Redrawn only when it changes: the bar refreshes five times a second. */
    static void icon(Button b, String icon, String tip) {
        if (!icon.equals(b.getUserData())) {
            b.setGraphic(ToolbarIcons.draw(ICONS.get(icon)));
            b.setUserData(icon);
        }
        b.getTooltip().setText(tip);
    }

    void micMenu() {
        var menu = MicMenu.show(mic, onMic);
        menu.setOnHidden(e -> onMenuClosed.run());
    }

    void speechMenu() {
        var menu = SpeechMenu.show(speech);
        menu.setOnHidden(e -> onMenuClosed.run());
    }

    /** Where it was last dragged to if that is still on a screen, otherwise bottom centre of the main screen. */
    void place() {
        var cfg = VoiceTypingSettings.getOnSpotSettings();
        if (cfg.barX() >= 0 && !Screen.getScreensForRectangle(cfg.barX(), cfg.barY(), W, H).isEmpty()) {
            stage.setX(cfg.barX());
            stage.setY(cfg.barY());
            return;
        }
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMinX() + (screen.getWidth() - W) / 2);
        stage.setY(screen.getMaxY() - H - 80);
    }

    boolean native_() { return floating != null && floating.native_(); }

    void show() {
        FxThread.run(() -> {
            place();
            floating.show();
        });
    }

    void hide() {
        FxThread.run(() -> floating.hide());
    }

    /** The bar for this moment of the session. FX thread. */
    void show(DictationState state, OnSpotDelivery d, String words, boolean finished) {
        var t = d.target;
        var name = t == null ? "" : t.name();
        var n = d.pendingWords();
        var held = n == 1 ? "1 word" : n + " words";
        String line, tone = "vt-wait";
        if (state == DictationState.ERROR) { line = "● Stopped by an error"; tone = "vt-bad"; }
        else if (t == null) line = "● Click into the app to type into";
        else if (d.gone) { line = "● " + name + " closed: " + held + " kept"; tone = "vt-bad"; }
        else if (n > 0 && d.away()) line = "● Holding " + held + " for " + name;
        else {
            line = switch (state) {
                case STARTING -> "● Connecting… " + name;
                case PAUSED -> "Paused: " + name;
                case SWITCHING, RECONNECTING -> "● Switching…";
                case FINISHING -> "● Finishing…";
                case STOPPED -> n > 0 ? "Done: " + held + " to type" : "Done";
                default -> "● " + name;
            };
            if (state == DictationState.LISTENING) tone = "vt-live";
        }
        var tip = new StringBuilder(line.replace("● ", ""));
        if (t != null) tip.append("\nTyping into: ").append(t.title.isEmpty() ? name : t.title).append(" (").append(name).append(")");
        if (n > 0) tip.append("\n").append(held).append(d.away() ? " held until you go back to " + name : " waiting to be typed");
        if (d.gone) tip.append("\nThe window was closed: use Copy to take the text");
        tip.append("\nSpeech: ").append(SpeechMenu.describe());
        status.getTooltip().setText(tip.toString());
        status.setText(line);
        status.getStyleClass().removeAll("vt-live", "vt-wait", "vt-bad");
        status.getStyleClass().add(tone);
        interim.setText(state == DictationState.PAUSED ? "" : words);
        speech.setText(SpeechMenu.shortName());
        var paused = state == DictationState.PAUSED;
        icon(pause, paused ? "play_arrow" : "pause", paused ? "Resume" : "Pause");
        icon(stop, finished ? "close" : "stop", finished ? "Close" : "Stop (" + GlobalHotkey.ON_SPOT.shown + ")");
        pause.setDisable(finished);
        long_.setDisable(finished);
    }
}
