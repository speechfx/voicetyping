package speechfx.voicetyping;

import java.io.*;
import java.nio.file.*;
import javax.sound.sampled.*;

/** Wraps a raw 16 kHz mono PCM file as WAV and transcribes it with Deepgram's batch endpoint. */
public class BatchCheck {
    public static void main(String[] args) throws Exception {
        var pcm = Files.readAllBytes(Path.of(args[0]));
        var wav = Files.createTempFile("vt-batch", ".wav");
        var fmt = new AudioFormat(16000, 16, 1, true, false);
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), fmt, pcm.length / 2), AudioFileFormat.Type.WAVE, wav.toFile());
        System.out.println("BATCH " + new DeepgramTranscriptionService().transcribeAsync(wav).get());
        Files.delete(wav);
        System.exit(0);
    }
}
