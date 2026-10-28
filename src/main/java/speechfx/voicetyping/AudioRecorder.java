package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records audio from the system microphone.
 * Records directly to MP3 format for efficient storage and bandwidth.
 */
public class AudioRecorder {
    private static final Logger log = LoggerFactory.getLogger(AudioRecorder.class);

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private TargetDataLine targetLine;
    private Thread recordingThread;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Path currentRecordingPath;
    private ByteArrayOutputStream audioBuffer;
    private AudioFormat audioFormat;
    private long recordingStartTime;

    private RecordingListener listener;
    private AudioChunkListener chunkListener;

    public interface RecordingListener {
        void onRecordingStarted();
        void onRecordingProgress(long durationMs);
        void onRecordingStopped(Path audioFile, long durationMs);
        void onRecordingError(String error);
    }

    public interface AudioChunkListener {
        void onChunk(byte[] chunk, int length);
    }

    public void setListener(RecordingListener listener) {
        this.listener = listener;
    }

    public void setChunkListener(AudioChunkListener chunkListener) {
        this.chunkListener = chunkListener;
    }

    public AudioFormat getAudioFormat() {
        RecordingSettings settings = VoiceTypingSettings.getRecordingSettings();
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                settings.sampleRate(),
                16,
                1,
                2,
                settings.sampleRate(),
                false
        );
    }

    public boolean isRecording() {
        return recording.get();
    }

    public long getRecordingDurationMs() {
        if (!recording.get()) return 0;
        return System.currentTimeMillis() - recordingStartTime;
    }

    public synchronized boolean startRecording() {
        if (recording.get()) {
            log.warn("Already recording");
            return false;
        }

        try {
            audioFormat = getAudioFormat();
            targetLine = Microphones.open(audioFormat);

            audioBuffer = new ByteArrayOutputStream();
            recording.set(true);
            recordingStartTime = System.currentTimeMillis();

            // Generate output file path (MP3 format)
            VoiceTypingSettings.ensureDirectoriesExist();
            String fileName = LocalDateTime.now().format(FILE_DATE_FORMAT) + "_audio.mp3";
            currentRecordingPath = VoiceTypingSettings.getTodaysTempDir().resolve(fileName);

            recordingThread = new Thread(this::recordLoop, "AudioRecorder");
            recordingThread.start();

            if (listener != null) {
                listener.onRecordingStarted();
            }

            log.info("Recording started: {}", currentRecordingPath.getFileName());
            return true;

        } catch (LineUnavailableException e) {
            notifyError("Microphone unavailable: " + e.getMessage());
            return false;
        }
    }

    private void recordLoop() {
        byte[] buffer = new byte[4096];
        RecordingSettings settings = VoiceTypingSettings.getRecordingSettings();
        int maxDurationMs = settings.maxDurationSeconds() * 1000;

        try {
            while (recording.get()) {
                int bytesRead = targetLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    audioBuffer.write(buffer, 0, bytesRead);
                    if (chunkListener != null) chunkListener.onChunk(buffer, bytesRead);
                }

                long duration = System.currentTimeMillis() - recordingStartTime;
                if (listener != null) {
                    listener.onRecordingProgress(duration);
                }

                // Auto-stop if max duration reached
                if (duration >= maxDurationMs) {
                    log.info("Max recording duration reached");
                    stopRecording();
                    break;
                }
            }
        } catch (Exception e) {
            notifyError("Recording error: " + e.getMessage());
        }
    }

    public synchronized Path stopRecording() {
        if (!recording.get()) {
            return null;
        }

        recording.set(false);
        long duration = System.currentTimeMillis() - recordingStartTime;

        if (targetLine != null) {
            targetLine.stop();
            targetLine.close();
            targetLine = null;
            Microphones.released();
        }

        // Wait for recording thread to finish
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }

        // Append 5 seconds of silence so the last spoken word is never cut off
        if (audioBuffer != null && audioFormat != null) {
            try {
                int silenceBytes = (int) (audioFormat.getSampleRate() * audioFormat.getFrameSize() * 5);
                audioBuffer.write(new byte[silenceBytes]);
            } catch (IOException e) {
                log.warn("Could not append silence padding: {}", e.getMessage());
            }
        }

        // Save the audio to file (MP3 format)
        Path savedPath = null;
        if (audioBuffer != null && audioBuffer.size() > 0) {
            try {
                savedPath = saveMp3File();
                log.info("Recording saved: {}", savedPath.getFileName());

                if (listener != null) {
                    listener.onRecordingStopped(savedPath, duration);
                }
            } catch (IOException e) {
                notifyError("Failed to save recording: " + e.getMessage());
            }
        }

        audioBuffer = null;
        return savedPath;
    }

    private Path saveMp3File() throws IOException {
        byte[] pcmData = audioBuffer.toByteArray();

        log.debug("Encoding audio to MP3...");

        // Encode PCM data directly to MP3 and save
        Path mp3Path = MP3AudioEncoder.savePcmAsMp3(pcmData, audioFormat, currentRecordingPath);

        if (mp3Path == null) {
            throw new IOException("Failed to encode audio to MP3 format");
        }

        log.debug("MP3 encoding complete");
        return mp3Path;
    }

    public void cancelRecording() {
        if (!recording.get()) {
            return;
        }

        recording.set(false);

        if (targetLine != null) {
            targetLine.stop();
            targetLine.close();
            targetLine = null;
            Microphones.released();
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }

        // Delete the temp file if it exists
        if (currentRecordingPath != null) {
            try {
                Files.deleteIfExists(currentRecordingPath);
            } catch (IOException e) {
                // Ignore
            }
        }

        audioBuffer = null;
        log.info("Recording cancelled");
    }

    private void notifyError(String error) {
        log.error(error);
        if (listener != null) {
            listener.onRecordingError(error);
        }
    }

    /**
     * Check if a microphone is available
     */
    public static boolean isMicrophoneAvailable() {
        try {
            AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            return AudioSystem.isLineSupported(info);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List available audio input devices
     */
    public static void listAudioInputDevices() {
        Logger logger = LoggerFactory.getLogger(AudioRecorder.class);
        logger.debug("Available audio input devices:");
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixerInfos) {
            Mixer mixer = AudioSystem.getMixer(info);
            Line.Info[] lineInfos = mixer.getTargetLineInfo();
            if (lineInfos.length > 0) {
                logger.debug("{}: {}", info.getName(), info.getDescription());
            }
        }
    }
}
