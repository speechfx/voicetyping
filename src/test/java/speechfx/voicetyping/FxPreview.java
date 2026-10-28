package speechfx.voicetyping;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.scene.robot.Robot;
import javafx.stage.*;
import javafx.util.Duration;

/**
 * Opens a JavaFX window of the app, captures the REAL screen around it (title bar and icon included),
 * then closes it. Nothing is typed or clicked. Used to look at each migrated window.
 * mvn -pl desktop exec:java -Dexec.classpathScope=test -Dexec.mainClass=speechfx.voicetyping.FxPreview -Dexec.args="out-dir"
 */
public final class FxPreview {

    public static void main(String[] args) throws Exception {
        var out = Path.of(args[0]);
        FxThread.start();
        shoot(out.resolve("dialog-confirm.png"), () -> Dialogs.confirm("Voice Typing",
                "The dictation has not been saved.\nSave it to the notes folder before closing?"));
        shoot(out.resolve("dialog-error.png"), () -> Dialogs.message("Voice Typing",
                "Microphone unavailable:\nLine is not supported: Headset (Jabra Evolve2 65)", Dialogs.Kind.ERROR));
        shoot(out.resolve("dialog-choice.png"), () -> Dialogs.choose("Voice Typing", "Stop the dictation?", Dialogs.Kind.INFO,
                new Dialogs.Answer("S", "Stop and save"), new Dialogs.Answer("D", "Discard"), new Dialogs.Answer("Esc", "Keep recording")));
        window(out.resolve("speech-keys.png"), SpeechKeysDialog::open);
        window(out.resolve("mic-order.png"), MicOrderDialog::open);
        window(out.resolve("phone-link.png"), () -> PhoneLinkDialog.open("Phone link: waiting for phone", () -> {}));
        window(out.resolve("api-keys.png"), ApiKeyManagerDialog::open);
        shoot(out.resolve("api-key-form.png"), () -> FxThread.call(() -> ApiKeyForm.ask(null, "Add Gemini API key", null)));
        var dw = new DictationWindow();
        dw.elapsedText(() -> "0:04:12   segment 2   nvidia-streaming");
        window(out.resolve("dictation.png"), () -> {
            dw.show();
            dw.setState(DictationState.LISTENING);
            dw.appendTimestamp();
            dw.appendFinal("The hearing is on Monday. We need the bundle ready by Friday.");
            dw.appendFinal("Also check the index");
            dw.appendFinal("A long point that runs on well past the width of the window, so that it has to wrap onto a second and even a third drawn line, and still it counts as one line with one number.");
            dw.appendFinal("Second point starts here and");
            dw.setInterim("the words still being recognised");
            dw.fx(() -> { dw.view.setLineNumbers(true); dw.view.setActiveLine(true); dw.view.setZoom(1.15); });
        });
        var hw = new HistoryWindow(new TranscriptHistory(), null);
        window(out.resolve("history.png"), hw::showWindow);
        var provider = new javafx.scene.control.Menu("Provider");
        var nvidia = new javafx.scene.control.RadioMenuItem("NVIDIA Nemotron Streaming");
        nvidia.setSelected(true);
        provider.getItems().addAll(new javafx.scene.control.RadioMenuItem("Gemini"), nvidia);
        var tray = new TrayMenu(() -> java.util.List.of(TrayMenu.item("Long Dictation... (Win+Y)", () -> {}), new javafx.scene.control.SeparatorMenuItem(),
                TrayMenu.item("View History...", () -> {}), TrayMenu.item("Speech Keys (Deepgram, NVIDIA)...", () -> {}),
                TrayMenu.item("Phone link: connected", () -> {}), provider, TrayMenu.item("Open Recording Folder...", () -> {}),
                new javafx.scene.control.SeparatorMenuItem(), TrayMenu.item("Exit", () -> {})));
        tray.show();
        Thread.sleep(800);
        FxThread.call(() -> {
            var w = Window.getWindows().stream().filter(x -> x instanceof javafx.scene.control.ContextMenu).findFirst().orElseThrow();
            capture(w, out.resolve("tray-menu.png"));
            ((javafx.scene.control.ContextMenu) w).hide();
            return null;
        });
        javafx.application.Platform.exit();
        System.exit(0);
    }

    /** Runs a blocking dialog; 700 ms after it appears, captures it and closes it. */
    static void shoot(Path png, Runnable dialog) {
        FxThread.run(() -> {
            var wait = new PauseTransition(Duration.millis(700));
            wait.setOnFinished(e -> Window.getWindows().stream().filter(Window::isShowing).reduce((a, b) -> b)
                    .ifPresent(w -> { capture(w, png); ((Stage) w).close(); }));
            wait.play();
        });
        dialog.run();
    }

    /** Opens a non-blocking window, waits for it to settle, captures and closes it. */
    static void window(Path png, Runnable open) throws InterruptedException {
        var before = FxThread.call(() -> Window.getWindows().size());
        open.run();
        for (var k = 0; k < 50 && FxThread.call(() -> Window.getWindows().size()) == before; k++) Thread.sleep(100);
        Thread.sleep(800);
        FxThread.call(() -> {
            var w = Window.getWindows().getLast();
            capture(w, png);
            ((Stage) w).close();
            return null;
        });
    }

    /** The real screen (title bar and icon included) next to png, and the scene alone in png; the latter works while the PC is locked. */
    static void capture(Window w, Path png) {
        write(w.getScene().snapshot(null), png);
        write(new Robot().getScreenCapture(null, w.getX() - 8, w.getY() - 8, w.getWidth() + 16, w.getHeight() + 16),
                png.resolveSibling(png.getFileName().toString().replace(".png", ".screen.png")));
    }

    static void write(javafx.scene.image.Image img, Path png) {
        int width = (int) img.getWidth(), height = (int) img.getHeight();
        var buf = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var r = img.getPixelReader();
        for (var y = 0; y < height; y++) for (var x = 0; x < width; x++) buf.setRGB(x, y, r.getArgb(x, y));
        try {
            javax.imageio.ImageIO.write(buf, "png", png.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static Consumer<Stage> none() { return s -> {}; }
}
