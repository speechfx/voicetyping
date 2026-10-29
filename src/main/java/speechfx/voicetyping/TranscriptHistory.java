package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the history of transcriptions.
 * History is built dynamically from disk files (mp3, txt, log).
 * No history.xml file - everything is disk-based.
 */
public class TranscriptHistory {

    private static final Logger log = LoggerFactory.getLogger(TranscriptHistory.class);

    private static final int MAX_HISTORY_SIZE = 100;

    private List<HistoryItem> items;
    private final List<HistoryChangeListener> listeners = new ArrayList<>();

    public interface HistoryChangeListener {
        void onHistoryChanged();
    }

    public TranscriptHistory() {
        this.items = new ArrayList<>();
        loadFromDisk();
    }

    public void addListener(HistoryChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(HistoryChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (HistoryChangeListener listener : listeners) {
            listener.onHistoryChanged();
        }
    }

    /**
     * Add a transcript with audio file reference.
     * All related files (audio, log, transcript) will use the same timestamp.
     */
    public void add(String text, long recordingDurationMs, Path audioFile) {
        if (text == null || text.isBlank()) {
            return;
        }

        // Extract timestamp from audio filename (e.g., 2026-01-02_03-03-16 from 2026-01-02_03-03-16_audio.mp3)
        String audioFileName = audioFile.getFileName().toString();
        String timestamp = extractTimestampFromFilename(audioFileName);

        HistoryItem item = new HistoryItem(text, recordingDurationMs, timestamp);
        items.add(0, item); // Add to beginning

        // Save transcript as text file with audio file's timestamp
        saveTranscriptAsTextFile(item, audioFile);

        // Trim to max size in memory
        while (items.size() > MAX_HISTORY_SIZE) {
            items.remove(items.size() - 1);
        }

        notifyListeners();
    }

    /**
     * Extract timestamp from audio filename.
     * Expects format: YYYY-MM-dd_HH-mm-ss_audio.mp3
     * Returns: YYYY-MM-dd_HH-mm-ss
     */
    private String extractTimestampFromFilename(String filename) {
        // Remove _audio.mp3 or _audio.wav suffix
        String withoutExtension = filename.replaceAll("_audio\\.(mp3|wav)$", "");
        return withoutExtension;
    }

    /**
     * Save a transcript as a text file in the date-based temp directory.
     * Uses the audio file's timestamp to create consistent filenames.
     */
    private void saveTranscriptAsTextFile(HistoryItem item, Path audioFile) {
        try {
            // Extract timestamp from audio filename: 2026-01-02_03-03-16_audio.mp3 -> 2026-01-02_03-03-16
            String audioFileName = audioFile.getFileName().toString();
            String timestamp = audioFileName.replaceAll("_audio\\.(mp3|wav)$", "");

            // Extract date from timestamp: 2026-01-02_03-03-16 -> 2026-01-02
            String dateFolder = timestamp.substring(0, 10);

            // Get the date-based directory and ensure it exists
            Path dateTempDir = VoiceTypingSettings.getTempDir().resolve(dateFolder);
            if (!Files.exists(dateTempDir)) {
                Files.createDirectories(dateTempDir);
            }

            String filename = timestamp + "_transcript.txt";
            Path txtFile = dateTempDir.resolve(filename);

            // Write transcript with metadata
            StringBuilder content = new StringBuilder();
            content.append("Timestamp: ").append(item.getDisplayTimestamp()).append("\n");
            content.append("Duration: ").append(item.durationMs()).append(" ms\n");
            content.append("---\n\n");
            content.append(item.text());

            Files.writeString(txtFile, content.toString(), StandardCharsets.UTF_8);
            log.info("Saved transcript to: {}", txtFile.getFileName());
        } catch (Exception e) {
            log.error("Failed to save transcript as text file: {}", e.getMessage());
        }
    }

    /**
     * Get the most recent transcription.
     */
    public HistoryItem getLatest() {
        return items.isEmpty() ? null : items.get(0);
    }

    /**
     * Get the text of the most recent transcription.
     */
    public String getLatestText() {
        HistoryItem latest = getLatest();
        return latest != null ? latest.text() : null;
    }

    /**
     * Get all history items (in-memory).
     */
    public List<HistoryItem> getAll() {
        return new ArrayList<>(items);
    }

    /**
     * Get history item by ID.
     */
    public HistoryItem getById(String id) {
        return items.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Delete a history item.
     */
    public void delete(String id) {
        items.removeIf(item -> item.id().equals(id));
        notifyListeners();
    }

    /**
     * Clear all history (both in-memory and disk files).
     */
    public void clear() {
        items.clear();
        notifyListeners();
    }

    /**
     * Get the number of items in history.
     */
    public int size() {
        return items.size();
    }

    /**
     * Load history from disk by scanning temp folders for transcript files.
     * Called on startup to rebuild history from files.
     * Files are named: YYYY-MM-dd_HH-mm-ss_transcript.txt
     */
    private void loadFromDisk() {
        try {
            Path tempDir = VoiceTypingSettings.getTempDir();
            if (!Files.exists(tempDir)) {
                return;
            }

            int loadedCount = 0;

            // Load transcript text files from all date folders
            // Files are named: 2026-01-02_14-17-31_transcript.txt
            try (var stream = Files.walk(tempDir, 2)) {
                var txtFiles = stream
                        .filter(p -> p.getFileName().toString().endsWith("_transcript.txt"))
                        .sorted((a, b) -> {
                            // Sort by filename (which contains timestamp) in descending order
                            return b.getFileName().toString().compareTo(a.getFileName().toString());
                        })
                        .toList();

                for (Path txtFile : txtFiles) {
                    try {
                        String content = Files.readString(txtFile, StandardCharsets.UTF_8);

                        // Parse the text file to extract transcript
                        String transcriptText = extractTranscriptFromFile(content);
                        long duration = extractDurationFromFile(content);

                        if (transcriptText != null && !transcriptText.isBlank()) {
                            // Extract timestamp from filename: 2026-01-02_14-17-31_transcript.txt
                            String fileName = txtFile.getFileName().toString();
                            String fileTimestamp = fileName.replace("_transcript.txt", "");

                            HistoryItem item = new HistoryItem(transcriptText, duration, fileTimestamp);
                            items.add(item);
                            loadedCount++;

                            if (loadedCount >= MAX_HISTORY_SIZE) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to load transcript file {}: {}", txtFile.getFileName(), e.getMessage());
                    }
                }
            }

            if (loadedCount > 0) {
                log.info("Loaded {} history items from disk", loadedCount);
            }
        } catch (Exception e) {
            log.error("Failed to scan temp folders for history: {}", e.getMessage());
        }
    }

    /**
     * Extract transcript text from a saved transcript file.
     */
    private String extractTranscriptFromFile(String content) {
        // Text files have format:
        // Timestamp: ...
        // Duration: ...
        // ---
        //
        // [actual transcript]

        int separatorIndex = content.indexOf("---");
        if (separatorIndex >= 0 && separatorIndex + 3 < content.length()) {
            return content.substring(separatorIndex + 3).trim();
        }
        return content; // Fallback: return entire content
    }

    /**
     * Extract duration from a saved transcript file.
     */
    private long extractDurationFromFile(String content) {
        try {
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (line.startsWith("Duration:")) {
                    String durationStr = line.substring(9).trim().replace(" ms", "");
                    return Long.parseLong(durationStr);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }
}
