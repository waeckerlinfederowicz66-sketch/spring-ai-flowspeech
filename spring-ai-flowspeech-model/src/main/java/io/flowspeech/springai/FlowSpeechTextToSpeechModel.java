package io.flowspeech.springai;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

	private final FlowSpeechApi flowSpeechApi;

	private final FlowSpeechTextToSpeechOptions options;

	public FlowSpeechTextToSpeechModel(FlowSpeechApi flowSpeechApi, FlowSpeechTextToSpeechOptions options) {
		this(flowSpeechApi, options, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	public FlowSpeechTextToSpeechModel(FlowSpeechApi flowSpeechApi, FlowSpeechTextToSpeechOptions options,
			RetryTemplate retryTemplate) {
		Assert.notNull(flowSpeechApi, "flowSpeechApi must not be null");
		Assert.notNull(options, "options must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		if (retryTemplate != RetryUtils.DEFAULT_RETRY_TEMPLATE) {
			throw new IllegalArgumentException("Configure retries on FlowSpeechApi; whole-generation RetryTemplate is unsupported");
		}
		this.flowSpeechApi = flowSpeechApi;
		this.options = options;
		// Retries belong to the API client so a download failure never creates another job.
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
		FlowSpeechApi.Input request = createRequest(prompt);
		byte[] audio = this.flowSpeechApi.generate(request, UUID.randomUUID());
		return new TextToSpeechResponse(List.of(new Speech(audio)));
	}

	@Override
	public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
		FlowSpeechApi.Input request = createRequest(prompt);
		UUID requestId = UUID.randomUUID();
		return Mono.fromCallable(() -> this.flowSpeechApi.generate(request, requestId))
			.subscribeOn(Schedulers.boundedElastic())
			.map(audio -> new TextToSpeechResponse(List.of(new Speech(audio))))
			.flux();
	}

	private FlowSpeechApi.Input createRequest(TextToSpeechPrompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		String text = prompt.getInstructions().getText();
		Assert.hasText(text, "Prompt must contain text to convert to speech");

		FlowSpeechTextToSpeechOptions mergedOptions = getOptions(prompt);
		String voice = mergedOptions.getVoice();
		Assert.hasText(voice, "A FlowSpeech voice must be specified");

		return FlowSpeechApi.Input.single(text, voice);
	}

	private FlowSpeechTextToSpeechOptions getOptions(TextToSpeechPrompt prompt) {
		if (prompt.getOptions() instanceof FlowSpeechTextToSpeechOptions runtimeOptions) {
			return runtimeOptions.merge(this.options);
		}
		if (prompt.getOptions() != null) {
			var runtime = prompt.getOptions();
			return FlowSpeechTextToSpeechOptions.builder().model(runtime.getModel()).format(runtime.getFormat())
				.speed(runtime.getSpeed()).voice(runtime.getVoice()).build().merge(this.options);
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

		@Deprecated
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
