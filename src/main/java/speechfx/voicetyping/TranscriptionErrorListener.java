package speechfx.voicetyping;

import java.util.function.Consumer;

/** Listener that cares only about failure; the dictation path reads results through StreamingResultListener. */
public final class TranscriptionErrorListener implements TranscriptionListener {

    final Consumer<String> onError;

    public TranscriptionErrorListener(Consumer<String> onError) {
        this.onError = onError;
    }

    @Override public void onTranscriptionStarted() {}
    @Override public void onTranscriptionProgress(String partialText, int estimatedPercent) {}
    @Override public void onTranscriptionComplete(String fullText) {}
    @Override public void onTranscriptionError(String error) { onError.accept(error); }
}
