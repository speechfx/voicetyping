package speechfx.voicetyping;

import speechfx.dictation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import javafx.animation.PauseTransition;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * A stage that floats over other apps and never takes the focus: the on-spot bar and the streaming overlay. On
 * Windows it is shown once, invisibly, and made non-activating (NoActivate); from then on it is shown and hidden
 * through Win32 only. Finding its native window can take seconds on a cold start, so adoption is retried until it
 * succeeds and the stage stays invisible meanwhile; it never falls back to an ordinary window, which would get a
 * taskbar entry, take the focus, and could be closed. It cannot be closed, and if its window is ever destroyed it
 * is rebuilt on the next show. Elsewhere (Mac) it is a plain stage.
 */
final class Floating {

    private static final Logger log = LoggerFactory.getLogger(Floating.class);

    final Stage stage;
    volatile MemorySegment hwnd = MemorySegment.NULL;
    boolean wanted, adopting;

    /** The stage needs its title and scene already, and must not have been shown. FX thread. */
    Floating(Stage stage) {
        this.stage = stage;
        if (Platform.WINDOWS) stage.initOwner(FxOwner.get());
        stage.setOnCloseRequest(e -> e.consume());
        if (Platform.WINDOWS) create();
    }

    boolean native_() { return hwnd.address() != 0; }

    void create() {
        hwnd = MemorySegment.NULL;
        stage.setOpacity(0);
        stage.show();
        if (!adopting) { adopting = true; adopt(0); }
    }

    void adopt(int attempt) {
        var h = NoActivate.adopt(stage.getTitle());
        if (h.address() != 0) {
            hwnd = h;
            adopting = false;
            if (wanted) NoActivate.show(h); else NoActivate.hide(h);
            stage.setOpacity(1);
            if (attempt > 0) log.info("{} made non-activating after {} tries", stage.getTitle(), attempt + 1);
            return;
        }
        if (attempt == 40) log.warn("{}: native window not found after 2 s, still trying", stage.getTitle());
        var later = new PauseTransition(Duration.millis(attempt < 40 ? 50 : 500));
        later.setOnFinished(e -> adopt(attempt + 1));
        later.play();
    }

    /** FX thread. */
    void show() {
        wanted = true;
        if (!Platform.WINDOWS) { stage.show(); return; }
        if (native_() && NoActivate.alive(hwnd)) NoActivate.show(hwnd);
        else if (!stage.isShowing()) create();
        else if (!adopting) {
            hwnd = MemorySegment.NULL;
            adopting = true;
            adopt(0);
        }
    }

    /** FX thread. */
    void hide() {
        wanted = false;
        if (!Platform.WINDOWS) stage.hide();
        else if (native_()) NoActivate.hide(hwnd);
    }
}
