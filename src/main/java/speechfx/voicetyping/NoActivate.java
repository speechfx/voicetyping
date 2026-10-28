package speechfx.voicetyping;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Windows only: makes a window that never takes the focus, as Swing's setFocusableWindowState(false) did and
 * JavaFX cannot. The window gets WS_EX_NOACTIVATE (clicks and showing do not activate it) and WS_EX_TOOLWINDOW
 * (no taskbar button), and is shown and hidden with ShowWindow(SW_SHOWNOACTIVATE / SW_HIDE) instead of
 * Stage.show/hide, which would activate it and pull the focus away from the window being dictated into.
 */
final class NoActivate {

    static final int GWL_EXSTYLE = -20, WS_EX_NOACTIVATE = 0x08000000, WS_EX_TOOLWINDOW = 0x80, SW_HIDE = 0,
            SW_SHOWNOACTIVATE = 4, SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10;
    static final MemorySegment HWND_TOPMOST = MemorySegment.ofAddress(-1);

    static final Linker L = Linker.nativeLinker();
    static final SymbolLookup USER32 = Platform.WINDOWS ? SymbolLookup.libraryLookup("user32", Arena.global()) : null;
    static final MethodHandle
            FIND = fn("FindWindowW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
            GET_EX = fn("GetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT)),
            SET_EX = fn("SetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG)),
            SHOW = fn("ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
            TOP = fn("GetTopWindow", FunctionDescriptor.of(ADDRESS, ADDRESS)),
            NEXT = fn("GetWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)),
            PID = fn("GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            TEXT = fn("GetWindowTextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
            IS_WINDOW = fn("IsWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
            POS = fn("SetWindowPos", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    static MethodHandle fn(String name, FunctionDescriptor d) {
        return USER32 == null ? null : L.downcallHandle(USER32.find(name).orElseThrow(), d);
    }

    /**
     * The window with exactly this title, made non-activating; NULL if not found or not on Windows. FindWindow alone
     * missed a freshly shown stage now and then (measured: 1 run in 7, the window plainly there with that title), so
     * this process's own top-level windows are walked as well.
     */
    static MemorySegment adopt(String title) {
        if (USER32 == null) return MemorySegment.NULL;
        try (var a = Arena.ofConfined()) {
            var hwnd = (MemorySegment) FIND.invokeExact(MemorySegment.NULL, a.allocateFrom(title, java.nio.charset.StandardCharsets.UTF_16LE));
            if (hwnd.address() == 0) hwnd = own(title, a);
            if (hwnd.address() == 0) return MemorySegment.NULL;
            var ex = (long) GET_EX.invokeExact(hwnd, GWL_EXSTYLE);
            var ignored = (long) SET_EX.invokeExact(hwnd, GWL_EXSTYLE, ex | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW);
            return hwnd;
        } catch (Throwable e) {
            return MemorySegment.NULL;
        }
    }

    static MemorySegment own(String title, Arena a) throws Throwable {
        var me = ProcessHandle.current().pid();
        var pid = a.allocate(JAVA_INT);
        var buf = a.allocate(512);
        var h = (MemorySegment) TOP.invokeExact(MemorySegment.NULL);
        while (h.address() != 0) {
            var tid = (int) PID.invokeExact(h, pid);
            if (pid.get(JAVA_INT, 0) == me) {
                var n = (int) TEXT.invokeExact(h, buf, 256);
                if (n > 0 && title.equals(new String(buf.asSlice(0, n * 2L).toArray(JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_16LE))) return h;
            }
            h = (MemorySegment) NEXT.invokeExact(h, 2);
        }
        return MemorySegment.NULL;
    }

    /** Shows it on top without activating it. */
    static void show(MemorySegment hwnd) {
        try {
            var a = (int) SHOW.invokeExact(hwnd, SW_SHOWNOACTIVATE);
            var b = (int) POS.invokeExact(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean alive(MemorySegment hwnd) {
        try {
            return (int) IS_WINDOW.invokeExact(hwnd) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    static void hide(MemorySegment hwnd) {
        try {
            var a = (int) SHOW.invokeExact(hwnd, SW_HIDE);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private NoActivate() {}
}
