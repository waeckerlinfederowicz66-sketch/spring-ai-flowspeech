package io.flowspeech.springai.api;

import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for the FlowSpeech Text-to-Speech API.
 */
public final class FlowSpeechApi {

	public static final String DEFAULT_BASE_URL = "https://flowspeech.io";

	public static final String DEFAULT_TTS_PATH = "/api/ai/text-to-speech";

	private final RestClient restClient;

	private final WebClient webClient;

	private final String textToSpeechPath;

	private FlowSpeechApi(String baseUrl, String textToSpeechPath, ApiKey apiKey, HttpHeaders headers,
			RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder,
			ResponseErrorHandler responseErrorHandler) {
		Assert.hasText(baseUrl, "baseUrl must not be empty");
		Assert.hasText(textToSpeechPath, "textToSpeechPath must not be empty");

		Consumer<HttpHeaders> defaultHeaders = h -> {
			if (!(apiKey instanceof NoopApiKey)) {
				h.setBearerAuth(apiKey.getValue());
			}
			h.addAll(HttpHeaders.readOnlyHttpHeaders(headers));
			h.setContentType(MediaType.APPLICATION_JSON);
		};

		this.restClient = restClientBuilder.clone()
			.baseUrl(baseUrl)
			.defaultHeaders(defaultHeaders)
			.defaultStatusHandler(responseErrorHandler)
			.build();
		this.webClient = webClientBuilder.clone().baseUrl(baseUrl).defaultHeaders(defaultHeaders).build();
		this.textToSpeechPath = textToSpeechPath;
	}

	public FlowSpeechApi(RestClient restClient, WebClient webClient) {
		this(restClient, webClient, DEFAULT_TTS_PATH);
	}

	public FlowSpeechApi(RestClient restClient, WebClient webClient, String textToSpeechPath) {
		this.restClient = restClient;
		this.webClient = webClient;
		this.textToSpeechPath = textToSpeechPath;
	}

	public static Builder builder() {
		return new Builder();
	}

	public FlowSpeechResponse textToSpeech(FlowSpeechRequest request) {
		validateRequest(request);
		return this.restClient.post()
			.uri(this.textToSpeechPath)
			.body(request)
			.retrieve()
			.body(FlowSpeechResponse.class);
	}

	public Mono<FlowSpeechResponse> textToSpeechAsync(FlowSpeechRequest request) {
		validateRequest(request);
		return this.webClient.post()
			.uri(this.textToSpeechPath)
			.bodyValue(request)
			.retrieve()
			.bodyToMono(FlowSpeechResponse.class);
	}

	private static void validateRequest(FlowSpeechRequest request) {
		Assert.notNull(request, "request must not be null");
		Assert.hasText(request.text(), "request.text must not be empty");
		Assert.notEmpty(request.speakers(), "request.speakers must not be empty");
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FlowSpeechRequest(@JsonProperty("text") String text,
			@JsonProperty("originalText") @Nullable String originalText,
			@JsonProperty("speakers") List<Speaker> speakers) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Speaker(@JsonProperty("voiceName") String voiceName, @JsonProperty("speaker") @Nullable String speaker) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FlowSpeechResponse(@JsonProperty("code") int code, @JsonProperty("message") @Nullable String message,
			@JsonProperty("data") @Nullable FlowSpeechData data) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FlowSpeechData(@JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("audioBase64") @Nullable String audioBase64, @JsonProperty("sampleRate") @Nullable Integer sampleRate,
			@JsonProperty("numChannels") @Nullable Integer numChannels,
			@JsonProperty("bitsPerSample") @Nullable Integer bitsPerSample) {
	}

	public static final class Builder {

		private String baseUrl = DEFAULT_BASE_URL;

		private String textToSpeechPath = DEFAULT_TTS_PATH;

	private ApiKey apiKey = new NoopApiKey();

		private HttpHeaders headers = new HttpHeaders();

		private RestClient.Builder restClientBuilder = RestClient.builder();

		private WebClient.Builder webClientBuilder = WebClient.builder();

		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;

		private Builder() {
		}

		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder textToSpeechPath(String textToSpeechPath) {
			this.textToSpeechPath = textToSpeechPath;
			return this;
		}

		public Builder apiKey(@Nullable String apiKey) {
			this.apiKey = (apiKey == null || apiKey.isBlank()) ? new NoopApiKey() : new SimpleApiKey(apiKey);
			return this;
		}

		public Builder headers(HttpHeaders headers) {
			this.headers = headers;
			return this;
		}

		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler) {
			this.responseErrorHandler = responseErrorHandler;
			return this;
		}

		public FlowSpeechApi build() {
			return new FlowSpeechApi(this.baseUrl, this.textToSpeechPath, this.apiKey, this.headers,
					this.restClientBuilder, this.webClientBuilder, this.responseErrorHandler);
		}

	}

}
