# Upstream Plan

## Current Status

This project provides a working external Spring AI provider for [FlowSpeech](https://flowspeech.io/):

- `FlowSpeechApi`
- `FlowSpeechTextToSpeechOptions`
- `FlowSpeechTextToSpeechModel`
- Spring Boot auto-configuration
- starter artifact
- tests and a runnable sample

## Recommended PR Path

### 1. Awesome Spring AI

Open a PR to `spring-ai-community/awesome-spring-ai` after this project is in a public repository.

Suggested entry:

```markdown
- [Spring AI FlowSpeech](https://github.com/<owner>/spring-ai-flowspeech) - FlowSpeech Text-to-Speech provider and Spring Boot starter for Spring AI.
```

### 2. Official Spring AI

Only open an official Spring AI PR after the community provider has working usage and clear demand.

Expected changes if upstreamed:

- Move package names from `io.flowspeech.springai` to `org.springframework.ai.flowspeech`
- Add a `models/spring-ai-flowspeech` module
- Add `auto-configurations/models/spring-ai-autoconfigure-model-flowspeech`
- Add `starters/spring-ai-starter-model-flowspeech`
- Add Antora documentation under `spring-ai-docs`
- Add integration tests gated by a `FLOWSPEECH_API_KEY` or a mock server

Do not submit a documentation-only backlink PR. The provider should be the reason the link exists.
