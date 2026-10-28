package speechfx.voicetyping;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Streams a saved 16 kHz dictation .pcm through a streaming provider at real-time pace, prints the
 * transcript and timing: first text, update rate, and how long the final takes after the audio ends.
 * Usage: ReplayProvider nvidia|deepgram|claude <file.pcm> [seconds] [skipSeconds]. Not a unit test.
 */
public final class ReplayProvider {

    public static void main(String[] args) throws Exception {
        var pcm = Files.readAllBytes(Path.of(args[1]));
        var from = args.length > 3 ? Math.min(pcm.length, Integer.parseInt(args[3]) * 32000) : 0;
        var to = args.length > 2 ? Math.min(pcm.length, from + Integer.parseInt(args[2]) * 32000) : pcm.length;
        StreamingTranscriptionProvider p = switch (args[0]) {
            case "nvidia" -> new NvidiaStreamingTranscriptionService();
            case "deepgram" -> new DeepgramStreamingTranscriptionService();
            case "claude" -> new ClaudeVoiceStream();
            default -> throw new IllegalArgumentException(args[0]);
        };
        var results = new CopyOnWriteArrayList<long[]>();
        var started = new long[]{0};
        p.setResultListener((text, fin) -> results.add(new long[]{System.currentTimeMillis() - started[0], fin ? 1 : 0}));
        p.onRecordingStarted(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000, 16, 1, 2, 16000, false));
        started[0] = System.currentTimeMillis();
        for (var off = from; off < to; off += 3200) {
            var n = Math.min(3200, to - off);
            var chunk = new byte[n];
            System.arraycopy(pcm, off, chunk, 0, n);
            p.onAudioChunk(chunk, n);
            Thread.sleep(100);
        }
        var audioEnd = System.currentTimeMillis() - started[0];
        var text = p.transcribeAsync(null).get();
        var done = System.currentTimeMillis() - started[0];
        if (System.getProperty("timeline") != null)
            Files.write(Path.of(System.getProperty("timeline")), results.stream().map(r -> r[0] + " " + r[1]).toList());
        var finals = results.stream().filter(r -> r[1] == 1).count();
        System.out.printf("%n=== %s | %s | %d-%d s | first text %d ms | %d updates (%.1f/s), %d finals | final ready %d ms after audio end ===%n%s%n",
                args[0], Path.of(args[1]).getFileName(), from / 32000, to / 32000,
                results.isEmpty() ? -1 : results.getFirst()[0], results.size(), results.size() * 1000.0 / audioEnd, finals,
                done - audioEnd, text);
        System.exit(0);
    }
}
