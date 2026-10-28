package speechfx.voicetyping;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders the overlay to a PNG by painting it offscreen rather than screenshotting the desktop.
 *
 * A desktop capture is worthless when the session is locked - it comes back as the lock screen - and
 * this has to be judged by eye, because neither the layout manager nor a passing compile can report
 * that the legend row wrapped, got clipped by the frame height, or ran off the right edge.
 */
public final class OverlayLegendPreview {

    public static void main(String[] args) throws Exception {
        var out = args.length > 0 ? args[0] : "overlay-legend.png";

        var overlay = new StreamingOverlayWindow();
        overlay.showOverlay();
        overlay.setStatus("● Recording", new java.awt.Color(80, 220, 120));
        overlay.updateText("this is the sort of live transcript that appears while the recording is "
                + "running, long enough to push the text area to more than one line so the legend "
                + "underneath has to hold its place");
        Thread.sleep(1200);

        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = null;
            for (var w : Window.getWindows()) {
                if (w instanceof JFrame f && f.isVisible() && f.isUndecorated()) frame = f;
            }
            if (frame == null) throw new IllegalStateException("overlay frame not found");

            var img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            frame.printAll(g);   // paints the real component tree, lock screen or not
            g.dispose();
            try {
                ImageIO.write(img, "png", new File(out));
                System.out.printf("wrote %s  (%dx%d)%n", out, img.getWidth(), img.getHeight());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
