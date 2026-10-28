package speechfx.voicetyping;

import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

/**
 * Does restoreWindow actually put the captured window back in front?
 *
 * The old code threw WrongMethodTypeException on its first AttachThreadInput, so SetForegroundWindow
 * was never called and the paste went wherever focus happened to be. That failure was invisible from
 * outside: the log blamed SetForegroundWindow, which had not run, and the paste still "succeeded".
 * So this checks the observable outcome — the HWND in front afterwards — not the absence of an
 * exception.
 *
 * Two windows of our own, so nothing here touches whatever the user is working in.
 */
public final class WindowRestoreCheck {

    public static void main(String[] args) throws Exception {
        var capture = new ActiveWindowCapture();
        System.out.println("FFM available: " + capture.isAvailable());
        if (!capture.isAvailable()) throw new AssertionError("FFM unavailable, nothing to test");

        var first = frame("FIRST - this one should come back", 100, 200);
        Thread.sleep(1500);
        var target = capture.captureActiveWindow();
        System.out.printf("captured hwnd=0x%X%n", target.address());
        if (target.address() == 0) throw new AssertionError("captured nothing - no window had focus");

        // Steal the foreground the same way a transcription does: another window takes over.
        var second = frame("SECOND - steals the foreground", 500, 400);
        Thread.sleep(1500);
        var stolen = capture.captureActiveWindow();
        System.out.printf("after steal  hwnd=0x%X  %s%n", stolen.address(),
                stolen.address() == target.address() ? "*** steal did not work, test is inconclusive ***" : "(foreground moved)");

        capture.restoreWindow(target);
        Thread.sleep(1200);
        var now = capture.captureActiveWindow();
        var ok = now.address() == target.address();
        System.out.printf("after restore hwnd=0x%X -> %s%n", now.address(),
                ok ? "ok, the captured window is back in front" : "WRONG, it did not come back");

        SwingUtilities.invokeLater(() -> { first.dispose(); second.dispose(); });
        if (!ok) throw new AssertionError("restoreWindow did not restore the foreground");
        System.exit(0);
    }

    static JFrame frame(String title, int x, int y) throws Exception {
        var f = new JFrame(title);
        SwingUtilities.invokeAndWait(() -> {
            f.getContentPane().add(new JTextField(title, 34), BorderLayout.CENTER);
            f.pack();
            f.setLocation(x, y);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            f.toFront();
            f.requestFocus();
        });
        return f;
    }
}
