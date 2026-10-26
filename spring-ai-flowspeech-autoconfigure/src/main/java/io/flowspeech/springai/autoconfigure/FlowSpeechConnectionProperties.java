package io.flowspeech.springai.autoconfigure;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.flowspeech.springai.api.FlowSpeechApi;

@ConfigurationProperties(FlowSpeechConnectionProperties.CONFIG_PREFIX)
public class FlowSpeechConnectionProperties {

	public static final String CONFIG_PREFIX = "spring.ai.flowspeech";

	/**
	 * Required server-side FlowSpeech developer API key.
	 */
	private @Nullable String apiKey;

	/**
	 * FlowSpeech API base URL.
	 */
	private String baseUrl = FlowSpeechApi.DEFAULT_BASE_URL;

	/**
	 * Path for text-to-speech generation.
	 */
	private String textToSpeechPath = FlowSpeechApi.DEFAULT_TTS_PATH;

	private Duration timeout = Duration.ofMinutes(10);
	private Duration requestTimeout = Duration.ofSeconds(30);
	private Duration pollInterval = Duration.ofSeconds(5);
	private int maxRetries = 2;
	private int maxDownloadBytes = 50 * 1024 * 1024;

	public Duration getTimeout() { return this.timeout; }
	public void setTimeout(Duration value) { this.timeout = value; }
	public Duration getRequestTimeout() { return this.requestTimeout; }
	public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
	public Duration getPollInterval() { return this.pollInterval; }
	public void setPollInterval(Duration value) { this.pollInterval = value; }
	public int getMaxRetries() { return this.maxRetries; }
	public void setMaxRetries(int value) { this.maxRetries = value; }
	public int getMaxDownloadBytes() { return this.maxDownloadBytes; }
	public void setMaxDownloadBytes(int value) { this.maxDownloadBytes = value; }

	public @Nullable String getApiKey() {
		return this.apiKey;
	}

	public void setApiKey(@Nullable String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getTextToSpeechPath() {
		return this.textToSpeechPath;
	}

	public void setTextToSpeechPath(String textToSpeechPath) {
		this.textToSpeechPath = textToSpeechPath;
	}

}
