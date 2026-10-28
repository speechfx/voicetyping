package speechfx.voicetyping;

public interface TranscriptionListener {
    void onTranscriptionStarted();
    void onTranscriptionProgress(String partialText, int estimatedPercent);
    void onTranscriptionComplete(String fullText);
    void onTranscriptionError(String error);
}
