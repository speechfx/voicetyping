package speechfx.voicetyping;

public interface StreamingResultListener {
    void onResult(String text, boolean isFinal);
}
