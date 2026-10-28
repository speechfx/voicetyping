package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;

import static java.lang.foreign.ValueLayout.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;

/**
 * Battery level of a Bluetooth headset, as Windows itself records it.
 *
 * Windows keeps the level on the headset's "Hands-Free AG" device node (the same number its
 * Bluetooth settings page shows), under DEVPKEY_Bluetooth_Battery. The microphone of that
 * headset is named "Headset (AirPods Pro Hands-Free)", so the two meet on the headset name.
 * A headset that does not report a level simply has no entry, and the caller shows nothing.
 */
public final class BluetoothBattery {

    private static final Logger log = LoggerFactory.getLogger(BluetoothBattery.class);

    static final int DIGCF_PRESENT = 0x2, DIGCF_ALLCLASSES = 0x4, DEVINFO_SIZE = 32;
    static final String
            BATTERY = "104EA319-6EE2-4701-BD47-8DDBF425BBE5",
            FRIENDLY_NAME = "A45C254E-DF1C-4EFD-8020-67D146A850E0";

    static MethodHandle getClassDevs, enumInfo, getProperty, destroy;
    static final boolean available = link();

    private BluetoothBattery() {}

    static boolean link() {
        if (!Platform.WINDOWS) return false;
        try {
            var linker = Linker.nativeLinker();
            var setupapi = SymbolLookup.libraryLookup("setupapi.dll", Arena.global());
            getClassDevs = linker.downcallHandle(setupapi.find("SetupDiGetClassDevsW").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
            enumInfo = linker.downcallHandle(setupapi.find("SetupDiEnumDeviceInfo").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            getProperty = linker.downcallHandle(setupapi.find("SetupDiGetDevicePropertyW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            destroy = linker.downcallHandle(setupapi.find("SetupDiDestroyDeviceInfoList").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            return true;
        } catch (Throwable e) {
            log.warn("Bluetooth battery unavailable: {}", e.getMessage());
            return false;
        }
    }

    /** Headset name (the part before " Hands-Free") to battery percent, for every headset that reports one. */
    public static Map<String, Integer> levels() {
        var out = new LinkedHashMap<String, Integer>();
        if (!available) return out;
        try (var arena = Arena.ofConfined()) {
            var set = (MemorySegment) getClassDevs.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, DIGCF_PRESENT | DIGCF_ALLCLASSES);
            if (set.address() == -1L) return out;
            try {
                var dev = arena.allocate(DEVINFO_SIZE, 8);
                var batteryKey = propertyKey(arena, BATTERY, 2);
                var nameKey = propertyKey(arena, FRIENDLY_NAME, 14);
                var buf = arena.allocate(512);
                var type = arena.allocate(JAVA_INT);
                for (var i = 0; ; i++) {
                    dev.set(JAVA_INT, 0, DEVINFO_SIZE);
                    if ((int) enumInfo.invokeExact(set, i, dev) == 0) break;
                    if ((int) getProperty.invokeExact(set, dev, batteryKey, type, buf, 512, MemorySegment.NULL, 0) == 0) continue;
                    var level = buf.get(JAVA_BYTE, 0) & 0xFF;
                    if ((int) getProperty.invokeExact(set, dev, nameKey, type, buf, 512, MemorySegment.NULL, 0) == 0) continue;
                    out.put(headsetName(buf.getString(0, UTF_16LE)), level);
                }
            } finally {
                var ignored = (int) destroy.invokeExact(set);
            }
        } catch (Throwable e) {
            log.warn("reading Bluetooth battery failed: {}", e.getMessage());
        }
        return out;
    }

    /** Battery percent of the headset this microphone belongs to, or null when it has none. */
    public static Integer forMicrophone(String micName) {
        return forMicrophone(micName, levels());
    }

    public static Integer forMicrophone(String micName, Map<String, Integer> levels) {
        if (micName == null || Microphones.SYSTEM_DEFAULT.equals(micName)) return null;
        return levels.entrySet().stream()
                .filter(e -> micName.contains(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    static String headsetName(String friendly) {
        return friendly.replaceFirst("\\s+Hands-Free.*$", "").strip();
    }

    static MemorySegment propertyKey(Arena arena, String guid, int pid) {
        var key = arena.allocate(20, 4);
        var hex = guid.replace("-", "");
        key.set(JAVA_INT, 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        key.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        key.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (var b = 0; b < 8; b++) key.set(JAVA_BYTE, 8 + b, (byte) Integer.parseInt(hex.substring(16 + 2 * b, 18 + 2 * b), 16));
        key.set(JAVA_INT, 16, pid);
        return key;
    }
}
