package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.nio.file.Path;
import java.util.concurrent.*;
import javax.sound.sampled.AudioSystem;

/**
 * The on-spot -> long dictation hand-over, with nothing shown and nothing typed: the target is a window that is
 * never in front, and the bar is never shown. Feeds the WAV args[0], hands over after args[1] seconds, prints what
 * the long dictation would receive and the session's state afterwards.
 */
public class HandOverCheck {
    public static void main(String[] args) throws Exception {
        FxThread.start();
        var got = new CompletableFuture<String>();
        var s = new OnSpotSession(new SpeechChain(DictationSettings::getProvider), new TranscriptHistory(), m -> System.out.println("NOTIFY " + m), got::complete);
        var never = new TargetWindow(1, 0, "nowhere", "nowhere");
        var fmt = new LongAudioRecorder(DictationSettings.getLongRecordingSettings(), null, null).format();
        FxThread.call(() -> { s.prepare(never); s.stream.start(fmt); return null; });
        var handAt = Long.parseLong(args[1]) * 10;
        try (var in = AudioSystem.getAudioInputStream(Path.of(args[0]).toFile())) {
            var buf = new byte[3200];
            int n, chunk = 0;
            while ((n = in.readNBytes(buf, 0, buf.length)) > 0) {
                if (chunk++ == handAt) {
                    System.out.println("pending before hand-over: '" + s.delivery.pending() + "'");
                    FxThread.run(s::toLong);
                }
                s.stream.onAudioChunk(buf, n);
                Thread.sleep(100);
            }
        }
        System.out.println("TO LONG: '" + got.get(60, TimeUnit.SECONDS) + "'");
        Thread.sleep(500);
        System.out.println("state=" + s.state + " delivery=" + s.delivery);
        System.exit(0);
    }
}
