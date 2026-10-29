package speechfx.voicetyping;

import speechfx.dictation.*;
import speechfx.dictation.controls.*;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.util.ArrayList;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Just enough of the Redis wire protocol, over TLS, for the phone link. A real client library would
 * be a dependency for five commands; the one thing a library would add that matters here is BLPOP,
 * and that is only a matter of holding the socket open - which is exactly why this exists instead of
 * the REST API, which has no blocking read and would have to poll through the free monthly quota.
 */
public final class RespClient implements Closeable {

    static final int PORT = 6379, READ_TIMEOUT_MS = 45_000;

    final SSLSocket socket;
    final InputStream in;
    final OutputStream out;

    RespClient(SSLSocket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RespClient connect(LinkCredentials c) throws IOException {
        var s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(c.host, PORT);
        var params = s.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        s.setSSLParameters(params);
        s.setSoTimeout(READ_TIMEOUT_MS);
        var client = new RespClient(s);
        client.call("AUTH", c.password);
        return client;
    }

    public synchronized Object call(String... argv) throws IOException {
        out.write(("*" + argv.length + "\r\n").getBytes(US_ASCII));
        for (var a : argv) {
            var bytes = a.getBytes(UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(US_ASCII));
            out.write(bytes);
            out.write('\r');
            out.write('\n');
        }
        out.flush();
        return read();
    }

    Object read() throws IOException {
        var line = line();
        var body = line.substring(1);
        return switch (line.charAt(0)) {
            case '+' -> body;
            case '-' -> throw new IOException("redis: " + body);
            case ':' -> Long.parseLong(body);
            case '$' -> {
                var n = Integer.parseInt(body);
                if (n < 0) yield null;
                var bytes = in.readNBytes(n);
                in.readNBytes(2);
                yield new String(bytes, UTF_8);
            }
            case '*' -> {
                var n = Integer.parseInt(body);
                if (n < 0) yield null;
                var items = new ArrayList<Object>(n);
                for (var i = 0; i < n; i++) items.add(read());
                yield items;
            }
            default -> throw new IOException("unexpected reply: " + line);
        };
    }

    String line() throws IOException {
        var b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\r') {
                in.read();
                return b.toString(UTF_8);
            }
            b.write(c);
        }
        throw new EOFException("relay closed the connection");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
