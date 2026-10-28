package speechfx.voicetyping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.jphil.ai.gemini.GeminiClientException;
import xyz.jphil.ai.gemini.api1.*;
import xyz.jphil.ai.gemini.service.GeminiApiService;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini-based transcription provider.
 */
public class TranscriptionService implements TranscriptionProvider {
    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private static final String TRANSCRIPTION_PROMPT = """
            Transcribe this audio file accurately and completely.

            CRITICAL INSTRUCTIONS:
            1. Output ONLY the spoken text - no timestamps, no speaker labels, no formatting
            2. Preserve punctuation and natural sentence structure
            3. If there are multiple speakers, transcribe in the order they speak
            4. If the audio is unclear or silent, output "[inaudible]" or "[silence]"
            5. Output the transcription as plain text, nothing else

            Begin transcription:
            """;

    private final GeminiApiService apiService;
    private TranscriptionListener listener;

    public TranscriptionService() {
        HttpClient httpClient = HttpClient.newHttpClient();
        this.apiService = new GeminiApiService(httpClient, new ObjectMapper());
    }

    @Override
    public String getProviderName() {
        return "Gemini";
    }

    @Override
    public void setListener(TranscriptionListener listener) {
        this.listener = listener;
    }

    /**
     * Transcribe an audio file using Gemini API with streaming.
     *
     * @param audioPath Path to the audio file (MP3 or WAV format)
     * @return CompletableFuture containing the transcribed text
     */
    @Override
    public CompletableFuture<String> transcribeAsync(Path audioPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transcribe(audioPath);
            } catch (Exception e) {
                throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Transcribe an audio file synchronously with streaming output.
     */
    public String transcribe(Path audioPath) throws IOException, GeminiClientException {
        TranscriptionLogger txnLog = new TranscriptionLogger(audioPath);

        String apiKey = VoiceTypingSettings.getRandomApiKey();
        if (apiKey == null) {
            txnLog.logFailure("No API key available. Please configure API keys in settings.");
            throw new IOException("No API key available. Please configure API keys in settings.");
        }

        String model = VoiceTypingSettings.getModel();
        log.info("Model: {}", model);
        txnLog.logStart(apiKey, model);

        if (listener != null) {
            listener.onTranscriptionStarted();
        }

        // Step 1: Upload the audio file
        log.info("Uploading: {}", audioPath.getFileName());
        txnLog.logFileUpload("Starting upload...");
        if (listener != null) {
            listener.onTranscriptionProgress("Uploading...", 10);
        }
        var uploadedFile = apiService.uploadFile(audioPath, audioPath.getFileName().toString(), apiKey);
        txnLog.logFileUpload("Upload complete");
        log.debug("File upload: {}", uploadedFile.name());

        // Step 2: Wait for file to be ready
        String fileUri = null;
        String mimeType = null;

        for (int i = 0; i < 30; i++) {
            var status = apiService.getFileStatus(uploadedFile.name(), apiKey);
            log.debug("Status: {}", status.state());

            if (listener != null) {
                listener.onTranscriptionProgress("Processing file...", 20 + i * 2);
            }

            if ("ACTIVE".equals(status.state())) {
                fileUri = status.uri();
                mimeType = status.mimeType();
                txnLog.logFileProcessing("File active and ready");
                break;
            }
            if ("FAILED".equals(status.state())) {
                String error = "File processing failed";
                txnLog.logFailure(error);
                if (listener != null) listener.onTranscriptionError(error);
                throw new IOException(error);
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for file");
            }
        }

        if (fileUri == null) {
            String error = "Timeout waiting for file to be ready";
            txnLog.logFailure(error);
            if (listener != null) listener.onTranscriptionError(error);
            throw new IOException(error);
        }

        log.debug("File ready");
        txnLog.logFileProcessing("Starting transcription stream");

        // Step 3: Stream transcription
        StringBuilder transcription = new StringBuilder();
        AtomicBoolean hadError = new AtomicBoolean(false);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        try {
            streamTranscription(fileUri, mimeType, model, apiKey, transcription, hadError, errorMessage, txnLog);
        } catch (Exception e) {
            hadError.set(true);
            errorMessage.set(e.getMessage());
        }

        if (hadError.get()) {
            String error = errorMessage.get() != null ? errorMessage.get() : "Unknown error";
            txnLog.logFailure(error);
            if (listener != null) listener.onTranscriptionError(error);
            throw new IOException("Transcription failed: " + error);
        }

        String result = transcription.toString().trim();
        log.info("Transcription complete: {} chars", result.length());
        txnLog.logSuccess(result, result.length(), txnLog.getElapsedSeconds());

        if (listener != null) {
            listener.onTranscriptionComplete(result);
        }

        return result;
    }

    private void streamTranscription(String fileUri, String mimeType, String model,
                                     String apiKey, StringBuilder result,
                                     AtomicBoolean hadError, AtomicReference<String> errorMessage,
                                     TranscriptionLogger txnLog)
            throws GeminiClientException {

        log.info("Starting streaming transcription...");

        // Build request with audio file and prompt
        List<Part> parts = new ArrayList<>();
        parts.add(new Part().fileData(new FileData().fileUri(fileUri).mimeType(mimeType)));
        parts.add(new Part().text(TRANSCRIPTION_PROMPT));

        Request request = new Request()
                .contents(List.of(new Content().parts(parts).role("user")));

        // Stream the response
        int[] chunkCount = {0};
        apiService.streamGenerateContentWithCallback(model, request, apiKey, (chunk) -> {
            if (hadError.get()) return;

            if (chunk.error() != null) {
                hadError.set(true);
                errorMessage.set(chunk.error().message());
                return;
            }

            if (chunk.promptFeedback() != null && chunk.promptFeedback().blockReason() != null) {
                hadError.set(true);
                errorMessage.set("Content blocked: " + chunk.promptFeedback().blockReason());
                return;
            }

            if (chunk.candidates() != null) {
                for (Candidate candidate : chunk.candidates()) {
                    if (candidate.content() != null && candidate.content().parts() != null) {
                        for (Part part : candidate.content().parts()) {
                            if (part.text() != null) {
                                result.append(part.text());
                                chunkCount[0]++;

                                // Notify listener of progress
                                if (listener != null) {
                                    // Estimate progress based on chunk count (rough approximation)
                                    int estimatedPercent = Math.min(95, chunkCount[0] * 5);
                                    listener.onTranscriptionProgress(result.toString(), estimatedPercent);
                                }

                                log.debug(part.text());
                            }
                        }
                    }
                }
            }
        });

        log.debug("Streaming complete");
    }
}
