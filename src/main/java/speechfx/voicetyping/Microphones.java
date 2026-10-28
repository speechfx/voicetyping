package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Which capture device to record from, chosen by an ordered list of preferred names.
 *
 * Devices are remembered by their system name, never by index: the system renumbers them as
 * headsets connect and disconnect. SYSTEM_DEFAULT is an entry like any other, and is
 * always connected, so it can sit anywhere in the order.
 */
public final class Microphones {

    private static final Logger log = LoggerFactory.getLogger(Microphones.class);

    public static final String SYSTEM_DEFAULT = "System default";
    static final Set<String> DEFAULT_NAMES = Set.of("Primary Sound Capture Driver", "Default Audio Device", "Windows default");

    static volatile String inUse;

    private Microphones() {}

    public static List<String> connected() {
        var names = new ArrayList<String>();
        names.add(SYSTEM_DEFAULT);
        for (var mi : AudioSystem.getMixerInfo()) if (isCapture(mi) && !DEFAULT_NAMES.contains(mi.getName())) names.add(mi.getName());
        return names;
    }

    public static List<String> preferred() {
        return VoiceTypingSettings.getMicrophoneSettings().preferred().stream()
                .map(n -> DEFAULT_NAMES.contains(n) ? SYSTEM_DEFAULT : n).distinct().collect(Collectors.toCollection(ArrayList::new));
    }

    public static String resolve() {
        var now = connected();
        return preferred().stream().filter(now::contains).findFirst().orElse(SYSTEM_DEFAULT);
    }

    public static String inUse() {
        var n = inUse;
        return n != null ? n : resolve();
    }

    public static void choose(String name) {
        var order = preferred();
        order.remove(name);
        order.addFirst(name);
        VoiceTypingSettings.setPreferredMicrophones(order);
        log.info("microphone preferred first: {}", name);
    }

    /** Puts name first and, if a recording is running, moves it there without a gap. The error, or null. */
    public static String chooseLive(LongAudioRecorder r, String name) {
        choose(name);
        if (r == null || !r.isRunning() || name.equals(inUse())) return null;
        try {
            r.switchTo(name);
            return null;
        } catch (Exception e) {
            log.error("could not switch microphone to {}: {}", name, e.getMessage());
            return e.getMessage();
        }
    }

    public static TargetDataLine open(AudioFormat fmt) throws LineUnavailableException {
        return open(resolve(), fmt);
    }

    public static TargetDataLine open(String name, AudioFormat fmt) throws LineUnavailableException {
        var info = new DataLine.Info(TargetDataLine.class, fmt);
        var line = (TargetDataLine) null;
        var used = SYSTEM_DEFAULT;
        var mixer = SYSTEM_DEFAULT.equals(name) ? null : mixer(name);
        if (mixer != null && mixer.isLineSupported(info)) {
            line = (TargetDataLine) mixer.getLine(info);
            used = name;
        } else {
            if (mixer != null) log.warn("{} does not support {}, using the Windows default", name, fmt);
            if (!AudioSystem.isLineSupported(info)) throw new LineUnavailableException("format not supported: " + fmt);
            line = (TargetDataLine) AudioSystem.getLine(info);
        }
        line.open(fmt);
        line.start();
        inUse = used;
        log.info("recording from: {}", used);
        return line;
    }

    public static void released() {
        inUse = null;
    }

    /** A device that can record: it offers a TargetDataLine (Port mixers and playback devices do not). */
    static boolean isCapture(Mixer.Info mi) {
        return Arrays.stream(AudioSystem.getMixer(mi).getTargetLineInfo())
                .anyMatch(li -> TargetDataLine.class.isAssignableFrom(li.getLineClass()));
    }

    static Mixer mixer(String name) {
        return Arrays.stream(AudioSystem.getMixerInfo())
                .filter(mi -> mi.getName().equals(name) && isCapture(mi))
                .findFirst().map(AudioSystem::getMixer).orElse(null);
    }
}
