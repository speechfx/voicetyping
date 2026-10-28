package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages retry logic for failed transcriptions.
 * Stores the last failed recording and allows retrying it.
 */
public class RetryManager {
    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    private Path lastFailedAudioPath;
    private String lastFailureReason;
    private long lastFailureTime;

    /**
     * Mark a transcription as failed
     */
    public synchronized void markFailed(Path audioFilePath, String failureReason) {
        this.lastFailedAudioPath = audioFilePath;
        this.lastFailureReason = failureReason;
        this.lastFailureTime = System.currentTimeMillis();

        log.info("Marked transcription as failed: {} - {}",
                audioFilePath.getFileName(), failureReason);
    }

    /**
     * Check if there's a retry available
     */
    public synchronized boolean hasFailedTranscription() {
        if (lastFailedAudioPath == null) {
            return false;
        }

        // Check if file still exists
        if (!Files.exists(lastFailedAudioPath)) {
            lastFailedAudioPath = null;
            return false;
        }

        return true;
    }

    /**
     * Get the path to retry
     */
    public synchronized Path getLastFailedAudioPath() {
        if (!hasFailedTranscription()) {
            return null;
        }
        return lastFailedAudioPath;
    }

    /**
     * Get the reason for last failure
     */
    public synchronized String getLastFailureReason() {
        return lastFailureReason;
    }

    /**
     * Get the time of last failure
     */
    public synchronized long getLastFailureTime() {
        return lastFailureTime;
    }

    /**
     * Clear the retry state (called after successful retry)
     */
    public synchronized void clearRetryState() {
        lastFailedAudioPath = null;
        lastFailureReason = null;
        lastFailureTime = 0;
    }
}
