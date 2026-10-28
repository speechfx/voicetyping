package speechfx.voicetyping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DeepgramTranscriptionService implements TranscriptionProvider {
    private static final Logger log = LoggerFactory.getLogger(DeepgramTranscriptionService.class);
    static final String API_URL = "https://api.deepgram.com/v1/listen";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TranscriptionListener listener;

    @Override
    public void setListener(TranscriptionListener listener) {
        this.listener = listener;
    }

    @Override
    public String getProviderName() {
        return "Deepgram";
    }

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

    String transcribe(Path audioPath) throws IOException, InterruptedException {
        var txnLog = new TranscriptionLogger(audioPath);
        var dgKey = SpeechKeys.pick("deepgram");
        var apiKey = dgKey == null ? null : dgKey.value();
        if (apiKey == null) {
            var err = "No Deepgram API key. Add key to ~/speechfx/keys/deepgram_keys.xml";
            txnLog.logFailure(err);
            throw new IOException(err);
        }

        var model = VoiceTypingSettings.getDeepgramModel();
        txnLog.logStart(apiKey, "deepgram:" + model);
        log.info("Deepgram model: {}", model);

        if (listener != null) listener.onTranscriptionStarted();
        if (listener != null) listener.onTranscriptionProgress("Uploading to Deepgram...", 15);

        var audioBytes = Files.readAllBytes(audioPath);
        var url = API_URL + "?model=" + model + "&smart_format=true&punctuate=true";
        log.info("Uploading {} ({} bytes)", audioPath.getFileName(), audioBytes.length);
        txnLog.logFileUpload("Sending " + audioBytes.length + " bytes to Deepgram");

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Token " + apiKey)
                .header("Content-Type", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(audioBytes))
                .build();

        if (listener != null) listener.onTranscriptionProgress("Waiting for Deepgram...", 50);

        var startMs = System.currentTimeMillis();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var elapsedMs = System.currentTimeMillis() - startMs;

        log.info("Deepgram response: status={}, time={}ms", response.statusCode(), elapsedMs);
        txnLog.logFileProcessing("HTTP " + response.statusCode() + " received in " + elapsedMs + "ms");

        if (response.statusCode() >= 400) {
            var err = "Deepgram API error " + response.statusCode() + ": " + response.body();
            txnLog.logFailure(err);
            if (listener != null) listener.onTranscriptionError(err);
            throw new IOException(err);
        }

        if (listener != null) listener.onTranscriptionProgress("Parsing result...", 90);

        var root = MAPPER.readTree(response.body());
        var channels = root.path("results").path("channels");
        if (channels.isMissingNode() || channels.isEmpty() || channels.get(0) == null) {
            var err = "Deepgram returned no channels in response";
            txnLog.logFailure(err);
            if (listener != null) listener.onTranscriptionError(err);
            throw new IOException(err);
        }
        var alternatives = channels.get(0).path("alternatives");
        if (alternatives.isMissingNode() || alternatives.isEmpty()) {
            var err = "Deepgram returned no alternatives";
            txnLog.logFailure(err);
            if (listener != null) listener.onTranscriptionError(err);
            throw new IOException(err);
        }

        var transcript = alternatives.get(0).path("transcript").asText("").trim();
        if (transcript.isBlank()) {
            var err = "Deepgram returned empty transcript";
            txnLog.logFailure(err);
            if (listener != null) listener.onTranscriptionError(err);
            throw new IOException(err);
        }

        txnLog.logSuccess(transcript, transcript.length(), elapsedMs / 1000.0);
        log.info("Deepgram transcribed {} chars in {}ms", transcript.length(), elapsedMs);
        if (listener != null) listener.onTranscriptionComplete(transcript);
        return transcript;
    }
}
