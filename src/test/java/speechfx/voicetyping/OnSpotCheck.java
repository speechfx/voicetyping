package speechfx.voicetyping;

import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Manual check of on-spot delivery against a real window of another process. Needs an unlocked desktop:
 * Windows passes no synthetic input to any window while the screen is locked. Args: targetTitle otherTitle. It types into the target, switches to the other window,
 * adds text that must be held, switches back and lets it arrive, then pastes a long text and checks the clipboard
 * was given back. The target writes its own text to a file, which is what the caller compares.
 * Run with plain java on the test classpath (target/classes;target/test-classes;deps): mvn exec:java runs the whole app,
 * because the pom fixes the main class.
 */
public class OnSpotCheck {

    static final java.lang.invoke.MethodHandle
            RECT = NoActivate.fn("GetWindowRect", java.lang.foreign.FunctionDescriptor.of(JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS)),
            CURSOR = NoActivate.fn("SetCursorPos", java.lang.foreign.FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

    public static void main(String[] args) throws Throwable {
        var target = find(args[0]);
        var other = find(args[1]);
        System.out.println("target " + target + "  other " + other);
        var windows = new ActiveWindowCapture();
        front(windows, target);
        var d = new OnSpotDelivery(target);

        d.add("hello world.");
        d.add("नमस्ते दुनिया, தமிழ் வணக்கம்.");
        waitEmpty(d, 3000);
        System.out.println("1 typed; pending=" + d.pending().length());

        front(windows, other);
        d.add("this was held.");
        Thread.sleep(1500);
        System.out.println("2 away=" + d.away() + " pending words=" + d.pendingWords());

        front(windows, target);
        waitEmpty(d, 3000);
        System.out.println("3 back; pending=" + d.pending().length());

        var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection("USER CLIPBOARD"), null);
        d.add("Long piece: " + "the quick brown fox jumps over the lazy dog ".repeat(6).strip());
        waitEmpty(d, 3000);
        Thread.sleep(500);
        System.out.println("4 pasted; clipboard now = " + cb.getData(DataFlavor.stringFlavor));
        d.shutdown();
    }

    static TargetWindow find(String title) throws Throwable {
        for (var i = 0; i < 50; i++) {
            var seg = (MemorySegment) NoActivate.FIND.invokeExact(MemorySegment.NULL, java.lang.foreign.Arena.global()
                    .allocateFrom(title, java.nio.charset.StandardCharsets.UTF_16LE));
            if (seg.address() != 0) return TargetWindow.of(seg.address());
            Thread.sleep(200);
        }
        throw new IllegalStateException("no window " + title);
    }

    /** Clicks into the middle of the window, as a person would: the one way to the front Windows never refuses. */
    static void front(ActiveWindowCapture w, TargetWindow t) throws Throwable {
        try (var a = java.lang.foreign.Arena.ofConfined()) {
            var r = a.allocate(16);
            var ok = (int) RECT.invokeExact(MemorySegment.ofAddress(t.hwnd), r);
            int x = (r.get(JAVA_INT, 0) + r.get(JAVA_INT, 8)) / 2, y = (r.get(JAVA_INT, 4) + r.get(JAVA_INT, 12)) / 2;
            var moved = (int) CURSOR.invokeExact(x, y);
            var in = a.allocate(Win32Input.INPUT_SIZE * 2L);
            in.set(JAVA_INT, 20, 0x2);
            in.set(JAVA_INT, Win32Input.INPUT_SIZE + 20, 0x4);
            Win32Input.send(in, 2);
        }
        Thread.sleep(400);
        System.out.println("  in front now: " + TargetWindow.inFrontNow());
    }

    static void waitEmpty(OnSpotDelivery d, long ms) throws InterruptedException {
        var end = System.currentTimeMillis() + ms;
        while (!d.pending().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50);
    }
}
