package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import javafx.scene.input.KeyCode;

/**
 * Every action the dictation window offers, declared once: DictationWindow builds its toolbar buttons, the
 * clickable key legend in the footer and the key bindings from this enum, so the window cannot advertise a key
 * nobody bound. Recording keys sit together on F5-F8, the speech service and account on F4; Save is the world-standard Ctrl+S (infrequent, so a combo is
 * fine); Esc closes. The editor inside binds no F-keys, and Copy all is the editor's own toolbar button (F7 here
 * calls it). F1 stays Help as in every Windows program.
 */
public enum DictationAction {

    START_PAUSE(KeyCode.F5, false, true),
    FINISH(KeyCode.F6, false, true),
    SAVE(KeyCode.S, true, true),
    COPY_ALL(KeyCode.F7, false, false),
    MICROPHONE(KeyCode.F8, false, true),
    SPEECH(KeyCode.F4, false, true),
    OPEN_FOLDER(KeyCode.F9, false, true),
    CLOSE(KeyCode.ESCAPE, false, true);

    public final KeyCode key;
    public final boolean ctrl;
    /** Has a button on the window's own toolbar (Copy all is on the editor's). */
    public final boolean onToolbar;

    DictationAction(KeyCode key, boolean ctrl, boolean onToolbar) {
        this.key = key;
        this.ctrl = ctrl;
        this.onToolbar = onToolbar;
    }

    /** "F5", "Ctrl+S", "Esc" */
    public String keyName() { return ctrl ? "Ctrl+" + key.getName() : this == CLOSE ? "Esc" : key.getName(); }

    public String label(DictationState state) {
        return switch (this) {
            case START_PAUSE -> switch (state) {
                case IDLE, STOPPED, ERROR -> "Record";
                case PAUSED -> "Resume";
                default -> "Pause";
            };
            case FINISH -> "Stop";
            case SAVE -> "Save";
            case COPY_ALL -> "Copy all";
            case OPEN_FOLDER -> "Folder";
            case MICROPHONE -> "Mic";
            case SPEECH -> "Speech";
            case CLOSE -> "Close";
        };
    }

    /** The legend's name for it, the same in every state. */
    public String legendLabel() { return this == START_PAUSE ? "Record/Pause" : label(DictationState.IDLE); }

    /** Material icon name in app-icons.tsv; Record turns into Pause while listening. */
    public String icon(DictationState state) {
        return switch (this) {
            case START_PAUSE -> label(state).equals("Pause") ? "pause" : "fiber_manual_record";
            case FINISH -> "stop";
            case SAVE -> "save";
            case COPY_ALL -> "content_copy";
            case OPEN_FOLDER -> "folder_open";
            case MICROPHONE -> "mic";
            case SPEECH -> "record_voice_over";
            case CLOSE -> "close";
        };
    }

    public String tooltip(DictationState state) {
        var name = switch (this) {
            case FINISH -> "Stop and finish";
            case SAVE -> "Save now";
            case OPEN_FOLDER -> "Open the notes folder";
            case MICROPHONE -> "Choose the microphone";
            case SPEECH -> "Speech service and account: " + SpeechMenu.describe() + ". Click to change, even while recording";
            default -> label(state);
        };
        return name + " (" + keyName() + ")";
    }

    /** Record and Stop carry their name beside the icon; the rest are icon-only with a tooltip. */
    public boolean showsText() { return this == START_PAUSE || this == FINISH; }

    public boolean enabledIn(DictationState state) {
        return this != FINISH || state != DictationState.IDLE;
    }
}
