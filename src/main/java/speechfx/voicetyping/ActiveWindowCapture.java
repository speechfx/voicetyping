package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Captures and restores the foreground window using Windows FFM (Foreign Function & Memory API).
 *
 * Uses the AttachThreadInput trick so SetForegroundWindow works even when called
 * from a background process thread.
 */
public class ActiveWindowCapture {

    private static final Logger log = LoggerFactory.getLogger(ActiveWindowCapture.class);

    private final MethodHandle getForegroundWindow;
    private final MethodHandle setForegroundWindow;
    private final MethodHandle attachThreadInput;
    private final MethodHandle getWindowThreadProcessId;
    private final MethodHandle getCurrentThreadId;
    private final boolean available;

    public ActiveWindowCapture() {
        MethodHandle gfw = null, sfw = null, ati = null, gwtpi = null, gti = null;
        boolean ok = false;
        try {
            if (!Platform.WINDOWS) throw new IllegalStateException("not Windows");
            var linker = Linker.nativeLinker();
            var user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
            var kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

            gfw = linker.downcallHandle(
                    user32.find("GetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));

            sfw = linker.downcallHandle(
                    user32.find("SetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // AttachThreadInput(idAttach, idAttachTo, fAttach)
            ati = linker.downcallHandle(
                    user32.find("AttachThreadInput").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            // GetWindowThreadProcessId(hWnd, lpProcessId) — pass NULL for lpProcessId
            gwtpi = linker.downcallHandle(
                    user32.find("GetWindowThreadProcessId").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            gti = linker.downcallHandle(
                    kernel32.find("GetCurrentThreadId").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            ok = true;
            log.info("ActiveWindowCapture: Windows FFM ready");
        } catch (Exception e) {
            if (Platform.WINDOWS) log.warn("ActiveWindowCapture: FFM unavailable ({}), window restore will be skipped", e.getMessage());
        }
        getForegroundWindow = gfw;
        setForegroundWindow = sfw;
        attachThreadInput = ati;
        getWindowThreadProcessId = gwtpi;
        getCurrentThreadId = gti;
        available = ok;
    }

    public boolean isAvailable() { return available; }

    /** Returns the HWND of the currently active window, or MemorySegment.NULL on failure. */
    public MemorySegment captureActiveWindow() {
        if (!available) return MemorySegment.NULL;
        try {
            return (MemorySegment) getForegroundWindow.invokeExact();
        } catch (Throwable e) {
            log.error("GetForegroundWindow failed: {}", e.getMessage());
            return MemorySegment.NULL;
        }
    }

    /**
     * Brings the given HWND back to the foreground.
     * Uses AttachThreadInput so the call works from a background thread/process.
     */
    public void restoreWindow(MemorySegment hwnd) {
        if (!available || hwnd == null || hwnd.address() == 0) return;
        try {
            int currentTid = (int) getCurrentThreadId.invokeExact();
            int targetTid = (int) getWindowThreadProcessId.invokeExact(hwnd, MemorySegment.NULL);
            var attach = targetTid != 0 && currentTid != targetTid;

            // Every return value below MUST be cast even where it is not wanted. invokeExact is
            // signature-polymorphic, so a bare statement call declares the expected return as void
            // and throws WrongMethodTypeException against a descriptor that returns int. It threw
            // here on the first AttachThreadInput for months, which meant SetForegroundWindow was
            // never reached at all and the paste landed in whatever window happened to have focus.
            if (attach) {
                int ok = (int) attachThreadInput.invokeExact(currentTid, targetTid, 1);
                if (ok == 0) log.debug("AttachThreadInput({}->{}) refused", currentTid, targetTid);
            }
            int brought = (int) setForegroundWindow.invokeExact(hwnd);
            if (attach) {
                int ignored = (int) attachThreadInput.invokeExact(currentTid, targetTid, 0);
            }

            // Windows refuses this whenever the caller does not own the foreground, and the refusal
            // is a return value rather than an exception. Without this line the paste goes to the
            // wrong window silently.
            if (brought == 0) log.warn("SetForegroundWindow refused by Windows; paste will go to the focused window");
        } catch (Throwable e) {
            log.error("restoreWindow failed: {}", e.toString());
        }
    }
}
