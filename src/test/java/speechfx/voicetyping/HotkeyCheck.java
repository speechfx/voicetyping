package speechfx.voicetyping;

import speechfx.dictation.*;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static com.github.kwhat.jnativehook.NativeInputEvent.*;

/**
 * Two checks, no tray and no microphone.
 *
 * First the pure matching table: Win+Y matches on its own, and NOT with any other modifier riding
 * along, which is what stops Win+Shift+Y or Win+Ctrl+Y being silently treated the same. Then the real
 * question: does anything else on this machine already own Win+Y and grab it before this app's hook
 * sees it - unlike the old Win+. family, Windows itself has no binding on Y, so this is really a
 * check for a THIRD PARTY conflict rather than an OS one.
 *
 * A locked screen captures as pure black and once produced a run that looked like a clean pass, so
 * every capture is now checked for having any content at all before it is believed.
 */
public final class HotkeyCheck {

    static final List<String> seen = new ArrayList<>();
    static final int Y = NativeKeyEvent.VC_Y;

    public static void main(String[] args) throws Exception {
        table();
        if (args.length > 0) live(args[0]);
        System.exit(0);
    }

    static void table() {
        System.out.println("=== match table ===");
        expect("Win+Y", Y, META_MASK, GlobalHotkey.DICTATION_WINDOW);

        expect("Y alone", Y, 0, null);
        expect("Shift+Y", Y, SHIFT_MASK, null);
        expect("Ctrl+Y", Y, CTRL_MASK, null);
        expect("Alt+Y", Y, ALT_MASK, null);
        expect("Win+Shift+Y", Y, META_MASK | SHIFT_MASK, null);
        expect("Win+Ctrl+Y", Y, META_MASK | CTRL_MASK, null);
        expect("Win+Alt+Y", Y, META_MASK | ALT_MASK, null);
        expect("Win+Ctrl+Shift+Y", Y, META_MASK | CTRL_MASK | SHIFT_MASK, null);
        expect("Win+,", NativeKeyEvent.VC_COMMA, META_MASK, null);
        expect("Win+Space", NativeKeyEvent.VC_SPACE, META_MASK, null);
        expect("Ctrl+F11", NativeKeyEvent.VC_F11, CTRL_MASK, null);
        expect("F11 alone", NativeKeyEvent.VC_F11, 0, null);
        expect("F12 alone", NativeKeyEvent.VC_F12, 0, null);
        expect("Ctrl+Alt+V", NativeKeyEvent.VC_V, CTRL_MASK | ALT_MASK, null);
    }

    static void expect(String name, int keyCode, int mods, GlobalHotkey want) {
        var got = GlobalHotkey.match(new NativeKeyEvent(NativeKeyEvent.NATIVE_KEY_PRESSED, mods, 0, keyCode, ' '));
        var ok = got == want;
        System.out.printf("  %-18s -> %-18s %s%n", name, got, ok ? "ok" : "WRONG, wanted " + want);
        if (!ok) throw new AssertionError(name);
    }

    static void live(String shot) throws Exception {
        System.out.println("\n=== live: does anything else on this machine intercept Win+Y? ===");
        java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);
        NativeEventSuppressor.installSynchronousDispatch();
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(new Watcher());

        // Take the focus into a window of our own first. Anything that leaks now leaks into this
        // text field instead of whatever the user was working in.
        var target = targetWindow();
        var r = new Robot();
        Thread.sleep(1200);
        shoot(r, shot.replace(".png", "-0-before.png"));

        press(r);
        Thread.sleep(1200);
        shoot(r, shot.replace(".png", "-win-y.png"));

        // If something else grabbed it, dismiss whatever it opened rather than leaving it there.
        r.keyPress(KeyEvent.VK_ESCAPE);
        r.keyRelease(KeyEvent.VK_ESCAPE);
        Thread.sleep(400);

        GlobalScreen.unregisterNativeHook();
        System.out.println("  hotkeys observed: " + seen);
        System.out.println("  leaked into the text field: \"" + target.getText() + "\"");
        SwingUtilities.invokeLater(() -> SwingUtilities.getWindowAncestor(target).dispose());
    }

    /** A focused text field, so an unsuppressed keystroke has somewhere harmless to go. */
    static JTextField targetWindow() throws Exception {
        var field = new JTextField(40);
        SwingUtilities.invokeAndWait(() -> {
            var f = new JFrame("HotkeyCheck target - keys land here");
            f.getContentPane().add(field);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setAlwaysOnTop(true);
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.setVisible(true);
            f.toFront();
            f.requestFocus();
            field.requestFocusInWindow();
        });
        return field;
    }

    static void press(Robot r) {
        r.keyPress(KeyEvent.VK_WINDOWS);
        r.keyPress(KeyEvent.VK_Y);
        r.delay(60);
        r.keyRelease(KeyEvent.VK_Y);
        r.keyRelease(KeyEvent.VK_WINDOWS);
    }

    static void shoot(Robot r, String path) throws Exception {
        var img = r.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        ImageIO.write(img, "png", new File(path));

        long lit = 0;
        for (var y = 0; y < img.getHeight(); y += 8)
            for (var x = 0; x < img.getWidth(); x += 8)
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) lit++;

        System.out.println("  captured " + path + (lit == 0
                ? "  *** PURE BLACK - screen is locked, this run proves nothing ***"
                : "  (" + lit + " non-black samples)"));
    }

    static final class Watcher implements NativeKeyListener {
        @Override public void nativeKeyPressed(NativeKeyEvent e) {
            var h = GlobalHotkey.match(e);
            if (h != null) NativeEventSuppressor.consume(e);
            System.out.printf("    key=%-8s mods=0x%04X (%s) -> %s%s%n",
                    NativeKeyEvent.getKeyText(e.getKeyCode()), e.getModifiers(),
                    NativeInputEvent.getModifiersText(e.getModifiers()), h, h != null ? " [suppressed]" : "");
            if (h != null) seen.add(h.name());
        }
    }
}
