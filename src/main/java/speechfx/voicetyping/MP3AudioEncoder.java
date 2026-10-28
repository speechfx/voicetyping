package speechfx.voicetyping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.sciss.jump3r.lowlevel.LameEncoder;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for encoding PCM audio directly to MP3 format.
 * Uses jump3r (pure Java LAME port) for direct PCM-to-MP3 encoding.
 * No intermediate WAV files required.
 *
 * Benefits:
 * - Reduces file size by 4-8x (typically 8:1 compression for speech)
 * - Saves bandwidth when uploading to Gemini API
 * - Saves disk space
 * - No intermediate files - direct memory encoding
 */
public class MP3AudioEncoder {

    private static final Logger log = LoggerFactory.getLogger(MP3AudioEncoder.class);

    // MP3 encoding settings optimized for speech
    private static final int BITRATE_MONO_SPEECH = 64; // 64 kbps - good quality for speech

    // LameEncoder channel mode constants
    private static final int CHANNEL_MODE_MONO = 3; // MONO mode

    // LameEncoder quality (0 = best, 9 = fastest)
    private static final int QUALITY_HIGH = 2;

    /**
     * Encodes PCM audio data directly to MP3 bytes.
     * This is the primary method - no intermediate files needed.
     *
     * @param pcmData The raw PCM audio data
     * @param audioFormat The format of the PCM data
     * @return MP3 encoded bytes, or null if encoding failed
     */
    public static byte[] encodePcmToMp3(byte[] pcmData, AudioFormat audioFormat) {
        if (pcmData == null || pcmData.length == 0) {
            log.error("Cannot encode null or empty PCM data");
            return null;
        }

        try {
            // Create LAME encoder with our audio format settings
            // LameEncoder(AudioFormat sourceFormat, int bitRate, int mpegMode, int quality, boolean VBR)
            LameEncoder encoder = new LameEncoder(
                    audioFormat,
                    BITRATE_MONO_SPEECH,
                    CHANNEL_MODE_MONO,
                    QUALITY_HIGH,
                    false // VBR disabled for consistent bitrate
            );

            ByteArrayOutputStream mp3Output = new ByteArrayOutputStream();

            // Get buffer size for encoding
            byte[] mp3Buffer = new byte[encoder.getPCMBufferSize()];

            int bytesWritten;
            int pcmOffset = 0;
            int pcmChunkSize = encoder.getPCMBufferSize();

            // Encode PCM data in chunks
            while (pcmOffset < pcmData.length) {
                int remaining = pcmData.length - pcmOffset;
                int chunkSize = Math.min(pcmChunkSize, remaining);

                bytesWritten = encoder.encodeBuffer(pcmData, pcmOffset, chunkSize, mp3Buffer);

                if (bytesWritten > 0) {
                    mp3Output.write(mp3Buffer, 0, bytesWritten);
                }

                pcmOffset += chunkSize;
            }

            // Flush encoder (get any remaining MP3 data)
            bytesWritten = encoder.encodeFinish(mp3Buffer);
            if (bytesWritten > 0) {
                mp3Output.write(mp3Buffer, 0, bytesWritten);
            }

            encoder.close();

            byte[] mp3Bytes = mp3Output.toByteArray();
            double compressionRatio = (double) pcmData.length / mp3Bytes.length;

            log.info("MP3 encoding complete: {} bytes PCM -> {} bytes MP3 (compression: {}.1fx)",
                    pcmData.length, mp3Bytes.length, compressionRatio);

            return mp3Bytes;

        } catch (Exception e) {
            log.error("Failed to encode PCM to MP3: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Saves PCM audio data directly to an MP3 file.
     * Combines encoding and file writing in one operation.
     *
     * @param pcmData The raw PCM audio data
     * @param audioFormat The format of the PCM data
     * @param outputPath The path to save the MP3 file
     * @return The path to the saved MP3 file, or null if failed
     */
    public static Path savePcmAsMp3(byte[] pcmData, AudioFormat audioFormat, Path outputPath) {
        byte[] mp3Data = encodePcmToMp3(pcmData, audioFormat);

        if (mp3Data == null) {
            return null;
        }

        try {
            Files.write(outputPath, mp3Data);
            log.info("Saved MP3 file: {} ({} bytes)", outputPath.getFileName(), mp3Data.length);
            return outputPath;
        } catch (IOException e) {
            log.error("Failed to save MP3 file: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Gets the estimated MP3 file size for a given PCM data size.
     * Useful for predicting bandwidth savings.
     *
     * @param pcmDataSize Size of the PCM data in bytes
     * @return Estimated MP3 file size in bytes
     */
    public static long estimateMp3Size(long pcmDataSize) {
        // For 16kHz mono speech at 64kbps, typical compression ratio is ~8:1
        return pcmDataSize / 8;
    }

    /**
     * Calculates bandwidth savings percentage.
     *
     * @param originalSize Original PCM/WAV size
     * @param mp3Size Compressed MP3 size
     * @return Bandwidth savings as percentage (0-100)
     */
    public static double calculateBandwidthSavings(long originalSize, long mp3Size) {
        if (originalSize == 0) return 0;
        return ((double) (originalSize - mp3Size) / originalSize) * 100;
    }
}
