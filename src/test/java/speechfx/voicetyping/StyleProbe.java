package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import java.lang.foreign.*;

/** Prints the extended style and owner of the window titled args[0]. */
public class StyleProbe {
    public static void main(String[] args) throws Throwable {
        try (var a = Arena.ofConfined()) {
            var h = (MemorySegment) NoActivate.FIND.invokeExact(MemorySegment.NULL, a.allocateFrom(args[0], java.nio.charset.StandardCharsets.UTF_16LE));
            if (h.address() == 0) { System.out.println("not found"); return; }
            var ex = (long) NoActivate.GET_EX.invokeExact(h, NoActivate.GWL_EXSTYLE);
            var owner = (long) NoActivate.GET_EX.invokeExact(h, -8);
            System.out.printf("hwnd=0x%x exstyle=0x%x appwindow=%b toolwindow=%b noactivate=%b owner=0x%x%n", h.address(), ex,
                    (ex & 0x40000) != 0, (ex & 0x80) != 0, (ex & 0x08000000) != 0, owner);
        }
    }
}
