import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpServer;
import io.flowspeech.springai.api.FlowSpeechApi;
import io.flowspeech.springai.autoconfigure.FlowSpeechAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Run against the packaged JARs, never target/classes or a production API. */
public class ArtifactSmoke {
    public static void main(String[] args) throws Exception {
        for (Class<?> type : new Class<?>[] { FlowSpeechApi.class, FlowSpeechAutoConfiguration.class }) {
            if (!type.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("-0.2.0.jar")) {
                throw new AssertionError("Expected packaged 0.2.0 JAR: " + type);
            }
        }
        var apiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var creates = new AtomicInteger();
        var downloads = new AtomicInteger();
        var quotaReads = new AtomicInteger();
        var submittedIds = new CopyOnWriteArrayList<String>();
        var submittedBodies = new CopyOnWriteArrayList<byte[]>();
        var requestId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        var wav = java.util.HexFormat.of().parseHex(
                "524946462800000057415645666d74201000000001000100c05d000080bb000002001000646174610400000000000100");
        storage.createContext("/audio.wav", exchange -> {
            if (exchange.getRequestHeaders().containsKey("Authorization") || exchange.getRequestHeaders().containsKey("Cookie")) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            int attempt = downloads.incrementAndGet();
            byte[] payload = wav.clone();
            if (attempt == 2) payload[32] = 4; // Invalid block alignment.
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, wav.length);
            exchange.getResponseBody().write(payload); exchange.close();
        });
        apiServer.createContext("/api/v1/tts/jobs", exchange -> {
            int create = creates.incrementAndGet();
            String submittedId = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (!"Bearer test-only-placeholder".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || submittedId == null || (create == 1 && !requestId.toString().equals(submittedId))) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            UUID.fromString(submittedId);
            byte[] inputBytes = exchange.getRequestBody().readAllBytes();
            submittedIds.add(submittedId);
            submittedBodies.add(inputBytes);
            String input = new String(inputBytes, StandardCharsets.UTF_8);
            if (!input.equals("{\"input\":{\"mode\":\"single\",\"text\":\"Hello\",\"voiceName\":\"Kore\"}}")) {
                exchange.sendResponseHeaders(400, -1); exchange.close(); return;
            }
            byte[] response = """
                {"id":"00000000-0000-4000-8000-000000000001","requestId":"%s","status":"success",
                "usage":{"billableCharacters":5,"reservedCredits":0,"chargedCredits":5},
                "result":{"url":"http://127.0.0.1:%s/audio.wav","mimeType":"audio/wav","sampleRate":24000,"channels":1,"bitsPerSample":16}}
                """.formatted(submittedId, storage.getAddress().getPort()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        apiServer.createContext("/api/v1/quota", exchange -> {
            if (quotaReads.incrementAndGet() == 1) {
                byte[] limited = "<html>Rate limited</html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Retry-After", "5");
                exchange.sendResponseHeaders(429, limited.length);
                exchange.getResponseBody().write(limited); exchange.close(); return;
            }
            byte[] response = """
                {"quota":{"available":10,"reserved":0,"remaining":10},
                "subscription":{"subscriptionNo":"test-subscription","productId":"basic-monthly",
                "entitledUntil":"2026-11-01T00:00:00.000Z"},"limits":{}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        apiServer.createContext("/api/v1/voices", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"test-only-placeholder\",\"message\":\"https://audio.example.test/private?signature=CANARY\",\"retryable\":false},\"traceId\":\"test-only-placeholder\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        apiServer.start(); storage.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-only",
                    java.util.Map.of("spring.ai.flowspeech.api-key", "test-only-placeholder",
                            "spring.ai.flowspeech.base-url", "http://127.0.0.1:" + apiServer.getAddress().getPort())));
            context.register(FlowSpeechAutoConfiguration.class);
            context.refresh();
            var api = context.getBean(FlowSpeechApi.class);
            byte[] bytes = api.generate(FlowSpeechApi.Input.single("Hello", "Kore"), requestId);
            if (!java.util.Arrays.equals(bytes, wav) || creates.get() != 1 || downloads.get() != 1) {
                throw new AssertionError("Packaged adapter did not generate/download exactly once");
            }
            if (!api.quota().subscription().get("productId").asString().equals("basic-monthly")) {
                throw new AssertionError("Packaged quota adapter discarded subscription");
            }
            if (quotaReads.get() != 2) throw new AssertionError("Packaged adapter did not retry HTML 429");
            try {
                api.voices();
                throw new AssertionError("Packaged adapter accepted rejected request");
            } catch (FlowSpeechApi.FlowSpeechException error) {
                if (!error.code().equals("http_error") || error.traceId() != null
                        || error.toString().contains("test-only-placeholder") || error.toString().contains("CANARY")) {
                    throw new AssertionError("Packaged error reflection was not sanitized");
                }
            }
            var existing = new FlowSpeechApi.Job("00000000-0000-4000-8000-000000000001",
                    requestId.toString(), "success", new FlowSpeechApi.Result(
                    "http://127.0.0.1:" + storage.getAddress().getPort() + "/audio.wav", "audio/wav", 24000, 1, 16),
                    false, new FlowSpeechApi.Usage(5L, 0L, 5L), null);
            try {
                api.download(existing);
                throw new AssertionError("Packaged adapter accepted corrupt PCM");
            } catch (FlowSpeechApi.FlowSpeechException error) {
                if (!error.code().equals("invalid_audio") || creates.get() != 1) throw error;
            }
            var speakers = new java.util.ArrayList<>(java.util.List.of(new FlowSpeechApi.Speaker("Kore", null)));
            var publisher = api.textToSpeechAsync(new FlowSpeechApi.FlowSpeechRequest("Hello", "Hello", speakers));
            if (creates.get() != 1) throw new AssertionError("Publisher performed eager HTTP");
            speakers.set(0, new FlowSpeechApi.Speaker("Puck", null));
            var first = publisher.block(java.time.Duration.ofSeconds(5));
            speakers.set(0, new FlowSpeechApi.Speaker("Aoede", null));
            var second = publisher.block(java.time.Duration.ofSeconds(5));
            if (!first.equals(second) || creates.get() != 3 || downloads.get() != 4
                    || !submittedIds.get(1).equals(submittedIds.get(2))
                    || !java.util.Arrays.equals(submittedBodies.get(1), submittedBodies.get(2))
                    || !java.util.Arrays.equals(java.util.Base64.getDecoder().decode(second.data().audioBase64()), wav)) {
                throw new AssertionError("Packaged async publisher changed body or UUID across subscriptions");
            }
            String imports = new String(FlowSpeechAutoConfiguration.class.getClassLoader().getResourceAsStream(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports").readAllBytes(), StandardCharsets.UTF_8);
            if (!imports.contains(FlowSpeechAutoConfiguration.class.getName())) throw new AssertionError("Missing auto-configuration import");
            System.out.println("PASS: local packaged JARs, HTML 429 retry, auto-configuration, v1 request, subscription, credential-free WAV, corrupt PCM rejection and immutable async replay");
        } finally { apiServer.stop(0); storage.stop(0); }
    }
}
