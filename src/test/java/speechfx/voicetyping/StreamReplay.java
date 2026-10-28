package speechfx.voicetyping;

import java.nio.file.*;

/** Feeds a raw 16 kHz mono PCM file (as LongAudioRecorder keeps) through SegmentedStreamingSession in real time, printing results. */
public class StreamReplay {
    public static void main(String[] args) throws Exception {
        var cfg = VoiceTypingSettings.getLongRecordingSettings();
        var s = new SegmentedStreamingSession(new SpeechChain(VoiceTypingSettings::getProvider), cfg, new DictationListener() {
            public void onFinalText(String t) { System.out.println("FINAL " + t); }
            public void onInterimText(String t) { if (!t.isEmpty()) System.out.println("  interim " + t); }
            public void onState(DictationState st, String d) { System.out.println("STATE " + st + " " + d); }
        });
        s.start(new LongAudioRecorder(cfg, null, null).format());
        var pcm = Files.readAllBytes(Path.of(args[0]));
        for (var o = 0; o < pcm.length; o += 3200) {
            s.onAudioChunk(java.util.Arrays.copyOfRange(pcm, o, Math.min(pcm.length, o + 3200)), Math.min(3200, pcm.length - o));
            Thread.sleep(100);
        }
        System.out.println("TRANSCRIPT " + s.stop().get());
        s.shutdown();
        System.exit(0);
    }
}
