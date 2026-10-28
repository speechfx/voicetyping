package speechfx.voicetyping;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import org.slf4j.*;

/**
 * The files a history item left in the temp folder: &lt;date&gt;/&lt;yyyy-MM-dd_HH-mm-ss&gt;_log.log and _audio.mp3.
 * Looked up in the item's date folder first, then anywhere two levels down.
 */
public final class HistoryFiles {

    static final Logger log = LoggerFactory.getLogger(HistoryFiles.class);

    /** 2026-01-02T02:43:50.1247168 → 2026-01-02_02-43-50 */
    static String stamp(HistoryItem item) {
        var t = item.timestamp();
        return t.substring(0, Math.min(19, t.length())).replace("T", "_").replace(":", "-");
    }

    static Optional<Path> find(HistoryItem item, String suffix) {
        var dir = VoiceTypingSettings.getTempDir();
        var stamp = stamp(item);
        var exact = dir.resolve(stamp.substring(0, 10)).resolve(stamp + suffix);
        if (Files.exists(exact)) return Optional.of(exact);
        try (var walk = Files.walk(dir, 2)) {
            return walk.filter(p -> p.getFileName().toString().startsWith(stamp) && p.getFileName().toString().endsWith(suffix)).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<Path> log(HistoryItem item) { return find(item, "_log.log"); }

    public static Optional<Path> audio(HistoryItem item) { return find(item, "_audio.mp3"); }

    /** The log's text, or a line saying why there is none. */
    public static String logText(HistoryItem item) {
        var f = log(item);
        if (f.isEmpty()) return "No log file found for this transcription.\nLog files are created during transcription attempts.\n"
                + "Expected: " + stamp(item).substring(0, 10) + "/" + stamp(item) + "_log.log";
        try {
            return Files.readString(f.get());
        } catch (IOException e) {
            return "Error loading log file: " + e.getMessage();
        }
    }

    public static String details(HistoryItem item) {
        var d = new StringBuilder();
        d.append("Timestamp: ").append(item.getDisplayTimestamp()).append('\n');
        d.append("Recording duration: ").append(String.format("%.2f s", item.durationMs() / 1000.0)).append('\n');
        d.append("Transcript length: ").append(item.text().length()).append(" characters\n");
        d.append("Item ID: ").append(item.id()).append("\n\n");
        log(item).ifPresent(f -> {
            try {
                Files.readAllLines(f).stream().map(String::trim)
                        .filter(l -> l.contains("API Key:") || l.contains("Model:") || l.contains("Status:"))
                        .forEach(l -> d.append(l).append('\n'));
            } catch (IOException ignored) {
                // details are best effort
            }
        });
        return d.toString();
    }

    /** Deletes every audio, log and stray text file in the temp folder (kept: transcript_*.txt). */
    public static void deleteAll() {
        var dir = VoiceTypingSettings.getTempDir();
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir, 2)) {
            walk.filter(p -> {
                var n = p.getFileName().toString();
                return n.endsWith(".mp3") || n.endsWith(".log") || (n.endsWith(".txt") && !n.startsWith("transcript_"));
            }).forEach(p -> {
                try {
                    Files.delete(p);
                    log.info("Deleted file: {}", p);
                } catch (IOException e) {
                    log.error("Failed to delete file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Error deleting history files: {}", e.getMessage());
        }
    }

    private HistoryFiles() {}
}
