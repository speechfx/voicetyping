package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.*;
import javafx.scene.input.*;
import javafx.util.Duration;
import java.nio.file.Path;

/** Wires the window, the capture and the segmented stream into one long dictation session. */
public final class DictationSession implements DictationListener {

    private static final Logger log = LoggerFactory.getLogger(DictationSession.class);

    final DictationWindow window;
    final SpeechChain chain;
    final TranscriptHistory history;

    LongRecordingSettings cfg;
    SegmentedStreamingSession stream;
    LongAudioRecorder recorder;
    Timeline autosave;
    String stamp;
    volatile DictationState state = DictationState.IDLE;

    public DictationSession(DictationWindow window, SpeechChain chain,
                            TranscriptHistory history) {
        this.window = window;
        this.chain = chain;
        this.history = history;
        window.onAction(this::dispatch);
        window.onClose(this::closeRequested);
        window.elapsedText(this::elapsedText);
    }

    public boolean isActive() {
        return state != DictationState.IDLE && state != DictationState.STOPPED && state != DictationState.ERROR;
    }

    public void open() {
        window.show();
    }

    /** The speech service or account changed: carry on on the new choice without a gap. */
    public void switchSpeech(String why) {
        var s = stream;
        if (s != null && isActive()) s.switchNow(why);
    }

    /** Carries on a dictation begun on the on-spot bar: its text first, then recording continues here. FX thread. */
    public void continueWith(String text) {
        window.show();
        if (!isActive()) start();
        if (text != null && !text.isBlank()) window.appendFinal(text);
    }

    void dispatch(DictationAction action) {
        switch (action) {
            case START_PAUSE -> togglePause();
            case FINISH -> finish();
            case SAVE -> saveNow(true);
            case COPY_ALL -> window.copyAll();
            case OPEN_FOLDER -> DictationNotes.openFolder();
            case MICROPHONE -> MicMenu.show(window.button(action), this::useMicrophone);
            case SPEECH -> SpeechMenu.show(window.button(action));
            case CLOSE -> closeRequested();
        }
    }

    /** From F8, or the tray: the next recording uses it, and one in progress moves to it without a gap. FX thread. */
    public void useMicrophone(String name) {
        var error = Microphones.chooseLive(isActive() ? recorder : null, name);
        if (error != null) Dialogs.message("Voice Typing", "Could not switch to " + name + ":\n" + error
                + "\n\nStill recording from " + Microphones.inUse() + ".", Dialogs.Kind.WARNING);
        var m = window.mic();
        if (m != null) m.refresh();
    }

    void togglePause() {
        switch (state) {
            case LISTENING, SWITCHING, RECONNECTING -> pause();
            case PAUSED -> resume();
            default -> start();
        }
    }

    void start() {
        if (isActive()) return;
        cfg = VoiceTypingSettings.getLongRecordingSettings();
        stamp = DictationNotes.newStamp();
        window.appendTimestamp();
        setState(DictationState.STARTING);
        stream = new SegmentedStreamingSession(chain, cfg, this);
        recorder = new LongAudioRecorder(cfg, (b, n) -> stream.onAudioChunk(b, n), () -> FxThread.run(this::finish));
        try {
            recorder.start();
        } catch (Exception e) {
            log.error("could not start dictation capture: {}", e.getMessage());
            setState(DictationState.ERROR);
            Dialogs.message("Voice Typing", "Microphone unavailable:\n" + e.getMessage(), Dialogs.Kind.ERROR);
            return;
        }
        stream.start(recorder.format());
        startAutosave();
    }

    void pause() {
        if (recorder == null) return;
        recorder.pause();
        stream.pause();
        setState(DictationState.PAUSED);
        saveNow(false);
    }

    void resume() {
        if (recorder == null) return;
        setState(DictationState.LISTENING);
        stream.resume();
        recorder.resume();
    }

    void finish() {
        if (state == DictationState.IDLE || state == DictationState.STOPPED) return;
        setState(DictationState.FINISHING);
        stopAutosave();
        if (recorder != null) recorder.stop();
        var s = stream;
        if (s == null) { setState(DictationState.STOPPED); return; }
        s.stop().whenComplete((text, err) -> FxThread.run(() -> {
            s.shutdown();
            setState(DictationState.STOPPED);
            var saved = saveNow(false);
            recordHistory(saved);
            if (err != null) log.warn("dictation finished with error: {}", err.toString());
        }));
    }

    void recordHistory(Path saved) {
        var text = window.text();
        if (history == null || text == null || text.isBlank()) return;
        var audio = recorder != null && cfg.keepAudio() ? recorder.audioPath() : null;
        history.add(text, recorder == null ? 0 : recorder.elapsedMs(), audio);
        log.info("dictation saved: {}", saved == null ? "(not saved)" : saved.getFileName());
    }

    void startAutosave() {
        stopAutosave();
        var everyMs = Math.max(5, cfg.autosaveSeconds()) * 1000;
        autosave = new Timeline(new KeyFrame(Duration.millis(everyMs), e -> saveNow(false)));
        autosave.setCycleCount(Animation.INDEFINITE);
        autosave.play();
    }

    void stopAutosave() {
        if (autosave != null) { autosave.stop(); autosave = null; }
    }

    Path saveNow(boolean announce) {
        if (stamp == null) return null;
        var saved = DictationNotes.save(stamp, window.text());
        if (announce && saved != null) {
            Dialogs.message("Voice Typing", "Saved to\n" + saved, Dialogs.Kind.INFO);
        }
        return saved;
    }

    void closeRequested() {
        if (isActive()) {
            var choice = Dialogs.choose("Voice Typing", "Recording is still running. Finish and save before closing?", Dialogs.Kind.WARNING,
                    new Dialogs.Answer("Y", "Finish and save"), new Dialogs.Answer("N", "Stop without finishing"), new Dialogs.Answer("Esc", "Keep recording"));
            if (choice == 2) return;
            if (choice == 0) { finish(); return; }
            stopAutosave();
            if (recorder != null) recorder.stop();
            if (stream != null) stream.shutdown();
            setState(DictationState.STOPPED);
        }
        window.hide();
    }

    String elapsedText() {
        if (recorder == null || !recorder.isRunning()) return "";
        var s = recorder.elapsedMs() / 1000;
        var segs = stream == null ? 0 : stream.segmentCount();
        var on = stream == null ? "" : stream.describeCurrent();
        return String.format("%d:%02d:%02d   segment %d%s", s / 3600, (s % 3600) / 60, s % 60, segs, on.isEmpty() ? "" : "   " + on);
    }

    void setState(DictationState s) {
        state = s;
        window.setState(s);
    }

    @Override public void onFinalText(String text) {
        window.appendFinal(text);
        FxThread.run(() -> { if (state == DictationState.STOPPED) saveNow(false); });
    }

    @Override public void onInterimText(String text) { window.setInterim(text); }

    @Override public void onState(DictationState s, String detail) {
        if (state == DictationState.PAUSED && s == DictationState.LISTENING) return;
        FxThread.run(() -> setState(s));
    }
}
