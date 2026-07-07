# Spring AI FlowSpeech

[FlowSpeech](https://flowspeech.io/) provider support for Spring AI Text-to-Speech.

This repository contains a real Spring AI-style starter that adapts the existing FlowSpeech
`/api/ai/text-to-speech` endpoint to Spring AI's `TextToSpeechModel` interface.

## Modules

- `spring-ai-flowspeech-model` - API client, options, and `FlowSpeechTextToSpeechModel`
- `spring-ai-flowspeech-autoconfigure` - Spring Boot auto-configuration and properties
- `spring-ai-flowspeech-starter` - convenience starter
- `samples/flowspeech-tts-demo` - minimal command-line demo

## Install Locally

```bash
mvn install
```

## Use In A Spring Boot App

```xml
<dependency>
  <groupId>io.flowspeech</groupId>
  <artifactId>spring-ai-flowspeech-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```properties
spring.ai.flowspeech.tts.voice=Kore
```

If your FlowSpeech deployment requires bearer authentication:

```properties
spring.ai.flowspeech.api-key=${FLOWSPEECH_API_KEY}
```

Then inject the standard Spring AI model interface:

```java
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.stereotype.Service;

@Service
class NarrationService {

  private final TextToSpeechModel textToSpeechModel;

  NarrationService(TextToSpeechModel textToSpeechModel) {
    this.textToSpeechModel = textToSpeechModel;
  }

  void saveNarration() throws Exception {
    byte[] audio = this.textToSpeechModel.call("FlowSpeech works with Spring AI.");
    Files.write(Path.of("narration.wav"), audio);
  }
}
```

## Runtime Options

```java
byte[] audio = textToSpeechModel.call(new TextToSpeechPrompt(
    "Read this with an upbeat voice.",
    FlowSpeechTextToSpeechOptions.builder().voice("Puck").build()
)).getResult().getOutput();
```

## Configuration

| Property | Default | Description |
|---|---:|---|
| `spring.ai.flowspeech.enabled` | `true` | Enables the auto-configuration |
| `spring.ai.flowspeech.base-url` | `https://flowspeech.io` | FlowSpeech API base URL |
| `spring.ai.flowspeech.text-to-speech-path` | `/api/ai/text-to-speech` | TTS endpoint path |
| `spring.ai.flowspeech.api-key` | empty | Optional bearer token |
| `spring.ai.flowspeech.tts.voice` | `Kore` | Default FlowSpeech voice |

## Demo

```bash
mvn -pl samples/flowspeech-tts-demo spring-boot:run \
  -Dspring-boot.run.arguments="Hello from FlowSpeech and Spring AI"
```

The demo writes `target/flowspeech-demo.wav`.

## Upstream / Directory Submission Route

This code is intentionally structured like a Spring AI provider:

1. Keep this repository public and buildable.
2. Publish an initial Maven artifact or keep it installable from source.
3. Submit it to `awesome-spring-ai` as a community provider.
4. If FlowSpeech wants official Spring AI inclusion later, port these modules into the Spring AI repository using the same layout as `spring-ai-elevenlabs`.

No credentials, local environment values, or private service keys are required by this project.
