package speechfx.voicetyping;

/**
 * Tracks how long the incoming 16-bit PCM has been below a loudness threshold.
 * Used to pick a rollover point between streaming segments that falls in a gap
 * between words rather than in the middle of one.
 */
public final class SilenceDetector {

    final double threshold;
    final int bytesPerSecond;
    volatile long quietBytes;

    public SilenceDetector(double threshold, int bytesPerSecond) {
        this.threshold = threshold;
        this.bytesPerSecond = Math.max(1, bytesPerSecond);
    }

    public void accept(byte[] chunk, int length) {
        if (rms(chunk, length) < threshold) quietBytes += length;
        else quietBytes = 0;
    }

    public long quietMillis() {
        return quietBytes * 1000L / bytesPerSecond;
    }

    public void reset() {
        quietBytes = 0;
    }

    public static double rms(byte[] b, int length) {
        var samples = length / 2;
        if (samples == 0) return 0;
        var sum = 0d;
        for (var i = 0; i + 1 < length; i += 2) {
            var s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            sum += (double) s * s;
        }
        return Math.sqrt(sum / samples);
    }
}
