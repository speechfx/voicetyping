package speechfx.voicetyping;

import java.util.List;
import java.util.function.Supplier;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.scene.robot.Robot;
import javafx.stage.*;

/**
 * The tray's menu, in JavaFX. JavaFX has no tray icon of its own, so the icon stays an AWT TrayIcon, which only
 * holds the image, the tooltip and the notifications; a right click (a click on the Mac) opens this menu at the
 * mouse instead of an AWT popup. It is built fresh each time, so microphones, provider and phone-link state are
 * always current. A ContextMenu needs an owner window: an invisible 1x1 utility stage (no taskbar entry) at the
 * mouse, brought to the front so that a click anywhere else closes the menu, as a native tray menu does.
 */
public final class TrayMenu {

    final Supplier<List<MenuItem>> items;
    Stage owner;
    ContextMenu showing;

    public TrayMenu(Supplier<List<MenuItem>> items) { this.items = items; }

    /** Opens at the mouse pointer. Any thread. */
    public void show() {
        FxThread.start();
        FxThread.run(() -> {
            if (showing != null) showing.hide();
            var robot = new Robot();
            double x = robot.getMouseX(), y = robot.getMouseY();
            if (owner == null) {
                owner = new Stage(StageStyle.UTILITY);
                owner.setOpacity(0);
                owner.setAlwaysOnTop(true);
                owner.setScene(new Scene(new Pane(), 1, 1));
            }
            owner.setX(x);
            owner.setY(y);
            owner.show();
            owner.toFront();
            owner.requestFocus();
            var menu = new ContextMenu();
            menu.getItems().setAll(items.get());
            menu.getItems().forEach(TrayMenu::plainLabels);
            // hiding the owner inside the menu's own hide would hide the menu a second time, mid-hide, and throw
            menu.setOnHidden(e -> javafx.application.Platform.runLater(() -> {
                if (showing == menu) { showing = null; owner.hide(); }
            }));
            showing = menu;
            menu.show(owner, x, y);
        });
    }

    /** No mnemonics: "Speech Keys (Deepgram, NVIDIA)..." must not turn its letters into shortcuts. */
    static void plainLabels(MenuItem item) {
        item.setMnemonicParsing(false);
        if (item instanceof Menu m) m.getItems().forEach(TrayMenu::plainLabels);
    }

    /** A menu item whose action runs on the AWT event thread, where the tray's actions always ran. */
    public static MenuItem item(String label, Runnable action) {
        var item = new MenuItem(label);
        item.setOnAction(e -> java.awt.EventQueue.invokeLater(action));
        return item;
    }
}
