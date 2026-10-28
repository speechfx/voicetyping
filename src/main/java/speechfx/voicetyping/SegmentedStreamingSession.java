package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * Turns a chain of short provider streams into one apparently endless dictation.
 *
 * A segment is closed deliberately every few minutes, in a gap between words, and a fresh one
 * opened; audio arriving mid-switch is parked and handed to the new segment. A segment that
 * fails, or stays connected but returns nothing while the speaker is clearly talking, is
 * abandoned at once: the next segment comes from SpeechChain (another key, or the other
 * provider), and the audio the dead one never confirmed is replayed into it, so nothing said
 * in the meantime is lost.
 */
public final class SegmentedStreamingSession {

    private static final Logger log = LoggerFactory.getLogger(SegmentedStreamingSession.class);

    static final int MAX_BARREN_FAILURES = 6, REPLAY_SECONDS = 45;
    static final long STALL_MS = 15_000;
    static final int STOP_WAIT_SECONDS = 4;

    final SpeechChain chain;
    final LongRecordingSettings cfg;
    final DictationListener out;

    AudioFormat format;
    SilenceDetector silence;
    UncommittedAudio uncommitted;
    int bytesPerSecond;

    volatile StreamingTranscriptionProvider current;
    volatile boolean active, switching, paused;
    volatile int segment, barrenFailures;
    volatile long segmentStart, speechMsWithoutText;

