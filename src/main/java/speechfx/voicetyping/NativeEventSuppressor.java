package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.dispatcher.VoidDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Stops a matched hotkey from also reaching Windows.
 *
 * Windows binds Win+. to the emoji picker, so without this every recording toggle would also drop
 * that panel over whatever the user was typing into. JNativeHook has no public API for consuming an
 * event; the documented route is setting the protected `reserved` field to 0x01, which the native
 * hook reads on the way back out.
 *
 * {@link #installSynchronousDispatch()} is not optional and must be called before the hook is
 * registered. JNativeHook's default dispatcher hands events to a separate thread, so the native
 * hook has already returned by the time a listener sets `reserved` — the flag is then read from an
 * event nobody is waiting on. Measured: the log said "suppressed" on every press and the emoji
 * picker opened regardless. VoidDispatchService runs listeners inline on the hook thread instead,
 * which is what makes the flag arrive in time.
 *
 * The cost of that is real and constrains every listener: they now run ON the hook thread, so
 * anything slow there delays the keystroke reaching the foreground app. Match, then hand off to
 * another thread; never do work in the listener itself.
 */
public final class NativeEventSuppressor {

    private static final Logger log = LoggerFactory.getLogger(NativeEventSuppressor.class);
    private static final Field RESERVED = reservedField();
    private static volatile boolean warned;

    private NativeEventSuppressor() {}

    static Field reservedField() {
        try {
            var f = NativeInputEvent.class.getDeclaredField("reserved");
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean available() {
        return RESERVED != null;
    }

    /** Call before {@code GlobalScreen.registerNativeHook()}, or nothing below has any effect. */
    public static void installSynchronousDispatch() {
        GlobalScreen.setEventDispatcher(new VoidDispatchService());
    }

    public static void consume(NativeInputEvent e) {
        if (RESERVED == null) {
            if (!warned) { warned = true; log.warn("cannot suppress hotkeys: jnativehook reserved field unavailable"); }
            return;
        }
        try {
            RESERVED.setShort(e, (short) 0x01);
        } catch (Exception ex) {
            if (!warned) { warned = true; log.warn("could not suppress hotkey: {}", ex.toString()); }
        }
    }
}
