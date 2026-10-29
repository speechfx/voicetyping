package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * macOS lets an app watch the keyboard only once the user has allowed it under Privacy & Security >
 * Accessibility, so on a first run the global hotkey cannot be registered. Instead of quitting, vt
 * explains this once, opens the right Settings page on request, and keeps retrying in the background;
 * the dictation window stays reachable from the menu-bar icon meanwhile.
 */
public final class MacAccessibility {

    private static final Logger log = LoggerFactory.getLogger(MacAccessibility.class);
    static final String SETTINGS = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";

    private MacAccessibility() {}

    public static void waitForPermission(BooleanSupplier tryRegister, Consumer<String> notify) {
        log.warn("hotkey needs Accessibility permission; retrying until it is granted");
        Thread.ofVirtual().start(MacAccessibility::explain);
        var retry = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Accessibility-Retry");
            t.setDaemon(true);
            return t;
        });
        retry.scheduleWithFixedDelay(() -> {
            // JNativeHook asks macOS to PROMPT on every failed attempt, so retrying it blindly put the system
            // dialog up every 3 seconds (seen on a real Mac). Ask silently first; register only once allowed.
            if (!trusted() || !tryRegister.getAsBoolean()) return;
            log.info("Accessibility granted - hotkey {} active", GlobalHotkey.DICTATION_WINDOW.shown);
            notify.accept("Hotkey ready: " + GlobalHotkey.DICTATION_WINDOW.shown + " opens the dictation window");
            retry.shutdown();
        }, 3, 3, TimeUnit.SECONDS);
    }

    private static MethodHandle axIsProcessTrusted;

    /** AXIsProcessTrusted(): whether Accessibility is allowed, without any prompt. True when it cannot be asked. */
    static boolean trusted() {
        try {
            if (axIsProcessTrusted == null) {
                var lib = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", Arena.global());
                axIsProcessTrusted = Linker.nativeLinker().downcallHandle(
                        lib.find("AXIsProcessTrusted").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN));
            }
            return (boolean) axIsProcessTrusted.invokeExact();
        } catch (Throwable e) {
            log.warn("cannot ask macOS about Accessibility: {}", e.toString());
            return true;
        }
    }

    static void explain() {
        var choice = Dialogs.choose("Voice Typing",
                "Allow Voice Typing to use the keyboard shortcut.\n\n"
                        + "macOS needs your permission before " + GlobalHotkey.DICTATION_WINDOW.shown + " can open the dictation window.\n\n"
                        + "1. Choose Open Settings.\n"
                        + "2. Under Accessibility, turn on Voice Typing (it may be listed as java).\n\n"
                        + "Voice Typing keeps running in the menu bar and picks the shortcut up by itself once allowed.\n"
                        + "Until then, open the dictation window from the menu-bar icon.",
                Dialogs.Kind.INFO, new Dialogs.Answer("O", "Open Settings"), new Dialogs.Answer("Esc", "Later"));
        if (choice == 0) {
            try {
                new ProcessBuilder("/usr/bin/open", SETTINGS).start();
            } catch (IOException e) {
                log.error("could not open System Settings: {}", e.getMessage());
            }
        }
    }
}
