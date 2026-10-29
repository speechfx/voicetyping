package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javafx.scene.control.*;

/**
 * The Mac-only tray entries: "Start at login" and the update item. The update item checks the
 * public releases once shortly after start and again whenever clicked; when a newer release
 * exists it reads "Update to vX" and a click installs it (the installer restarts the app).
 * The tray menu is built on every open, so items() reads the current state each time.
 */
public final class MacTrayItems {

    final BiConsumer<String, String> notify;
    volatile String available;

    public MacTrayItems(BiConsumer<String, String> notify) {
        this.notify = notify;
        CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(() -> check(false));
    }

    public List<MenuItem> items() {
        var login = new CheckMenuItem("Start at login");
        login.setSelected(MacApp.startsAtLogin());
        login.setOnAction(e -> MacApp.startAtLogin(login.isSelected()));
        var label = available != null ? "Update to v" + available + "..." : "Check for updates (v" + MacApp.version() + ")";
        return List.of(login, TrayMenu.item(label, this::clicked));
    }

    void clicked() {
        if (available != null) {
            notify.accept("Voice Typing", "Installing v" + available + " - the app will restart");
            MacApp.update();
        } else {
            CompletableFuture.runAsync(() -> check(true));
        }
    }

    void check(boolean tellIfCurrent) {
        var newer = MacApp.newerRelease();
        if (newer.isPresent()) {
            available = newer.get();
            notify.accept("Voice Typing", "Version " + available + " is available - see the tray menu");
        } else if (tellIfCurrent) {
            notify.accept("Voice Typing", "You have the latest version (v" + MacApp.version() + ")");
        }
    }
}
