package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import static java.lang.foreign.ValueLayout.*;

/**
 * The window an on-spot dictation types into, fixed when it starts: its top-level handle, process id, exe and
 * title. Text is only ever sent while this exact window is in front, so another window of the same program
 * (a second browser window) does not count. Windows only; elsewhere nothing is ever found.
 */
final class TargetWindow {

    static final int GA_ROOT = 2;
    /** Taskbar, tray overflow, desktop, Start and search: never something to type into. */
    static final Set<String> SHELL = Set.of("Shell_TrayWnd", "Shell_SecondaryTrayWnd", "NotifyIconOverflowWindow",
            "TopLevelWindowForOverflowXamlIsland", "Progman", "WorkerW", "Windows.UI.Core.CoreWindow",
            "XamlExplorerHostIslandWindow");
    static final long OWN_PID = ProcessHandle.current().pid();

    static final MethodHandle
            FOREGROUND = NoActivate.fn("GetForegroundWindow", FunctionDescriptor.of(ADDRESS)),
            ANCESTOR = NoActivate.fn("GetAncestor", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)),
            PID = NoActivate.fn("GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            IS_WINDOW = NoActivate.fn("IsWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
            TITLE = NoActivate.fn("GetWindowTextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
            CLASS = NoActivate.fn("GetClassNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    final long hwnd, pid;
    final String app, title;

    TargetWindow(long hwnd, long pid, String app, String title) {
        this.hwnd = hwnd;
        this.pid = pid;
        this.app = app;
        this.title = title;
    }

    /** The top-level window in front, as a raw handle; 0 if none or not on Windows. Cheap: safe on the hook thread. */
    static long foreground() {
        if (FOREGROUND == null) return 0;
        try {
            var fg = (MemorySegment) FOREGROUND.invokeExact();
            if (fg.address() == 0) return 0;
            return ((MemorySegment) ANCESTOR.invokeExact(fg, GA_ROOT)).address();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** The window as a target, or null when it is ours, the shell, or gone. */
    static TargetWindow of(long hwnd) {
        if (hwnd == 0 || PID == null) return null;
        try (var a = Arena.ofConfined()) {
            var h = MemorySegment.ofAddress(hwnd);
            var pidOut = a.allocate(JAVA_INT);
            var thread = (int) PID.invokeExact(h, pidOut);
            var pid = Integer.toUnsignedLong(pidOut.get(JAVA_INT, 0));
            if (thread == 0 || pid == OWN_PID || SHELL.contains(text(CLASS, h, a))) return null;
            return new TargetWindow(hwnd, pid, exe(pid), text(TITLE, h, a));
        } catch (Throwable e) {
            return null;
        }
    }

    static TargetWindow inFrontNow() { return of(foreground()); }

    boolean inFront() { return foreground() == hwnd; }

    boolean alive() {
        try {
            return (int) IS_WINDOW.invokeExact(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** "Notepad", "Chrome": the exe name, capitalised; the title when the exe cannot be read (an elevated program). */
    String name() {
        if (app.isEmpty()) return title.isEmpty() ? "the window" : title;
        return Character.toUpperCase(app.charAt(0)) + app.substring(1);
    }

    static String exe(long pid) {
        return ProcessHandle.of(pid).flatMap(p -> p.info().command())
                .map(c -> Path.of(c).getFileName().toString().toLowerCase().replaceFirst("\\.exe$", ""))
                .orElse("");
    }

    static String text(MethodHandle fn, MemorySegment h, Arena a) throws Throwable {
        var buf = a.allocate(512);
        var n = (int) fn.invokeExact(h, buf, 256);
        return n <= 0 ? "" : new String(buf.asSlice(0, n * 2L).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
    }

    @Override public String toString() { return name() + " (pid " + pid + ", hwnd 0x" + Long.toHexString(hwnd) + ")"; }
}
