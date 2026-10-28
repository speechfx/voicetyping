package speechfx.voicetyping;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import nvidia.riva.RivaAudio.AudioEncoding;
import nvidia.riva.asr.RivaAsr.*;
import nvidia.riva.asr.RivaSpeechRecognitionGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * NVIDIA Nemotron-ASR-Streaming live transcription provider.
 *
 * Streams raw PCM to the hosted Riva gRPC endpoint (grpc.nvcf.nvidia.com:443)
 * via the bidirectional StreamingRecognize RPC, authenticated with an nvapi key
 * and the model's NVCF function-id. Interim/final results drive the overlay
 * through listener.onTranscriptionProgress(); when recording stops the send
 * stream is half-closed so the server finalises the last utterance, then the
 * returned future resolves with the full committed transcript.
 */
public class NvidiaStreamingTranscriptionService implements StreamingTranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(NvidiaStreamingTranscriptionService.class);
    private static final String HOST = "grpc.nvcf.nvidia.com";
    private static final int PORT = 443;

    private TranscriptionListener listener;
    private volatile StreamingResultListener resultListener;

    private volatile ManagedChannel channel;
    private volatile StreamObserver<StreamingRecognizeRequest> requestObserver;
    private volatile CompletableFuture<String> sessionFuture;
    private volatile boolean sessionActive;
    private volatile boolean closing;
    private volatile SpeechKey key;

    private final StringBuilder fullTranscript = new StringBuilder();
    private volatile String lastInterim = "";
    private volatile long bytesSent;

    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "NV-Stream-Sender");
        t.setDaemon(true);
        return t;
    });
    private final Queue<byte[]> pendingChunks = new ConcurrentLinkedQueue<>();
    private final LagMeter lag = new LagMeter("nvidia");

    @Override
    public String getProviderName() { return "NVIDIA Nemotron (Streaming)"; }

    @Override
    public void setListener(TranscriptionListener l) { this.listener = l; }

    @Override
    public void setResultListener(StreamingResultListener l) { this.resultListener = l; }

    @Override
    public SpeechKey key() { return key; }

    @Override
    public void onRecordingStarted(AudioFormat format) {
        sessionActive = true;
        sessionFuture = new CompletableFuture<>();
        requestObserver = null;
        fullTranscript.setLength(0);
        lastInterim = "";
        bytesSent = 0;
        pendingChunks.clear();
        lag.open(format);

        closing = false;
        key = SpeechKeys.pick("nvidia");
        var apiKey = key == null ? null : key.value();
        if (apiKey == null) {
            sessionActive = false;
            sessionFuture.completeExceptionally(new IllegalStateException("No NVIDIA API key available"));
            if (listener != null) listener.onTranscriptionError("No NVIDIA API key available");
            return;
        }

        var md = new Metadata();
        md.put(Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER), VoiceTypingSettings.getNvidiaFunctionId());
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + apiKey);

        channel = ManagedChannelBuilder.forAddress(HOST, PORT).useTransportSecurity().build();
        var stub = RivaSpeechRecognitionGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        sendExecutor.submit(() -> {
            requestObserver = stub.streamingRecognize(new ResponseHandler());
            requestObserver.onNext(buildConfigRequest(format));
            lag.connected();
            byte[] pending;
            while ((pending = pendingChunks.poll()) != null) {
                requestObserver.onNext(audioRequest(pending, pending.length));
            }
            log.info("NVIDIA streaming connected ({}Hz)", (int) format.getSampleRate());
            if (listener != null) listener.onTranscriptionStarted();
        });
    }

    @Override
    public void onAudioChunk(byte[] chunk, int length) {
        onAudioChunk(chunk, length, System.currentTimeMillis());
    }

    @Override
    public void onAudioChunk(byte[] chunk, int length, long capturedAt) {
        if (!sessionActive) return;
        var copy = Arrays.copyOf(chunk, length);
        lag.sent(length, capturedAt);
        lag.queued.incrementAndGet();
        sendExecutor.submit(() -> {
            lag.queued.decrementAndGet();
            var ro = requestObserver;
            if (ro != null) ro.onNext(audioRequest(copy, copy.length));
            else pendingChunks.add(copy);
        });
    }

    @Override
    public CompletableFuture<String> transcribeAsync(Path audioPath) {
        closing = true;
        sendExecutor.submit(() -> {
            var ro = requestObserver;
            log.debug("NV half-close: {} audio bytes sent", bytesSent);
            if (ro == null) { sessionFuture.complete(""); return; }
            try {
                ro.onCompleted();
            } catch (Exception e) {
                log.error("Error closing NVIDIA stream: {}", e.getMessage());
                sessionFuture.completeExceptionally(e);
            }
        });
        return sessionFuture.orTimeout(30, TimeUnit.SECONDS).whenComplete((t, e) -> {
            if (e != null) {
                lag.closed("abandoned, finalise did not complete: " + e);
                shutdownChannel();
            }
        });
    }

    private StreamingRecognizeRequest buildConfigRequest(AudioFormat format) {
        var recConfig = RecognitionConfig.newBuilder()
                .setEncoding(AudioEncoding.LINEAR_PCM)
                .setSampleRateHertz((int) format.getSampleRate())
                .setLanguageCode(VoiceTypingSettings.getNvidiaLanguage())
                .setMaxAlternatives(1)
                .setEnableAutomaticPunctuation(true)
                .build();
        var streamConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(recConfig)
                .setInterimResults(true)
                .build();
        return StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamConfig).build();
    }

    private StreamingRecognizeRequest audioRequest(byte[] data, int length) {
        bytesSent += length;
        return StreamingRecognizeRequest.newBuilder()
                .setAudioContent(ByteString.copyFrom(data, 0, length))
                .build();
    }

    private void fail(Throwable t) {
        var f = SpeechFailure.of(t);
        SpeechKeys.report(key, f, String.valueOf(t.getMessage()));
        var l = listener;
        if (!closing && l != null) l.onTranscriptionError(f + ": " + t.getMessage());
    }

    private void shutdownChannel() {
        var ch = channel;
        if (ch != null) ch.shutdownNow();
    }

    private final class ResponseHandler implements StreamObserver<StreamingRecognizeResponse> {
        @Override
        public void onNext(StreamingRecognizeResponse response) {
            for (var result : response.getResultsList()) {
                if (result.getAlternativesCount() == 0) continue;
                var transcript = result.getAlternatives(0).getTranscript().trim();
                if (transcript.isEmpty()) continue;
                lag.result(result.getAudioProcessed(), result.getIsFinal(), transcript);

                var rl = resultListener;
                if (rl != null) rl.onResult(transcript, result.getIsFinal());

                if (result.getIsFinal()) {
                    log.debug("NV final: '{}'", transcript);
                    if (!fullTranscript.isEmpty()) fullTranscript.append(" ");
                    fullTranscript.append(transcript);
                    lastInterim = "";
                } else {
                    lastInterim = transcript;
                }

                var display = fullTranscript.toString();
                if (!lastInterim.isEmpty()) display = display.isEmpty() ? lastInterim : display + " " + lastInterim;
                if (listener != null) listener.onTranscriptionProgress(display, result.getIsFinal() ? 80 : 40);
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("NVIDIA streaming error: {}", t.getMessage());
            lag.closed("error");
            sessionActive = false;
            sessionFuture.completeExceptionally(t);
            shutdownChannel();
            fail(t);
        }

        @Override
        public void onCompleted() {
            log.info("NVIDIA streaming completed");
            lag.closed("completed");
            sessionActive = false;
            sessionFuture.complete(fullTranscript.toString().trim());
            shutdownChannel();
        }
    }
}
