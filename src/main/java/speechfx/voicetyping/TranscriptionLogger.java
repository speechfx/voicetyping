package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages per-transcription logging.
 * Creates a log file for each transcription attempt with details about the process.
 */
public class TranscriptionLogger {
    private static final Logger log = LoggerFactory.getLogger(TranscriptionLogger.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logPath;
    private final long startTime;

    /**
     * Create a new transcription logger for the given audio file
     * Audio file: YYYY-MM-dd_HH-mm-ss_audio.mp3
     * Log file: YYYY-MM-dd_HH-mm-ss_log.log
     */
    public TranscriptionLogger(Path audioFilePath) throws IOException {
        this.startTime = System.currentTimeMillis();

        // Extract timestamp from audio file name and create matching log file
        String audioFileName = audioFilePath.getFileName().toString();
        // Replace _audio.mp3 with _log.log
        String logFileName = audioFileName.replaceAll("_audio\\.mp3$|_audio\\.wav$", "_log.log");
        this.logPath = audioFilePath.getParent().resolve(logFileName);

        if (!Files.exists(logPath)) {
            Files.createFile(logPath);
        }

        // Write header
        writeHeader(audioFilePath);
    }

    /**
     * Write the transcription log header
     */
    private void writeHeader(Path audioFilePath) {
        try {
            long fileSize = Files.size(audioFilePath);
            String header = String.format("""
                    ═══════════════════════════════════════════════════════════
                    Transcription Log
                    ═══════════════════════════════════════════════════════════
                    Audio File: %s
                    File Size: %s
                    Created: %s
                    ───────────────────────────────────────────────────────────
                    """,
                    audioFilePath.getFileName(),
                    formatBytes(fileSize),
                    LocalDateTime.now().format(DATE_TIME_FORMAT));

            appendToLog(header);
        } catch (IOException e) {
            log.error("Failed to write log header", e);
        }
    }

    /**
     * Log transcription start with metadata
     */
    public void logStart(String apiKeyIdentifier, String model) {
        String entry = String.format("""

                Attempt %d:
                  Start Time: %s
                  API Key: %s
                  Model: %s
                """,
                countAttempts(),
                LocalDateTime.now().format(TIME_FORMAT),
                maskApiKey(apiKeyIdentifier),
                model);

        appendToLog(entry);
    }

    /**
     * Log file upload progress
     */
    public void logFileUpload(String status) {
        String entry = String.format("  File Upload: %s\n", status);
        appendToLog(entry);
    }

    /**
     * Log file processing status
     */
    public void logFileProcessing(String status) {
        String entry = String.format("  File Processing: %s\n", status);
        appendToLog(entry);
    }

    /**
     * Log transcription completion
     */
    public void logSuccess(String transcriptionText, int charCount, double durationSeconds) {
        String entry = String.format("""
                  Status: SUCCESS
                  Duration: %.2f seconds
                  Transcription Length: %d characters
                  Transcription:
                  %s
                """,
                durationSeconds,
                charCount,
                truncateText(transcriptionText, 500));

        appendToLog(entry);
        logDuration();
    }

    /**
     * Log transcription failure
     */
    public void logFailure(String errorMessage) {
        String entry = String.format("""
                  Status: FAILED
                  Error: %s
                """,
                errorMessage);

        appendToLog(entry);
        logDuration();
    }

    /**
     * Log processing duration
     */
    private void logDuration() {
        long durationMs = System.currentTimeMillis() - startTime;
        double durationSeconds = durationMs / 1000.0;
        String footer = String.format("""
                  Total Processing Time: %.2f seconds
                ───────────────────────────────────────────────────────────
                """, durationSeconds);

        appendToLog(footer);
    }

    /**
     * Get the log file path
     */
    public Path getLogPath() {
        return logPath;
    }

    /**
     * Get elapsed time in seconds since logging started
     */
    public double getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * Append text to the log file
     */
    private void appendToLog(String text) {
        try {
            Files.write(logPath, text.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write to transcription log", e);
        }
    }

    /**
     * Count existing attempts in the log file
     */
    private int countAttempts() {
        try {
            if (!Files.exists(logPath)) return 1;
            String content = Files.readString(logPath);
            return (int) content.lines().filter(l -> l.contains("Attempt ")).count() + 1;
        } catch (IOException e) {
            return 1;
        }
    }

    /**
     * Mask API key for logging (show first 10 and last 4 chars)
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 15) {
            return "***";
        }
        return apiKey.substring(0, Math.min(10, apiKey.length())) + "..." +
               apiKey.substring(Math.max(0, apiKey.length() - 4));
    }

    /**
     * Truncate text to a maximum length
     */
    private static String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...\n  [Truncated, full text available above]";
    }

    /**
     * Format bytes to human-readable size
     */
    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
