package speechfx.voicetyping;

import speechfx.dictation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Lists every enabled Deepgram and NVIDIA key with its state, and freezes or unfreezes the
 * selected one. A frozen key is skipped until its time is up, then used again by itself.
 * Keys are added or switched off for good in ~/speechfx/keys/&lt;provider&gt;_keys.xml.
 *
 * F2/F3/F4 freeze for 1 hour / 1 day / 7 days, F5 unfreezes, Esc closes. Changes apply at once.
 */
public final class SpeechKeysDialog {

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneId.systemDefault());

    final RowTable list = new RowTable(new String[] {"Provider", "Account", "Key", "Status"}, 90, 280, 90, 420);
    final List<SpeechKey> keys = new ArrayList<>();
    Stage stage;

    /** Callable from any thread. */
    public static void open() {
        FxThread.start();
        FxThread.run(() -> new SpeechKeysDialog().show());
    }

    void show() {
        var legend = new KeyLegend()
                .key(KeyCode.F2, "Freeze 1 hour", () -> freeze(Duration.ofHours(1)))
                .key(KeyCode.F3, "Freeze 1 day", () -> freeze(Duration.ofDays(1)))
                .key(KeyCode.F4, "Freeze 7 days", () -> freeze(Duration.ofDays(7)))
                .key(KeyCode.F5, "Unfreeze", this::unfreeze)
                .key(KeyCode.ESCAPE, "Close", () -> stage.close());
        var root = new BorderPane(list.view, new Label("A frozen key is skipped until the time shown, then comes back by itself. "
                + "Add or remove keys in ~/speechfx/keys/<provider>_keys.xml."), null, legend.bar(), null);
        root.getStyleClass().add("vt-window");
        reload(0);
        stage = FxWindows.show("Voice Typing — Speech keys", root, 900, 420, legend);
        list.view.requestFocus();
    }

    void reload(int select) {
        keys.clear();
        SpeechKeys.PROVIDERS.forEach(p -> keys.addAll(SpeechKeys.all(p)));
        list.fill(keys.stream().map(SpeechKeysDialog::row).toList(), select);
    }

    static List<String> row(SpeechKey k) {
        var f = SpeechKeys.freezeOf(k);
        var state = f == null ? "ready" : "frozen until " + WHEN.format(Instant.parse(f.until())) + "  (" + f.reason() + ")";
        return List.of(k.provider(), k.account(), "#" + k.id(), state);
    }

    void freeze(Duration d) {
        var i = list.selected();
        if (i < 0) return;
        SpeechKeys.freeze(keys.get(i), d, "frozen by hand");
        reload(i);
    }

    void unfreeze() {
        var i = list.selected();
        if (i < 0) return;
        SpeechKeys.unfreeze(keys.get(i));
        reload(i);
    }
}
