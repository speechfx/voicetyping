package speechfx.voicetyping;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the floating bar in each state to PNGs in args[0], with the status tooltip text printed. A scene snapshot:
 * nothing is shown on screen. The deliveries are fakes whose ticker is stopped at once, so nothing is ever typed.
 */
public class OnSpotBarLook {

    public static void main(String[] args) throws Exception {
        FxThread.start();
        var bar = new OnSpotBar(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, m -> {});
        Thread.sleep(800);
        var npp = new TargetWindow(1, 6116, "notepad++", "*new 1 - Notepad++");
        render(bar, args[0], "listening", DictationState.LISTENING, fake(npp, ""), "and this is what is being recognised right now", false);
        render(bar, args[0], "holding", DictationState.LISTENING, fake(npp, "some words that are being held here"), "", false);
        render(bar, args[0], "paused", DictationState.PAUSED, fake(npp, ""), "", false);
        render(bar, args[0], "armed", DictationState.LISTENING, fake(null, ""), "", false);
        var gone = fake(npp, "twelve words not typed");
        gone.gone = true;
        render(bar, args[0], "gone", DictationState.STOPPED, gone, "", true);
        render(bar, args[0], "switching", DictationState.SWITCHING, fake(npp, ""), "", false);
        System.exit(0);
    }

    static OnSpotDelivery fake(TargetWindow t, String pending) {
        var d = new OnSpotDelivery(t);
        d.shutdown();
        d.target = t;
        if (!pending.isEmpty()) d.add(pending);
        return d;
    }

    static void render(OnSpotBar bar, String dir, String name, DictationState state, OnSpotDelivery d, String words, boolean finished) throws Exception {
        var img = FxThread.call(() -> {
            bar.show(state, d, words, finished);
            var root = bar.stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            System.out.println(name + ": " + bar.status.getTooltip().getText().replace("\n", " | "));
            return bar.stage.getScene().snapshot(null);
        });
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var px = img.getPixelReader();
        for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) out.setRGB(x, y, px.getArgb(x, y));
        ImageIO.write(out, "png", new File(dir, "bar-" + name + ".png"));
    }
}
