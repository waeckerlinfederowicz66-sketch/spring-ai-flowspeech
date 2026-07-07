package io.flowspeech.springai.autoconfigure;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.flowspeech.springai.FlowSpeechTextToSpeechModel;
import io.flowspeech.springai.api.FlowSpeechApi;

import static org.assertj.core.api.Assertions.assertThat;

class FlowSpeechAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(FlowSpeechAutoConfiguration.class));

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

}
