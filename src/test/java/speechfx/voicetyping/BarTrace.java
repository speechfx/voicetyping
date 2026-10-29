package speechfx.voicetyping;

import speechfx.dictation.*;

/** Overlay then bar, as at vt start-up; every 250 ms prints whether the bar stage shows and which windows exist. */
public class BarTrace {
    public static void main(String[] args) throws Throwable {
        FxThread.start();
        if (args.length > 0) new StreamingOverlayWindow();
        var bar = new OnSpotBar(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, m -> {});
        for (var i = 0; i < 10; i++) {
            if (i == 5) { System.out.println("show"); bar.show(); }
            Thread.sleep(250);
            var showing = FxThread.call(() -> bar.stage == null ? "no stage" : "showing=" + bar.stage.isShowing() + " opacity=" + bar.stage.getOpacity());
            System.out.println((i * 250 + 250) + "ms native=" + bar.native_() + " " + showing);
        }
        WindowList.list();
        System.exit(0);
    }
}
