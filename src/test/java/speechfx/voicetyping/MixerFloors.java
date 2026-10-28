package speechfx.voicetyping;

import javax.sound.sampled.*;

/** Two seconds from every capture device Java sees, with the median per-second RMS, to find the quiet one. */
public class MixerFloors {
    public static void main(String[] args) throws Exception {
        var fmt = new AudioFormat(16000, 16, 1, true, false);
        for (var mi : AudioSystem.getMixerInfo()) {
            var m = AudioSystem.getMixer(mi);
            var info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!m.isLineSupported(info)) continue;
            try (var line = (TargetDataLine) m.getLine(info)) {
                line.open(fmt);
                line.start();
                var buf = new byte[64000];
                var n = 0;
                var end = System.currentTimeMillis() + 3000;
                while (n < buf.length && System.currentTimeMillis() < end) n += line.read(buf, n, Math.min(3200, buf.length - n));
                double sum = 0;
                for (var i = 0; i + 1 < n; i += 2) { double v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8)); sum += v * v; }
                System.out.printf("%6d  %s%n", n < 2 ? -1 : (int) Math.sqrt(sum / (n / 2)), mi.getName());
            } catch (Exception e) {
                System.out.println("  err  " + mi.getName() + ": " + e.getMessage());
            }
        }
        System.exit(0);
    }
}
