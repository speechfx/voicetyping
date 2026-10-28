package speechfx.voicetyping;

import java.lang.foreign.Arena;

/** Sends one Win+J (LWin down, J down, J up, LWin up), to see whether vt's keyboard hook answers. */
public class SendWinJ {
    public static void main(String[] args) {
        try (var a = Arena.ofConfined()) {
            var in = a.allocate(Win32Input.INPUT_SIZE * 4L);
            Win32Input.key(in, 0, 0x5B, 0, 0);
            Win32Input.key(in, 1, 0x4A, 0, 0);
            Win32Input.key(in, 2, 0x4A, 0, Win32Input.KEYEVENTF_KEYUP);
            Win32Input.key(in, 3, 0x5B, 0, Win32Input.KEYEVENTF_KEYUP);
            System.out.println("sent=" + Win32Input.send(in, 4));
        }
    }
}
