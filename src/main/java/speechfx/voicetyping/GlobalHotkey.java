package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import static com.github.kwhat.jnativehook.NativeInputEvent.*;

/**
 * The system-wide keys. Win+Y opens the dictation window; everything else there - start, pause, finish, retry -
 * happens on that window's own F-key legend (see DictationAction), because those keys only need to work while it
 * has focus. Win+J starts and stops dictating into the app in front; its Pause and Copy are buttons on the
 * floating bar. A whole family of global Win+. combinations used to live here; keeping to two removes both the
 * memorising and the chording the user's hands cannot do repeatedly.
 *
 * Windows 11 binds nothing to Win+Y, and Win+J only to "focus a Windows tip", so neither opens a system panel.
 * Both are consumed anyway (NativeEventSuppressor), since the same dispatch path is shared.
 *
 * The &lt;hotkey&gt; and &lt;on-spot-hotkey&gt; settings override the defaults per machine, e.g. "Ctrl+Z" on a Mac,
 * where undo is Cmd+Z and Ctrl+Z is free outside a terminal. Modifiers must match exactly. Read once at start.
 */
public enum GlobalHotkey {

    // Mac: Cmd+Y is Safari's History and Cmd+Shift+Y the system "Make Sticky"; Ctrl+Option is free
    DICTATION_WINDOW(VoiceTypingSettings.getHotkey(), Platform.MAC ? "Ctrl+Option+Y" : "Win+Y"),
    ON_SPOT(VoiceTypingSettings.getOnSpotHotkey(), Platform.MAC ? "Ctrl+Option+J" : "Win+J");

    final int key;
    final int modifiers;
    public final String shown;

    GlobalHotkey(String configured, String fallback) {
        var parsed = configured == null ? null : parse(configured);
        if (configured != null && parsed == null) {
            LoggerFactory.getLogger(GlobalHotkey.class).warn("hotkey setting '{}' not understood; using {}", configured, fallback);
        }
        if (parsed == null) parsed = parse(fallback);
        this.shown = (String) parsed[0];
        this.key = (int) parsed[1];
        this.modifiers = (int) parsed[2];
    }

    /** "Ctrl+Option+Y" -> {shown, key code, modifier mask}, or null when it cannot be read. */
    private static Object[] parse(String text) {
        var parts = text.strip().split("\\s*\\+\\s*");
        if (parts.length < 2) return null;
        var mods = 0;
        var shown = new StringBuilder();
        for (var i = 0; i < parts.length - 1; i++) {
            var name = switch (parts[i].toLowerCase(Locale.ROOT)) {
                case "ctrl", "control" -> { mods |= CTRL_MASK; yield "Ctrl"; }
                case "alt", "option", "opt" -> { mods |= ALT_MASK; yield Platform.MAC ? "Option" : "Alt"; }
                case "cmd", "command", "win", "meta", "super" -> { mods |= META_MASK; yield Platform.MAC ? "Cmd" : "Win"; }
                case "shift" -> { mods |= SHIFT_MASK; yield "Shift"; }
                default -> null;
            };
            if (name == null) return null;
            shown.append(name).append('+');
        }
        var key = parts[parts.length - 1].toUpperCase(Locale.ROOT);
        try {
            var code = NativeKeyEvent.class.getField("VC_" + key).getInt(null);
            return new Object[]{shown + key, code, mods};
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static GlobalHotkey match(NativeKeyEvent e) {
        for (var k : values()) if (e.getKeyCode() == k.key && k.modifiersMatch(e.getModifiers())) return k;
        return null;
    }

    /** Each required modifier held (either side), and none of the others. */
    boolean modifiersMatch(int m) {
        for (var mask : new int[]{CTRL_MASK, ALT_MASK, META_MASK, SHIFT_MASK}) {
            if (((modifiers & mask) != 0) != ((m & mask) != 0)) return false;
        }
        return true;
    }
}
