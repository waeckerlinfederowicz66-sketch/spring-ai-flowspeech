package io.flowspeech.springai;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.audio.tts.TextToSpeechOptions;

/**
 * FlowSpeech runtime options for Spring AI text-to-speech requests.
 */
public final class FlowSpeechTextToSpeechOptions implements TextToSpeechOptions {

	private final @Nullable String model;

	private final @Nullable String voice;

	private final @Nullable String format;

	private final @Nullable Double speed;

	private FlowSpeechTextToSpeechOptions(Builder builder) {
		if (builder.model != null || builder.format != null || builder.speed != null) {
			throw new IllegalArgumentException("API v1 does not support model, format or speed options");
		}
		this.model = builder.model;
		this.voice = builder.voice;
		this.format = builder.format;
		this.speed = builder.speed;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public @Nullable String getModel() {
		return this.model;
	}

	@Override
	public @Nullable String getVoice() {
		return this.voice;
	}

	@Override
	public @Nullable String getFormat() {
		return this.format;
	}

	@Override
	public @Nullable Double getSpeed() {
		return this.speed;
	}

	public FlowSpeechTextToSpeechOptions merge(FlowSpeechTextToSpeechOptions defaults) {
		return builder()
			.model(valueOrDefault(this.model, defaults.model))
			.voice(valueOrDefault(this.voice, defaults.voice))
			.format(valueOrDefault(this.format, defaults.format))
			.speed(valueOrDefault(this.speed, defaults.speed))
			.build();
	}

	private static <T> @Nullable T valueOrDefault(@Nullable T value, @Nullable T defaultValue) {
		return value != null ? value : defaultValue;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof FlowSpeechTextToSpeechOptions that)) {
			return false;
		}
		return Objects.equals(this.model, that.model) && Objects.equals(this.voice, that.voice)
				&& Objects.equals(this.format, that.format) && Objects.equals(this.speed, that.speed);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.model, this.voice, this.format, this.speed);
	}

	public static final class Builder {

		private @Nullable String model;

		private @Nullable String voice = "Kore";

		private @Nullable String format;

		private @Nullable Double speed;

		private Builder() {
		}

		public Builder model(@Nullable String model) {
			this.model = model;
			return this;
		}

		public Builder voice(@Nullable String voice) {
			this.voice = voice;
			return this;
		}

		public Builder format(@Nullable String format) {
			this.format = format;
			return this;
		}

		public Builder speed(@Nullable Double speed) {
			this.speed = speed;
			return this;
		}

		public FlowSpeechTextToSpeechOptions build() {
			return new FlowSpeechTextToSpeechOptions(this);
		}

	}

}
