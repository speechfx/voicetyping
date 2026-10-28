package speechfx.voicetyping;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javafx.stage.Stage;

/**
 * The window icon of every JavaFX window: the old Swing history window's steel-blue microphone on a clear
 * background, drawn in code at each size.
 * Handed to the Stage as PNG through JavaFX's image loader, because Windows silently ignores a WritableImage
 * in stage.getIcons() and shows the generic window glyph instead.
 */
public final class Icons {

    static final Color MIC = new Color(70, 130, 180);
    static volatile List<javafx.scene.image.Image> window;

    public static void applyTo(Stage stage) {
        if (window == null) window = List.of(16, 24, 32, 48, 64, 256).stream().map(s -> load(draw(s))).toList();
        stage.getIcons().setAll(window);
    }

    static BufferedImage draw(int s) {
        var img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(s / 32.0, s / 32.0);
        g.setColor(MIC);
        g.fill(new RoundRectangle2D.Double(12, 5, 8, 14, 8, 8));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(8.5, 9, 15, 14.5, 200, 140, Arc2D.OPEN));
        g.draw(new Line2D.Double(16, 23.5, 16, 27));
        g.draw(new Line2D.Double(12, 27, 20, 27));
        g.dispose();
        return img;
    }

    static javafx.scene.image.Image load(BufferedImage img) {
        try {
            var bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bytes);
            return new javafx.scene.image.Image(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Icons() {}
}
