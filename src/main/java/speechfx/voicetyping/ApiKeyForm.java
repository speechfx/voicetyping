package speechfx.voicetyping;

import speechfx.dictation.*;

import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.*;

/**
 * Add or edit one Gemini API key: name, key (hidden), enabled. F2 saves, Esc cancels; the fields take
 * typing, so the keys are F2 and Esc only. Waits for the answer; call on the FX thread.
 */
public final class ApiKeyForm {

    /** The filled-in key, or empty when cancelled. An empty key value is refused in the form itself. */
    public static Optional<ApiKey> ask(Window owner, String title, ApiKey start) {
        var name = new TextField(start == null ? "" : start.name());
        var key = new PasswordField();
        key.setText(start == null ? "" : start.value());
        var enabled = new CheckBox("Enabled");
        enabled.setMnemonicParsing(false);
        enabled.setSelected(start == null || start.isEnabled());
        var status = new Label("");
        status.getStyleClass().add("vt-status");
        var result = new ApiKey[1];
        var stage = new Stage();
        Runnable save = () -> {
            var value = key.getText().trim();
            if (value.isEmpty()) { status.setText("The API key cannot be empty."); return; }
            result[0] = new ApiKey(name.getText().trim(), value, enabled.isSelected() ? "y" : "n");
            stage.close();
        };
        var legend = new KeyLegend().key(KeyCode.F2, "Save", save).key(KeyCode.ESCAPE, "Cancel", stage::close);
        var grid = new GridPane(10, 10);
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("API key"), key);
        grid.addRow(2, new Label(""), enabled);
        GridPane.setHgrow(name, Priority.ALWAYS);
        name.setPrefColumnCount(30);
        var root = new VBox(10, grid, status, legend.bar());
        root.setPadding(new Insets(16, 16, 8, 16));
        root.getStyleClass().add("vt-form");
        var scene = Styles.apply(new javafx.scene.Scene(root));
        legend.install(scene);
        stage.setTitle(title);
        stage.setScene(scene);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setResizable(false);
        Icons.applyTo(stage);
        stage.setOnShown(e -> (start == null ? key : name).requestFocus());
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    private ApiKeyForm() {}
}
