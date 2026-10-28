package speechfx.voicetyping;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;

/**
 * Why a speech stream failed, which decides what happens to its key.
 *
 * A rejected key is frozen for a day and an exhausted one for an hour; a network failure
 * freezes nothing, because the key was never tried. STALLED is a stream that stayed
 * connected but returned no text while the speaker was clearly talking.
 */
public enum SpeechFailure {

    AUTH(Duration.ofHours(24)),
    QUOTA(Duration.ofHours(1)),
    NETWORK(Duration.ZERO),
    STALLED(Duration.ZERO),
    OTHER(Duration.ZERO);

    public final Duration freeze;

    SpeechFailure(Duration freeze) {
        this.freeze = freeze;
    }

    public static SpeechFailure ofHttp(int status) {
        return switch (status) {
            case 401, 403 -> AUTH;
            case 402, 429 -> QUOTA;
            default -> OTHER;
        };
    }

    public static SpeechFailure of(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof WebSocketHandshakeException h) return ofHttp(h.getResponse().statusCode());
            if (c instanceof StatusRuntimeException s) return ofGrpc(s.getStatus().getCode(), String.valueOf(s.getMessage()));
            if (c instanceof ConnectException || c instanceof UnknownHostException
                    || c instanceof HttpTimeoutException || c instanceof UnresolvedAddressException) return NETWORK;
        }
        return OTHER;
    }

    /** Providers report errors as "QUOTA: message"; anything without a known prefix is OTHER. */
    public static SpeechFailure fromMessage(String message) {
        var head = message == null ? "" : message.split(":", 2)[0].trim();
        for (var f : values()) if (f.name().equals(head)) return f;
        return OTHER;
    }

    static SpeechFailure ofGrpc(Status.Code code, String message) {
        return switch (code) {
            case UNAUTHENTICATED, PERMISSION_DENIED -> AUTH;
            case RESOURCE_EXHAUSTED -> QUOTA;
            case UNAVAILABLE, DEADLINE_EXCEEDED -> NETWORK;
            default -> message.contains("429") ? QUOTA : message.contains("401") || message.contains("403") ? AUTH : OTHER;
        };
    }
}
