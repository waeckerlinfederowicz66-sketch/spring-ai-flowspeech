# Upstream Plan

## Current Status

This project is already public on GitHub and provides an external Spring AI integration for [FlowSpeech](https://flowspeech.io/).
Version 0.2.0 is prepared locally for API v1; no Maven Central publication or
official Spring AI acceptance is claimed.

- `FlowSpeechApi`
- `FlowSpeechTextToSpeechOptions`
- `FlowSpeechTextToSpeechModel`
- Spring Boot auto-configuration
- starter artifact
- tests and a runnable sample

## Recommended PR Path

### 1. Awesome Spring AI

The coordinator should inspect any existing directory PR before making an update.
Do not create a duplicate submission.

Suggested entry:

```markdown
- [Spring AI FlowSpeech](https://github.com/waeckerlinfederowicz66-sketch/spring-ai-flowspeech) - FlowSpeech public API v1 text-to-speech adapter and Spring Boot starter for Spring AI.
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
