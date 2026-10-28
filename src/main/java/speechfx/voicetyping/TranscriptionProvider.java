package speechfx.voicetyping;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface TranscriptionProvider {
    void setListener(TranscriptionListener listener);
    CompletableFuture<String> transcribeAsync(Path audioPath);
    String getProviderName();
}
