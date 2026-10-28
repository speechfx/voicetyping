package speechfx.voicetyping;

import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Reproduces the reported failure against the RUNNING app: the first Win+. starts a recording and
 * the second one does not stop it.
 *
 * Runs in a separate process from the app, so it proves nothing about internals — it only presses
 * the keys and leaves the app's own log as the evidence. Owns a focused text area so the paste at
 * the end of the recording lands here rather than in the user's work.
 */
public final class ToggleRepro {

    public static void main(String[] args) throws Exception {
        var gapSeconds = args.length > 0 ? Integer.parseInt(args[0]) : 10;

        var area = new JTextArea(6, 50);
        SwingUtilities.invokeAndWait(() -> {
            var f = new JFrame("ToggleRepro - paste lands here");
            f.getContentPane().add(area);
            f.pack();
            f.setLocation(60, 60);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            f.toFront();
            area.requestFocusInWindow();
        });

        var r = new Robot();
        Thread.sleep(1500);

        System.out.println("press 1 (start) at " + stamp());
        winDot(r);

        // Capture the overlay while a recording is actually running - the legend can only be judged
        // by looking at it, and the API cannot report that a row wrapped or got clipped.
        Thread.sleep(gapSeconds * 1000L / 2);
        if (args.length > 1) {
            var img = r.createScreenCapture(new java.awt.Rectangle(
                    java.awt.Toolkit.getDefaultToolkit().getScreenSize()));
            javax.imageio.ImageIO.write(img, "png", new java.io.File(args[1]));
            System.out.println("captured " + args[1]);
        }
        Thread.sleep(gapSeconds * 1000L / 2);

        System.out.println("press 2 (stop)  at " + stamp());
        winDot(r);

        Thread.sleep(12000);
        System.out.println("text area now holds " + area.getText().length() + " chars");
        System.exit(0);
    }

    static void winDot(Robot r) {
        r.keyPress(KeyEvent.VK_WINDOWS);
        r.keyPress(KeyEvent.VK_PERIOD);
        r.delay(60);
        r.keyRelease(KeyEvent.VK_PERIOD);
        r.keyRelease(KeyEvent.VK_WINDOWS);
    }

    static String stamp() {
        return java.time.LocalTime.now().withNano(0).toString();
    }
}
