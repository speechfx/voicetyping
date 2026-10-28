package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javafx.animation.*;
import javafx.util.Duration;

import static speechfx.voicetyping.DictationState.*;

/**
 * Dictation into another app: the long-dictation engine (LongAudioRecorder, SegmentedStreamingSession,
 * SpeechChain) with its finished text going to the target window through OnSpotDelivery instead of into an editor,
 * and a floating OnSpotBar instead of a window. No new lines are added; the user types those. The session's text
 * is kept as a note, autosaved on every finished piece, and goes into the history when it stops.
 */
public final class OnSpotSession implements DictationListener {

    private static final Logger log = LoggerFactory.getLogger(OnSpotSession.class);

    final SpeechChain chain;
    final TranscriptHistory history;
    final Consumer<String> notify, toLong;
    final OnSpotBar bar;
    final Timeline refresh;

    LongRecordingSettings cfg;
    SegmentedStreamingSession stream;
    LongAudioRecorder recorder;
    OnSpotDelivery delivery;
    String stamp, kind;
    volatile String interim = "";
    final StringBuilder lateText = new StringBuilder();
    volatile boolean handingOver;
    volatile DictationState state = IDLE;

    /** toLong: continues a dictation in the long-dictation window, given all the text so far. */
    public OnSpotSession(SpeechChain chain, TranscriptHistory history, Consumer<String> notify, Consumer<String> toLong) {
        this.chain = chain;
        this.history = history;
        this.notify = notify;
        this.toLong = toLong;
        bar = new OnSpotBar(this::togglePause, this::stopOrClose, this::copy, this::toLong, this::refocusTarget, this::useMicrophone);
        refresh = FxThread.call(() -> {
            var t = new Timeline(new KeyFrame(Duration.millis(200), e -> refreshBar()));
            t.setCycleCount(Animation.INDEFINITE);
            return t;
        });
    }

    public boolean isActive() {
        return state != IDLE && state != STOPPED && state != ERROR;
    }

    /** The hotkey: start typing into the window that was in front when it was pressed, or stop. Any thread. */
    public void hotkey(long foreground) {
        FxThread.run(() -> { if (isActive()) stop(); else start(TargetWindow.of(foreground)); });
    }

    /** From the tray: the tray itself is in front, so the target is the first window the user goes to. Any thread. */
    public void fromTray() {
        FxThread.run(() -> { if (!isActive()) start(null); });
    }

    void start(TargetWindow target) {
        if (!prepare(target)) return;
        recorder = new LongAudioRecorder(cfg, (b, n) -> stream.onAudioChunk(b, n), () -> FxThread.run(this::stop));
        try {
            recorder.start();
        } catch (Exception e) {
            log.error("could not start on-spot capture: {}", e.getMessage());
            notify.accept("Microphone unavailable: " + e.getMessage());
            setState(ERROR);
            closeNow();
            return;
        }
        go(recorder.format(), target);
    }

    /** Everything but the microphone, so that a test can feed the stream itself. False if it cannot start. */
    boolean prepare(TargetWindow target) {
        if (!Platform.WINDOWS || !Win32Input.available()) {
            notify.accept("Dictating into other apps works on Windows only for now.");
            return false;
        }
        if (delivery != null) {
            var left = delivery.pending();
            if (!left.isBlank()) { copyToClipboard(left); notify.accept("Text from the last dictation that was never typed in is on the clipboard."); }
            closeNow();
        }
        recorder = null;
        cfg = VoiceTypingSettings.getLongRecordingSettings();
        stamp = DictationNotes.newStamp();
        kind = target == null || target.app.isEmpty() ? "on-spot" : "into-" + target.app;
        lateText.setLength(0);
        interim = "";
        delivery = new OnSpotDelivery(target);
        setState(STARTING);
        stream = new SegmentedStreamingSession(chain, cfg, this);
        return true;
    }

    void go(AudioFormat format, TargetWindow target) {
        stream.start(format);
        log.info("on-spot dictation into {}", target == null ? "the next window brought to the front" : target);
        bar.show();
        refresh.play();
        refreshBar();
    }

    void togglePause() {
        switch (state) {
            case LISTENING, SWITCHING, RECONNECTING -> {
                if (recorder != null) recorder.pause();
                stream.pause();
                setState(PAUSED);
            }
            case PAUSED -> {
                setState(LISTENING);
                stream.resume();
                if (recorder != null) recorder.resume();
            }
            default -> {}
        }
    }

    void stop() { stop(null); }

