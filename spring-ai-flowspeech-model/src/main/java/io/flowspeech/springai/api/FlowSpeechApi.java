package io.flowspeech.springai.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Public API v1 client. API requests and credential-free audio downloads use separate clients. */
public final class FlowSpeechApi {

    public static final String DEFAULT_BASE_URL = "https://flowspeech.io";
    public static final String DEFAULT_TTS_PATH = "/api/v1/tts/jobs";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> PENDING = Set.of("queued", "running", "merging", "settling");
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final URI baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final Duration requestTimeout;
    private final Duration pollInterval;
    private final int maxRetries;
    private final int maxDownloadBytes;
    private final HttpClient apiClient;
    private final HttpClient downloadClient;
    private final TimeSource time;

    private FlowSpeechApi(Builder builder) {
        this.baseUrl = safeUrl(builder.baseUrl);
        if (!(this.baseUrl.getPath().isEmpty() || this.baseUrl.getPath().equals("/"))
                || this.baseUrl.getQuery() != null || this.baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an origin without a path, query or fragment");
        }
        requireText(builder.apiKey, "A FlowSpeech API key is required");
        if (builder.apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException("Invalid API key");
        }
        this.apiKey = builder.apiKey;
        this.timeout = positiveDuration(builder.timeout);
        this.requestTimeout = positiveDuration(builder.requestTimeout);
        this.pollInterval = positiveDuration(builder.pollInterval);
        if (this.pollInterval.compareTo(Duration.ofSeconds(5)) < 0) {
            throw new IllegalArgumentException("pollInterval must be at least five seconds");
        }
        if (builder.maxRetries < 0 || builder.maxRetries > 5 || builder.maxDownloadBytes < 1) {
            throw new IllegalArgumentException("Invalid retry or download limit");
        }
        this.maxRetries = builder.maxRetries;
        this.maxDownloadBytes = builder.maxDownloadBytes;
        this.time = builder.time;
        // No caller-supplied interceptors, authenticators, cookies or automatic redirects.
        this.apiClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(this.requestTimeout).build();
        this.downloadClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(this.requestTimeout).build();
    }

    public static Builder builder() { return new Builder(); }

    /** Unsafe legacy transports cannot enforce credential isolation and deadlines. */
    @Deprecated
    public FlowSpeechApi(RestClient restClient, WebClient webClient) {
        throw new IllegalArgumentException("Use FlowSpeechApi.builder().apiKey(...); custom transports are unsupported");
    }

    @Deprecated
    public FlowSpeechApi(RestClient restClient, WebClient webClient, String textToSpeechPath) {
        throw new IllegalArgumentException("Use FlowSpeechApi.builder().apiKey(...); custom transports are unsupported");
    }

    public QuotaResponse quota() {
        Context ctx = new Context(null, null);
        QuotaResponse result = decode(request("GET", "/api/v1/quota", null, ctx).body(), QuotaResponse.class, ctx);
        if (result.quota() == null || result.limits() == null || !result.limits().isObject()
                || !nonnegative(result.quota().get("available"))
                || !nonnegative(result.quota().get("reserved"))
                || !nonnegative(result.quota().get("remaining"))) {
            throw ctx.error("invalid_response", "Malformed quota response");
        }
        return result;
    }

    public VoicesResponse voices() {
        Context ctx = new Context(null, null);
        VoicesResponse result = decode(request("GET", "/api/v1/voices", null, ctx).body(), VoicesResponse.class, ctx);
        if (result.voices() == null || result.voices().stream().anyMatch(v -> v == null || blank(v.name()))) {
            throw ctx.error("invalid_response", "Malformed voices response");
        }
        return result;
    }

    public Job createJob(Input input, UUID requestId) {
        return create(input, new Context(requiredUuid(requestId), null)).job();
    }

    public Job getJob(String id) {
        Context ctx = new Context(null, validId(id));
        return read(ctx).job();
    }

    public Job recoverJob(UUID requestId) {
        Context ctx = new Context(requiredUuid(requestId), null);
        return job(request("GET", DEFAULT_TTS_PATH + "?requestId=" + ctx.requestId, null, ctx), ctx).job();
    }

    public Job cancelJob(String id) {
        Context ctx = new Context(null, validId(id));
        return job(request("POST", DEFAULT_TTS_PATH + "/" + ctx.jobId + "/cancel", null, ctx), ctx).job();
    }

    public Job awaitJob(String id) {
        Context ctx = new Context(null, validId(id));
        return await(read(ctx), ctx);
    }

    public byte[] generate(Input input, UUID requestId) {
        Context ctx = new Context(requiredUuid(requestId), null);
        return download(await(create(input, ctx), ctx), ctx);
    }

