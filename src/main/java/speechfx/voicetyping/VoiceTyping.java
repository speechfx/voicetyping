package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.*;
import javafx.scene.control.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Main application class for Voice Typing.
 *
 * A Windows desktop tool that sits in the system tray. See GlobalHotkey for the system-wide keys.
 *
 * After recording, audio is sent to Gemini for transcription.
 * The transcribed text is copied to clipboard and pasted where the cursor is.
 */
public class VoiceTyping implements NativeKeyListener {
    private static final Logger log = LoggerFactory.getLogger(VoiceTyping.class);

    private TrayIcon trayIcon;
    private TrayIconAnimator trayAnimator;
    private AudioRecorder audioRecorder;
    private volatile TranscriptionProvider transcriptionService;
    private TranscriptHistory history;
    private ClipboardPaster clipboardPaster;
    private HistoryWindow historyWindow;
    private RetryManager retryManager;

    private ActiveWindowCapture windowCapture;
    private StreamingOverlayWindow overlayWindow;
    private volatile MemorySegment savedWindowHandle = MemorySegment.NULL;

    private DictationWindow dictationWindow;
    private volatile DictationSession dictation;
    private volatile OnSpotSession onSpot;
    private volatile SpeechChain chain;

    private long recordingStartTime;
    private boolean isProcessing = false;

    private Path lastProcessedAudioPath;

    private PhoneLink phoneLink;
    private TrayMenu trayMenu;
    private MacTrayItems macTrayItems;
    /** The tray's phone-link entry, read each time the menu opens. */
    private volatile String phoneLinkLabel = "Phone link: starting...";
    private volatile String phoneLinkDetail = "starting";

    public VoiceTyping() {
        // Disable JNativeHook logging
        java.util.logging.Logger jdkLogger = java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
        jdkLogger.setLevel(Level.OFF);
        jdkLogger.setUseParentHandlers(false);

        // Set java.library.path to include current directory for native libraries
        String libPath = System.getProperty("java.library.path", "");
        String currentDir = System.getProperty("user.dir", ".");
        if (!libPath.contains(currentDir)) {
            System.setProperty("java.library.path", currentDir + java.io.File.pathSeparator + libPath);
        }
    }

    public void start() {
        VoiceTypingSettings.ensureDirectoriesExist();

        // Initialize components
        trayAnimator = new TrayIconAnimator();
        audioRecorder = new AudioRecorder();
        history = new TranscriptHistory();
        clipboardPaster = new ClipboardPaster();
        retryManager = new RetryManager();
        windowCapture = new ActiveWindowCapture();
        transcriptionService = createProvider();
        transcriptionService.setListener(buildTranscriptionListener());

        // Build overlay on EDT (Swing must be created on EDT)
        EventQueue.invokeLater(() -> {
            overlayWindow = new StreamingOverlayWindow();
            dictationWindow = new DictationWindow();
            chain = new SpeechChain(DictationSettings::getProvider);
            dictation = new DictationSession(dictationWindow, chain, history);
            var long_ = dictation;
            onSpot = new OnSpotSession(chain, history, msg -> showNotification("Voice Typing", msg, TrayIcon.MessageType.INFO), long_::continueWith);
        });

        // Route raw PCM chunks to any active streaming provider
        audioRecorder.setChunkListener((chunk, length) -> {
            TranscriptionProvider svc = transcriptionService;
            if (svc instanceof StreamingTranscriptionProvider streaming) {
                streaming.onAudioChunk(chunk, length);
            }
        });

        // Set up audio recorder listener
        audioRecorder.setListener(new AudioRecorder.RecordingListener() {
            @Override
            public void onRecordingStarted() {
                recordingStartTime = System.currentTimeMillis();
                trayAnimator.setState(TrayIconAnimator.State.RECORDING);
                TranscriptionProvider svc = transcriptionService;
                if (svc instanceof StreamingTranscriptionProvider streaming) {
                    streaming.onRecordingStarted(audioRecorder.getAudioFormat());
                    if (overlayWindow != null) overlayWindow.showOverlay();
                }
            }

            @Override
            public void onRecordingProgress(long durationMs) {
                // Progress is shown via animated icon
            }

            @Override
            public void onRecordingStopped(Path audioFile, long durationMs) {
                trayAnimator.setState(TrayIconAnimator.State.ENCODING);
                processRecording(audioFile, durationMs);
            }

            @Override
            public void onRecordingError(String error) {
                trayAnimator.setState(TrayIconAnimator.State.ERROR);
                trayAnimator.showTemporaryState(TrayIconAnimator.State.ERROR, 3000);
                showNotification("Recording Error", error, TrayIcon.MessageType.ERROR);
            }
        });

        // Set up system tray
        if (!setupSystemTray()) {
            log.error("System tray not supported. Exiting.");
            System.exit(1);
        }

        phoneLink = new PhoneLink(this::onPhoneLinkStatus, this::onFromPhone);
        phoneLink.start();

        // Register global keyboard hooks
        if (!registerKeyboardHooks()) {
            if (Platform.MAC) {
                MacAccessibility.waitForPermission(this::registerKeyboardHooks,
                        msg -> showNotification("Voice Typing", msg, TrayIcon.MessageType.INFO));
            } else {
                log.error("Failed to register keyboard hooks. Exiting.");
                System.exit(1);
            }
        }

        // Check for microphone
        if (!AudioRecorder.isMicrophoneAvailable()) {
            showNotification("Voice Typing", "No microphone detected!", TrayIcon.MessageType.WARNING);
        }

        // Create history window (hidden initially)
        EventQueue.invokeLater(() -> {
            historyWindow = new HistoryWindow(history, clipboardPaster);
            // Set retry callback to trigger transcription for selected audio file
            historyWindow.setRetryCallback(audioFile -> {
                log.info("Retry triggered from history window for: {}", audioFile.getFileName());
                // Process the audio file directly (skip recording step)
                processRecording(audioFile, 0);
            });
        });

        log.info("Voice Typing started successfully!");
        for (var k : GlobalHotkey.values()) log.info("  {} - {}", k.shown, k.name().toLowerCase().replace('_', ' '));
    }