    /** then: run on the FX thread once the last words are in, with everything said. */
    void stop(Consumer<String> then) {
        if (!isActive() || state == FINISHING) return;
        setState(FINISHING);
        var elapsed = recorder == null ? 0 : recorder.elapsedMs();
        if (recorder != null) recorder.stop();
        var s = stream;
        s.stop().whenComplete((text, err) -> FxThread.run(() -> {
            s.shutdown();
            setState(STOPPED);
            save();
            var all = s.transcript();
            var audio = recorder == null ? null : recorder.audioPath();
            if (!all.isBlank() && audio != null) history.add(all, elapsed, audio);
            if (err != null) log.warn("on-spot dictation finished with error: {}", err.toString());
            if (then != null) then.accept(all);
        }));
    }

    /** From the bar or the tray: the next recording uses it, and one in progress moves to it without a gap. FX thread. */
    public void useMicrophone(String name) {
        var error = Microphones.chooseLive(isActive() ? recorder : null, name);
        if (error != null) notify.accept("Could not switch to " + name + ": " + error + ". Still recording from " + Microphones.inUse() + ".");
    }

    /** The speech service or account changed: carry on on the new choice without a gap. */
    public void switchSpeech(String why) {
        var s = stream;
        if (s != null && isActive()) s.switchNow(why);
    }

    /**
     * Win+Y or the bar's Long button: stop typing into the app and carry on in the long-dictation window with
     * all of this session's text. Words not yet typed in are not typed in; they go to the window with the rest.
     */
    public void toLong() {
        if (!isActive()) return;
        handingOver = true;
        if (delivery != null) delivery.clear();
        stop(all -> {
            handingOver = false;
            closeNow();
            toLong.accept(all);
        });
    }

    /** After the bar's speech menu: a popup menu can take the focus, so give it back to the app being typed into. */
    void refocusTarget() {
        var d = delivery;
        var t = d == null ? null : d.target;
        if (t == null || !t.alive() || TargetWindow.foreground() == t.hwnd) return;
        if (TargetWindow.of(TargetWindow.foreground()) != null) return;
        new ActiveWindowCapture().restoreWindow(MemorySegment.ofAddress(t.hwnd));
    }

    void stopOrClose() {
        if (isActive()) stop(); else closeNow();
    }

    void closeNow() {
        refresh.stop();
        if (delivery != null) delivery.shutdown();
        delivery = null;
        bar.hide();
        if (!isActive()) state = IDLE;
    }

    /** The text still waiting if any (it is then not typed in as well), otherwise everything said so far. */
    void copy() {
        var d = delivery;
        var waiting = d == null ? "" : d.pending();
        if (!waiting.isBlank()) {
            d.clear();
            copyToClipboard(waiting);
        } else if (stream != null) {
            copyToClipboard(stream.transcript());
        }
    }

    static void copyToClipboard(String s) {
        if (s != null && !s.isBlank()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    void save() {
        if (stamp != null && stream != null) DictationNotes.save(stamp, kind, stream.transcript());
    }

    void setState(DictationState s) { state = s; }

    void refreshBar() {
        var d = delivery;
        if (d == null) return;
        bar.show(state, d, interim, !isActive());
        if (state == STOPPED && d.pendingWords() == 0) closeNow();
    }

    @Override public void onFinalText(String text) {
        var d = delivery;
        if (d != null && !handingOver) d.add(text);
        else if (d == null && !handingOver && state == STOPPED) FxThread.run(() -> late(text));
        FxThread.run(this::save);
    }

    /**
     * Words that came back after the bar had closed (a slow network): too late to type in, since the user has moved on,
     * so they go onto the clipboard, gathered with any others from the same dictation, and are in the note as well.
     */
    void late(String text) {
        lateText.append(lateText.isEmpty() ? "" : " ").append(text.strip());
        copyToClipboard(lateText.toString());
        log.info("on-spot: {} chars came back after the dictation stopped, put on the clipboard", text.length());
        notify.accept("The last words came back late and were not typed in; they are on the clipboard: " + LagMeter.abbreviate(lateText.toString()));
    }

    @Override public void onInterimText(String text) { interim = text == null ? "" : text; }

    @Override public void onState(DictationState s, String detail) {
        if (state == PAUSED && s == LISTENING) return;
        FxThread.run(() -> {
            if (s == ERROR && isActive()) {
                if (recorder != null) recorder.stop();
                save();
                notify.accept("Dictation stopped: no speech service answered (" + detail + "). What was said is in the note.");
            }
            if (state != STOPPED && state != FINISHING || s == STOPPED) setState(s);
        });
    }
}
