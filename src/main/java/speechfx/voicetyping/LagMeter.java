package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures how far behind real time one provider stream is running.
 *
 * Every chunk handed to the stream is marked with the wall-clock moment its last sample was
 * captured. When the provider reports how far into the stream's audio a result reaches
 * (Deepgram start+duration, Riva audio_processed), the moment that audio was spoken is looked
 * up and the lag is now minus that moment. Replayed and parked audio keeps its original
 * capture time, so a stream that is catching up shows as lagging, which is what the user sees.
 *
 * Log lines start with "lag" so `grep " lag "` over program.log gives the whole picture.
 */
final class LagMeter {

    private static final Logger log = LoggerFactory.getLogger(LagMeter.class);

    static final long WARN_MS = 2_500;
    static final int MAX_MARKS = 20_000;

    record Mark(long endByte, long capturedAt) {}

    final String name;
    final ArrayDeque<Mark> marks = new ArrayDeque<>();
    final AtomicInteger queued = new AtomicInteger();
    double bytesPerMs;
    long bytes, openedAt, connectedAt, bufferedAtConnect, firstResultAt;
    long finals, lagSum, lagMax, interimMax, interimSinceFinal, warned;
    boolean noPosition;

    LagMeter(String name) { this.name = name; }

    synchronized void open(AudioFormat f) {
        bytesPerMs = f.getSampleRate() * f.getFrameSize() / 1000.0;
        marks.clear();
        bytes = finals = lagSum = lagMax = interimMax = interimSinceFinal = warned = 0;
        connectedAt = firstResultAt = 0;
        noPosition = false;
        openedAt = System.currentTimeMillis();
        queued.set(0);
    }

    /** A chunk was handed to the stream; capturedAt is when its last sample was recorded. */
    synchronized void sent(int length, long capturedAt) {
        bytes += length;
        marks.addLast(new Mark(bytes, capturedAt));
        if (marks.size() > MAX_MARKS) marks.removeFirst();
    }

    synchronized void connected() {
        connectedAt = System.currentTimeMillis();
        bufferedAtConnect = audioMs();
        log.info("lag {}: connected in {} ms, {} ms of audio waiting to be sent", name, connectedAt - openedAt, bufferedAtConnect);
    }

    long audioMs() { return bytesPerMs <= 0 ? 0 : (long) (bytes / bytesPerMs); }

    /** When was the audio at this many seconds into the stream actually spoken? -1 if unknown. */
    synchronized long spokenAt(double audioSec) {
        if (bytesPerMs <= 0 || marks.isEmpty()) return -1;
        long at = (long) (audioSec * 1000 * bytesPerMs);
        for (var m : marks) {
            if (m.endByte >= at) return m.capturedAt - (long) ((m.endByte - at) / bytesPerMs);
        }
        var last = marks.peekLast();
        return last.capturedAt;
    }

    /** A result covering audio up to audioEndSec into the stream; audioEndSec <= 0 means the provider did not say. */
    void result(double audioEndSec, boolean isFinal, String text) {
        if (audioEndSec <= 0) {
            synchronized (this) {
                if (noPosition) return;
                noPosition = true;
            }
            log.warn("lag {}: provider did not report an audio position - lag cannot be measured on this stream", name);
            return;
        }
        var spoken = spokenAt(audioEndSec);
        if (spoken < 0) return;
        var lag = System.currentTimeMillis() - spoken;
        long worstInterim;
        synchronized (this) {
            if (firstResultAt == 0) {
                firstResultAt = System.currentTimeMillis();
                log.info("lag {}: first result {} ms after the stream opened, lag {} ms", name, firstResultAt - openedAt, lag);
            }
            if (!isFinal) {
                interimSinceFinal = Math.max(interimSinceFinal, lag);
                interimMax = Math.max(interimMax, lag);
                return;
            }
            finals++;
            lagSum += lag;
            lagMax = Math.max(lagMax, lag);
            worstInterim = interimSinceFinal;
            interimSinceFinal = 0;
        }
        var line = "lag {}: final {} ms (worst interim {} ms), at {} s of {} s sent, {} chunks queued, \"{}\"";
        var args = new Object[]{name, lag, worstInterim, String.format("%.1f", audioEndSec),
                String.format("%.1f", audioMs() / 1000.0), queued.get(), abbreviate(text)};
        // What the user sees is the interim text; a provider may hold a final back for seconds
        // (Deepgram does, routinely) while the words are already on screen.
        var seen = worstInterim > 0 ? worstInterim : lag;
        if (seen >= WARN_MS) { synchronized (this) { warned++; } log.warn(line, args); }
        else log.info(line, args);
    }

    synchronized String summary() {
        var wall = (System.currentTimeMillis() - openedAt) / 1000.0;
        return String.format("%.0f s open, %.1f s audio sent, connect %s, %d finals, final lag avg %d ms max %d ms, worst interim %d ms, %d slow",
                wall, audioMs() / 1000.0, connectedAt == 0 ? "never" : (connectedAt - openedAt) + " ms",
                finals, finals == 0 ? 0 : lagSum / finals, lagMax, interimMax, warned);
    }

    void closed(String how) {
        log.info("lag {}: closed ({}) - {}", name, how, summary());
    }

    static final long DISPLAY_WARN_MS = 300;

    /** Text posted to the UI (JavaFX) thread at postedAt has just been applied; report it if the UI is behind. */
    static void displayed(String what, long postedAt) {
        var behind = System.currentTimeMillis() - postedAt;
        if (behind >= DISPLAY_WARN_MS) log.warn("lag display: {} reached the window {} ms after it arrived (UI thread busy)", what, behind);
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= 40 ? s : s.substring(0, 37) + "...";
    }
}
