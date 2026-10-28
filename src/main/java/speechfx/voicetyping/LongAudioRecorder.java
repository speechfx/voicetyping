package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capture for the long dictation mode: raw PCM straight to disk, pausable, no ceiling.
 *
 * The short flow accumulates everything in a ByteArrayOutputStream and encodes MP3
 * at the end, which is fine for two minutes and is 159 MB of heap for thirty. Here every
 * chunk is handed to the listener and written through to a file, so memory is flat
 * however long the session runs.
 */
public final class LongAudioRecorder {

    private static final Logger log = LoggerFactory.getLogger(LongAudioRecorder.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    final AudioRecorder.AudioChunkListener chunks;
    final LongRecordingSettings cfg;
    final Runnable onSafetyStop;

    final AtomicBoolean running = new AtomicBoolean(false);
    volatile boolean paused;
    volatile TargetDataLine line;
    volatile OutputStream sink;
    volatile Path audioPath;
    Thread thread;
    long startedAt, pausedAccum, pausedAt;

    public LongAudioRecorder(LongRecordingSettings cfg, AudioRecorder.AudioChunkListener chunks, Runnable onSafetyStop) {
        this.cfg = cfg;
        this.chunks = chunks;
        this.onSafetyStop = onSafetyStop;
    }

    public AudioFormat format() {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, cfg.sampleRate(), 16, 1, 2, cfg.sampleRate(), false);
    }

    public boolean isRunning() { return running.get(); }
    public boolean isPaused() { return paused; }
    public Path audioPath() { return audioPath; }

    public long elapsedMs() {
        if (!running.get()) return 0;
        var extra = paused ? System.currentTimeMillis() - pausedAt : 0;
        return System.currentTimeMillis() - startedAt - pausedAccum - extra;
    }

    public synchronized boolean start() throws LineUnavailableException, IOException {
        if (running.get()) return false;
        line = Microphones.open(format());

        VoiceTypingSettings.ensureDirectoriesExist();
        audioPath = VoiceTypingSettings.getTodaysTempDir().resolve(LocalDateTime.now().format(STAMP) + "_dictation.pcm");
        sink = cfg.keepAudio() ? new BufferedOutputStream(Files.newOutputStream(audioPath), 1 << 16) : null;

        running.set(true);
        paused = false;
        startedAt = System.currentTimeMillis();
        pausedAccum = 0;
        thread = new Thread(this::loop, "LongAudioRecorder");
        thread.setDaemon(true);
        thread.start();
        log.info("dictation capture started at {} Hz -> {}", cfg.sampleRate(), cfg.keepAudio() ? audioPath.getFileName() : "(audio not kept)");
        return true;
    }

    void loop() {
        var buffer = new byte[4096];
        var safetyMs = cfg.safetyStopMinutes() * 60_000L;
        try {
            while (running.get()) {
                if (paused) { Thread.sleep(50); continue; }
                var l = line;
                if (l == null) break;
                var read = l.read(buffer, 0, buffer.length);
                if (read > 0) {
                    if (sink != null) sink.write(buffer, 0, read);
                    if (chunks != null) chunks.onChunk(buffer, read);
                }
                if (safetyMs > 0 && elapsedMs() >= safetyMs) {
                    log.warn("dictation safety stop after {} minutes", cfg.safetyStopMinutes());
                    if (onSafetyStop != null) onSafetyStop.run();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("dictation capture failed: {}", e.getMessage());
        }
    }

    public synchronized void pause() {
        if (!running.get() || paused) return;
        paused = true;
        pausedAt = System.currentTimeMillis();
        var l = line;
        if (l != null) { l.stop(); l.flush(); }
    }

    public synchronized void resume() {
        if (!running.get() || !paused) return;
        pausedAccum += System.currentTimeMillis() - pausedAt;
        paused = false;
        var l = line;
        if (l != null) l.start();
    }

    public synchronized void switchTo(String device) throws LineUnavailableException {
        if (!running.get()) return;
        var next = Microphones.open(device, format());
        if (paused) next.stop();
        var old = line;
        line = next;
        if (old != null) { old.stop(); old.close(); }
        log.info("dictation switched microphone to {}", device);
    }

    public synchronized Path stop() {
        var ranFor = elapsedMs();
        if (!running.getAndSet(false)) return audioPath;
        var l = line;
        line = null;
        if (l != null) { l.stop(); l.close(); }
        Microphones.released();
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            thread = null;
        }
        var s = sink;
        sink = null;
        if (s != null) {
            try { s.close(); } catch (Exception e) { log.warn("could not close audio file: {}", e.getMessage()); }
        }
        log.info("dictation capture stopped after {} s", ranFor / 1000);
        return audioPath;
    }
}
