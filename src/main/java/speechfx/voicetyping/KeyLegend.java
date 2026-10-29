package speechfx.voicetyping;

import speechfx.dictation.*;

import java.util.*;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;

/**
 * A window's shortcuts, declared once: the always-visible clickable strip along the bottom AND the key
 * bindings both come from the same entries, so a key is never advertised without being bound. Keys are
 * plain (no modifier) and caught by a scene filter, so they work wherever the focus is. Use F-keys, Enter
 * and Esc in any window that has a text field; letters only where nothing can be typed.
 */
public final class KeyLegend {

    record Entry(KeyCode code, String label, Runnable run) {}

    final List<Entry> entries = new ArrayList<>();

    public KeyLegend key(KeyCode code, String label, Runnable run) {
        entries.add(new Entry(code, label, run));
        return this;
    }

    static String shown(KeyCode code) {
        return switch (code) {
            case ESCAPE -> "Esc";
            case ENTER -> "Enter";
            case DELETE -> "Del";
            default -> code.getName();
        };
    }

    public HBox bar() {
        var bar = new HBox();
        bar.getStyleClass().add("vt-legend");
        for (var e : entries) {
            var b = new Button(shown(e.code()) + "  " + e.label());
            b.setMnemonicParsing(false);
            b.setFocusTraversable(false);
            b.setOnAction(x -> e.run().run());
            bar.getChildren().add(b);
        }
        return bar;
    }

    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, k -> {
            if (k.isControlDown() || k.isAltDown() || k.isMetaDown() || k.isShiftDown()) return;
            for (var e : entries) {
                if (e.code() == k.getCode()) {
                    k.consume();
                    e.run().run();
                    return;
                }
            }
        });
    }
}
