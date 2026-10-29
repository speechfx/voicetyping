package speechfx.voicetyping;

import speechfx.dictation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;

/**
 * The speech service (Deepgram Flux, Deepgram or NVIDIA) and its account, in one menu shared by the tray, the dictation window
 * (F4) and the on-spot bar. A choice is saved and applied at once: a dictation in progress switches to a fresh
 * connection on the new choice, its audio parked meanwhile, so nothing said is lost. "Automatic" rotates among
 * the provider's usable keys, as before; a frozen key is shown but cannot be picked.
 */
public final class SpeechMenu {

    static final String[][] SERVICES = {{"deepgram-flux", "Deepgram Flux (English, word by word)"}, {"deepgram-streaming", "Deepgram"}, {"nvidia-streaming", "NVIDIA"}};
    static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** Told after every choice; set once by VoiceTyping, which switches whatever is running. */
    static volatile Runnable onChange = () -> {};

    /** Opens under the anchor (a toolbar or bar button). FX thread. */
    public static ContextMenu show(Node anchor) {
        var menu = new ContextMenu();
        menu.getItems().setAll(items());
        menu.show(anchor, Side.BOTTOM, 0, 0);
        return menu;
    }

    public static List<MenuItem> items() {
        var items = new ArrayList<MenuItem>();
        var provider = provider();
        var services = new ToggleGroup();
        for (var s : SERVICES) items.add(radio(s[1], services, s[0].equals(provider), () -> DictationSettings.setProvider(s[0])));
        items.add(new SeparatorMenuItem());
        var heading = new MenuItem(name(provider) + " account");
        heading.setDisable(true);
        items.add(heading);
        var keyProvider = SpeechChain.keyProvider(provider);
        var pinned = DictationSettings.getSpeechSettings().pinned(keyProvider);
        var accounts = new ToggleGroup();
        items.add(radio("Automatic (rotate among keys)", accounts, pinned.isEmpty(), () -> DictationSettings.setSpeechKey(keyProvider, "")));
        for (var k : SpeechKeys.all(keyProvider)) {
            var frozen = SpeechKeys.frozenUntil(k);
            var label = account(k) + (frozen == null ? "" : "   (frozen until " + HHMM.format(frozen) + ")");
            var item = radio(label, accounts, k.id().equals(pinned), () -> DictationSettings.setSpeechKey(keyProvider, k.id()));
            item.setDisable(frozen != null);
            items.add(item);
        }
        return items;
    }

    static RadioMenuItem radio(String label, ToggleGroup group, boolean selected, Runnable save) {
        var item = new RadioMenuItem(label);
        item.setMnemonicParsing(false);
        item.setToggleGroup(group);
        item.setSelected(selected);
        item.setOnAction(e -> { save.run(); onChange.run(); });
        return item;
    }

    /** The streaming provider in use: the tray's choice, or Deepgram if that is not a streaming one. */
    static String provider() {
        var p = DictationSettings.getProvider();
        return Arrays.stream(SERVICES).anyMatch(s -> s[0].equals(p)) ? p : SERVICES[0][0];
    }

    static String name(String provider) {
        return Arrays.stream(SERVICES).filter(s -> s[0].equals(provider)).map(s -> s[1]).findFirst().orElse(provider);
    }

    static String account(SpeechKey k) {
        return (k.account() == null || k.account().isBlank() ? "(no account name)" : k.account()) + "  #" + k.id();
    }

    /** "Deepgram, automatic" or "NVIDIA, someone@example.org #1a2b3c4d". */
    public static String describe() {
        var provider = provider();
        var keyProvider = SpeechChain.keyProvider(provider);
        var pinned = DictationSettings.getSpeechSettings().pinned(keyProvider);
        var key = SpeechKeys.all(keyProvider).stream().filter(k -> k.id().equals(pinned)).findFirst();
        return name(provider) + ", " + key.map(SpeechMenu::account).orElse("automatic");
    }

    /** "DG" or "NV", for the bar's button. */
    public static String shortName() {
        var p = provider();
        return p.equals("deepgram-flux") ? "DF" : p.startsWith("deepgram") ? "DG" : "NV";
    }

    private SpeechMenu() {}
}
