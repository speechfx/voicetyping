package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Where a dictation session lands on disk, and the autosave that means a crash costs nothing. */
public final class DictationNotes {

    private static final Logger log = LoggerFactory.getLogger(DictationNotes.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private DictationNotes() {}

    public static Path notesDir() {
        return VoiceTypingSettings.getAppHome().resolve("notes");
    }

    public static String newStamp() {
        return LocalDateTime.now().format(STAMP);
    }

    /** kind: "dictation" for the dictation window, "into-notepad" and the like for on-spot dictation. */
    public static Path pathFor(String stamp, String kind) {
        return notesDir().resolve(stamp + "_" + kind + ".md");
    }

    public static Path save(String stamp, String text) {
        return save(stamp, "dictation", text);
    }

    public static Path save(String stamp, String kind, String text) {
        var target = pathFor(stamp, kind);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            log.error("could not save dictation note {}: {}", target, e.getMessage());
            return null;
        }
    }

    public static void openFolder() {
        try {
            var dir = notesDir();
            Files.createDirectories(dir);
            new ProcessBuilder("explorer.exe", dir.toString()).start();
        } catch (IOException e) {
            log.warn("could not open notes folder: {}", e.getMessage());
        }
    }
}
