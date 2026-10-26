package io.flowspeech.springai.autoconfigure;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.flowspeech.springai.FlowSpeechTextToSpeechModel;
import io.flowspeech.springai.api.FlowSpeechApi;

import static org.assertj.core.api.Assertions.assertThat;

class FlowSpeechAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(FlowSpeechAutoConfiguration.class))
		.withPropertyValues("spring.ai.flowspeech.api-key=test-only-placeholder");

	@Test
	void createsApiAndModelByDefault() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(FlowSpeechApi.class)
			.hasSingleBean(FlowSpeechTextToSpeechModel.class));
	}

	@Test
	void canDisableAutoConfiguration() {
		this.contextRunner.withPropertyValues("spring.ai.flowspeech.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(FlowSpeechApi.class)
				.doesNotHaveBean(FlowSpeechTextToSpeechModel.class));
	}

	@Test
	void bindsSpeechProperties() {
		this.contextRunner.withPropertyValues("spring.ai.flowspeech.tts.voice=Charon")
			.run(context -> assertThat(context.getBean(FlowSpeechTextToSpeechModel.class).getOptions().getVoice())
				.isEqualTo("Charon"));
	}

	@Test
	void missingKeyFailsAtStartup() {
		this.contextRunner.withPropertyValues("spring.ai.flowspeech.api-key=")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void unsupportedOptionsFailAtStartup() {
		this.contextRunner.withPropertyValues("spring.ai.flowspeech.tts.speed=1.2")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void invalidPollingIntervalFailsAtStartup() {
		this.contextRunner.withPropertyValues("spring.ai.flowspeech.poll-interval=1s")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void bindsTimeoutsAndLimits() {
		this.contextRunner.withPropertyValues("spring.ai.flowspeech.timeout=2m",
				"spring.ai.flowspeech.request-timeout=10s", "spring.ai.flowspeech.max-retries=1",
				"spring.ai.flowspeech.max-download-bytes=4096")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var properties = context.getBean(FlowSpeechConnectionProperties.class);
				assertThat(properties.getTimeout()).isEqualTo(java.time.Duration.ofMinutes(2));
				assertThat(properties.getMaxDownloadBytes()).isEqualTo(4096);
			});
	}

}
