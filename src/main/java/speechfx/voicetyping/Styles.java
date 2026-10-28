package speechfx.voicetyping;

import javafx.scene.Scene;

/** Puts the shared stylesheet (vt.css: layout only, JavaFX's default look) on a scene. */
public final class Styles {

    static final String CSS = Styles.class.getResource("vt.css").toExternalForm();

    public static Scene apply(Scene scene) {
        scene.getStylesheets().add(CSS);
        return scene;
    }

    private Styles() {}
}
