package io.flowspeech.springai.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

class FlowSpeechApiTests {
    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private static final UUID REQUEST = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final byte[] WAV = WavFixtures.wav();
    private static final FlowSpeechApi.Input INPUT = FlowSpeechApi.Input.single("Hello", "Kore");

    private static class FakeTime implements FlowSpeechApi.TimeSource {
        private long nanos;
        final List<Duration> sleeps = new ArrayList<>();
        public long nanoTime() { return this.nanos; }
        public Instant instant() { return Instant.parse("2026-10-26T00:00:00Z").plusNanos(this.nanos); }
        public void sleep(Duration duration) { this.sleeps.add(duration); this.nanos += duration.toNanos(); }
    }
    private FlowSpeechApi.Builder builder(MockWebServer server) {
        return FlowSpeechApi.builder().apiKey("test-only-placeholder").baseUrl(server.url("/").toString());
    }
    private static MockResponse json(String value) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(value);
    }
    private static String job(String status, String result) {
        return """
            {"id":"%s","requestId":"%s","status":"%s","usage":{"billableCharacters":5,"reservedCredits":0,"chargedCredits":5}%s}
            """.formatted(ID, REQUEST, status, result);
    }
    private static String result(MockWebServer storage) {
        return ",\"result\":{\"url\":\"" + storage.url("/audio.wav")
                + "\",\"mimeType\":\"audio/wav\",\"sampleRate\":24000,\"channels\":1,\"bitsPerSample\":16}";
    }

    @Test
    void pollsEveryPendingStateAndDownloadsWithoutCredentials() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            server.enqueue(json(job("queued", "")).setResponseCode(202).setHeader("Retry-After", "9"));
            for (String status : List.of("running", "merging", "settling")) server.enqueue(json(job(status, "")));
            server.enqueue(json(job("success", result(storage))));
            storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(WAV)));
            FakeTime time = new FakeTime();
            byte[] bytes = builder(server).timeSource(time).build().generate(INPUT, REQUEST);
            assertThat(bytes).isEqualTo(WAV);
            assertThat(time.sleeps).containsExactly(Duration.ofSeconds(9), Duration.ofSeconds(5),
                    Duration.ofSeconds(5), Duration.ofSeconds(5));
            var create = server.takeRequest();
            assertThat(create.getPath()).isEqualTo("/api/v1/tts/jobs");
            assertThat(create.getHeader("Authorization")).isEqualTo("Bearer test-only-placeholder");
            assertThat(create.getHeader("Idempotency-Key")).isEqualTo(REQUEST.toString());
            assertThat(create.getBody().readUtf8()).isEqualTo("{\"input\":{\"mode\":\"single\",\"text\":\"Hello\",\"voiceName\":\"Kore\"}}");
            for (int i = 0; i < 4; i++) assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/tts/jobs/" + ID);
            var download = storage.takeRequest();
            assertThat(download.getHeader("Authorization")).isNull();
            assertThat(download.getHeader("Cookie")).isNull();
            assertThat(download.getHeader("Idempotency-Key")).isNull();
        }
    }

    @Test
    void retryPreservesUuidAndBodyAndHonorsHttpDate() throws Exception {
        try (var server = new MockWebServer()) {
            String error = "{\"error\":{\"code\":\"busy\",\"message\":\"Try later\",\"retryable\":true},\"traceId\":\"trace\"}";
            server.enqueue(json(error).setResponseCode(503).setHeader("Retry-After", "Mon, 26 Oct 2026 00:00:12 GMT"));
            server.enqueue(json(error).setResponseCode(429).setHeader("Retry-After", "15"));
            server.enqueue(json(job("queued", "")).setResponseCode(202));
            FakeTime time = new FakeTime();
            builder(server).timeSource(time).build().createJob(INPUT, REQUEST);
            var first = server.takeRequest();
            String body = first.getBody().readUtf8();
            for (int i = 0; i < 2; i++) {
                var retry = server.takeRequest();
                assertThat(retry.getBody().readUtf8()).isEqualTo(body);
                assertThat(retry.getHeader("Idempotency-Key")).isEqualTo(first.getHeader("Idempotency-Key"));
            }
            assertThat(time.sleeps).containsExactly(Duration.ofSeconds(12), Duration.ofSeconds(15));
        }
    }

    @Test
    void retriesNetworkFailureWithOriginalIdentity() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(json(job("queued", "")).setResponseCode(202));
            builder(server).timeSource(new FakeTime()).build().createJob(INPUT, REQUEST);
            var first = server.takeRequest();
            var retry = server.takeRequest();
            assertThat(first.getBody().readUtf8()).isEqualTo(retry.getBody().readUtf8());
            assertThat(first.getHeader("Idempotency-Key")).isEqualTo(retry.getHeader("Idempotency-Key"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"60", "Mon, 26 Oct 2026 00:01:00 GMT"})
    void bodyReadFailureRetainsHeaderCooldownAndIdentity(String retryAfter) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("x".repeat(16384)).setResponseCode(429).setHeader("Retry-After", retryAfter)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            server.enqueue(json(job("queued", "")).setResponseCode(202));
            FakeTime time = new FakeTime();
            builder(server).timeSource(time).build().createJob(INPUT, REQUEST);
            assertThat(time.sleeps).containsExactly(Duration.ofSeconds(60));
            var first = server.takeRequest();
            var retry = server.takeRequest();
            assertThat(retry.getBody().readUtf8()).isEqualTo(first.getBody().readUtf8());
            assertThat(retry.getHeader("Idempotency-Key")).isEqualTo(REQUEST.toString());
        }
    }

    @Test
    void truncatedBodyCooldownCannotExceedTotalDeadline() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("x".repeat(16384)).setResponseCode(503).setHeader("Retry-After", "60")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            FakeTime time = new FakeTime();
            assertThatThrownBy(() -> builder(server).timeSource(time).timeout(Duration.ofSeconds(30))
                    .build().createJob(INPUT, REQUEST)).hasMessageContaining("deadline_exceeded");
            assertThat(time.sleeps).isEmpty();
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void quotaPreservesProductionSubscriptionAndOptionalLegacyEntitlement() throws Exception {
        try (var server = new MockWebServer()) {
            String quota = "\"quota\":{\"available\":10,\"remaining\":12,\"reserved\":2},\"limits\":{}";
            server.enqueue(json("{" + quota + ",\"subscription\":{\"subscriptionNo\":\"test-subscription\","
                    + "\"productId\":\"basic-monthly\",\"entitledUntil\":\"2026-11-01T00:00:00.000Z\"}}"));
            server.enqueue(json("{" + quota + ",\"subscription\":null}"));
            server.enqueue(json("{" + quota + ",\"entitlement\":{\"legacy\":true}}"));
            var api = builder(server).build();
            var current = api.quota();
            assertThat(current.subscription().get("subscriptionNo").asString()).isEqualTo("test-subscription");
            assertThat(current.subscription().get("productId").asString()).isEqualTo("basic-monthly");
            assertThat(current.subscription().get("entitledUntil").asString()).isEqualTo("2026-11-01T00:00:00.000Z");
            assertThat(current.entitlement()).isNull();
            assertThat(api.quota().subscription().isNull()).isTrue();
            var legacy = api.quota();
            assertThat(legacy.subscription()).isNull();
            assertThat(legacy.entitlement().get("legacy").asBoolean()).isTrue();
        }
    }

    @Test
    void multiInputQuotaVoicesRecoveryAndCancel() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(job("queued", "")).setResponseCode(202));
            server.enqueue(json("{\"quota\":{\"available\":10,\"remaining\":12,\"reserved\":2},\"subscription\":null,\"limits\":{}}"));
            server.enqueue(json("{\"voices\":[{\"name\":\"Kore\",\"description\":\"Firm\",\"gender\":\"female\"}]}"));
            server.enqueue(json(job("queued", "")));
            server.enqueue(json(job("queued", "")));
            server.enqueue(json(job("cancelled", "")));
            var api = builder(server).build();
            api.createJob(FlowSpeechApi.Input.multi(List.of(new FlowSpeechApi.Segment("Alice", "Hi", "Kore"),
                    new FlowSpeechApi.Segment("Bob", "Hello", "Puck"))), REQUEST);
            assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo(
                    "{\"input\":{\"mode\":\"multi\",\"segments\":[{\"speaker\":\"Alice\",\"text\":\"Hi\",\"voiceName\":\"Kore\"},{\"speaker\":\"Bob\",\"text\":\"Hello\",\"voiceName\":\"Puck\"}]}}");
            assertThat(api.quota().quota().get("available").asInt()).isEqualTo(10);
            assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/quota");
            assertThat(api.voices().voices().get(0).name()).isEqualTo("Kore");
            assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/voices");
            assertThat(api.recoverJob(REQUEST).id()).isEqualTo(ID);
            assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/tts/jobs?requestId=" + REQUEST);
            api.getJob(ID);
            assertThat(server.takeRequest().getPath()).endsWith(ID);
            assertThat(api.cancelJob(ID).status()).isEqualTo("cancelled");
            var cancel = server.takeRequest();
            assertThat(cancel.getMethod()).isEqualTo("POST");
            assertThat(cancel.getPath()).endsWith(ID + "/cancel");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "cancelled", "unknown"})
    void terminalFailuresOrUnknownStatusesNeverDownload(String status) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(job(status, ",\"error\":{\"code\":\"test_error\"}")));
            assertThatThrownBy(() -> builder(server).build().generate(INPUT, REQUEST))
                    .isInstanceOf(FlowSpeechApi.FlowSpeechException.class);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"result\":null", ",\"result\":null,\"resultDeleted\":true"})
    void missingOrDeletedSuccessIsExplicit(String result) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(job("success", result)));
            assertThatThrownBy(() -> builder(server).build().generate(INPUT, REQUEST))
                    .hasMessageContaining(result.contains("resultDeleted") ? "result_deleted" : "invalid_result");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "not-json", "{\"status\":\"queued\"}"})
    void rejectsMalformedJob(String body) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(body));
            assertThatThrownBy(() -> builder(server).build().createJob(INPUT, REQUEST)).hasMessageContaining("invalid_response");
        }
    }

    @Test
    void rejectsMismatchedJobAndResultMetadata() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            server.enqueue(json(job("queued", "").replace(ID, "00000000-0000-4000-8000-000000000003")));
            assertThatThrownBy(() -> builder(server).build().getJob(ID)).hasMessageContaining("invalid_response");
            server.enqueue(json(job("success", result(storage).replace("24000", "44100"))));
            assertThatThrownBy(() -> builder(server).build().getJob(ID)).hasMessageContaining("invalid_result");
        }
    }

    @Test
    void errorPreservesCodeTraceAndRecoveryIdsWithoutRetryingPermanentFailure() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("{\"error\":{\"code\":\"subscription_required\",\"message\":\"Upgrade\",\"retryable\":false},\"traceId\":\"trace-123\"}").setResponseCode(403));
            assertThatThrownBy(() -> builder(server).build().createJob(INPUT, REQUEST))
                    .isInstanceOfSatisfying(FlowSpeechApi.FlowSpeechException.class, ex -> {
                        assertThat(ex.code()).isEqualTo("subscription_required");
                        assertThat(ex.traceId()).isEqualTo("trace-123");
                        assertThat(ex.requestId()).isEqualTo(REQUEST.toString());
                        assertThat(ex.statusCode()).isEqualTo(403);
                    });
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void retryBudgetIsBounded() throws Exception {
        try (var server = new MockWebServer()) {
            for (int i = 0; i < 3; i++) server.enqueue(json("{\"error\":{\"code\":\"busy\",\"message\":\"Busy\",\"retryable\":true},\"traceId\":\"trace\"}").setResponseCode(503));
            assertThatThrownBy(() -> builder(server).timeSource(new FakeTime()).build().createJob(INPUT, REQUEST))
                    .hasMessageContaining("busy");
            assertThat(server.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    void totalDeadlinePreventsOversleepAndExtraRequest() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(job("queued", "")).setHeader("Retry-After", "60"));
            FakeTime time = new FakeTime();
            assertThatThrownBy(() -> builder(server).timeSource(time).timeout(Duration.ofSeconds(10))
                    .build().generate(INPUT, REQUEST)).hasMessageContaining("deadline_exceeded");
            assertThat(time.sleeps).isEmpty();
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void slowResponseBodyRespectsDeadline() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(job("queued", "")).setBodyDelay(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertThatThrownBy(() -> builder(server).timeout(Duration.ofMillis(100)).build().createJob(INPUT, REQUEST))
                    .hasMessageContaining("deadline_exceeded");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void apiRedirectDoesNotLeakKey() throws Exception {
        try (var server = new MockWebServer(); var other = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", other.url("/")));
            assertThatThrownBy(() -> builder(server).build().createJob(INPUT, REQUEST)).hasMessageContaining("unexpected_redirect");
            assertThat(other.getRequestCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 404, 503})
    void downloadFailureNeverCreatesAnotherJob(int status) throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            server.enqueue(json(job("success", result(storage))));
            storage.enqueue(new MockResponse().setResponseCode(status).setHeader("Location", server.url("/leak")));
            var api = builder(server).build();
            assertThatThrownBy(() -> api.generate(INPUT, REQUEST)).hasMessageContaining("download_failed");
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(storage.takeRequest().getHeader("Authorization")).isNull();
            // Explicit retry is a download only.
            storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(WAV)));
            var job = new FlowSpeechApi.Job(ID, REQUEST.toString(), "success",
                    new FlowSpeechApi.Result(storage.url("/audio.wav").toString(), "audio/wav", 24000, 1, 16),
                    false, new FlowSpeechApi.Usage(5L, 0L, 5L), null);
            assertThat(api.download(job)).isEqualTo(WAV);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void boundsDownloadAndRejectsNonWav() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            server.enqueue(json(job("success", result(storage))));
            storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(WAV)));
            assertThatThrownBy(() -> builder(server).maxDownloadBytes(10).build().generate(INPUT, REQUEST))
                    .hasMessageContaining("response_too_large");
            server.enqueue(json(job("success", result(storage))));
            storage.enqueue(new MockResponse().setBody("not-wav"));
            assertThatThrownBy(() -> builder(server).build().generate(INPUT, REQUEST)).hasMessageContaining("invalid_audio");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/audio.wav", "https://user:password@example.com/audio.wav", "http://example.com/audio.wav"})
    void rejectsUnsafeResultUrls(String url) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(job("success", ",\"result\":{\"url\":\"" + url
                    + "\",\"mimeType\":\"audio/wav\",\"sampleRate\":24000,\"channels\":1,\"bitsPerSample\":16}")));
            assertThatThrownBy(() -> builder(server).build().generate(INPUT, REQUEST)).hasMessageContaining("invalid_result");
        }
    }

    @Test
    void validatesConfigurationIdsAndInputsBeforeNetwork() throws Exception {
        assertThatThrownBy(() -> FlowSpeechApi.builder().build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowSpeechApi.builder().apiKey("bad\r\nkey").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowSpeechApi.builder().apiKey("test").baseUrl("https://user:pass@example.com").build()).isInstanceOf(IllegalArgumentException.class);
        try (var server = new MockWebServer()) {
            var api = builder(server).build();
            assertThatThrownBy(() -> api.getJob("../quota")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> api.createJob(FlowSpeechApi.Input.single("", "Kore"), REQUEST)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> api.createJob(FlowSpeechApi.Input.multi(List.of()), REQUEST)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> api.textToSpeech(new FlowSpeechApi.FlowSpeechRequest("Hello", "Hello",
                    List.of(new FlowSpeechApi.Speaker("Kore", "Alice"))))).isInstanceOf(IllegalArgumentException.class);
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test
    void compatibilityBase64IsLocallyEncoded() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            server.enqueue(json(job("success", result(storage))));
            storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(WAV)));
            var response = builder(server).build().textToSpeech(new FlowSpeechApi.FlowSpeechRequest("Hello", "Hello",
                    List.of(new FlowSpeechApi.Speaker("Kore", null))), REQUEST);
            assertThat(java.util.Base64.getDecoder().decode(response.data().audioBase64())).isEqualTo(WAV);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asyncSnapshotsMutableSpeakersBeforeSubscriptionAndResubscription(boolean mutateBeforeFirst) throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    return json(job("success", result(storage)).replace(REQUEST.toString(), request.getHeader("Idempotency-Key")));
                }
            });
            for (int i = 0; i < 2; i++) storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(WAV)));
            var source = new HashMap<>(Map.of("text", "Hello", "voiceName", "Kore"));
            var speakers = new ArrayList<>(List.of(new FlowSpeechApi.Speaker(source.get("voiceName"), null)));
            var request = new FlowSpeechApi.FlowSpeechRequest(source.get("text"), source.get("text"), speakers);
            var publisher = builder(server).build().textToSpeechAsync(request);
            assertThat(server.getRequestCount()).isZero();

            if (mutateBeforeFirst) {
                source.put("voiceName", "Puck");
                speakers.set(0, new FlowSpeechApi.Speaker(source.get("voiceName"), null));
            }
            var firstResponse = publisher.block(Duration.ofSeconds(5));
            var first = server.takeRequest(1, TimeUnit.SECONDS);
            byte[] firstBytes = first.getBody().readByteArray();
            assertThat(new String(firstBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("{\"input\":{\"mode\":\"single\",\"text\":\"Hello\",\"voiceName\":\"Kore\"}}");

            source.put("text", "Changed after the first subscription");
            source.put("voiceName", "Aoede");
            speakers.set(0, new FlowSpeechApi.Speaker(source.get("voiceName"), null));
            var secondResponse = publisher.block(Duration.ofSeconds(5));
            var second = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(second.getHeader("Idempotency-Key")).isEqualTo(first.getHeader("Idempotency-Key"));
            assertThat(second.getBody().readByteArray()).isEqualTo(firstBytes);
            assertThat(UUID.fromString(first.getHeader("Idempotency-Key"))).isNotNull();
            assertThat(first.getMethod()).isEqualTo("POST");
            assertThat(second.getMethod()).isEqualTo("POST");
            assertThat(firstResponse).isEqualTo(secondResponse);
            assertThat(java.util.Base64.getDecoder().decode(secondResponse.data().audioBase64())).isEqualTo(WAV);
            assertThat(server.getRequestCount()).isEqualTo(2);
            assertThat(storage.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void asyncValidatesInputBeforeReturningPublisher() throws Exception {
        try (var server = new MockWebServer()) {
            var api = builder(server).build();
            assertThatThrownBy(() -> api.textToSpeechAsync(null)).isInstanceOf(IllegalArgumentException.class);
            for (var request : List.of(
                    new FlowSpeechApi.FlowSpeechRequest("", "", List.of(new FlowSpeechApi.Speaker("Kore", null))),
                    new FlowSpeechApi.FlowSpeechRequest("Hello", "Hello", List.of(new FlowSpeechApi.Speaker(" ", null))),
                    new FlowSpeechApi.FlowSpeechRequest("Hello", "Hello", List.of()),
                    new FlowSpeechApi.FlowSpeechRequest("x".repeat(100001), null, List.of(new FlowSpeechApi.Speaker("Kore", null))))) {
                assertThatThrownBy(() -> api.textToSpeechAsync(request)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test
    void multiInputSnapshotsCallerMapsAndSegmentsAcrossSubscriptions() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            for (int i = 0; i < 2; i++) {
                server.enqueue(json(job("success", result(storage))));
                storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(WAV)));
            }
            var source = new HashMap<>(Map.of("speaker", "Alice", "text", "Hello", "voiceName", "Kore"));
            var segments = new ArrayList<>(List.of(
                    new FlowSpeechApi.Segment(source.get("speaker"), source.get("text"), source.get("voiceName"))));
            var input = FlowSpeechApi.Input.multi(segments);
            var api = builder(server).build();
            var publisher = Mono.fromCallable(() -> api.generate(input, REQUEST));
            assertThat(publisher.block(Duration.ofSeconds(5))).isEqualTo(WAV);
            var first = server.takeRequest(1, TimeUnit.SECONDS);
            byte[] firstBytes = first.getBody().readByteArray();
            assertThat(new String(firstBytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                    "{\"input\":{\"mode\":\"multi\",\"segments\":[{\"speaker\":\"Alice\",\"text\":\"Hello\",\"voiceName\":\"Kore\"}]}}");
            source.put("speaker", "Bob");
            source.put("text", "Changed");
            source.put("voiceName", "Puck");
            segments.set(0, new FlowSpeechApi.Segment(source.get("speaker"), source.get("text"), source.get("voiceName")));
            segments.add(new FlowSpeechApi.Segment("Carol", "New segment", "Aoede"));
            assertThat(publisher.block(Duration.ofSeconds(5))).isEqualTo(WAV);
            var second = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(second.getBody().readByteArray()).isEqualTo(firstBytes);
            assertThat(second.getHeader("Idempotency-Key")).isEqualTo(first.getHeader("Idempotency-Key"))
                    .isEqualTo(REQUEST.toString());
            assertThat(first.getMethod()).isEqualTo("POST");
            assertThat(second.getMethod()).isEqualTo("POST");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }
}
