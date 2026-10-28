package speechfx.voicetyping;

import java.io.File;
import java.lang.foreign.MemorySegment;
import java.nio.file.*;
import javafx.geometry.Point2D;
import javafx.scene.input.MouseButton;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;

/**
 * End-to-end check of on-spot dictation with a synthetic voice instead of the microphone: a 16 kHz mono WAV
 * is fed in real time through the real speech services, OnSpotSession, OnSpotDelivery and the floating bar, into a
 * window of another process. Args: wav targetTitle otherTitle shotDir. Phases: typed while in front; held while the
 * other window is in front; delivered on return; the bar's Pause clicked with the mouse without the target losing
 * the front; stopped and the note saved. Each step prints what it saw; the targets write their own text to files.
 */
public class OnSpotE2E {

    public static void main(String[] args) throws Throwable {
        var wav = Path.of(args[0]);
        var target = OnSpotCheck.find(args[1]);
        var other = OnSpotCheck.find(args[2]);
        var shots = Path.of(args[3]);
        var windows = new ActiveWindowCapture();
        FxThread.start();
        var s = new OnSpotSession(new SpeechChain(VoiceTypingSettings::getProvider), new TranscriptHistory(), m -> System.out.println("NOTIFY " + m), t -> System.out.println("TO LONG " + t));
        Thread.sleep(500);

        OnSpotCheck.front(windows, target);
        var fmt = FxThread.call(() -> {
            s.prepare(target);
            var f = new LongAudioRecorder(VoiceTypingSettings.getLongRecordingSettings(), null, null).format();
            s.go(f, target);
            return f;
        });
        System.out.println("A started: state=" + s.state + " format=" + fmt);
        feed(s, wav, 2500);
        shot(shots, "1-typing");
        System.out.println("A delivered=" + s.delivery.delivered + " pending='" + s.delivery.pending() + "' state=" + s.state);

        OnSpotCheck.front(windows, other);
        feed(s, wav, 3000);
        System.out.println("B away=" + s.delivery.away() + " held words=" + s.delivery.pendingWords());
        shot(shots, "2-holding");

        OnSpotCheck.front(windows, target);
        Thread.sleep(2000);
        System.out.println("C back: pending='" + s.delivery.pending() + "' delivered=" + s.delivery.delivered);

        var pause = FxThread.call(() -> s.bar.pause.localToScreen(s.bar.pause.getWidth() / 2, s.bar.pause.getHeight() / 2));
        click(pause);
        Thread.sleep(800);
        System.out.println("D clicked Pause at " + pause + ": state=" + s.state + " target still in front=" + target.inFront()
                + " front=" + TargetWindow.inFrontNow());
        shot(shots, "3-paused");
        FxThread.run(s::togglePause);
        Thread.sleep(500);

        FxThread.run(s::stop);
        for (var i = 0; i < 100 && s.state != DictationState.STOPPED; i++) Thread.sleep(100);
        System.out.println("E stopped: state=" + s.state + " transcript='" + s.stream.transcript() + "'");
        System.out.println("  note " + DictationNotes.pathFor(s.stamp, s.kind) + " exists=" + Files.exists(DictationNotes.pathFor(s.stamp, s.kind)));
        Thread.sleep(800);
        System.out.println("  bar closed itself=" + (s.delivery == null));
        System.exit(0);
    }

    /** The WAV in real time, 100 ms at a time, then silence so the last words are finalised. */
    static void feed(OnSpotSession s, Path wav, long silenceMs) throws Exception {
        try (var in = AudioSystem.getAudioInputStream(wav.toFile())) {
            var buf = new byte[3200];
            int n;
            while ((n = in.readNBytes(buf, 0, buf.length)) > 0) {
                s.stream.onAudioChunk(buf, n);
                Thread.sleep(100);
            }
        }
        var quiet = new byte[3200];
        for (var t = 0; t < silenceMs; t += 100) {
            s.stream.onAudioChunk(quiet, quiet.length);
            Thread.sleep(100);
        }
    }

    static void click(Point2D p) {
        FxThread.call(() -> {
            var r = new javafx.scene.robot.Robot();
            r.mouseMove(p);
            r.mouseClick(MouseButton.PRIMARY);
            return null;
        });
    }

    static void shot(Path dir, String name) throws Exception {
        var screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        var img = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(screen));
        ImageIO.write(img, "png", new File(dir.toFile(), name + ".png"));
    }
}
