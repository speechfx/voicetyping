package speechfx.voicetyping;

import speechfx.dictation.*;

import java.util.*;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Edits the ordered list of preferred microphones. Recording uses the first one in the list
 * that is connected, else the Windows default.
 *
 * Keys are F-keys with a clickable legend, same as the dictation window: arrows move the
 * selection, F2 adds or removes the selected device from the preferred list, F3/F4 move it
 * up/down, Enter saves, Esc cancels.
 */
public final class MicOrderDialog {

    final RowTable list = new RowTable(new String[] {"Order", "Microphone", "Battery", "Status"}, 50, 380, 70, 100);
    final List<String> order = Microphones.preferred();
    final List<String> connected = Microphones.connected();
    final Map<String, Integer> levels = BluetoothBattery.levels();
    Stage stage;

    /** Callable from any thread. Device and battery are read first, off the FX thread. */
    public static void open() {
        FxThread.start();
        var d = new MicOrderDialog();
        FxThread.run(d::show);
    }

    void show() {
        var legend = new KeyLegend()
                .key(KeyCode.F2, "Prefer / remove", this::toggle)
                .key(KeyCode.F3, "Move up", () -> move(-1))
                .key(KeyCode.F4, "Move down", () -> move(1))
                .key(KeyCode.ENTER, "Save", this::save)
                .key(KeyCode.ESCAPE, "Cancel", () -> stage.close());
        var root = new BorderPane(list.view, new Label("Recording uses the first connected device in the numbered list, else the Windows default."),
                null, legend.bar(), null);
        root.getStyleClass().add("vt-window");
        rebuild(rows().isEmpty() ? null : rows().getFirst());
        stage = FxWindows.show("Voice Typing — Microphones", root, 720, 420, legend);
        list.view.requestFocus();
    }

    List<String> rows() {
        var rows = new ArrayList<>(order);
        connected.stream().filter(n -> !order.contains(n)).forEach(rows::add);
        return rows;
    }

    void rebuild(String select) {
        var rows = rows();
        list.fill(rows.stream().map(this::row).toList(), select == null ? 0 : rows.indexOf(select));
    }

    List<String> row(String name) {
        var rank = order.indexOf(name);
        var battery = BluetoothBattery.forMicrophone(name, levels);
        return List.of(rank >= 0 ? String.valueOf(rank + 1) : "", name, battery == null ? "" : battery + "%",
                connected.contains(name) ? "connected" : "not connected");
    }

    String selected() {
        var i = list.selected();
        return i < 0 ? null : rows().get(i);
    }

    void toggle() {
        var name = selected();
        if (name == null) return;
        if (!order.remove(name)) order.add(name);
        rebuild(name);
    }

    void move(int by) {
        var name = selected();
        var i = order.indexOf(name);
        if (i < 0 || i + by < 0 || i + by >= order.size()) return;
        order.remove(i);
        order.add(i + by, name);
        rebuild(name);
    }

    void save() {
        DictationSettings.setPreferredMicrophones(order);
        stage.close();
    }
}
