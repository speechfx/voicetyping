package speechfx.voicetyping;

import speechfx.dictation.*;

/** Builds and shows the floating bar, then prints its window styles (taskbar button or not). */
public class BarStyleProbe {
    public static void main(String[] args) throws Throwable {
        FxThread.start();
        var bar = new OnSpotBar(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, m -> {});
        Thread.sleep(800);
        bar.show();
        Thread.sleep(800);
        WindowList.list();
        StyleProbe.main(new String[]{OnSpotBar.TITLE});
        System.out.println("bar native=" + bar.native_() + " title=" + FxThread.call(() -> bar.stage.getTitle()));
        var ov = new StreamingOverlayWindow();
        Thread.sleep(800);
        System.out.println("overlay native=" + ov.native_());
        StyleProbe.main(new String[]{StreamingOverlayWindow.TITLE});
        bar.hide();
        System.exit(0);
    }
}
