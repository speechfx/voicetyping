package speechfx.voicetyping;

import speechfx.dictation.*;

import javafx.scene.*;
import javafx.stage.Stage;

/** Opens an app window the same way every time: shared stylesheet, app icon, centred, brought to the front. */
public final class FxWindows {

    /** Call on the FX thread. The legend's keys are installed on the scene. */
    public static Stage show(String title, Parent root, double width, double height, KeyLegend legend) {
        var stage = new Stage();
        var scene = Styles.apply(new Scene(root, width, height));
        if (legend != null) legend.install(scene);
        stage.setTitle(title);
        stage.setScene(scene);
        Icons.applyTo(stage);
        stage.show();
        stage.toFront();
        stage.requestFocus();
        return stage;
    }

    private FxWindows() {}
}
