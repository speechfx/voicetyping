package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.io.File;
import java.nio.file.*;
import javax.imageio.ImageIO;

/**
 * Is the dictation window showing words as they come back, or holding them? Feeds a 16 kHz mono WAV in real time
 * through SegmentedStreamingSession on the given provider into a real DictationWindow, placed off-screen so it
 * takes no focus, prints each result with its time, and snapshots the window every 500 ms.
 * Args: provider (deepgram-flux | deepgram-streaming | nvidia-streaming) wav shotDir.
 */
public class WindowReplay {
    static java.awt.image.BufferedImage toAwt(javafx.scene.image.Image img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        var out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var px = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), px, 0, w);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    public static void main(String[] args) throws Throwable {
        var shots = Path.of(args[2]);
        Files.createDirectories(shots);
        FxThread.start();
        var window = new DictationWindow();
        window.fx(() -> {
            var st = window.stage();
            st.setX(-4000);
            st.setY(100);
            st.show();
        });
        Thread.sleep(1500);
        window.appendTimestamp();
        var t0 = System.currentTimeMillis();
        var cfg = DictationSettings.getLongRecordingSettings();
        var s = new SegmentedStreamingSession(new SpeechChain(() -> args[0]), cfg, new DictationListener() {
            public void onFinalText(String t) { System.out.printf("%6d FINAL   %s%n", System.currentTimeMillis() - t0, t); window.appendFinal(t); }
            public void onInterimText(String t) { if (!t.isEmpty()) System.out.printf("%6d interim %s%n", System.currentTimeMillis() - t0, t); window.setInterim(t); }
            public void onState(DictationState st, String d) { System.out.printf("%6d STATE   %s %s%n", System.currentTimeMillis() - t0, st, d); window.setState(st); }
        });
        var shooter = new Thread(() -> {
            try {
                for (var i = 0; ; i++) {
                    Thread.sleep(500);
                    var ms = System.currentTimeMillis() - t0;
                    var img = FxThread.call(() -> window.stage().getScene().snapshot(null));
                    ImageIO.write(toAwt(img), "png", new File(shots.toFile(), String.format("%05d.png", ms)));
                }
            } catch (InterruptedException e) { /* done */ } catch (Exception e) { e.printStackTrace(); }
        });
        shooter.setDaemon(true);
        s.start(new LongAudioRecorder(cfg, null, null).format());
        shooter.start();
        var pcm = Files.readAllBytes(Path.of(args[1]));
        for (var o = 44; o < pcm.length; o += 3200) {
            var n = Math.min(3200, pcm.length - o);
            s.onAudioChunk(java.util.Arrays.copyOfRange(pcm, o, o + n), n);
            Thread.sleep(100);
        }
        System.out.printf("%6d audio ended, stopping%n", System.currentTimeMillis() - t0);
        s.stop().get();
        Thread.sleep(3000);
        shooter.interrupt();
        System.out.println("WINDOW TEXT: " + FxThread.call(window::text));
        s.shutdown();
        System.exit(0);
    }
}
