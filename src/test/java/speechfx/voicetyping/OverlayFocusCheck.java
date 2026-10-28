package speechfx.voicetyping;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import static java.lang.foreign.ValueLayout.*;

/**
 * Does the streaming overlay leave the focus alone? A target window takes the front, the overlay is shown,
 * fed text and hidden, and after each step Windows is asked which window is in front. Prints PASS/FAIL lines
 * and saves a screenshot with the overlay up. No keys are sent anywhere.
 */
public final class OverlayFocusCheck {

    static final Linker L = Linker.nativeLinker();
    static final SymbolLookup U = SymbolLookup.libraryLookup("user32", Arena.global());
    static final MethodHandle FOREGROUND = L.downcallHandle(U.find("GetForegroundWindow").orElseThrow(), FunctionDescriptor.of(ADDRESS)),
            TEXT = L.downcallHandle(U.find("GetWindowTextW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
            VISIBLE = L.downcallHandle(U.find("IsWindowVisible").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static void main(String[] args) throws Throwable {
        var out = Path.of(args[0]);
        FxThread.start();
        var overlay = new StreamingOverlayWindow();
        Thread.sleep(1500);
        var target = FxThread.call(() -> {
            var s = new Stage();
            s.setTitle("vt overlay focus target");
            s.setScene(new Scene(new Label("  the window being dictated into  "), 420, 160));
            s.show();
            s.toFront();
            s.requestFocus();
            return s;
        });
        Thread.sleep(1000);
        var before = foreground();
        System.out.println("front before: " + before);
        overlay.showOverlay();
        overlay.setStatus("● Streaming", new java.awt.Color(50, 200, 50));
        overlay.updateText("The hearing is on Monday and the bundle must be ready by Friday, so the index goes first.");
        Thread.sleep(1200);
        var during = foreground();
        var shown = (int) VISIBLE.invokeExact(overlay.floating.hwnd) != 0;
        System.out.println((during.equals(before) ? "PASS" : "FAIL") + " focus kept while shown: " + during);
        System.out.println((shown ? "PASS" : "FAIL") + " overlay visible: " + shown);
        FxThread.call(() -> {
            var b = overlay.stage;
            FxPreview.write(new javafx.scene.robot.Robot().getScreenCapture(null, b.getX() - 10, b.getY() - 10, b.getWidth() + 20, b.getHeight() + 20), out.resolve("overlay.png"));
            return null;
        });
        overlay.hideOverlay();
        Thread.sleep(800);
        var after = foreground();
        var hidden = (int) VISIBLE.invokeExact(overlay.floating.hwnd) == 0;
        System.out.println((after.equals(before) ? "PASS" : "FAIL") + " focus kept after hide: " + after);
        System.out.println((hidden ? "PASS" : "FAIL") + " overlay hidden: " + hidden);
        FxThread.call(() -> { target.close(); return null; });
        javafx.application.Platform.exit();
        System.exit(0);
    }

    static String foreground() throws Throwable {
        var h = (MemorySegment) FOREGROUND.invokeExact();
        try (var a = Arena.ofConfined()) {
            var buf = a.allocate(JAVA_CHAR, 256);
            var n = (int) TEXT.invokeExact(h, buf, 256);
            return new String(buf.asSlice(0, n * 2L).toArray(JAVA_CHAR));
        }
    }
}
