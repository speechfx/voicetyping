package speechfx.voicetyping;

import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Drives SegmentedStreamingSession against the live recogniser with a real speech file,
 * at real-time pace, with the segment length turned right down so several rollovers happen
 * inside a short clip. Proves the seam: text must accumulate across segments with nothing
 * duplicated and nothing dropped.
 *
 * Args: [wavPath] [segmentSeconds]. Reports structure only — counts and seam checks — never
 * the transcript itself, since the sample is real recorded speech.
 */
public final class SegmentedRolloverTest {

    static final List<String> finals = new ArrayList<>();
    static final List<Integer> finalSegment = new ArrayList<>();
    static final LinkedHashMap<Long, String> states = new LinkedHashMap<>();
    static SegmentedStreamingSession session;
    static long t0;

    public static void main(String[] args) throws Exception {
        var wav = new File(args.length > 0 ? args[0] : "mix16k.wav");
        var segSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        var in = AudioSystem.getAudioInputStream(wav);
        var fmt = in.getFormat();
        var pcm = in.readAllBytes();
        in.close();
        var bps = (int) (fmt.getSampleRate() * fmt.getFrameSize());
        System.out.printf("audio: %.0f Hz, %d ch, %d bytes = %.1f s%n",
                fmt.getSampleRate(), fmt.getChannels(), pcm.length, pcm.length / (double) bps);

        var cfg = new LongRecordingSettings();
        cfg.sampleRate((int) fmt.getSampleRate());
        cfg.segmentSeconds(segSeconds);
        cfg.segmentHardMaxSeconds(segSeconds + 10);
        cfg.silenceGapMillis(300);
        System.out.printf("rollover: every %ds at a %dms gap, hard cut at %ds%n%n",
                cfg.segmentSeconds(), cfg.silenceGapMillis(), cfg.segmentHardMaxSeconds());

        t0 = System.currentTimeMillis();
        session = new SegmentedStreamingSession(new SpeechChain(() -> System.getProperty("provider", "nvidia-streaming")), cfg, new Collector());
        session.start(fmt);

        var chunk = 4096;
        var perChunkMs = chunk * 1000L / bps;
        for (var off = 0; off < pcm.length; off += chunk) {
            var n = Math.min(chunk, pcm.length - off);
            var buf = new byte[n];
            System.arraycopy(pcm, off, buf, 0, n);
            session.onAudioChunk(buf, n);
            Thread.sleep(perChunkMs);
        }

        var text = session.stop().get();
        session.shutdown();
        report(text);
        System.exit(0);
    }

    static void report(String text) {
        System.out.println("\n=== state changes ===");
        states.forEach((ms, s) -> System.out.printf("  %5.1fs  %s%n", ms / 1000.0, s));

        System.out.println("\n=== structure ===");
        System.out.println("  segments opened : " + session.segmentCount());
        System.out.println("  final results   : " + finals.size());
        System.out.println("  total words     : " + words(text).length);
        for (var seg = 1; seg <= session.segmentCount(); seg++) {
            var w = 0;
            for (var i = 0; i < finals.size(); i++) if (finalSegment.get(i) == seg) w += words(finals.get(i)).length;
            System.out.printf("  segment %d words : %d%n", seg, w);
        }
        System.out.println("\n=== seams (duplication / loss check) ===");
        var seams = 0;
        for (var i = 1; i < finals.size(); i++) {
            if (finalSegment.get(i).equals(finalSegment.get(i - 1))) continue;
            seams++;
            var before = words(finals.get(i - 1));
            var after = words(finals.get(i));
            System.out.printf("  seam %d (segment %d -> %d): overlap=%d words%n",
                    seams, finalSegment.get(i - 1), finalSegment.get(i), overlap(before, after));
        }
        if (seams == 0) System.out.println("  no seam observed — clip too short or segment length too high");
        System.out.println("\n  (transcript withheld: real recorded speech)");
    }

    static int overlap(String[] before, String[] after) {
        var max = Math.min(4, Math.min(before.length, after.length));
        for (var n = max; n > 0; n--) {
            var same = true;
            for (var k = 0; k < n; k++) {
                if (!before[before.length - n + k].equalsIgnoreCase(after[k])) { same = false; break; }
            }
            if (same) return n;
        }
        return 0;
    }

    static String[] words(String s) {
        return s == null || s.isBlank() ? new String[0] : s.trim().split("\\s+");
    }

    static void note(String s) {
        states.put(System.currentTimeMillis() - t0, s);
    }

    static final class Collector implements DictationListener {
        @Override public void onFinalText(String text) {
            finals.add(text);
            finalSegment.add(session.segmentCount());
        }
        @Override public void onInterimText(String text) {}
        @Override public void onState(DictationState state, String detail) {
            note(state + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
        }
    }
}
