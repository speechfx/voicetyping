package speechfx.voicetyping;

import speechfx.dictation.*;

import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;

/** Lists this process's top-level windows: handle, title, visible. */
public class WindowList {
    static final java.lang.invoke.MethodHandle
            TOP = NoActivate.fn("GetTopWindow", FunctionDescriptor.of(ADDRESS, ADDRESS)),
            NEXT = NoActivate.fn("GetWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)),
            VIS = NoActivate.fn("IsWindowVisible", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static void main(String[] a) throws Throwable { list(Long.parseLong(a[0])); }

    static void list() throws Throwable { list(ProcessHandle.current().pid()); }

    static void list(long who) throws Throwable {
        try (var a = Arena.ofConfined()) {
            var h = (MemorySegment) TOP.invokeExact(MemorySegment.NULL);
            while (h.address() != 0) {
                var pid = a.allocate(JAVA_INT);
                var tid = (int) TargetWindow.PID.invokeExact(h, pid);
                if (pid.get(JAVA_INT, 0) == who) {
                    var title = TargetWindow.text(TargetWindow.TITLE, h, a);
                    var cls = TargetWindow.text(TargetWindow.CLASS, h, a);
                    var vis = (int) VIS.invokeExact(h);
                    var ex = (long) NoActivate.GET_EX.invokeExact(h, NoActivate.GWL_EXSTYLE);
                    System.out.printf("  0x%x vis=%d ex=0x%x class=%s title='%s'%n", h.address(), vis, ex, cls, title);
                }
                h = (MemorySegment) NEXT.invokeExact(h, 2);
            }
        }
    }
}
