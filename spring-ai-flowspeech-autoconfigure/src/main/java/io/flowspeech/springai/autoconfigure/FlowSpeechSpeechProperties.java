package io.flowspeech.springai.autoconfigure;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.flowspeech.springai.FlowSpeechTextToSpeechOptions;

@ConfigurationProperties(FlowSpeechSpeechProperties.CONFIG_PREFIX)
public class FlowSpeechSpeechProperties {

	public static final String CONFIG_PREFIX = "spring.ai.flowspeech.tts";

	private @Nullable String model;

	private @Nullable String voice = "Kore";

	private @Nullable String format;

	private @Nullable Double speed;

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	public @Nullable String getVoice() {
		return this.voice;
	}

	public void setVoice(@Nullable String voice) {
		this.voice = voice;
	}

	public @Nullable String getFormat() {
		return this.format;
	}

	public void setFormat(@Nullable String format) {
		this.format = format;
	}

	public @Nullable Double getSpeed() {
		return this.speed;
	}

	public void setSpeed(@Nullable Double speed) {
		this.speed = speed;
	}

	public FlowSpeechTextToSpeechOptions toOptions() {
		return FlowSpeechTextToSpeechOptions.builder()
			.model(this.model)
			.voice(this.voice)
			.format(this.format)
			.speed(this.speed)
			.build();
	}

}
