package speechfx.voicetyping;

import speechfx.dictation.*;

import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import static luvjfx.Fx.*;

/**
 * Message, confirm and choice dialogs in JavaFX, replacing JOptionPane. Callable from any thread; the caller
 * waits for the answer. These windows hold no text field, so a bare letter is safe as a shortcut (the keyboard
 * guideline's one exception): each answer is a button wearing its key, Enter takes the first, Esc the last.
 */
public final class Dialogs {

    public enum Kind { INFO, WARNING, ERROR }

    /** One answer: the key that picks it and its label, e.g. new Answer("Y", "Yes"). */
    public record Answer(String key, String label) {}

    public static void message(String title, String text, Kind kind) {
        choose(title, text, kind, new Answer("Enter", "OK"));
    }

    /** True for Yes. Esc and closing the window are No. */
    public static boolean confirm(String title, String text) {
        return choose(title, text, Kind.WARNING, new Answer("Y", "Yes"), new Answer("N", "No")) == 0;
    }

    /** The index of the answer picked; closing the window or Esc picks the last one. */
    public static int choose(String title, String text, Kind kind, Answer... answers) {
        FxThread.start();
        return FxThread.call(() -> show(title, text, kind, answers));
    }

    static int show(String title, String text, Kind kind, Answer... answers) {
        var picked = new int[] {answers.length - 1};
        var stage = new Stage();
        var buttons = new ArrayList<Button>();
        for (var k = 0; k < answers.length; k++) {
            var index = k;
            var b = new Button(answers[k].key() + "  " + answers[k].label());
            b.setMnemonicParsing(false);
            b.setOnAction(e -> { picked[0] = index; stage.close(); });
            buttons.add(b);
        }
        buttons.getFirst().setDefaultButton(true);
        var body = label(text).wrapText(true).maxWidth(460).styleClass("vt-dialog-text");
        var row = hbox().spacing(8).alignment(Pos.CENTER_RIGHT).nodes(buttons.stream().map(b -> fx(b)).toArray(luvjfx.FxNodeOps[]::new));
        var root = borderPane().styleClass("vt-dialog")
                .center(body)
                .bottom(row);
        BorderPane.setMargin(row.node, new javafx.geometry.Insets(16, 0, 0, 0));
        var scene = new Scene(root.node);
        Styles.apply(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) return;
            if (e.getCode() == KeyCode.ESCAPE) { picked[0] = answers.length - 1; stage.close(); e.consume(); return; }
            for (var k = 0; k < answers.length; k++) {
                if (answers[k].key().length() == 1 && e.getCode().getName().equalsIgnoreCase(answers[k].key())) {
                    picked[0] = k;
                    stage.close();
                    e.consume();
                    return;
                }
            }
        });
        stage.setTitle(title);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        Icons.applyTo(stage);
        stage.setOnShown(e -> { stage.toFront(); stage.requestFocus(); buttons.getFirst().requestFocus(); });
        stage.showAndWait();
        return picked[0];
    }

    private Dialogs() {}
}
