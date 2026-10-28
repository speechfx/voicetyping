package speechfx.voicetyping;

import java.nio.file.Path;
import javax.sound.sampled.AudioSystem;

/**
 * Live switch of speech service and account mid-dictation, on the WAV args[0] played twice: after 4 s the other
 * provider is chosen with its second key pinned, and the stream is told to switch. Prints the final text; the log
 * shows which provider and key each segment used. The user's provider and account choices are restored at the end.
 */
public class SwitchCheck {
    public static void main(String[] args) throws Exception {
        var provider = VoiceTypingSettings.getProvider();
        var speech = VoiceTypingSettings.getSpeechSettings();
        String dg = speech.deepgramKey(), nv = speech.nvidiaKey();
        var other = provider.startsWith("nvidia") ? "deepgram-streaming" : "nvidia-streaming";
        var keys = SpeechKeys.all(SpeechChain.keyProvider(other));
        var pin = keys.get(Math.min(1, keys.size() - 1));
        var chain = new SpeechChain(VoiceTypingSettings::getProvider);
        var cfg = VoiceTypingSettings.getLongRecordingSettings();
        var s = new SegmentedStreamingSession(chain, cfg, new DictationListener() {
            public void onFinalText(String t) { System.out.println("FINAL " + t); }
            public void onInterimText(String t) {}
            public void onState(DictationState st, String d) { System.out.println("STATE " + st + " " + d); }
        });
        try {
            s.start(new LongAudioRecorder(cfg, null, null).format());
            for (var round = 0; round < 2; round++) {
                try (var in = AudioSystem.getAudioInputStream(Path.of(args[0]).toFile())) {
                    var buf = new byte[3200];
                    int n, chunk = 0;
                    while ((n = in.readNBytes(buf, 0, buf.length)) > 0) {
                        if (round == 0 && chunk++ == 40) {
                            System.out.println("SWITCH to " + other + " pinned #" + pin.id());
                            VoiceTypingSettings.setProvider(other);
                            VoiceTypingSettings.setSpeechKey(SpeechChain.keyProvider(other), pin.id());
                            chain.prefer(other);
                            s.switchNow("test switch");
                        }
                        s.onAudioChunk(buf, n);
                        Thread.sleep(100);
                    }
                }
            }
            System.out.println("TRANSCRIPT " + s.stop().get());
        } finally {
            VoiceTypingSettings.setProvider(provider);
            VoiceTypingSettings.setSpeechKey("deepgram", dg);
            VoiceTypingSettings.setSpeechKey("nvidia", nv);
            s.shutdown();
        }
        System.exit(0);
    }
}
