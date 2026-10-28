package speechfx.voicetyping;

import javax.sound.sampled.AudioSystem;
import java.io.File;

/**
 * Does transcription turn sluggish after a pause? Streams a speech file at real-time
 * pace into SegmentedStreamingSession against the live recogniser, pauses (no audio at all,
 * exactly as LongAudioRecorder does), resumes, and streams again. The LagMeter lines in the
 * log ("lag ...") give the lag before and after; compare them.
 *
 * Args: [wavPath] [secondsBeforePause] [pauseSeconds] [secondsAfterResume]; -Dprovider=deepgram-streaming|nvidia-streaming
 */
public final class PauseLagTest {

    public static void main(String[] args) throws Exception {
        var wav = new File(args.length > 0 ? args[0] : "mix16k.wav");
        var before = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        var pause = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        var after = args.length > 3 ? Integer.parseInt(args[3]) : 40;

        var in = AudioSystem.getAudioInputStream(wav);
        var fmt = in.getFormat();
        var pcm = in.readAllBytes();
        in.close();
        var bps = (int) (fmt.getSampleRate() * fmt.getFrameSize());

        var cfg = new LongRecordingSettings();
        cfg.sampleRate((int) fmt.getSampleRate());
        var t0 = System.currentTimeMillis();
        var session = new SegmentedStreamingSession(new SpeechChain(() -> System.getProperty("provider", "nvidia-streaming")), cfg,
                new DictationListener() {
                    public void onFinalText(String text) {}
                    public void onInterimText(String text) {}
                    public void onState(DictationState s, String detail) {
                        System.out.printf("%6.1fs  STATE %s %s%n", (System.currentTimeMillis() - t0) / 1000.0, s, detail);
                    }
                });
        session.start(fmt);

        var offset = new int[]{0};
        stream(session, pcm, offset, bps, before);
        System.out.printf("%6.1fs  >>> PAUSE for %d s%n", (System.currentTimeMillis() - t0) / 1000.0, pause);
        session.pause();
        Thread.sleep(pause * 1000L);
        System.out.printf("%6.1fs  >>> RESUME%n", (System.currentTimeMillis() - t0) / 1000.0);
        session.resume();
        stream(session, pcm, offset, bps, after);

        session.stop().get();
        Thread.sleep(3000);
        session.shutdown();
        System.exit(0);
    }

    /** Feed `seconds` of audio at real-time pace, wrapping round the file if it runs out. */
    static void stream(SegmentedStreamingSession s, byte[] pcm, int[] offset, int bps, int seconds) throws InterruptedException {
        var chunk = 4096;
        var start = System.nanoTime();
        long fed = 0, target = (long) bps * seconds;
        while (fed < target) {
            if (offset[0] >= pcm.length) offset[0] = 0;
            var n = Math.min(chunk, pcm.length - offset[0]);
            var buf = new byte[n];
            System.arraycopy(pcm, offset[0], buf, 0, n);
            offset[0] += n;
            fed += n;
            // pace against the clock, not per-chunk sleeps, so the feed never drifts slower than real time
            var dueNs = start + fed * 1_000_000_000L / bps;
            var waitMs = (dueNs - System.nanoTime()) / 1_000_000;
            if (waitMs > 0) Thread.sleep(waitMs);
            s.onAudioChunk(buf, n);
        }
    }
}
