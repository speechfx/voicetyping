package speechfx.voicetyping;

import speechfx.dictation.*;

import java.util.*;
import java.util.function.Consumer;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;

/**
 * The microphone list, for the dictation window's F8 popup and the tray's Microphone submenu: every connected
 * device, the one in use ticked, battery shown where the headset reports it. Arrows and Enter pick, Esc closes.
 * Picking a device puts it first in the preferred order. Call on the FX thread; the device list is read here,
 * which takes a moment.
 */
public final class MicMenu {

    /** Drops down under the anchor (the toolbar's Mic button). */
    public static ContextMenu show(Node anchor, Consumer<String> picked) {
        var menu = new ContextMenu();
        menu.getItems().setAll(items(picked));
        menu.show(anchor, Side.BOTTOM, 0, 0);
        return menu;
    }

    public static List<MenuItem> items(Consumer<String> picked) {
        var current = Microphones.inUse();
        var levels = BluetoothBattery.levels();
        var items = new ArrayList<MenuItem>();
        for (var name : Microphones.connected()) {
            var item = new CheckMenuItem(label(name, levels));
            item.setMnemonicParsing(false);
            item.setSelected(name.equals(current));
            item.setOnAction(e -> picked.accept(name));
            items.add(item);
        }
        items.add(new SeparatorMenuItem());
        var order = new MenuItem("Preferred order...");
        order.setMnemonicParsing(false);
        order.setOnAction(e -> MicOrderDialog.open());
        items.add(order);
        return items;
    }

    public static String label(String name, Map<String, Integer> levels) {
        var battery = BluetoothBattery.forMicrophone(name, levels);
        return battery == null ? name : name + "   (battery " + battery + "%)";
    }

    private MicMenu() {}
}
