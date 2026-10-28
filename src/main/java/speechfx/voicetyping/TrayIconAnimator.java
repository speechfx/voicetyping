package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Handles animated system tray icon with detailed status feedback and ring progress bar.
 *
 * Phases:
 * 1. IDLE - Not doing anything, waiting for the record hotkey
 * 2. RECORDING - hotkey pressed, recording audio
 * 3. ENCODING - Converting audio to MP3
 * 4. UPLOADING - Uploading file to Gemini
 * 5. PROCESSING - Waiting for transcription to start
 * 6. TRANSCRIBING - Receiving transcription stream
 * 7. PASTING - Copying to clipboard and pasting
 * 8. SUCCESS - Done, showing success briefly
 * 9. ERROR - Error occurred
 */
public class TrayIconAnimator {

    private static final Logger log = LoggerFactory.getLogger(TrayIconAnimator.class);

    public enum State {
        IDLE(0, "Idle - Press " + GlobalHotkey.DICTATION_WINDOW.shown),
        RECORDING(1, "Recording"),
        ENCODING(2, "Encoding MP3"),
        UPLOADING(3, "Uploading"),
        PROCESSING(4, "Processing"),
        TRANSCRIBING(5, "Transcribing"),
        PASTING(6, "Pasting"),
        SUCCESS(7, "Success"),
        ERROR(8, "Error");

        private final int step;
        private final String label;

        State(int step, String label) {
            this.step = step;
            this.label = label;
        }

        public int getStep() {
            return step;
        }

        public String getLabel() {
            return label;
        }

        public int getTotalSteps() {
            return 7; // IDLE to SUCCESS (excluding ERROR)
        }
    }

    private static final int ICON_SIZE = 16;
    private static final int ANIMATION_FPS = 10;
    private static final int ANIMATION_INTERVAL = 1000 / ANIMATION_FPS;
    private static final int MONITOR_INTERVAL = 500; // Check state every 500ms
    private static final int VISIBILITY_REFRESH_INTERVAL = 5000; // Force refresh every 5s to keep icon visible

    // Colors with good contrast for system tray visibility
    private static final Color BG_IDLE = new Color(60, 60, 60, 230);
    private static final Color BG_RECORDING = new Color(200, 50, 50, 230);
    private static final Color BG_PROCESSING = new Color(70, 130, 180, 230);
    private static final Color BG_SUCCESS = new Color(50, 180, 50, 230);
    private static final Color BG_ERROR = new Color(220, 50, 50, 230);

    private static final Color RING_BG = new Color(200, 200, 200, 100);
    private static final Color RING_PROGRESS = new Color(255, 255, 255, 220);
    private static final Color ICON_COLOR = new Color(255, 255, 255, 255);

    private TrayIcon trayIcon;
    private SystemTray systemTray;
    private volatile State currentState = State.IDLE;
    private volatile State targetState = State.IDLE; // For transition management
    private Timer animationTimer;
    private Timer monitorTimer;
    private Timer visibilityTimer;
    private int animationFrame = 0;
    private volatile int progressPercent = 0;
    private volatile long lastStateChangeTime = System.currentTimeMillis();
    private volatile long lastVisibilityRefreshTime = System.currentTimeMillis();
    private volatile boolean idlePulseFrame = false;
    private volatile Color linkColor;

    public TrayIconAnimator() {
        startMonitor();
    }

    public void setTrayIcon(TrayIcon trayIcon) {
        this.trayIcon = trayIcon;
        this.systemTray = SystemTray.getSystemTray();
        updateIcon();
        startVisibilityRefresh();
    }

