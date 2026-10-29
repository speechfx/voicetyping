package speechfx.voicetyping;

import speechfx.dictation.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Windows only: synthetic input through SendInput. Text goes as KEYEVENTF_UNICODE events, which carry the
 * character itself rather than a key, so Devanagari, Tamil and accents arrive exactly as recognised whatever the
 * keyboard layout or IME. One SendInput call is atomic: the user's own keystrokes cannot land in the middle of it.
 */
final class Win32Input {

    static final int INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_UNICODE = 0x4, INPUT_SIZE = 40,
            VK_CONTROL = 0x11, VK_V = 0x56;
    /** Mouse buttons, Shift, Ctrl, Alt, both Win keys: typing while one is down would turn letters into shortcuts. */
    static final int[] HELD = {0x01, 0x02, 0x10, 0x11, 0x12, 0x5B, 0x5C};

    static final MethodHandle
            SEND = NoActivate.fn("SendInput", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
            KEY_STATE = NoActivate.fn("GetAsyncKeyState", FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));

    static boolean available() { return SEND != null; }

    /** Types the text into whatever window is in front. True if Windows took every event. */
    static boolean type(String s) {
        try (var a = Arena.ofConfined()) {
            var in = a.allocate((long) INPUT_SIZE * s.length() * 2);
            for (var i = 0; i < s.length(); i++) {
                key(in, 2 * i, 0, s.charAt(i), KEYEVENTF_UNICODE);
                key(in, 2 * i + 1, 0, s.charAt(i), KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
            }
            return send(in, s.length() * 2);
        }
    }

    /** Ctrl+V into whatever window is in front. */
    static boolean ctrlV() {
        try (var a = Arena.ofConfined()) {
            var in = a.allocate(INPUT_SIZE * 4L);
            key(in, 0, VK_CONTROL, 0, 0);
            key(in, 1, VK_V, 0, 0);
            key(in, 2, VK_V, 0, KEYEVENTF_KEYUP);
            key(in, 3, VK_CONTROL, 0, KEYEVENTF_KEYUP);
            return send(in, 4);
        }
    }

    /** True while the user holds a modifier or a mouse button. */
    static boolean userHolding() {
        if (KEY_STATE == null) return false;
        try {
            for (var vk : HELD) if (((short) KEY_STATE.invokeExact(vk) & 0x8000) != 0) return true;
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** INPUT on x64: type at 0, then KEYBDINPUT at 8 - wVk 8, wScan 10, dwFlags 12, time 16, dwExtraInfo 24. */
    static void key(MemorySegment in, int i, int vk, int scan, int flags) {
        var o = (long) i * INPUT_SIZE;
        in.set(JAVA_INT, o, INPUT_KEYBOARD);
        in.set(JAVA_SHORT, o + 8, (short) vk);
        in.set(JAVA_SHORT, o + 10, (short) scan);
        in.set(JAVA_INT, o + 12, flags);
    }

    static boolean send(MemorySegment in, int n) {
        try {
            return (int) SEND.invokeExact(n, in, INPUT_SIZE) == n;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private Win32Input() {}
}
