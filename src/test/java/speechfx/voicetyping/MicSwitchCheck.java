package speechfx.voicetyping;

import java.util.ArrayList;

/** Starts a capture, moves it to another device with Microphones.chooseLive, checks bytes keep flowing; restores the preferred order. */
public class MicSwitchCheck {
    public static void main(String[] args) throws Exception {
        var order = new ArrayList<>(VoiceTypingSettings.getMicrophoneSettings().preferred());
        var bytes = new long[1];
        var cfg = VoiceTypingSettings.getLongRecordingSettings().keepAudio(false);
        var r = new LongAudioRecorder(cfg, (b, n) -> bytes[0] += n, null);
        try {
            r.start();
            Thread.sleep(1500);
            System.out.println("before: " + Microphones.inUse() + " bytes=" + bytes[0]);
            var to = Microphones.connected().stream().filter(m -> !m.equals(Microphones.inUse())).findFirst().orElse(Microphones.SYSTEM_DEFAULT);
            System.out.println("switch to " + to + " -> error=" + Microphones.chooseLive(r, to));
            var mark = bytes[0];
            Thread.sleep(1500);
            System.out.println("after: " + Microphones.inUse() + " bytes since switch=" + (bytes[0] - mark));
        } finally {
            r.stop();
            VoiceTypingSettings.setPreferredMicrophones(order);
        }
        System.exit(0);
    }
}
