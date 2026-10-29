package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.util.*;
import java.util.function.*;
import javafx.animation.*;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import prosemirrorfx.core.editor.EditorModel;
import prosemirrorfx.markdown.Markdown;
import prosemirrorfx.view.*;

import static prosemirrorfx.core.schema.Build.*;

/**
 * The long dictation window: the prosemirrorfx editor with recognition running into it.
 *
 * Committed speech goes onto the end of the document as bullets; words still being recognised show grey after
 * it and are never part of the document. Top: the window's own toolbar (Record/Pause, Stop, Save | Folder, Mic,
 * Close), then the editor's toolbar (which has Copy all). Bottom: the status bar (state, mic, then the editor's
 * words and zoom) and a small clickable key legend. Keys and buttons come from one enum (DictationAction). Esc
 * closes, but only after the controls inside had their turn (the editor's find bar closes on Esc first).
 *
 * Built on the FX thread the first time it is shown, then hidden and reused. Every method is safe from any thread.
 */
public final class DictationWindow {

    Stage stage;
    DocView view;
    final MicStatusLabel[] mic = new MicStatusLabel[1];
    final Label status = new Label();
    final Map<DictationAction, Button> buttons = new EnumMap<>(DictationAction.class);
    final Map<DictationAction, Label> legendItems = new EnumMap<>(DictationAction.class);

    Consumer<DictationAction> onAction = a -> {};
    Runnable onClose = () -> {};
    Supplier<String> elapsedText = () -> "";
    volatile DictationState state = DictationState.IDLE;

    public void onAction(Consumer<DictationAction> c) { this.onAction = c; }
    public void onClose(Runnable r) { this.onClose = r; }
    public void elapsedText(Supplier<String> s) { this.elapsedText = s; }

    static final Map<String, String> ICONS = ToolbarIcons.load(DictationWindow.class, "app-icons.tsv");

