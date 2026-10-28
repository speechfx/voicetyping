package speechfx.voicetyping;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The audio sent since the last final result, capped at a few tens of seconds. When a stream
 * dies or stalls, this is the speech it never confirmed, and it is replayed into the next one
 * so nothing said in the meantime is lost. Cleared whenever a final result arrives.
 */
public final class UncommittedAudio {

    /** A chunk and the wall-clock moment its last sample was captured. */
    public record Chunk(byte[] data, long capturedAt) {}

    final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    final long capBytes;
    long bytes;

    public UncommittedAudio(int bytesPerSecond, int seconds) {
        this.capBytes = (long) bytesPerSecond * seconds;
    }

    public synchronized void add(Chunk chunk) {
        chunks.addLast(chunk);
        bytes += chunk.data().length;
        while (bytes > capBytes && chunks.size() > 1) bytes -= chunks.removeFirst().data().length;
    }

    public synchronized void clear() {
        chunks.clear();
        bytes = 0;
    }

    public synchronized List<Chunk> snapshot() {
        return new ArrayList<>(chunks);
    }

    public synchronized long millis(int bytesPerSecond) {
        return bytes * 1000 / Math.max(1, bytesPerSecond);
    }
}
