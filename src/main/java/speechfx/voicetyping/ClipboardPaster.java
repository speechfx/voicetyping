package speechfx.voicetyping;

import speechfx.dictation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

/**
 * Handles copying text to clipboard and pasting it to the active window.
 */
public class ClipboardPaster {

    private static final Logger log = LoggerFactory.getLogger(ClipboardPaster.class);

    private Robot robot;

    public ClipboardPaster() {
        try {
            robot = new Robot();
            robot.setAutoDelay(10);
        } catch (AWTException e) {
            log.error("Failed to create Robot: {}", e.getMessage());
        }
    }

    /**
     * Copy text to clipboard.
     */
    public void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        StringSelection selection = new StringSelection(text);
        clipboard.setContents(selection, null);
        log.info("Copied to clipboard: {} chars", text.length());
    }

    /**
     * Copy text to clipboard and paste it using Ctrl+V.
     */
    public void copyAndPaste(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (robot == null) {
            log.error("Robot not available, copying only");
            copyToClipboard(text);
            return;
        }

        copyToClipboard(text);

        // Give a small delay for clipboard to be ready
        robot.delay(50);

        // Simulate Ctrl+V to paste
        paste();
    }

    /**
     * Paste from clipboard: Ctrl+V, or Cmd+V on a Mac.
     */
    public void paste() {
        if (robot == null) {
            log.error("Robot not available");
            return;
        }

        try {
            var modifier = Platform.MAC ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
            robot.keyPress(modifier);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(modifier);
            log.info("Pasted from clipboard");
        } catch (Exception e) {
            log.error("Failed to paste: {}", e.getMessage());
        }
    }

    /**
     * Re-paste the last clipboard content.
     */
    public void repaste() {
        paste();
    }
}