    /**
     * Start periodic pulse animation on IDLE state to keep icon visible.
     * This adds subtle visual feedback without forceful removal/re-addition.
     */
    private void startVisibilityRefresh() {
        visibilityTimer = new Timer("TrayIconVisibility", true);
        visibilityTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // Add subtle pulse animation on IDLE state to keep visible
                if (currentState == State.IDLE) {
                    idlePulseFrame = !idlePulseFrame;
                    updateIcon();
                }
            }
        }, VISIBILITY_REFRESH_INTERVAL, VISIBILITY_REFRESH_INTERVAL);
    }

    /**
     * Set the current state. This will update the icon immediately.
     */
    public synchronized void setState(State state) {
        if (state == null) return;

        log.info("TrayIcon State: {} -> {}", currentState, state);

        this.targetState = state;
        this.currentState = state;
        this.lastStateChangeTime = System.currentTimeMillis();
        this.animationFrame = 0;

        // Reset progress when entering new state
        if (state != State.TRANSCRIBING && state != State.UPLOADING) {
            this.progressPercent = 0;
        }

        if (needsAnimation(state)) {
            startAnimation();
        } else {
            stopAnimation();
        }

        updateIcon();
        updateTooltip();
    }

    /**
     * Set progress percentage (0-100) for current state.
     */
    public synchronized void setProgress(int percent) {
        this.progressPercent = Math.max(0, Math.min(100, percent));
        updateTooltip();
        // Force update icon to show new progress
        if (!needsAnimation(currentState)) {
            updateIcon();
        }
    }

    public State getState() {
        return currentState;
    }

    private boolean needsAnimation(State state) {
        return state == State.RECORDING || state == State.UPLOADING ||
               state == State.PROCESSING || state == State.TRANSCRIBING;
    }

    /**
     * Monitors state and ensures icon stays synchronized.
     * This prevents the icon from getting stuck in wrong state.
     * Also ensures visibility by triggering refreshes when state changes.
     */
    private void startMonitor() {
        monitorTimer = new Timer("TrayIconMonitor", true);
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            private volatile State lastMonitoredState = State.IDLE;

            @Override
            public void run() {
                // Verify state consistency
                if (currentState != targetState) {
                    log.warn("TrayIcon: State mismatch detected, correcting: {} -> {}", currentState, targetState);
                    setState(targetState);
                }

                lastMonitoredState = currentState;

                // Auto-transition from temporary states
                long timeSinceChange = System.currentTimeMillis() - lastStateChangeTime;
                if (currentState == State.SUCCESS && timeSinceChange > 2000) {
                    setState(State.IDLE);
                } else if (currentState == State.ERROR && timeSinceChange > 5000) {
                    setState(State.IDLE);
                }
            }
        }, MONITOR_INTERVAL, MONITOR_INTERVAL);
    }

    private void startAnimation() {
        stopAnimation();

        animationTimer = new Timer("TrayIconAnimator", true);
        animationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                animationFrame++;
                updateIcon();
            }
        }, 0, ANIMATION_INTERVAL);
    }

    private void stopAnimation() {
        if (animationTimer != null) {
            animationTimer.cancel();
            animationTimer = null;
        }
    }

    private synchronized void updateIcon() {
        if (trayIcon == null) return;

        Image icon = createStateIcon(currentState, animationFrame, progressPercent);
        trayIcon.setImage(icon);
    }

    private Image createStateIcon(State state, int frame, int progress) {
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw background circle with state-specific color
        Color bgColor = getBackgroundColor(state);
        g.setColor(bgColor);
        g.fillOval(0, 0, ICON_SIZE, ICON_SIZE);

        // Draw ring progress bar (only for non-idle states)
        if (state != State.IDLE) {
            drawRingProgress(g, state, progress);
        }

        // Draw state-specific icon/symbol in center
        drawStateSymbol(g, state, frame);
        drawLinkDot(g);

        g.dispose();
        return img;
    }

    /** The phone link's green/orange/red light, bottom-right, over whatever state is showing. */
    public void setLinkColor(Color c) {
        linkColor = c;
        updateIcon();
    }

    private void drawLinkDot(Graphics2D g) {
        var c = linkColor;
        if (c == null) return;
        g.setColor(new Color(20, 20, 20, 230));
        g.fillOval(ICON_SIZE - 8, ICON_SIZE - 8, 8, 8);
        g.setColor(c);
        g.fillOval(ICON_SIZE - 7, ICON_SIZE - 7, 6, 6);
    }

    private Color getBackgroundColor(State state) {
        return switch (state) {
            case IDLE -> BG_IDLE;
            case RECORDING -> BG_RECORDING;
            case ENCODING, UPLOADING, PROCESSING, TRANSCRIBING, PASTING -> BG_PROCESSING;
            case SUCCESS -> BG_SUCCESS;
            case ERROR -> BG_ERROR;
        };
    }

    private void drawRingProgress(Graphics2D g, State state, int progress) {
        int centerX = ICON_SIZE / 2;
        int centerY = ICON_SIZE / 2;
        int outerRadius = ICON_SIZE / 2 - 1;
        int ringWidth = 2;

        // Calculate progress based on state
        float progressAngle;
        if (state == State.SUCCESS) {
            progressAngle = 360f; // Full circle
        } else if (state == State.ERROR) {
            progressAngle = 0f; // No progress
        } else {
            // Calculate based on step + intra-step progress
            int currentStep = state.getStep();
            int totalSteps = state.getTotalSteps();
            float stepProgress = currentStep / (float) totalSteps;
            float intraStepProgress = (progress / 100f) / totalSteps;
            progressAngle = (stepProgress + intraStepProgress) * 360f;
        }

        // Draw ring background (light gray)
        g.setColor(RING_BG);
        g.setStroke(new BasicStroke(ringWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawOval(centerX - outerRadius + ringWidth, centerY - outerRadius + ringWidth,
                   (outerRadius - ringWidth) * 2, (outerRadius - ringWidth) * 2);

        // Draw progress ring (white/bright)
        if (progressAngle > 0) {
            g.setColor(RING_PROGRESS);
            g.drawArc(centerX - outerRadius + ringWidth, centerY - outerRadius + ringWidth,
                     (outerRadius - ringWidth) * 2, (outerRadius - ringWidth) * 2,
                     90, -(int) progressAngle);
        }
    }

    private void drawStateSymbol(Graphics2D g, State state, int frame) {
        g.setColor(ICON_COLOR);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int centerX = ICON_SIZE / 2;
        int centerY = ICON_SIZE / 2;

        switch (state) {
            case IDLE -> {
                // Subtle pulse glow on IDLE to keep visible (very subtle)
                if (idlePulseFrame) {
                    g.setColor(new Color(255, 255, 255, 40));
                    g.drawOval(2, 2, 12, 12);
                }
                // Microphone icon (smaller, centered)
                g.setColor(ICON_COLOR);
                g.fillRoundRect(6, 4, 4, 5, 2, 2);
                g.drawArc(5, 7, 6, 4, 0, -180);
                g.drawLine(8, 11, 8, 12);
                g.drawLine(6, 12, 10, 12);
            }
            case RECORDING -> {
                // Pulsing filled circle
                float pulse = (float) Math.abs(Math.sin(Math.PI * frame / 10));
                int size = (int) (4 + 2 * pulse);
                g.fillOval(centerX - size / 2, centerY - size / 2, size, size);
            }
            case ENCODING, UPLOADING -> {
                // Upward arrow with animation
                int offset = frame % 4;
                g.drawLine(centerX, centerY - 2 + offset, centerX, centerY + 3 + offset);
                g.drawLine(centerX - 2, centerY + offset, centerX, centerY - 2 + offset);
                g.drawLine(centerX + 2, centerY + offset, centerX, centerY - 2 + offset);
            }
            case PROCESSING, TRANSCRIBING -> {
                // Spinning dots
                int dotCount = 6;
                int radius = 3;
                for (int i = 0; i < dotCount; i++) {
                    double angle = Math.PI * 2 * i / dotCount + Math.PI * frame / 20;
                    int x = centerX + (int) (radius * Math.cos(angle));
                    int y = centerY + (int) (radius * Math.sin(angle));
                    int alpha = 100 + (int) (155 * Math.abs(Math.cos(angle)));
                    g.setColor(new Color(255, 255, 255, alpha));
                    g.fillOval(x - 1, y - 1, 2, 2);
                }
            }
            case PASTING -> {
                // Clipboard icon
                g.setColor(ICON_COLOR);
                g.drawRect(5, 4, 6, 8);
                g.drawLine(7, 4, 7, 3);
                g.drawLine(9, 4, 9, 3);
                g.drawLine(7, 3, 9, 3);
            }
            case SUCCESS -> {
                // Checkmark
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(5, 8, 7, 10);
                g.drawLine(7, 10, 11, 5);
            }
            case ERROR -> {
                // X mark
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(5, 5, 11, 11);
                g.drawLine(11, 5, 5, 11);
            }
        }
    }

    private void updateTooltip() {
        String tooltip = buildTooltip();
        if (trayIcon != null) {
            trayIcon.setToolTip(tooltip);
        }
    }

    private String buildTooltip() {
        int currentStep = currentState.getStep();
        int totalSteps = currentState.getTotalSteps();

        return switch (currentState) {
            case IDLE -> "Voice Typing - Press " + GlobalHotkey.DICTATION_WINDOW.shown + " to dictate";
            case RECORDING -> String.format("Recording... (Step %d/%d)", currentStep, totalSteps);
            case ENCODING -> String.format("Encoding MP3... (Step %d/%d)", currentStep, totalSteps);
            case UPLOADING -> String.format("Uploading... (Step %d/%d) %d%%",
                                           currentStep, totalSteps, progressPercent);
            case PROCESSING -> String.format("Processing... (Step %d/%d)", currentStep, totalSteps);
            case TRANSCRIBING -> String.format("Transcribing... (Step %d/%d) %d%%",
                                              currentStep, totalSteps, progressPercent);
            case PASTING -> String.format("Pasting... (Step %d/%d)", currentStep, totalSteps);
            case SUCCESS -> "Transcription complete!";
            case ERROR -> "Error - check console for details";
        };
    }

    public void showTemporaryState(State state, int durationMs) {
        setState(state);
        // The monitor will auto-transition after the duration
    }

    public void cleanup() {
        stopAnimation();
        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer = null;
        }
        if (visibilityTimer != null) {
            visibilityTimer.cancel();
            visibilityTimer = null;
        }
    }
}
