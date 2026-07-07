package io.flowspeech.springai;

import java.util.Base64;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;

import io.flowspeech.springai.api.FlowSpeechApi;

/**
 * Spring AI {@link TextToSpeechModel} implementation backed by FlowSpeech.
 */
public class FlowSpeechTextToSpeechModel implements TextToSpeechModel {

	private final Log logger = LogFactory.getLog(getClass());

	private final FlowSpeechApi flowSpeechApi;

	private final RetryTemplate retryTemplate;

	private final FlowSpeechTextToSpeechOptions options;

	public FlowSpeechTextToSpeechModel(FlowSpeechApi flowSpeechApi, FlowSpeechTextToSpeechOptions options) {
		this(flowSpeechApi, options, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	public FlowSpeechTextToSpeechModel(FlowSpeechApi flowSpeechApi, FlowSpeechTextToSpeechOptions options,
			RetryTemplate retryTemplate) {
		Assert.notNull(flowSpeechApi, "flowSpeechApi must not be null");
		Assert.notNull(options, "options must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		this.flowSpeechApi = flowSpeechApi;
		this.options = options;
		this.retryTemplate = retryTemplate;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
		FlowSpeechApi.FlowSpeechRequest request = createRequest(prompt);
		byte[] audio = RetryUtils.execute(this.retryTemplate, () -> decodeAudio(this.flowSpeechApi.textToSpeech(request)));
		return new TextToSpeechResponse(List.of(new Speech(audio)));
	}

	@Override
	public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
		FlowSpeechApi.FlowSpeechRequest request = createRequest(prompt);
		return RetryUtils.execute(this.retryTemplate, () -> this.flowSpeechApi.textToSpeechAsync(request)
			.map(this::decodeAudio)
			.map(audio -> new TextToSpeechResponse(List.of(new Speech(audio))))
			.flux());
	}

	private FlowSpeechApi.FlowSpeechRequest createRequest(TextToSpeechPrompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		String text = prompt.getInstructions().getText();
		Assert.hasText(text, "Prompt must contain text to convert to speech");

		FlowSpeechTextToSpeechOptions mergedOptions = getOptions(prompt);
		String voice = mergedOptions.getVoice();
		Assert.hasText(voice, "A FlowSpeech voice must be specified");

		return new FlowSpeechApi.FlowSpeechRequest(text, text,
				List.of(new FlowSpeechApi.Speaker(voice, null)));
	}

	private byte[] decodeAudio(FlowSpeechApi.FlowSpeechResponse response) {
		if (response == null) {
			throw new IllegalStateException("FlowSpeech returned an empty response");
		}
		if (response.code() != 0) {
			throw new IllegalStateException(response.message() != null ? response.message() : "FlowSpeech TTS failed");
		}
		if (response.data() == null || response.data().audioBase64() == null || response.data().audioBase64().isBlank()) {
			if (this.logger.isWarnEnabled()) {
				this.logger.warn("FlowSpeech returned no audio data");
			}
			return new byte[0];
		}
		return Base64.getDecoder().decode(stripDataUriPrefix(response.data().audioBase64()));
	}

	private static String stripDataUriPrefix(String value) {
		int commaIndex = value.indexOf(',');
		return value.startsWith("data:") && commaIndex >= 0 ? value.substring(commaIndex + 1) : value;
	}

	private FlowSpeechTextToSpeechOptions getOptions(TextToSpeechPrompt prompt) {
		if (prompt.getOptions() instanceof FlowSpeechTextToSpeechOptions runtimeOptions) {
			return runtimeOptions.merge(this.options);
		}
		return this.options;
	}

	@Override
	public FlowSpeechTextToSpeechOptions getOptions() {
		return this.options;
	}

	@Override
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	public FlowSpeechTextToSpeechOptions getDefaultOptions() {
		return this.options;
	}

	public static final class Builder {

		private @Nullable FlowSpeechApi flowSpeechApi;

		private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

		private FlowSpeechTextToSpeechOptions options = FlowSpeechTextToSpeechOptions.builder().build();

		private Builder() {
		}

		public Builder flowSpeechApi(FlowSpeechApi flowSpeechApi) {
			this.flowSpeechApi = flowSpeechApi;
			return this;
		}

		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder options(FlowSpeechTextToSpeechOptions options) {
			this.options = options;
			return this;
		}

		public FlowSpeechTextToSpeechModel build() {
			Assert.notNull(this.flowSpeechApi, "flowSpeechApi must not be null");
			return new FlowSpeechTextToSpeechModel(this.flowSpeechApi, this.options, this.retryTemplate);
		}

	}

}
