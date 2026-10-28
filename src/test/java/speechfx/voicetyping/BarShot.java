package speechfx.voicetyping;

import java.io.File;
import javax.imageio.ImageIO;

/** Shows the bar (and builds the overlay, as vt does at start-up), then screenshots the whole screen to args[0]. */
public class BarShot {
    public static void main(String[] args) throws Throwable {
        FxThread.start();
        var ov = new StreamingOverlayWindow();
        var bar = new OnSpotBar(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, m -> {});
        Thread.sleep(300);
        System.out.println("300ms native=" + bar.native_() + " " + FxThread.call(() -> bar.stage.isShowing() + " " + bar.stage.getTitle()));
        WindowList.list();
        Thread.sleep(1200);
        bar.show();
        Thread.sleep(1500);
        var screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        ImageIO.write(new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(screen)), "png", new File(args[0]));
        System.out.println("bar native=" + bar.native_() + " overlay native=" + ov.native_());
        WindowList.list();
        System.exit(0);
    }
}
