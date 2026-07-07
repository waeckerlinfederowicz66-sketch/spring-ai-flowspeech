package io.flowspeech.springai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.http.MediaType;

import io.flowspeech.springai.api.FlowSpeechApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowSpeechTextToSpeechModelTests {

	@Test
	void callDecodesAudioAndSendsVoice() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			byte[] expectedAudio = "fake-wav".getBytes(StandardCharsets.UTF_8);
			server.enqueue(new MockResponse().setResponseCode(200)
				.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.setBody("""
						{"code":0,"message":"ok","data":{"mimeType":"audio/wav","audioBase64":"%s"}}
						""".formatted(Base64.getEncoder().encodeToString(expectedAudio))));
			server.start();

			FlowSpeechApi api = FlowSpeechApi.builder().baseUrl(server.url("/").toString()).build();
			FlowSpeechTextToSpeechModel model = FlowSpeechTextToSpeechModel.builder()
				.flowSpeechApi(api)
				.options(FlowSpeechTextToSpeechOptions.builder().voice("Puck").build())
				.build();

			byte[] audio = model.call("Hello from FlowSpeech");

			assertThat(audio).isEqualTo(expectedAudio);
			String body = server.takeRequest().getBody().readUtf8();
			assertThat(body).contains("\"text\":\"Hello from FlowSpeech\"");
			assertThat(body).contains("\"voiceName\":\"Puck\"");
		}
	}

	@Test
	void runtimeOptionsOverrideDefaultVoice() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setResponseCode(200)
				.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.setBody("""
						{"code":0,"message":"ok","data":{"audioBase64":"YQ=="}}
						"""));
			server.start();

			FlowSpeechApi api = FlowSpeechApi.builder().baseUrl(server.url("/").toString()).build();
			FlowSpeechTextToSpeechModel model = FlowSpeechTextToSpeechModel.builder()
				.flowSpeechApi(api)
				.options(FlowSpeechTextToSpeechOptions.builder().voice("Kore").build())
				.build();

			model.call(new TextToSpeechPrompt("Hello", FlowSpeechTextToSpeechOptions.builder().voice("Charon").build()));

			assertThat(server.takeRequest().getBody().readUtf8()).contains("\"voiceName\":\"Charon\"");
		}
	}

	@Test
	void apiErrorsRaiseException() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.enqueue(new MockResponse().setResponseCode(200)
				.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.setBody("""
						{"code":-1,"message":"Quota exceeded"}
						"""));
			server.start();

			FlowSpeechTextToSpeechModel model = FlowSpeechTextToSpeechModel.builder()
				.flowSpeechApi(FlowSpeechApi.builder().baseUrl(server.url("/").toString()).build())
				.build();

			assertThatThrownBy(() -> model.call("Hello")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Quota exceeded");
		}
	}

}
