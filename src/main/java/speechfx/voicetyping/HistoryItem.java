package speechfx.voicetyping;

import speechfx.dictation.*;

import datapotter.datahelper.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Data
public final class HistoryItem extends HistoryItem_A {

    static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    String id;
    String timestamp;
    long durationMs;
    String text;

    public HistoryItem() {
        id = UUID.randomUUID().toString();
    }

    public HistoryItem(String text, long durationMs) {
        id = UUID.randomUUID().toString();
        timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        this.text = text;
        this.durationMs = durationMs;
    }

    public HistoryItem(String text, long durationMs, String fileTimestamp) {
        id = UUID.randomUUID().toString();
        // Convert 2026-01-02_03-03-16 -> 2026-01-02T03:03:16
        var iso = fileTimestamp.replaceAll("_", "T");
        var lastDash = iso.lastIndexOf('-');
        var prevDash = iso.lastIndexOf('-', lastDash - 1);
        if (prevDash > 0 && lastDash > prevDash) {
            timestamp = iso.substring(0, prevDash) + ":" + iso.substring(prevDash + 1, lastDash) + ":" + iso.substring(lastDash + 1);
        } else {
            timestamp = iso;
        }
        this.text = text;
        this.durationMs = durationMs;
    }

    public LocalDateTime getDateTime() {
        try {
            return LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /**
     * Long-dictation items carry the file name's tail ("2026-10-07T22:43:00Tdictation.pcm"), so only the first
     * 19 characters are read. Shown as stored when even that does not parse, never as "now" like getDateTime().
     */
    public String getDisplayTimestamp() {
        if (timestamp == null) return "time unknown";
        try {
            return LocalDateTime.parse(timestamp.substring(0, Math.min(19, timestamp.length()))).format(DISPLAY_FORMAT);
        } catch (Exception e) {
            return timestamp;
        }
    }

    public String getPreview() {
        if (text == null) return "";
        var preview = text.replace("\n", " ").trim();
        return preview.length() > 80 ? preview.substring(0, 77) + "..." : preview;
    }
}