    final Queue<UncommittedAudio.Chunk> parked = new ConcurrentLinkedQueue<>();
    volatile int pauses;
    volatile long pausedAt;
    final StringBuilder committed = new StringBuilder();
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "Dictation-Segments");
        t.setDaemon(true);
        return t;
    });

    public SegmentedStreamingSession(SpeechChain chain, LongRecordingSettings cfg, DictationListener out) {
        this.chain = chain;
        this.cfg = cfg;
        this.out = out;
    }

    public void start(AudioFormat fmt) {
        this.format = fmt;
        bytesPerSecond = (int) (fmt.getSampleRate() * fmt.getFrameSize());
        silence = new SilenceDetector(cfg.silenceRmsThreshold(), bytesPerSecond);
        uncommitted = new UncommittedAudio(bytesPerSecond, REPLAY_SECONDS);
        active = true;
        committed.setLength(0);
        segment = 0;
        openSegment();
    }

    public void onAudioChunk(byte[] chunk, int length) {
        if (!active || paused) return;
        var copy = Arrays.copyOf(chunk, length);
        var timed = new UncommittedAudio.Chunk(copy, System.currentTimeMillis());
        synchronized (parked) {
            if (switching) { parked.add(timed); return; }
        }
        var svc = current;
        if (svc != null) svc.onAudioChunk(copy, length, timed.capturedAt());
        uncommitted.add(timed);
        silence.accept(copy, length);
        if (SilenceDetector.rms(copy, length) >= cfg.silenceRmsThreshold()) speechMsWithoutText += length * 1000L / bytesPerSecond;
        if (speechMsWithoutText >= STALL_MS) failover(SpeechFailure.STALLED, "no text for " + STALL_MS / 1000 + " s of speech");
        else if (rolloverDue()) rollover("segment age");
    }

    boolean rolloverDue() {
        var age = System.currentTimeMillis() - segmentStart;
        if (age >= cfg.segmentHardMaxSeconds() * 1000L) return true;
        return age >= cfg.segmentSeconds() * 1000L && silence.quietMillis() >= cfg.silenceGapMillis();
    }

    void openSegment() {
        segment++;
        segmentStart = System.currentTimeMillis();
        speechMsWithoutText = 0;
        silence.reset();
        var svc = chain.next();
        svc.setListener(new TranscriptionErrorListener(this::onSegmentFailed));
        svc.setResultListener(this::onSegmentResult);
        current = svc;
        svc.onRecordingStarted(format);
        synchronized (parked) {
            switching = false;
            drainParked(svc);
        }
        out.onState(DictationState.LISTENING, "segment " + segment);
        log.info("dictation segment {} open on {}", segment, describe(svc));
    }

    void drainParked(StreamingTranscriptionProvider svc) {
        UncommittedAudio.Chunk c;
        long bytes = 0, oldest = 0;
        while ((c = parked.poll()) != null) {
            if (oldest == 0) oldest = c.capturedAt();
            bytes += c.data().length;
            svc.onAudioChunk(c.data(), c.data().length, c.capturedAt());
        }
        if (bytes > 0) log.info("lag segment {}: handed {} ms of parked audio to the new stream, oldest spoken {} ms ago",
                segment, bytes * 1000 / bytesPerSecond, System.currentTimeMillis() - oldest);
    }

    void onSegmentResult(String text, boolean isFinal) {
        if (text == null || text.isBlank()) return;
        speechMsWithoutText = 0;
        barrenFailures = 0;
        if (isFinal) {
            uncommitted.clear();
            var svc = current;
            if (svc != null) chain.succeeded(svc);
            if (!committed.isEmpty()) committed.append(' ');
            committed.append(text.trim());
            out.onFinalText(text.trim());
            out.onInterimText("");
        } else {
            out.onInterimText(text.trim());
        }
    }

    /** Close the current segment and open the next on whatever provider and account are chosen now. Audio is parked meanwhile. */
    public void switchNow(String why) {
        if (active && !paused) rollover(why);
    }

    void rollover(String why) {
        if (switching || !active) return;
        switching = true;
        out.onState(DictationState.SWITCHING, why);
        var closing = current;
        current = null;
        closeQuietly(closing).whenComplete((t, err) -> {
            if (!active || paused) { switching = false; return; }
            if (err != null) log.warn("segment {} closed with error: {}", segment, err.toString());
            timer.execute(this::openSegment);
        });
    }

    void onSegmentFailed(String error) {
        var f = SpeechFailure.fromMessage(error);
        failover(f, error != null && error.startsWith(f + ": ") ? error.substring(f.name().length() + 2) : error);
    }

    /** Abandon the current segment without waiting for it, and replay what it never confirmed. */
    synchronized void failover(SpeechFailure f, String why) {
        if (!active || paused || switching) return;
        var dead = current;
        if (++barrenFailures >= MAX_BARREN_FAILURES) {
            log.error("dictation stopping after {} failures without any text: {}", barrenFailures, why);
            active = false;
            current = null;
            closeQuietly(dead);
            out.onInterimText("");
            out.onState(DictationState.ERROR, why);
            return;
        }
        if (dead != null) chain.failed(dead, f);
        // Its audio is replayed into the next segment, so anything it still says would arrive twice.
        if (dead != null) dead.setResultListener(null);
        var replay = uncommitted.snapshot();
        synchronized (parked) {
            switching = true;
            current = null;
            var later = new ArrayList<>(parked);
            parked.clear();
            parked.addAll(replay);
            parked.addAll(later);
        }
        closeQuietly(dead);
        log.warn("dictation segment {} on {} failed ({}: {}) - replaying {} s into a new segment",
                segment, describe(dead), f, why, uncommitted.millis(bytesPerSecond) / 1000);
        out.onState(DictationState.RECONNECTING, f + " on " + SpeechChain.shortName(dead));
        timer.schedule(this::openSegment, 300L * barrenFailures, TimeUnit.MILLISECONDS);
    }

    /** Finalise the current segment and open none, so nothing sits idle against the server. */
    public void pause() {
        if (!active || paused) return;
        paused = true;
        pausedAt = System.currentTimeMillis();
        var gen = ++pauses;
        switching = true;
        var closing = current;
        current = null;
        out.onInterimText("");
        log.info("dictation paused: closing segment {} on {}", segment, describe(closing));
        closeQuietly(closing).whenComplete((t, err) -> {
            log.info("dictation pause: segment {} finalised {} ms after pause{}", segment,
                    System.currentTimeMillis() - pausedAt, paused && pauses == gen ? "" : " (already resumed)");
            // A resume that came first owns the buffers and the switching flag by now; leave them alone.
            if (!paused || pauses != gen) return;
            parked.clear();
            uncommitted.clear();
            switching = false;
        });
    }

    public void resume() {
        if (!active || !paused) return;
        paused = false;
        log.info("dictation resumed after {} s paused", (System.currentTimeMillis() - pausedAt) / 1000);
        synchronized (parked) {
            switching = true;
            parked.clear();
        }
        uncommitted.clear();
        timer.execute(this::openSegment);
    }

    CompletableFuture<String> closeQuietly(StreamingTranscriptionProvider svc) {
        if (svc == null) return CompletableFuture.completedFuture("");
        try {
            return svc.transcribeAsync(null)
                    .orTimeout(cfg.finalizeTimeoutSeconds(), TimeUnit.SECONDS)
                    .exceptionally(e -> "");
        } catch (Exception e) {
            return CompletableFuture.completedFuture("");
        }
    }

    /**
     * Stop without waiting on a stream that is still catching up (after a replay it can take
     * many seconds): resolve after at most STOP_WAIT_SECONDS. The stream keeps running, and
     * any final text it still sends goes to the listener as usual.
     */
    public CompletableFuture<String> stop() {
        if (!active) return CompletableFuture.completedFuture(transcript());
        active = false;
        out.onState(DictationState.FINISHING, "");
        var svc = current;
        current = null;
        parked.clear();
        var tail = svc == null ? CompletableFuture.completedFuture("")
                : closeQuietly(svc).copy().completeOnTimeout("", STOP_WAIT_SECONDS, TimeUnit.SECONDS);
        return tail.thenApply(last -> {
            out.onInterimText("");
            out.onState(DictationState.STOPPED, "");
            return transcript();
        });
    }

    public String transcript() { return committed.toString().trim(); }

    public int segmentCount() { return segment; }

    /** "deepgram legal@…" for the status line, or "" between segments. */
    public String describeCurrent() { return describe(current); }

    static String describe(StreamingTranscriptionProvider p) {
        if (p == null) return "";
        var k = p.key();
        return SpeechChain.shortName(p) + (k == null || k.account() == null ? "" : " " + k.account());
    }

    public void shutdown() {
        active = false;
        timer.shutdownNow();
    }
}
