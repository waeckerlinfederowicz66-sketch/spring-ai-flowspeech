package io.flowspeech.springai;

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import io.flowspeech.springai.api.FlowSpeechApi;
import static org.assertj.core.api.Assertions.*;

class FlowSpeechTextToSpeechModelTests {
    private void setup(MockWebServer server, MockWebServer storage) {
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                    {"id":"00000000-0000-4000-8000-000000000001","requestId":"%s","status":"success",
                    "usage":{"billableCharacters":5,"reservedCredits":0,"chargedCredits":5},
                    "result":{"url":"%s","mimeType":"audio/wav","sampleRate":24000,"channels":1,"bitsPerSample":16}}
                    """.formatted(request.getHeader("Idempotency-Key"), storage.url("/audio.wav")));
            }
        });
    }
    @Test
    void callAndRuntimeOptionsReturnDownloadedAudio() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            setup(server, storage);
            storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(io.flowspeech.springai.api.WavFixtures.wav())));
            var model = new FlowSpeechTextToSpeechModel(FlowSpeechApi.builder().apiKey("test-only-placeholder")
                    .baseUrl(server.url("/").toString()).build(), FlowSpeechTextToSpeechOptions.builder().voice("Kore").build());
            byte[] audio = model.call(new TextToSpeechPrompt("Hello",
                    FlowSpeechTextToSpeechOptions.builder().voice("Puck").build())).getResult().getOutput();
            assertThat(audio).isEqualTo(io.flowspeech.springai.api.WavFixtures.wav());
            assertThat(server.takeRequest().getBody().readUtf8()).contains("\"voiceName\":\"Puck\"");
        }
    }
    @Test
    void streamIsLazyAndEmitsOneCompletedWav() throws Exception {
        try (var server = new MockWebServer(); var storage = new MockWebServer()) {
            setup(server, storage);
            storage.enqueue(new MockResponse().setBody(new okio.Buffer().write(io.flowspeech.springai.api.WavFixtures.wav())));
            var model = new FlowSpeechTextToSpeechModel(FlowSpeechApi.builder().apiKey("test-only-placeholder")
                    .baseUrl(server.url("/").toString()).build(), FlowSpeechTextToSpeechOptions.builder().build());
            var stream = model.stream(new TextToSpeechPrompt("Hello"));
            assertThat(server.getRequestCount()).isZero();
            StepVerifier.create(stream).assertNext(response -> assertThat(response.getResult().getOutput()).isEqualTo(io.flowspeech.springai.api.WavFixtures.wav()))
                    .verifyComplete();
            assertThat(storage.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization")).isNull();
        }
    }
    @Test
    void unsupportedOptionsAreRejected() {
        assertThatThrownBy(() -> FlowSpeechTextToSpeechOptions.builder().model("obsolete").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowSpeechTextToSpeechOptions.builder().format("mp3").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowSpeechTextToSpeechOptions.builder().speed(1.0).build()).isInstanceOf(IllegalArgumentException.class);
    }
}
