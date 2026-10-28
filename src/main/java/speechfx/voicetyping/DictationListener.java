package speechfx.voicetyping;

public interface DictationListener {
    void onFinalText(String text);
    void onInterimText(String text);
    void onState(DictationState state, String detail);
}
