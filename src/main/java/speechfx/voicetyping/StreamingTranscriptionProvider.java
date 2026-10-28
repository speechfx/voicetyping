package speechfx.voicetyping;

import javax.sound.sampled.AudioFormat;

public interface StreamingTranscriptionProvider extends TranscriptionProvider {
    void onRecordingStarted(AudioFormat audioFormat);
    void onAudioChunk(byte[] chunk, int length);

    /** As above, for audio recorded earlier (parked or replayed): capturedAt is when its last sample was spoken. */
    default void onAudioChunk(byte[] chunk, int length, long capturedAt) { onAudioChunk(chunk, length); }

    /** Optional: report each result separately so callers can tell a committed phrase from a guess. */
    default void setResultListener(StreamingResultListener listener) {}

    /** The key this stream was opened with, or null when the provider has none. */
    default SpeechKey key() { return null; }
}
