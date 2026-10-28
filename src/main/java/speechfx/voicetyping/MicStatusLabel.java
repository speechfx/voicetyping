package speechfx.voicetyping;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.*;
import javafx.scene.control.*;
import javafx.util.Duration;

/**
 * "Mic: Headset (AirPods Pro Hands-Free)   Battery 80%" in the dictation window's top bar.
 *
 * Reads the device and its battery off the FX thread and refreshes every half minute, so a headset running
 * down during a long session shows up before it cuts out. The battery part gets the style class vt-low, then
 * vt-very-low, as it falls (bold, then bold red); a mic with no battery report shows no battery at all.
 */
public final class MicStatusLabel extends Label {

    final AtomicInteger asked = new AtomicInteger();

    public MicStatusLabel() {
        getStyleClass().add("vt-mic");
        var every = new Timeline(new KeyFrame(Duration.seconds(30), e -> refresh()));
        every.setCycleCount(Animation.INDEFINITE);
        every.play();
        refresh();
    }

    /** Callable from any thread. */
    public void refresh() {
        record Reading(String mic, Integer battery) {}
        var ticket = asked.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            var mic = Microphones.inUse();
            return new Reading(mic, BluetoothBattery.forMicrophone(mic));
        }).thenAccept(r -> FxThread.run(() -> { if (ticket == asked.get()) show(r.mic(), r.battery()); }));
    }

    void show(String mic, Integer battery) {
        var low = VoiceTypingSettings.getMicrophoneSettings().lowBatteryPercent();
        setText("Mic: " + mic + (battery == null ? "" : "   Battery " + battery + "%"));
        getStyleClass().removeAll("vt-low", "vt-very-low");
        if (battery != null && battery <= low) getStyleClass().add(battery <= low / 2 ? "vt-very-low" : "vt-low");
        setTooltip(new Tooltip(battery == null ? mic : mic + " - battery " + battery + "%"));
    }
}
