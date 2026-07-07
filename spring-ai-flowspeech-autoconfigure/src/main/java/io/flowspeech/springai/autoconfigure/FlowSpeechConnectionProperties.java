package io.flowspeech.springai.autoconfigure;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.flowspeech.springai.api.FlowSpeechApi;

@ConfigurationProperties(FlowSpeechConnectionProperties.CONFIG_PREFIX)
public class FlowSpeechConnectionProperties {

	public static final String CONFIG_PREFIX = "spring.ai.flowspeech";

	/**
	 * Optional FlowSpeech API key. Public guest usage can leave this empty.
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
