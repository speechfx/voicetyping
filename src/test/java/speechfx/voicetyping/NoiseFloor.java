package speechfx.voicetyping;

import java.util.*;

/** Records N seconds from the dictation microphone (as LongAudioRecorder opens it) and prints per-second RMS. */
public class NoiseFloor {
    public static void main(String[] args) throws Exception {
        var cfg = VoiceTypingSettings.getLongRecordingSettings();
        var fmt = new LongAudioRecorder(cfg, null, null).format();
        var line = Microphones.open(fmt);
        System.out.println("mic: " + Microphones.inUse());
        line.start();
        var secs = Integer.parseInt(args[0]);
        var buf = new byte[32000];
        var out = new ArrayList<Integer>();
        for (var s = 0; s < secs; s++) {
            var n = 0;
            while (n < buf.length) n += line.read(buf, n, buf.length - n);
            double sum = 0;
            for (var i = 0; i < buf.length; i += 2) { double v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8)); sum += v * v; }
            out.add((int) Math.sqrt(sum / (buf.length / 2)));
        }
        line.close();
        System.out.println("rms per second: " + out);
        System.exit(0);
    }
}
