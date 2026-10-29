package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.*;

/**
 * Recent transcripts on the left; the transcript, its log and its details in tabs on the right.
 * Enter pastes into the window you came from, F2 copies, F3 retries the transcription from the saved audio,
 * Del deletes, F4 clears all, F6 next tab, Esc hides. The window is built once and hidden, not closed.
 * Callable from any thread.
 */
public final class HistoryWindow implements TranscriptHistory.HistoryChangeListener {

    static final Logger log = LoggerFactory.getLogger(HistoryWindow.class);

    final TranscriptHistory history;
    final ClipboardPaster paster;
    Consumer<Path> retry = p -> {};
    Stage stage;
    ListView<HistoryItem> list;
    TabPane tabs;
    TextArea transcript, logs, details;
    Label status;

    public HistoryWindow(TranscriptHistory history, ClipboardPaster paster) {
        this.history = history;
        this.paster = paster;
        history.addListener(this);
    }

    public void setRetryCallback(Consumer<Path> retry) { this.retry = retry; }

    public void showWindow() {
        FxThread.start();
        FxThread.run(() -> {
            if (stage == null) build();
            reload();
            stage.show();
            stage.toFront();
            stage.requestFocus();
            if (!list.getItems().isEmpty()) list.getSelectionModel().select(0);
            list.requestFocus();
        });
    }

    public void dispose() { FxThread.run(() -> { if (stage != null) stage.close(); }); }

    @Override public void onHistoryChanged() { FxThread.run(() -> { if (stage != null) reload(); }); }

    void build() {
        list = new ListView<>();
        list.setCellFactory(v -> new HistoryCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> show(item));
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) paste(); });
        transcript = reader();
        logs = reader();
        logs.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        details = reader();
        tabs = new TabPane(new Tab("Transcript", transcript), new Tab("Logs", logs), new Tab("Details", details));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var split = new SplitPane(list, tabs);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.34);
        status = new Label("");
        status.getStyleClass().add("sfx-status");
        var legend = new KeyLegend()
                .key(KeyCode.ENTER, "Paste", this::paste)
                .key(KeyCode.F2, "Copy", this::copy)
                .key(KeyCode.F3, "Retry", this::retry)
                .key(KeyCode.DELETE, "Delete", this::delete)
                .key(KeyCode.F4, "Clear all", this::clear)
                .key(KeyCode.F6, "Next tab", () -> tabs.getSelectionModel().select((tabs.getSelectionModel().getSelectedIndex() + 1) % tabs.getTabs().size()))
                .key(KeyCode.ESCAPE, "Close", () -> stage.hide());
        var foot = new VBox(4, legend.bar(), status);
        foot.getStyleClass().add("sfx-foot");
        var root = new BorderPane(split, null, null, foot, null);
        root.getStyleClass().add("sfx-window");
        stage = FxWindows.show("Voice Typing — History", root, 820, 560, legend);
        stage.hide();
    }

    static TextArea reader() {
        var a = new TextArea();
        a.setEditable(false);
        a.setWrapText(true);
        return a;
    }

    void reload() {
        var keep = list.getSelectionModel().getSelectedItem();
        list.setItems(FXCollections.observableArrayList(history.getAll()));
        if (keep != null && list.getItems().contains(keep)) list.getSelectionModel().select(keep);
        count();
    }

    void show(HistoryItem item) {
        transcript.setText(item == null ? "" : item.text());
        logs.setText(item == null ? "" : HistoryFiles.logText(item));
        details.setText(item == null ? "" : HistoryFiles.details(item));
    }

    HistoryItem selected() { return list.getSelectionModel().getSelectedItem(); }

    void count() {
        var n = history.size();
        status.setText(n + " item" + (n == 1 ? "" : "s") + " in history");
    }

    void flash(String message) {
        status.setText(message);
        var back = new PauseTransition(Duration.seconds(3));
        back.setOnFinished(e -> count());
        back.play();
    }

    /** Hide first so the window you came from gets the focus back, then paste into it. */
    void paste() {
        var item = selected();
        if (item == null) return;
        stage.hide();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            paster.copyAndPaste(item.text());
        });
    }

    void copy() {
        var item = selected();
        if (item == null) return;
        var c = new ClipboardContent();
        c.putString(item.text());
        Clipboard.getSystemClipboard().setContent(c);
        flash("Copied to clipboard");
    }

    void retry() {
        var item = selected();
        if (item == null) return;
        var audio = HistoryFiles.audio(item);
        if (audio.isEmpty()) {
            Dialogs.message("Audio file not found", "The original audio for this transcript is gone, so it cannot be transcribed again.\n"
                    + "Expected: " + HistoryFiles.stamp(item) + "_audio.mp3", Dialogs.Kind.WARNING);
            return;
        }
        if (!Dialogs.confirm("Retry transcription", "Transcribe this recording again?\n" + audio.get().getFileName())) return;
        log.info("Retry from history for: {}", audio.get().getFileName());
        Thread.ofVirtual().start(() -> retry.accept(audio.get()));
        flash("Retrying " + audio.get().getFileName());
    }

    void delete() {
        var item = selected();
        if (item != null && Dialogs.confirm("Delete transcript", "Delete this transcript?")) history.delete(item.id());
    }

    void clear() {
        if (!Dialogs.confirm("Clear history", "Clear all history? This cannot be undone.\nIt also deletes the saved audio and log files.")) return;
        HistoryFiles.deleteAll();
        history.clear();
    }
}