    void build() {
        FxThread.start();
        view = new DocView(new EditorModel(doc(p())));
        mic[0] = new MicStatusLabel();
        status.getStyleClass().add("sfx-dictation-state");
        var right = new Region();
        HBox.setHgrow(right, Priority.ALWAYS);
        var bar = new HBox(4);
        bar.getStyleClass().add("sfx-dictation-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        for (var a : DictationAction.values()) {
            if (!a.onToolbar) continue;
            if (a == DictationAction.MICROPHONE) bar.getChildren().add(right);
            var b = new Button();
            b.setMnemonicParsing(false);
            b.setFocusTraversable(false);
            b.setOnAction(e -> act(a));
            buttons.put(a, b);
            bar.getChildren().add(b);
            if (a == DictationAction.SAVE) bar.getChildren().add(new Separator(Orientation.VERTICAL));
        }
        var legend = new HBox();
        legend.getStyleClass().add("sfx-legend");
        legend.setAlignment(Pos.CENTER_RIGHT);
        for (var a : DictationAction.values()) {
            var item = new Label(a.keyName() + " " + a.legendLabel());
            item.getStyleClass().add("sfx-legend-item");
            item.setOnMouseClicked(e -> { if (!item.isDisable()) act(a); });
            legendItems.put(a, item);
            legend.getChildren().add(item);
        }
        var statusBar = StatusBar.of(view, status, new Separator(Orientation.VERTICAL), mic[0], new Separator(Orientation.VERTICAL));
        var root = new BorderPane(view, new VBox(bar, Toolbar.of(view)), null, new VBox(statusBar, legend), null);
        root.getStyleClass().add("sfx-dictation");
        var scene = Styles.apply(new Scene(root, 900, 620));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::keys);
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) { e.consume(); onClose.run(); }
        });
        stage = new Stage();
        stage.setTitle("Voice Typing — Dictation");
        stage.setScene(scene);
        Icons.applyTo(stage);
        stage.setOnCloseRequest(e -> { e.consume(); onClose.run(); });
        var ticker = new Timeline(new KeyFrame(Duration.millis(500), e -> refreshStatus()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
        refresh();
    }

    /** A toolbar button or legend item: the same function as its key. */
    void act(DictationAction a) {
        if (!a.enabledIn(state)) return;
        if (a == DictationAction.CLOSE) onClose.run(); else onAction.accept(a);
        if (a != DictationAction.CLOSE && a != DictationAction.MICROPHONE && a != DictationAction.SPEECH) view.requestFocus();
    }

    /** F5-F9 with no modifier, Ctrl+S. Esc is left to the controls first (the find bar closes on it) and handled after. */
    void keys(KeyEvent e) {
        if (e.isAltDown() || e.isShiftDown() || e.isMetaDown()) return;
        for (var a : DictationAction.values()) {
            if (a == DictationAction.CLOSE || a.key != e.getCode() || a.ctrl != e.isControlDown()) continue;
            e.consume();
            if (a.enabledIn(state)) onAction.accept(a);
            return;
        }
    }
    void fx(Runnable r) {
        FxThread.start();
        FxThread.run(() -> {
            if (stage == null) build();
            r.run();
        });
    }

    public MicStatusLabel mic() { return mic[0]; }

    /** The toolbar button of an action, to open a popup (the F8 mic menu) under it. FX thread. */
    public Button button(DictationAction a) { return buttons.get(a); }

    public Stage stage() { return stage; }

    /** The document as Markdown: what is saved, copied and kept in history. */
    public String text() {
        FxThread.start();
        return FxThread.call(() -> view == null ? "" : Markdown.write(view.model().state().getDoc()));
    }

    public boolean isShowing() { return stage != null && FxThread.call(stage::isShowing); }

    public void show() {
        fx(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
            mic[0].refresh();
            view.requestFocus();
        });
    }

    public void hide() { fx(() -> stage.hide()); }

    /** Committed speech: onto the end of the document, sentences as bullets. */
    public void appendFinal(String text) {
        var postedAt = System.currentTimeMillis();
        fx(() -> {
            LagMeter.displayed("final text", postedAt);
            view.appendCommitted(text);
        });
    }

    /** Words still being recognised, grey after the end of the document. */
    public void setInterim(String text) {
        var postedAt = System.currentTimeMillis();
        fx(() -> {
            if (text != null && !text.isEmpty()) LagMeter.displayed("interim text", postedAt);
            view.setInterim(text);
        });
    }

    /** The session's ISO-8601 timestamp line, with a list for the speech starting under it. */
    public void appendTimestamp() {
        var stamp = "[" + java.time.OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "]";
        fx(() -> view.appendLine(stamp));
    }

    /** The whole document onto the clipboard, formatted (the editor's Copy all). */
    public void copyAll() { fx(() -> view.copyAll(CopyFormat.FORMATTED)); }

    public void setState(DictationState s) {
        state = s;
        fx(() -> {
            mic[0].refresh();
            refresh();
        });
    }

    void refresh() {
        buttons.forEach((a, b) -> {
            var icon = ToolbarIcons.draw(ICONS.get(a.icon(state)));
            if (icon != null && a == DictationAction.START_PAUSE && a.icon(state).equals("fiber_manual_record")) icon.getStyleClass().add("sfx-record");
            b.setGraphic(icon);
            b.setText(a.showsText() || icon == null ? a.label(state) : null);
            var tip = new Tooltip(a.tooltip(state));
            tip.setOnShowing(e -> tip.setText(a.tooltip(state)));
            b.setTooltip(tip);
            b.setDisable(!a.enabledIn(state));
        });
        legendItems.forEach((a, l) -> l.setDisable(!a.enabledIn(state)));
        refreshStatus();
    }

    void refreshStatus() {
        status.setText("● " + labelFor(state) + "   " + elapsedText.get());
        status.getStyleClass().removeAll("sfx-live", "sfx-wait", "sfx-bad");
        var kind = switch (state) {
            case LISTENING -> "sfx-live";
            case PAUSED, SWITCHING, RECONNECTING, FINISHING, STARTING -> "sfx-wait";
            case ERROR -> "sfx-bad";
            default -> null;
        };
        if (kind != null) status.getStyleClass().add(kind);
    }

    static String labelFor(DictationState s) {
        return switch (s) {
            case IDLE -> "Ready";
            case STARTING -> "Starting…";
            case LISTENING -> "Listening";
            case PAUSED -> "Paused — edit freely";
            case SWITCHING -> "Listening";
            case RECONNECTING -> "Reconnecting…";
            case FINISHING -> "Finishing…";
            case STOPPED -> "Finished";
            case ERROR -> "Error";
        };
    }
}
