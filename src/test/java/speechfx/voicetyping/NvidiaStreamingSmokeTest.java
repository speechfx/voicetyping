package speechfx.voicetyping;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import nvidia.riva.RivaAudio.AudioEncoding;
import nvidia.riva.asr.RivaAsr.*;
import nvidia.riva.asr.RivaSpeechRecognitionGrpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Streams a raw 16-bit mono LE PCM file to grpc.nvcf.nvidia.com and prints every
 * server response. Args: [pcmPath] [sampleRate].
 * Run: java -cp ... speechfx.voicetyping.NvidiaStreamingSmokeTest speech16k.pcm 16000
 */
public class NvidiaStreamingSmokeTest {
    public static void main(String[] args) throws Exception {
        var pcmPath = Path.of(args.length > 0 ? args[0] : "C:/tmp/speech16k.pcm");
        var rate = args.length > 1 ? Integer.parseInt(args[1]) : 16000;
        var pcm = Files.readAllBytes(pcmPath);
        System.out.println("=== " + pcmPath.getFileName() + "  rate=" + rate + "  bytes=" + pcm.length + " ===");

        var apiKey = args.length > 2 ? args[2] : SpeechKeys.pick("nvidia").value();
        var md = new Metadata();
        md.put(Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER), VoiceTypingSettings.getNvidiaFunctionId());
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + apiKey);

        var channel = ManagedChannelBuilder.forAddress("grpc.nvcf.nvidia.com", 443).useTransportSecurity().build();
        var stub = RivaSpeechRecognitionGrpc.newStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        var done = new CountDownLatch(1);
        var reqObs = stub.streamingRecognize(new StreamObserver<StreamingRecognizeResponse>() {
            public void onNext(StreamingRecognizeResponse r) {
                System.out.println("onNext: " + r.getResultsCount() + " result(s)");
                for (var res : r.getResultsList())
                    System.out.println("  final=" + res.getIsFinal() + " stab=" + res.getStability()
                            + " audioProc=" + res.getAudioProcessed()
                            + " alt='" + (res.getAlternativesCount() > 0 ? res.getAlternatives(0).getTranscript() : "") + "'");
            }
            public void onError(Throwable t) { System.out.println("onError: " + t); done.countDown(); }
            public void onCompleted() { System.out.println("onCompleted"); done.countDown(); }
        });

        var recConfig = RecognitionConfig.newBuilder()
                .setEncoding(AudioEncoding.LINEAR_PCM).setSampleRateHertz(rate)
                .setLanguageCode("en-US").setMaxAlternatives(1).setEnableAutomaticPunctuation(true).build();
        reqObs.onNext(StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(StreamingRecognitionConfig.newBuilder().setConfig(recConfig).setInterimResults(true).build())
                .build());

        int chunk = rate / 10 * 2; // ~100ms
        for (int off = 0; off < pcm.length; off += chunk) {
            int len = Math.min(chunk, pcm.length - off);
            reqObs.onNext(StreamingRecognizeRequest.newBuilder().setAudioContent(ByteString.copyFrom(pcm, off, len)).build());
            Thread.sleep(90);
        }
        reqObs.onCompleted();

        done.await(30, TimeUnit.SECONDS);
        channel.shutdownNow();
        System.out.println("=== end ===");
        System.exit(0);
    }
}
