package speechfx.voicetyping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * Deepgram live-streaming transcription provider.
 *
 * Opens a WebSocket when recording starts and streams raw PCM audio.
 * Calls listener.onTranscriptionProgress() with the accumulated display
 * text on every interim/final result — the UI layer (StreamingOverlayWindow)
 * renders it; no Robot, no keyboard events here.
 *
 * When recording stops, transcribeAsync() sends 2 s of silence + CloseStream
 * so Deepgram finalises the last utterance, then resolves the returned future
 * with the full committed transcript.
 */
public class DeepgramStreamingTranscriptionService implements StreamingTranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepgramStreamingTranscriptionService.class);
    private static final String WS_URL = "wss://api.deepgram.com/v1/listen";
    private static final String FLUX_URL = "wss://api.deepgram.com/v2/listen";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TranscriptionListener listener;
    private volatile StreamingResultListener resultListener;

    private volatile WebSocket webSocket;
    private volatile CompletableFuture<String> sessionFuture;
    private volatile boolean sessionActive;
    private volatile boolean closing;
    private volatile SpeechKey key;
    private volatile AudioFormat audioFormat;

    private final StringBuilder fullTranscript = new StringBuilder();
    private volatile String lastInterimTyped = "";

    // Single-threaded executor keeps WebSocket sends sequential.
    // Chunks that arrive before the socket opens are buffered here.
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DG-Stream-Sender");
        t.setDaemon(true);
        return t;
    });
    private final Queue<byte[]> pendingChunks = new ConcurrentLinkedQueue<>();
    private final LagMeter lag;

    /** Flux (v2): a word at a time, about every quarter second, in English only; a turn is final when the speaker pauses. */
    final boolean flux;

    public DeepgramStreamingTranscriptionService() { this(false); }

    public DeepgramStreamingTranscriptionService(boolean flux) {
        this.flux = flux;
        lag = new LagMeter(flux ? "deepgram-flux" : "deepgram");
    }

    @Override
    public String getProviderName() { return flux ? "Deepgram Flux" : "Deepgram (Streaming)"; }

    @Override
    public void setListener(TranscriptionListener l) { this.listener = l; }

    @Override
    public void setResultListener(StreamingResultListener l) { this.resultListener = l; }

    @Override
    public SpeechKey key() { return key; }

    @Override
    public void onRecordingStarted(AudioFormat format) {
        sessionActive = true;
        webSocket = null;
        sessionFuture = new CompletableFuture<>();
        audioFormat = format;
        fullTranscript.setLength(0);
        lastInterimTyped = "";
        pendingChunks.clear();
        lag.open(format);

        closing = false;
        key = SpeechKeys.pick("deepgram");
        if (key == null) {
            sessionActive = false;
            sessionFuture.completeExceptionally(new IllegalStateException("No usable Deepgram key"));
            if (listener != null) listener.onTranscriptionError("No usable Deepgram key (all disabled or frozen)");
            return;
        }
        var apiKey = key.value();
        var model = VoiceTypingSettings.getDeepgramModel();
        int sampleRate = (int) format.getSampleRate();

        String url = flux
                ? FLUX_URL + "?model=flux-general-en&encoding=linear16&sample_rate=" + sampleRate
                : WS_URL
                + "?model=" + model
                + "&encoding=linear16&sample_rate=" + sampleRate + "&channels=1"
                + "&smart_format=true&punctuate=true"
                + "&interim_results=true&endpointing=300";

        var msgBuf = new StringBuilder();

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Token " + apiKey)
                .buildAsync(URI.create(url), new WebSocket.Listener() {

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        msgBuf.append(data);
                        if (last) {
                            handleMessage(msgBuf.toString());
                            msgBuf.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        log.info("Deepgram streaming closed ({} {})", statusCode, reason);
                        lag.closed(statusCode + " " + reason);
                        promoteInterim("stream closed");
                        if (statusCode != 1000 && statusCode != 1005) failed(SpeechFailure.OTHER, "closed " + statusCode + " " + reason);
                        sessionActive = false;
                        sessionFuture.complete(fullTranscript.toString().trim());
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("Deepgram streaming error: {}", error.getMessage());
                        sessionActive = false;
                        sessionFuture.completeExceptionally(error);
                        failed(SpeechFailure.of(error), String.valueOf(error.getMessage()));
                    }
                })
                .thenAccept(ws -> sendExecutor.submit(() -> {
                    webSocket = ws;
                    lag.connected();
                    byte[] pending;
                    while ((pending = pendingChunks.poll()) != null) {
                        trySend(ws, ByteBuffer.wrap(pending));
                    }
                    log.info("Deepgram streaming connected ({}Hz)", sampleRate);
                    if (listener != null) listener.onTranscriptionStarted();
                }))
                .exceptionally(ex -> {
                    log.error("Failed to connect Deepgram streaming WebSocket: {}", ex.getMessage());
                    sessionActive = false;
                    sessionFuture.completeExceptionally(ex);
                    failed(SpeechFailure.of(ex), String.valueOf(ex.getMessage()));
                    return null;
                });
    }

    @Override
    public void onAudioChunk(byte[] chunk, int length) {
        onAudioChunk(chunk, length, System.currentTimeMillis());
    }

    @Override
    public void onAudioChunk(byte[] chunk, int length, long capturedAt) {
        if (!sessionActive) return;
        byte[] copy = Arrays.copyOf(chunk, length);
        lag.sent(length, capturedAt);
        lag.queued.incrementAndGet();
        sendExecutor.submit(() -> {
            lag.queued.decrementAndGet();
            WebSocket ws = webSocket;
            if (ws != null) {
                trySend(ws, ByteBuffer.wrap(copy));
            } else {
                pendingChunks.add(copy);
            }
        });
    }

    @Override
    public CompletableFuture<String> transcribeAsync(Path audioPath) {
        closing = true;
        // audioPath is the saved MP3 (kept for history/retry); we don't re-transcribe it.
        // Send silence to flush the last utterance, then close the socket.
        sendExecutor.submit(() -> {
            WebSocket ws = webSocket;
            if (ws == null) {
                sessionFuture.complete("");
                return;
            }
            try {
                AudioFormat fmt = audioFormat;
                if (fmt != null) {
                    int silenceBytes = (int) (fmt.getSampleRate() * fmt.getFrameSize() * 2);
                    trySend(ws, ByteBuffer.wrap(new byte[silenceBytes]));
                }
                ws.sendText("{\"type\":\"CloseStream\"}", true).join();
            } catch (Exception e) {
                log.error("Error closing Deepgram stream: {}", e.getMessage());
                sessionFuture.completeExceptionally(e);
            }
        });
        return sessionFuture.orTimeout(30, TimeUnit.SECONDS).whenComplete((t, e) -> {
            var ws = webSocket;
            if (e != null) promoteInterim("finalise did not complete");
            if (e != null && ws != null) {
                lag.closed("aborted, finalise did not complete: " + e);
                ws.abort();
            }
        });
    }

    private void failed(SpeechFailure f, String message) {
        SpeechKeys.report(key, f, message);
        var l = listener;
        if (!closing && l != null) l.onTranscriptionError(f + ": " + message);
    }

    private void trySend(WebSocket ws, ByteBuffer buf) {
        try {
            ws.sendBinary(buf, true).join();
        } catch (Exception e) {
            log.debug("Failed to send audio chunk: {}", e.getMessage());
        }
    }

    private void handleMessage(String json) {
        try {
            var root = MAPPER.readTree(json);
            var type = root.path("type").asText();
            if (flux) {
                // TurnInfo carries the whole turn so far on every event; EndOfTurn is its final form.
                if (!"TurnInfo".equals(type)) {
                    if (type.contains("Error")) log.warn("Deepgram Flux {}: {}", type, json);
                    return;
                }
                deliver(root.path("transcript").asText("").trim(), "EndOfTurn".equals(root.path("event").asText()),
                        root.path("audio_window_end").asDouble(0));
                return;
            }
            if (!"Results".equals(type)) return;

            var alternatives = root.path("channel").path("alternatives");
            if (alternatives.isEmpty() || alternatives.get(0).isMissingNode()) return;

            deliver(alternatives.get(0).path("transcript").asText("").trim(), root.path("is_final").asBoolean(false),
                    root.path("start").asDouble(0) + root.path("duration").asDouble(0));
        } catch (Exception e) {
            log.error("Error handling Deepgram message: {}", e.getMessage());
        }
    }

    /** A closing stream can leave words that were shown but never made final; they were said, so keep them. */
    private void promoteInterim(String why) {
        var left = lastInterimTyped;
        if (left.isEmpty()) return;
        log.info("{}: keeping words never made final ({} chars) - {}", lag.name, left.length(), why);
        deliver(left, true, 0);
    }

    private synchronized void deliver(String transcript, boolean isFinal, double audioEnd) {
        if (transcript.isEmpty()) return;
        if (audioEnd > 0) lag.result(audioEnd, isFinal, transcript);

        var rl = resultListener;
        if (rl != null) rl.onResult(transcript, isFinal);

        if (isFinal) {
            if (!fullTranscript.isEmpty()) fullTranscript.append(" ");
            fullTranscript.append(transcript);
            lastInterimTyped = "";
        } else {
            lastInterimTyped = transcript;
        }

        // Build display text: committed words + current in-progress interim
        String display = fullTranscript.toString();
        if (!lastInterimTyped.isEmpty()) {
            display = display.isEmpty() ? lastInterimTyped : display + " " + lastInterimTyped;
        }

        if (listener != null) {
            listener.onTranscriptionProgress(display, isFinal ? 80 : 40);
        }
    }
}