    private boolean setupSystemTray() {
        if (!SystemTray.isSupported()) {
            return false;
        }

        SystemTray tray = SystemTray.getSystemTray();
        if (Platform.MAC) macTrayItems = new MacTrayItems((title, msg) -> showNotification(title, msg, TrayIcon.MessageType.INFO));
        trayMenu = new TrayMenu(this::trayItems);
        SpeechMenu.onChange(this::speechChanged);

        // The icon only: the menu is JavaFX (TrayMenu), opened by a right click, or any click on the Mac's menu bar
        trayIcon = new TrayIcon(createDefaultIcon(), "Voice Typing - Press " + GlobalHotkey.DICTATION_WINDOW.shown + " to dictate");
        trayIcon.setImageAutoSize(true);

        // Double-click to show history
        trayIcon.addActionListener(e -> showHistory());
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (opensMenu(e)) trayMenu.show(); }
            @Override public void mouseReleased(MouseEvent e) { if (opensMenu(e)) trayMenu.show(); }
        });
        trayAnimator.setTrayIcon(trayIcon);

        try {
            tray.add(trayIcon);
            return true;
        } catch (AWTException e) {
            log.error("Failed to add tray icon: {}", e.getMessage());
            return false;
        }
    }

    /** The popup trigger is a press on the Mac and a release on Windows; the Mac's menu bar also opens on a plain click. */
    private static boolean opensMenu(MouseEvent e) {
        return e.isPopupTrigger() || (Platform.MAC && e.getID() == MouseEvent.MOUSE_PRESSED && e.getButton() == MouseEvent.BUTTON1);
    }

    /** The tray menu, built on every open so that microphones, provider and phone link are current. */
    private List<MenuItem> trayItems() {
        var items = new ArrayList<MenuItem>();
        items.add(TrayMenu.item("Long Dictation... (" + GlobalHotkey.DICTATION_WINDOW.shown + ")", this::openDictation));
        items.add(TrayMenu.item("Dictate into an App... (" + GlobalHotkey.ON_SPOT.shown + ")", this::onSpotFromTray));
        items.add(new SeparatorMenuItem());
        items.add(TrayMenu.item("View History...", this::showHistory));
        items.add(TrayMenu.item("Open Settings...", VoiceTypingSettings::openSettingsInEditor));
        items.add(TrayMenu.item("Manage API Keys...", this::openApiKeyManager));
        items.add(TrayMenu.item("Speech Keys (Deepgram, NVIDIA)...", SpeechKeysDialog::open));
        items.add(TrayMenu.item(phoneLinkLabel, () -> PhoneLinkDialog.open(phoneLinkDetail, () -> phoneLink.start())));
        if (macTrayItems != null) items.addAll(macTrayItems.items());
        var speech = new Menu("Speech: " + SpeechMenu.describe());
        speech.getItems().setAll(SpeechMenu.items());
        items.add(speech);
        var mics = new Menu("Microphone");
        mics.getItems().setAll(MicMenu.items(this::micChosen));
        items.add(mics);
        items.add(TrayMenu.item("Open Recording Folder...", this::openRecordingFolder));
        items.add(new SeparatorMenuItem());
        items.add(TrayMenu.item("Exit", this::shutdown));
        return items;
    }

    private Image createDefaultIcon() {
        int size = 16;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.DARK_GRAY);
        g.fillRoundRect(5, 2, 6, 8, 4, 4);
        g.setStroke(new BasicStroke(1.5f));
        g.drawArc(3, 6, 10, 6, 0, -180);
        g.drawLine(8, 12, 8, 14);
        g.drawLine(5, 14, 11, 14);

        g.dispose();
        return img;
    }

    private int hookFailures;

    private boolean registerKeyboardHooks() {
        try {
            NativeEventSuppressor.installSynchronousDispatch();
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            return true;
        } catch (UnsatisfiedLinkError e) {
            log.error("Failed to load native libraries for keyboard hooks: {}", e.getMessage());
            log.error("This usually happens when running from a JAR file without proper native library access.");
            log.error("Try running with Java argument: --enable-native-access=ALL-UNNAMED");
            return false;
        } catch (NativeHookException e) {
            if (hookFailures++ == 0) log.error("Failed to register native hook: {}", e.getMessage());
            if (e.getCause() instanceof UnsatisfiedLinkError) {
                log.error("Native library issue detected. Ensure jnativehook native libraries are available.");
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error registering keyboard hooks: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        var hotkey = GlobalHotkey.match(e);

        // DEBUG on purpose. Every press of the hotkey's base key is recorded, matched or not, which is
        // the only way to tell "the key never reached the hook" apart from "it arrived with modifiers
        // we rejected" — the two have completely different causes and one silent failure already cost
        // an evening. But at INFO it fires on every full stop the user types, so it stays off until a
        // recurrence needs it. Deliberately one key and no character data.
        if (log.isDebugEnabled() && e.getKeyCode() == NativeKeyEvent.VC_PERIOD) {
            log.debug("period pressed: mods=0x{} -> {}", String.format("%04X", e.getModifiers()),
                    hotkey == null ? "no match" : hotkey.name());
        }

        if (hotkey == null) return;
        var startNanos = System.nanoTime();
        NativeEventSuppressor.consume(e);
        switch (hotkey) {
            case DICTATION_WINDOW -> EventQueue.invokeLater(this::openDictation);
            case ON_SPOT -> {
                var fg = TargetWindow.foreground();
                EventQueue.invokeLater(() -> onSpotFromHotkey(fg));
            }
        }

        // This runs ON the native hook thread, because consuming an event requires it. Windows
        // removes a low-level hook that overruns LowLevelHooksTimeout, and does so silently — the
        // app then looks alive while every hotkey is dead. So the cost is measured, not assumed.
        var ms = (System.nanoTime() - startNanos) / 1_000_000;
        if (ms > 100) log.warn("hotkey {} held the hook thread for {} ms - Windows may unhook us", hotkey.shown, ms);
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        // Not used
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // Not used
    }

    private TranscriptionProvider createProvider() {
        return switch (DictationSettings.getProvider()) {
            case "deepgram" -> new DeepgramTranscriptionService();
            case "deepgram-flux" -> new DeepgramStreamingTranscriptionService(true);
            case "deepgram-streaming" -> new DeepgramStreamingTranscriptionService();
            case "nvidia-streaming" -> new NvidiaStreamingTranscriptionService();
            default -> new TranscriptionService();
        };
    }

    private void openDictation() {
        var s = onSpot;
        if (s != null && s.isActive()) {
            FxThread.run(s::toLong);
            return;
        }
        var d = dictation;
        if (d == null) {
            log.warn("dictation window not ready yet");
            return;
        }
        d.open();
    }

    private void onSpotFromHotkey(long foreground) {
        var s = onSpot;
        if (s == null) return;
        if (!s.isActive() && dictation != null && dictation.isActive()) {
            showNotification("Voice Typing", "Long dictation is recording; finish it before dictating into an app.", TrayIcon.MessageType.INFO);
            return;
        }
        s.hotkey(foreground);
    }

    private void onSpotFromTray() {
        var s = onSpot;
        if (s == null) return;
        if (dictation != null && dictation.isActive()) {
            showNotification("Voice Typing", "Long dictation is recording; finish it before dictating into an app.", TrayIcon.MessageType.INFO);
            return;
        }
        s.fromTray();
    }

    private TranscriptionListener buildTranscriptionListener() {
        return new TranscriptionListener() {
            @Override
            public void onTranscriptionStarted() {
                trayAnimator.setState(TrayIconAnimator.State.TRANSCRIBING);
                if (transcriptionService instanceof StreamingTranscriptionProvider && overlayWindow != null) {
                    overlayWindow.setStatus("● Streaming", new Color(50, 200, 50));
                }
            }

            @Override
            public void onTranscriptionProgress(String partialText, int estimatedPercent) {
                if (transcriptionService instanceof StreamingTranscriptionProvider) {
                    if (overlayWindow != null) overlayWindow.updateText(partialText);
                } else {
                    if (estimatedPercent < 80) {
                        if (trayAnimator.getState() != TrayIconAnimator.State.PROCESSING)
                            trayAnimator.setState(TrayIconAnimator.State.PROCESSING);
                    } else {
                        if (trayAnimator.getState() != TrayIconAnimator.State.TRANSCRIBING)
                            trayAnimator.setState(TrayIconAnimator.State.TRANSCRIBING);
                    }
                    trayAnimator.setProgress(estimatedPercent);
                }
            }

            @Override
            public void onTranscriptionComplete(String fullText) {}

            @Override
            public void onTranscriptionError(String error) {}
        };
    }

    /** The tray's Microphone menu: also moves a dictation in progress to it. FX thread. */
    private void micChosen(String name) {
        var d = dictation;
        var s = onSpot;
        if (d != null) d.useMicrophone(name);
        if (s != null) s.useMicrophone(name);
        if (d == null && s == null) Microphones.choose(name);
    }

    /** A choice made in SpeechMenu (tray, dictation window or bar): applied at once, also to a dictation in progress. */
    private void speechChanged() {
        var provider = SpeechMenu.provider();
        var c = chain;
        if (c != null) c.prefer(provider);
        transcriptionService = createProvider();
        transcriptionService.setListener(buildTranscriptionListener());
        var why = "speech changed to " + SpeechMenu.describe();
        log.info(why);
        if (dictation != null) dictation.switchSpeech(why);
        if (onSpot != null) onSpot.switchSpeech(why);
    }

    private void processRecording(Path audioFile, long recordingDurationMs) {
        if (audioFile == null) {
            trayAnimator.setState(TrayIconAnimator.State.IDLE);
            return;
        }

        lastProcessedAudioPath = audioFile;
        isProcessing = true;
        trayAnimator.setState(TrayIconAnimator.State.UPLOADING);

        if (transcriptionService instanceof StreamingTranscriptionProvider && overlayWindow != null) {
            overlayWindow.setStatus("● Finalizing…", new Color(255, 165, 0));
        }

        CompletableFuture<String> future = transcriptionService.transcribeAsync(audioFile);

        future.thenAccept(transcription -> {
            EventQueue.invokeLater(() -> {
                if (transcription != null && !transcription.isBlank()) {
                    history.add(transcription, recordingDurationMs, audioFile);

                    if (transcriptionService instanceof StreamingTranscriptionProvider) {
                        trayAnimator.setState(TrayIconAnimator.State.PASTING);
                        if (overlayWindow != null) overlayWindow.hideOverlay();
                        MemorySegment hwnd = savedWindowHandle;
                        CompletableFuture.runAsync(() -> {
                            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                            windowCapture.restoreWindow(hwnd);
                            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                            EventQueue.invokeLater(() -> {
                                clipboardPaster.copyAndPaste(transcription);
                                trayAnimator.setState(TrayIconAnimator.State.SUCCESS);
                                isProcessing = false;
                            });
                        });
                    } else {
                        trayAnimator.setState(TrayIconAnimator.State.PASTING);
                        clipboardPaster.copyAndPaste(transcription);
                        trayAnimator.setState(TrayIconAnimator.State.SUCCESS);
                        isProcessing = false;
                    }
                } else {
                    if (transcriptionService instanceof StreamingTranscriptionProvider && overlayWindow != null) {
                        overlayWindow.hideOverlay();
                    }
                    trayAnimator.setState(TrayIconAnimator.State.ERROR);
                    showNotification("Voice Typing", "No text transcribed", TrayIcon.MessageType.WARNING);
                    isProcessing = false;
                }
            });
        }).exceptionally(ex -> {
            EventQueue.invokeLater(() -> {
                if (transcriptionService instanceof StreamingTranscriptionProvider && overlayWindow != null) {
                    overlayWindow.hideOverlay();
                }
                trayAnimator.setState(TrayIconAnimator.State.ERROR);
                String errorMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                showNotification("Transcription Failed", errorMsg, TrayIcon.MessageType.ERROR);
                log.error("Transcription failed: {}", errorMsg);

                if (lastProcessedAudioPath != null) {
                    retryManager.markFailed(lastProcessedAudioPath, errorMsg);
                }

                isProcessing = false;
            });
            return null;
        });
    }

    private void showHistory() {
        if (historyWindow != null) {
            historyWindow.showWindow();
        }
    }

    private void openRecordingFolder() {
        try {
            java.nio.file.Path tempDir = VoiceTypingSettings.getTempDir();
            if (java.nio.file.Files.exists(tempDir)) {
                Desktop.getDesktop().open(tempDir.toFile());
            } else {
                showNotification("Voice Typing", "Recording folder does not exist yet", TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) {
            showNotification("Error", "Failed to open folder: " + e.getMessage(), TrayIcon.MessageType.ERROR);
        }
    }

    private void openApiKeyManager() {
        ApiKeyManagerDialog.open();
    }

    private void onPhoneLinkStatus(PhoneLink.Status s, String detail) {
        phoneLinkDetail = detail;
        trayAnimator.setLinkColor(switch (s) {
            case CONNECTED -> new Color(40, 200, 70);
            case SERVER_ONLY -> new Color(255, 160, 0);
            case OFF -> new Color(230, 40, 40);
        });
        var label = switch (s) {
            case CONNECTED -> "Phone link: connected";
            case SERVER_ONLY -> "Phone link: waiting for phone";
            case OFF -> "Phone link: offline - set up...";
        };
        phoneLinkLabel = label;
    }

    /** No questions asked: the clipboard keeps a history, so nothing is lost by this. */
    private void onFromPhone(String text, String from) {
        if (text.isBlank()) return;
        clipboardPaster.copyToClipboard(text);
        var preview = text.length() > 160 ? text.substring(0, 160) + "..." : text;
        showNotification("From " + from + " - copied to clipboard", preview, TrayIcon.MessageType.INFO);
    }

    private void showNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    private void shutdown() {
        log.info("Shutting down Voice Typing...");

        // Cancel any ongoing recording
        if (audioRecorder != null && audioRecorder.isRecording()) {
            audioRecorder.cancelRecording();
        }

        if (phoneLink != null) {
            phoneLink.stop();
            phoneLink.retireAliveKey();
        }

        // Cleanup tray animator
        if (trayAnimator != null) {
            trayAnimator.cleanup();
        }

        // Unregister keyboard hooks
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException e) {
            // Ignore
        }

        // Remove tray icon
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }

        // Close history window
        if (historyWindow != null) {
            historyWindow.dispose();
        }

        System.exit(0);
    }

    public static void main(String[] args) {
        VoiceTypingSettings.init();
        // The JavaFX windows run next to the AWT tray; the toolkit stays up with no window open.
        FxThread.start();

        // Check system tray support
        if (!SystemTray.isSupported()) {
            LoggerFactory.getLogger(VoiceTyping.class).error("System tray is not supported on this platform!");
            System.exit(1);
        }

        // Start the application
        VoiceTyping app = new VoiceTyping();
        app.start();

        // Keep the application running
        // The application will run until shutdown() is called
    }
}
