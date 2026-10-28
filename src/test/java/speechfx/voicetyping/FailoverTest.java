package speechfx.voicetyping;

import javax.sound.sampled.AudioSystem;
import java.io.File;

/**
 * Streams a wav through a real dictation session and cuts the live connection part-way, the way
 * a dropped network would, to prove the session fails over at once and replays what the dead
 * stream never confirmed. Args: wav provider(deepgram-streaming|nvidia-streaming) cutAtSeconds.
 * Not a unit test.
 */
public final class FailoverTest {

    public static void main(String[] args) throws Exception {
        var in = AudioSystem.getAudioInputStream(new File(args[0]));
        var fmt = in.getFormat();
        var pcm = in.readAllBytes();
        in.close();
        var cutAt = Integer.parseInt(args[2]);
        var bps = (int) (fmt.getSampleRate() * fmt.getFrameSize());
        var t0 = System.currentTimeMillis();
        var cfg = new LongRecordingSettings();
        cfg.sampleRate((int) fmt.getSampleRate());

        var session = new SegmentedStreamingSession(new SpeechChain(() -> args[1]), cfg, new DictationListener() {
            @Override public void onFinalText(String text) {}
            @Override public void onInterimText(String text) {}
            @Override public void onState(DictationState s, String detail) {
                System.out.printf("%5.1f s  %s %s%n", (System.currentTimeMillis() - t0) / 1000.0, s, detail);
            }
        });
        session.start(fmt);
        var cut = false;
        for (var off = 0; off < pcm.length; off += 3200) {
            var n = Math.min(3200, pcm.length - off);
            var buf = new byte[n];
            System.arraycopy(pcm, off, buf, 0, n);
            session.onAudioChunk(buf, n);
            if (!cut && off >= cutAt * bps) { cut = true; kill(session.current); }
            Thread.sleep(100);
        }
        var stopAt = System.currentTimeMillis();
        var text = session.stop().get();
        System.out.printf("stop returned after %d ms with %d words%n", System.currentTimeMillis() - stopAt, text.split("\s+").length);
        Thread.sleep(25_000);
        text = session.transcript();
        System.out.printf("%n=== %d segments, %d words ===%n%s%n", session.segmentCount(), text.split("\\s+").length, text);
        System.exit(0);
    }

    static void kill(StreamingTranscriptionProvider p) throws Exception {
        System.out.println("--- cutting the connection of " + SegmentedStreamingSession.describe(p));
        if (p instanceof DeepgramStreamingTranscriptionService) {
            var f = p.getClass().getDeclaredField("webSocket");
            f.setAccessible(true);
            ((java.net.http.WebSocket) f.get(p)).abort();
        } else {
            var f = p.getClass().getDeclaredField("channel");
            f.setAccessible(true);
            ((io.grpc.ManagedChannel) f.get(p)).shutdownNow();
        }
    }
}
