package speechfx.voicetyping;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.AudioFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * Experiment only: Claude Code's internal /voice endpoint, driven the way Claude Code 2.1.293
 * drives it, so it can be compared with NVIDIA and Deepgram on the same audio. Undocumented and
 * signed in with the claude.ai login token from ~/.claude/.credentials.json (never printed).
 */
public final class ClaudeVoiceStream implements StreamingTranscriptionProvider {

    static final String URL = "wss://api.anthropic.com/api/ws/speech_to_text/voice_stream?encoding=linear16"
            + "&sample_rate=16000&channels=1&endpointing_ms=300&utterance_end_ms=1000&language=en&use_conversation_engine=true" + System.getProperty("cv.extra", "");
    static final ObjectMapper JSON = new ObjectMapper();

    final StringBuilder text = new StringBuilder();
    final CompletableFuture<String> done = new CompletableFuture<>();
    final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor();
    volatile WebSocket ws;
    volatile String pending = "";

    @Override public void onRecordingStarted(AudioFormat f) {
        try {
            var token = JSON.readTree(Path.of(System.getProperty("user.home"), ".claude", ".credentials.json").toFile())
                    .path("claudeAiOauth").path("accessToken").asText();
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "claude-cli/2.1.293 (external, cli)")
                    .header("x-app", "cli")
                    .buildAsync(URI.create(URL), new Listener()).get(15, TimeUnit.SECONDS);
            ws.sendText("{\"type\":\"KeepAlive\"}", true);
            keepAlive.scheduleAtFixedRate(() -> ws.sendText("{\"type\":\"KeepAlive\"}", true), 8, 8, TimeUnit.SECONDS);
        } catch (Exception e) {
            done.completeExceptionally(e);
        }
    }

    @Override public void onAudioChunk(byte[] chunk, int length) {
        var w = ws;
        if (w != null) w.sendBinary(ByteBuffer.wrap(chunk, 0, length), true).join();
    }

    @Override public CompletableFuture<String> transcribeAsync(Path ignored) {
        if (ws != null) ws.sendText("{\"type\":\"CloseStream\"}", true);
        CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(() -> done.complete(finish()));
        return done;
    }

    synchronized String finish() {
        if (!pending.isEmpty()) { text.append(pending).append(' '); pending = ""; }
        keepAlive.shutdownNow();
        return text.toString().trim();
    }

    final class Listener implements WebSocket.Listener {
        final StringBuilder frame = new StringBuilder();

        @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            frame.append(data);
            if (last) { handle(frame.toString()); frame.setLength(0); }
            w.request(1);
            return null;
        }

        void handle(String msg) {
            try {
                var n = JSON.readTree(msg);
                if (System.getenv("CVDEBUG") != null) System.err.println("FRAME " + n.path("type").asText() + " | " + n.path("data").asText("").replaceAll("^(.{0,40}).*?(.{0,50})$", "$1 … $2"));
                switch (n.path("type").asText()) {
                    case "TranscriptInterim", "TranscriptText" -> pending = n.path("data").asText("");
                    case "TranscriptEndpoint" -> { synchronized (ClaudeVoiceStream.this) { text.append(pending).append(' '); pending = ""; } }
                    default -> System.err.println("claude voice: " + msg);
                }
            } catch (Exception e) {
                System.err.println("claude voice, unreadable frame: " + msg);
            }
        }

        @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            System.err.println("claude voice closed " + code + " " + reason);
            done.complete(finish());
            return null;
        }

        @Override public void onError(WebSocket w, Throwable e) {
            System.err.println("claude voice error: " + e);
            done.complete(finish());
        }
    }

    @Override public void setListener(TranscriptionListener l) {}
    @Override public String getProviderName() { return "Claude Code voice (experiment)"; }
}
