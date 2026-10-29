package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.util.function.BooleanSupplier;

/**
 * Puts text into the window in front, two ways. Typing sends it as Unicode key events in small chunks and checks
 * before every chunk that the target is still in front, so a switch of window mid-sentence stops it at once and
 * the rest is held. Pasting is for long text: the clipboard is borrowed and the user's text put back afterwards;
 * when the clipboard holds something other than text (an image, files) it is left alone and the text typed.
 */
final class TextInjector {

    private static final Logger log = LoggerFactory.getLogger(TextInjector.class);
    static final int CHUNK = 24;

    /** How many chars were typed: all of them, or fewer if the target left the front or the user grabbed a key. */
    static int type(String s, BooleanSupplier stillOk) {
        var sent = 0;
        while (sent < s.length()) {
            if (!stillOk.getAsBoolean()) break;
            var end = Math.min(s.length(), sent + CHUNK);
            if (end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) end--;
            if (!Win32Input.type(s.substring(sent, end))) {
                log.warn("SendInput refused text for the target (an elevated window blocks it)");
                break;
            }
            sent = end;
        }
        return sent;
    }

    /** True if pasted. False, with nothing done, when the clipboard is not plain text or the target left the front. */
    static boolean paste(String s, BooleanSupplier stillOk) {
        var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        String before;
        try {
            var held = cb.getContents(null);
            if (held != null && held.getTransferDataFlavors().length > 0 && !held.isDataFlavorSupported(DataFlavor.stringFlavor)) return false;
            before = held == null || !held.isDataFlavorSupported(DataFlavor.stringFlavor) ? null : (String) held.getTransferData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            log.debug("clipboard unreadable, typing instead: {}", e.toString());
            return false;
        }
        try {
            cb.setContents(new StringSelection(s), null);
            sleep(40);
            if (!stillOk.getAsBoolean()) return false;
            Win32Input.ctrlV();
            sleep(300);
            return true;
        } catch (Exception e) {
            log.warn("paste failed: {}", e.toString());
            return false;
        } finally {
            if (before != null) restore(cb, s, before);
        }
    }

    /** Puts the user's text back, unless something else has written the clipboard meanwhile. */
    static void restore(Clipboard cb, String ours, String before) {
        try {
            var now = cb.getContents(null);
            if (now != null && now.isDataFlavorSupported(DataFlavor.stringFlavor) && ours.equals(now.getTransferData(DataFlavor.stringFlavor)))
                cb.setContents(new StringSelection(before), null);
        } catch (Exception e) {
            log.debug("could not restore clipboard: {}", e.toString());
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TextInjector() {}
}
