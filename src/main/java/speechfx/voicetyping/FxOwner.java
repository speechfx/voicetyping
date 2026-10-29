package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.*;

/**
 * One invisible utility stage that owns the floating windows. Windows never gives an owned window a taskbar entry,
 * so the bar and the overlay have none even in the moment before they are made non-activating; without an owner,
 * each start-up left a blank, flashing taskbar entry that outlived its window. Being a utility stage, the owner
 * itself has no taskbar entry either.
 */
final class FxOwner {

    static Stage owner;

    /** FX thread. */
    static Stage get() {
        if (owner == null) {
            owner = new Stage(StageStyle.UTILITY);
            owner.setOpacity(0);
            owner.setScene(new Scene(new Pane(), 1, 1));
            owner.setX(-32000);
            owner.setY(-32000);
            owner.setOnCloseRequest(e -> e.consume());
            owner.show();
        }
        return owner;
    }

    private FxOwner() {}
}