    /** Re-download an existing successful job; this method never creates a recording. */
    public byte[] download(Job job) {
        Context ctx = new Context(job == null ? null : job.requestId(), job == null ? null : job.id());
        validateJob(job, ctx);
        return download(job, ctx);
    }

    /** Compatibility envelope. Base64 is encoded locally, not returned by API v1. */
    public FlowSpeechResponse textToSpeech(FlowSpeechRequest request) {
        return textToSpeech(request, UUID.randomUUID());
    }

    public FlowSpeechResponse textToSpeech(FlowSpeechRequest request, UUID requestId) {
        Input input = legacyInput(request);
        return compatibilityResponse(generate(input, requestId));
    }

    private static FlowSpeechResponse compatibilityResponse(byte[] audio) {
        return new FlowSpeechResponse(0, "ok", new FlowSpeechData("audio/wav",
                Base64.getEncoder().encodeToString(audio), 24000, 1, 16));
    }

    public Mono<FlowSpeechResponse> textToSpeechAsync(FlowSpeechRequest request) {
        // Snapshot both the input and identity once; only HTTP work is deferred.
        Input input = legacyInput(request);
        validateInput(input);
        UUID requestId = UUID.randomUUID();
        return Mono.fromCallable(() -> compatibilityResponse(generate(input, requestId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Input legacyInput(FlowSpeechRequest request) {
        if (request == null || request.speakers() == null || request.speakers().size() != 1
                || request.speakers().get(0) == null || request.speakers().get(0).speaker() != null
                || (request.originalText() != null && !request.originalText().equals(request.text()))) {
            throw new IllegalArgumentException("Legacy speaker scripts are unsupported; use Input.multi with explicit segments");
        }
        return Input.single(request.text(), request.speakers().get(0).voiceName());
    }

    private JobReply create(Input input, Context ctx) {
        validateInput(input);
        String body = JSON.writeValueAsString(new CreateRequest(input));
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("Request body exceeds 1 MiB");
        }
        return job(request("POST", DEFAULT_TTS_PATH, body, ctx), ctx);
    }

    private JobReply read(Context ctx) {
        return job(request("GET", DEFAULT_TTS_PATH + "/" + ctx.jobId, null, ctx), ctx);
    }

    private Job await(JobReply reply, Context ctx) {
        while (PENDING.contains(reply.job().status())) {
            ctx.sleep(delay(reply.response()));
            reply = read(ctx);
        }
        success(reply.job(), ctx);
        return reply.job();
    }

    private void success(Job job, Context ctx) {
        if (!"success".equals(job.status())) {
            throw ctx.error(job.error() == null || blank(job.error().code()) ? job.status() : job.error().code(),
                    "Job ended with status " + job.status());
        }
        if (Boolean.TRUE.equals(job.resultDeleted())) {
            throw ctx.error("result_deleted", "Recording was deleted; no downloadable result");
        }
        if (job.result() == null) throw ctx.error("invalid_result", "Successful job has no result");
    }

    private byte[] download(Job job, Context ctx) {
        success(job, ctx);
        URI uri = safeUrl(job.result().url());
        HttpResponse<byte[]> response = exchange(downloadClient, "GET", uri, null, false, ctx, this.maxDownloadBytes);
        if (response.statusCode() != 200) {
            throw ctx.error("download_failed", "Audio download failed (HTTP " + response.statusCode()
                    + "); retry download on the same job, not generation");
        }
        byte[] bytes = response.body();
        if (!WavAudio.isValid(bytes)) {
            throw ctx.error("invalid_audio", "Expected complete 24 kHz mono 16-bit PCM WAV");
        }
        return bytes;
    }

    private HttpResponse<byte[]> request(String method, String path, @Nullable String body, Context ctx) {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = exchange(this.apiClient, method, this.baseUrl.resolve(path), body, true, ctx, 2 * 1024 * 1024);
            }
            catch (FlowSpeechException error) {
                if (!error.retryable() || attempt >= this.maxRetries) throw error;
                ctx.sleep(delay(error.retryAfter));
                continue;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response;
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw ctx.error("unexpected_redirect", "API redirects are not permitted");
            }
            ErrorResponse envelope = decode(response.body(), ErrorResponse.class, ctx);
            ApiError error = envelope.error();
            if (error == null || blank(error.code()) || error.retryable() == null || blank(envelope.traceId())) {
                throw ctx.error("invalid_response", "Malformed API error response");
            }
            boolean retry = response.statusCode() == 429
                    || (response.statusCode() >= 500 && Boolean.TRUE.equals(error.retryable()));
            if (retry && attempt < this.maxRetries) {
                ctx.sleep(delay(response));
                continue;
            }
            throw new FlowSpeechException(error.code(), "FlowSpeech API error (HTTP " + response.statusCode() + ")",
                    ctx.requestId, ctx.jobId, envelope.traceId(), retry, response.statusCode());
        }
    }

    private HttpResponse<byte[]> exchange(HttpClient client, String method, URI uri, @Nullable String body,
            boolean authenticated, Context ctx, int limit) {
        Duration budget = ctx.remaining();
        if (budget.compareTo(this.requestTimeout) > 0) budget = this.requestTimeout;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(budget)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) {
            builder.header("Authorization", "Bearer " + this.apiKey).header("Accept", "application/json");
            if (body != null) builder.header("Content-Type", "application/json").header("Idempotency-Key", ctx.requestId);
        }
        AtomicReference<HttpResponse.ResponseInfo> received = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(builder.build(), info -> {
            received.set(info);
            return new BoundedBody(limit);
        });
        try {
            HttpResponse<byte[]> response = future.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            ctx.remaining();
            return response;
        }
        catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ctx.error("interrupted", "Operation interrupted; recover with requestId");
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            throw ctx.error("deadline_exceeded", "Request or total deadline exceeded; recover with requestId");
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BodyLimitException) throw ctx.error("response_too_large", "Response exceeds configured byte limit");
            if (cause instanceof IOException) {
                HttpResponse.ResponseInfo info = received.get();
                throw new FlowSpeechException("transport_error", "HTTP transport failed; recover with requestId",
                        ctx.requestId, ctx.jobId, null, authenticated, info == null ? 0 : info.statusCode(),
                        info == null ? "" : info.headers().firstValue("Retry-After").orElse(""));
            }
            throw ctx.error("transport_error", "HTTP transport failed");
        }
    }

    private JobReply job(HttpResponse<byte[]> response, Context ctx) {
        Job job = decode(response.body(), Job.class, ctx);
        validateJob(job, ctx);
        ctx.jobId = job.id();
        ctx.requestId = job.requestId();
        return new JobReply(job, response);
    }

    private void validateJob(Job job, Context ctx) {
        if (job == null || !isId(job.id()) || !isId(job.requestId()) || job.status() == null
                || !(PENDING.contains(job.status()) || Set.of("success", "failed", "cancelled").contains(job.status()))
                || (ctx.jobId != null && !ctx.jobId.equalsIgnoreCase(job.id()))
                || (ctx.requestId != null && !ctx.requestId.equalsIgnoreCase(job.requestId()))
                || job.usage() == null || job.usage().billableCharacters() == null
                || job.usage().reservedCredits() == null || job.usage().chargedCredits() == null
                || job.usage().billableCharacters() < 0 || job.usage().reservedCredits() < 0 || job.usage().chargedCredits() < 0) {
            throw ctx.error("invalid_response", "Malformed or mismatched job response");
        }
        Result result = job.result();
        if (result != null && (!"audio/wav".equals(result.mimeType()) || !Integer.valueOf(24000).equals(result.sampleRate())
                || !Integer.valueOf(1).equals(result.channels()) || !Integer.valueOf(16).equals(result.bitsPerSample()))) {
            throw ctx.error("invalid_result", "Malformed WAV result metadata");
        }
        if (result != null) {
            try { safeUrl(result.url()); }
            catch (IllegalArgumentException ex) { throw ctx.error("invalid_result", "Unsafe audio URL"); }
        }
        if ("success".equals(job.status()) && result == null && !Boolean.TRUE.equals(job.resultDeleted())) {
            throw ctx.error("invalid_result", "Successful job has no result");
        }
    }

    private <T> T decode(byte[] bytes, Class<T> type, Context ctx) {
        try {
            T value = JSON.readValue(bytes, type);
            if (value == null) throw ctx.error("invalid_response", "Empty JSON response");
            return value;
        }
        catch (tools.jackson.core.JacksonException ex) {
            throw ctx.error("invalid_response", "Malformed JSON response");
        }
    }

    private Duration delay(HttpResponse<?> response) {
        return delay(response.headers().firstValue("Retry-After").orElse(""));
    }

    private Duration delay(String value) {
        Duration delay = this.pollInterval;
        try {
            Duration candidate = value.matches("[0-9]+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(this.time.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if (candidate.compareTo(delay) > 0) delay = candidate;
        }
        catch (RuntimeException ignored) { /* Missing or invalid Retry-After uses the minimum poll interval. */ }
        return delay;
    }

    private static void validateInput(Input input) {
        if (input == null) throw new IllegalArgumentException("input is required");
        if ("single".equals(input.mode())) {
            requireText(input.text(), "input.text is required");
            requireText(input.voiceName(), "input.voiceName is required");
            if (input.segments() != null || input.text().length() > 100000 || input.voiceName().length() > 100) {
                throw new IllegalArgumentException("Invalid single input");
            }
        }
        else if ("multi".equals(input.mode())) {
            if (input.text() != null || input.voiceName() != null || input.segments() == null
                    || input.segments().isEmpty() || input.segments().size() > 1000) {
                throw new IllegalArgumentException("Invalid multi input");
            }
            long length = 0;
            for (Segment segment : input.segments()) {
                if (segment == null) throw new IllegalArgumentException("segment is required");
                requireText(segment.text(), "segment.text is required");
                requireText(segment.speaker(), "segment.speaker is required");
                requireText(segment.voiceName(), "segment.voiceName is required");
                if (segment.speaker().length() > 100 || segment.voiceName().length() > 100) {
                    throw new IllegalArgumentException("Speaker or voice exceeds 100 characters");
                }
                length += segment.text().length();
            }
            if (length > 100000) throw new IllegalArgumentException("Text exceeds 100000 UTF-16 code units");
        }
        else throw new IllegalArgumentException("input.mode must be single or multi");
    }

    private static URI safeUrl(String value) {
        if (blank(value)) throw new IllegalArgumentException("URL is required");
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("URL must use HTTPS without embedded credentials (loopback HTTP is allowed for tests)");
        }
        return uri;
    }

    private static boolean blank(@Nullable String value) { return value == null || value.isBlank(); }
    private static void requireText(@Nullable String value, String message) {
        if (blank(value)) throw new IllegalArgumentException(message);
    }
    private static boolean nonnegative(@Nullable JsonNode value) {
        return value != null && value.isIntegralNumber() && value.asLong() >= 0;
    }
    private static boolean isId(@Nullable String value) { return value != null && UUID_PATTERN.matcher(value).matches(); }
    private static String validId(String value) {
        if (!isId(value)) throw new IllegalArgumentException("Job ID must be a UUID");
        return value;
    }
    private static String requiredUuid(UUID value) {
        if (value == null) throw new IllegalArgumentException("requestId is required");
        return value.toString();
    }
    private static Duration positiveDuration(Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Duration must be between 1 ms and 24 hours");
        }
        return value;
    }

    private final class Context {
        private @Nullable String requestId;
        private @Nullable String jobId;
        private final long deadline = time.nanoTime() + timeout.toNanos();
        private Context(@Nullable String requestId, @Nullable String jobId) { this.requestId = requestId; this.jobId = jobId; }
        private Duration remaining() {
            long nanos = this.deadline - time.nanoTime();
            if (nanos <= 0) throw error("deadline_exceeded", "Total deadline exceeded; recover with requestId");
            return Duration.ofNanos(nanos);
        }
        private void sleep(Duration duration) {
            if (duration.compareTo(remaining()) >= 0) throw error("deadline_exceeded", "Retry delay exceeds remaining deadline");
            try { time.sleep(duration); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw error("interrupted", "Operation interrupted; recover with requestId");
            }
            remaining();
        }
        private FlowSpeechException error(String code, String message) {
            return new FlowSpeechException(code, message, this.requestId, this.jobId, null, false, 0);
        }
    }

    interface TimeSource {
        long nanoTime();
        Instant instant();
        void sleep(Duration duration) throws InterruptedException;
    }
    private static final TimeSource SYSTEM_TIME = new TimeSource() {
        public long nanoTime() { return System.nanoTime(); }
        public Instant instant() { return Instant.now(); }
        public void sleep(Duration duration) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(duration.toNanos()); }
    };

    private static final class BodyLimitException extends IOException { }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return this.result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > this.limit - this.bytes.size()) {
                    this.subscription.cancel();
                    this.result.completeExceptionally(new BodyLimitException());
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                this.bytes.writeBytes(part);
            }
            this.subscription.request(1);
        }
        public void onError(Throwable error) { this.result.completeExceptionally(error); }
        public void onComplete() { this.result.complete(this.bytes.toByteArray()); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(String mode, @Nullable String text, @Nullable String voiceName, @Nullable List<Segment> segments) {
        public Input { if (segments != null) segments = List.copyOf(segments); }
        public static Input single(String text, String voiceName) { return new Input("single", text, voiceName, null); }
        public static Input multi(List<Segment> segments) { return new Input("multi", null, null, segments); }
    }
    public record Segment(String speaker, String text, String voiceName) { }
    private record CreateRequest(Input input) { }
    public record Usage(Long billableCharacters, Long reservedCredits, Long chargedCredits) { }
    public record Result(String url, String mimeType, Integer sampleRate, Integer channels, Integer bitsPerSample) { }
    public record ApiError(String code, @Nullable String message, @Nullable Boolean retryable) { }
    private record ErrorResponse(ApiError error, String traceId) { }
    public record Job(String id, String requestId, String status, @Nullable Result result,
            @Nullable Boolean resultDeleted, Usage usage, @Nullable ApiError error) { }
    private record JobReply(Job job, HttpResponse<byte[]> response) { }
    public record QuotaResponse(JsonNode quota, @Nullable JsonNode subscription,
            @Nullable JsonNode entitlement, JsonNode limits) {
        public QuotaResponse(JsonNode quota, @Nullable JsonNode entitlement, JsonNode limits) {
            this(quota, null, entitlement, limits);
        }
    }
    public record Voice(String name, @Nullable String description, @Nullable String gender) { }
    public record VoicesResponse(List<Voice> voices) { }
    public record FlowSpeechRequest(String text, @Nullable String originalText, List<Speaker> speakers) { }
    public record Speaker(String voiceName, @Nullable String speaker) { }
    public record FlowSpeechResponse(int code, @Nullable String message, @Nullable FlowSpeechData data) { }
    public record FlowSpeechData(String mimeType, String audioBase64, Integer sampleRate, Integer numChannels, Integer bitsPerSample) { }

    /** Recovery identifiers are deliberately available without logging credentials or script text. */
    public static final class FlowSpeechException extends IllegalStateException {
        private final String code;
        private final @Nullable String requestId;
        private final @Nullable String jobId;
        private final @Nullable String traceId;
        private final boolean retryable;
        private final int statusCode;
        private final String retryAfter;
        public FlowSpeechException(String code, String message, @Nullable String requestId, @Nullable String jobId,
                @Nullable String traceId, boolean retryable, int statusCode) {
            this(code, message, requestId, jobId, traceId, retryable, statusCode, "");
        }
        private FlowSpeechException(String code, String message, @Nullable String requestId, @Nullable String jobId,
                @Nullable String traceId, boolean retryable, int statusCode, String retryAfter) {
            super(message + " [" + code + ", requestId=" + requestId + ", jobId=" + jobId + "]");
            this.code = code; this.requestId = requestId; this.jobId = jobId; this.traceId = traceId;
            this.retryable = retryable; this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }
        public String code() { return this.code; }
        public @Nullable String requestId() { return this.requestId; }
        public @Nullable String jobId() { return this.jobId; }
        public @Nullable String traceId() { return this.traceId; }
        public boolean retryable() { return this.retryable; }
        public int statusCode() { return this.statusCode; }
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey = "";
        private Duration timeout = Duration.ofMinutes(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration pollInterval = Duration.ofSeconds(5);
        private int maxRetries = 2;
        private int maxDownloadBytes = 50 * 1024 * 1024;
        private TimeSource time = SYSTEM_TIME;
        private Builder() { }
        public Builder baseUrl(String value) { this.baseUrl = value; return this; }
        public Builder apiKey(@Nullable String value) { this.apiKey = value == null ? "" : value; return this; }
        public Builder timeout(Duration value) { this.timeout = value; return this; }
        public Builder requestTimeout(Duration value) { this.requestTimeout = value; return this; }
        public Builder pollInterval(Duration value) { this.pollInterval = value; return this; }
        public Builder maxRetries(int value) { this.maxRetries = value; return this; }
        public Builder maxDownloadBytes(int value) { this.maxDownloadBytes = value; return this; }
        Builder timeSource(TimeSource value) { this.time = value; return this; }
        public Builder textToSpeechPath(String value) {
            if (!DEFAULT_TTS_PATH.equals(value)) throw new IllegalArgumentException("Only /api/v1/tts/jobs is supported");
            return this;
        }
        @Deprecated public Builder headers(HttpHeaders value) { throw new IllegalArgumentException("Custom headers are unsupported"); }
        @Deprecated public Builder restClientBuilder(RestClient.Builder value) { throw new IllegalArgumentException("Custom transports are unsupported"); }
        @Deprecated public Builder webClientBuilder(WebClient.Builder value) { throw new IllegalArgumentException("Custom transports are unsupported"); }
        @Deprecated public Builder responseErrorHandler(ResponseErrorHandler value) { throw new IllegalArgumentException("Custom error handlers are unsupported"); }
        public FlowSpeechApi build() { return new FlowSpeechApi(this); }
    }
}
