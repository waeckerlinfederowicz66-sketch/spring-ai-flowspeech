# Spring AI FlowSpeech

[FlowSpeech](https://flowspeech.io/) public API v1 adapter for Spring AI
`TextToSpeechModel`. Version **0.2.1** is a prepared release candidate; it has **not been
published to Maven Central**. This community integration is not an official
Spring AI provider.

## Requirements and local installation

- Java 17 or newer, Maven 3.9+, Spring Boot 4.0.6 and Spring AI 2.0.0.
- An active paid Basic, Pro or Scale subscription and a developer API key from
  [API settings](https://flowspeech.io/settings/apikeys). Trials and guest access
  do not support public API generation.
- Use keys server-side only. Never put keys in source, browser code or URLs.

```bash
mvn verify
mvn install
```

Tests use local fake HTTP servers and do not generate production audio. After
installing from source, add:

```xml
<dependency>
  <groupId>io.flowspeech</groupId>
  <artifactId>spring-ai-flowspeech-starter</artifactId>
  <version>0.2.1</version>
</dependency>
```

```properties
spring.ai.flowspeech.api-key=${FLOWSPEECH_API_KEY}
spring.ai.flowspeech.tts.voice=Kore
```

## Generate audio

Inject `org.springframework.ai.audio.tts.TextToSpeechModel` and call:

```java
byte[] audio = textToSpeechModel.call("Hello from FlowSpeech.");
Files.write(Path.of("speech.wav"), audio);
```

Per-request voice:

```java
byte[] audio = textToSpeechModel.call(new TextToSpeechPrompt(
    "Hello.", FlowSpeechTextToSpeechOptions.builder().voice("Puck").build()
)).getResult().getOutput();
```

The adapter submits `POST /api/v1/tts/jobs`, polls the job, then downloads the
result separately. It returns WAV bytes at 24000 Hz, mono, 16 bit. `stream()`
is lazy and emits **one completed recording**, not incremental audio chunks.
Stopping a subscriber does not cancel the server-side job.

## Explicit job lifecycle and multiple speakers

Use the API client when you need to persist a request ID before submitting:

```java
FlowSpeechApi api = FlowSpeechApi.builder()
    .apiKey(System.getenv("FLOWSPEECH_API_KEY")).build();
UUID requestId = UUID.randomUUID(); // Persist this and the unchanged input for recovery.
FlowSpeechApi.Input input = FlowSpeechApi.Input.multi(List.of(
    new FlowSpeechApi.Segment("Alice", "Welcome!", "Kore"),
    new FlowSpeechApi.Segment("Bob", "Thank you.", "Puck")
));
FlowSpeechApi.Job job = api.createJob(input, requestId);
FlowSpeechApi.Job completed = api.awaitJob(job.id());
byte[] audio = api.download(completed);
```

`api.generate(input, requestId)` combines submission, polling and download under
one total deadline. Individual lifecycle methods each get their own configured
deadline. Available operations:

- `quota()`: top-level `quota`, `subscription`, `limits`; use `quota.available`.
  The nullable subscription object is preserved; an optional legacy
  `entitlement` field remains available for compatibility.
- `voices()`: top-level `voices` list with case-sensitive voice names.
- `getJob(id)`, `recoverJob(requestId)`, `cancelJob(id)`.
- `download(job)`: download an existing successful job, with no generation.

Pending states are `queued`, `running`, `merging`, `settling`. HTTP 200 does
not establish success. Failed/cancelled jobs stop polling. Deleted successful
recordings raise `result_deleted`; missing or malformed results are errors.
Raw lifecycle methods expose terminal states for inspection.

## Timeouts, retries and credential boundaries

| Property prefix: `spring.ai.flowspeech.` | Default |
| --- | --- |
| `enabled` | `true` |
| `base-url` | `https://flowspeech.io` |
| `api-key` | Required |
| `text-to-speech-path` | `/api/v1/tts/jobs`, no alternate paths |
| `timeout` | `10m`, total per operation |
| `request-timeout` | `30s`, includes reading the response body |
| `poll-interval` | `5s`, minimum allowed |
| `max-retries` | `2`, maximum 5 |
| `max-download-bytes` | `52428800` (50 MiB), enforced while reading |
| `tts.voice` | `Kore` |

Builder methods expose the same limits with `Duration` values. Retryable network
errors, HTTP 429 and retryable 5xx responses are bounded. Retries retain the UUID
and serialized body. Polling and retries honor a longer numeric or HTTP-date
`Retry-After`, including headers received before a response body fails, and
stop rather than exceed the total deadline.

API and terminal-job errors use local messages and filter reflected keys and
private URLs from diagnostic codes and traces. Interrupted permanent HTTP errors
do not retry. Non-JSON HTTP 429 responses retain their cooldown without exposing
gateway bodies. Do not log raw request objects, transport exceptions or result
URLs; application logging remains a separate trusted boundary.

`FlowSpeechException` exposes `code()`, `traceId()`, `requestId()`,
`jobId()`, `statusCode()` and `retryable()`. Persist identifiers, not API keys.
Recover after an ambiguous submission with the same request ID and body.
If recovery returns 404, retry that unchanged create request. A new UUID means
a new recording and may spend credits.

Downloads use a separate client with no Bearer token, cookie jar or API headers.
Automatic redirects are disabled for both clients. HTTPS URLs without embedded
credentials are required; loopback HTTP is allowed only for local testing.
Results are link-accessible: protect the URL as you would the recording.
Downloads validate complete RIFF length and chunk boundaries, PCM format,
24000 Hz/mono/16-bit samples, block alignment 2, byte rate 48000, and nonempty
aligned audio data. Truncated, duplicate or malformed format/data chunks fail.
On download failure call `download(existingJob)` again; do not call the model
again to repair a download.

## Migration from 0.1.0-SNAPSHOT

- The public API no longer returns a synchronous base64 envelope.
- Existing `textToSpeech(FlowSpeechRequest)` and async methods remain single-voice
  adapters; their compatibility base64 is encoded **locally after downloading**.
- `textToSpeechAsync` snapshots and validates input before returning its
  publisher. Invalid input throws immediately, while HTTP work stays lazy.
  Re-subscribing retains both the same UUID and the same immutable input,
  even if the caller later changes the original speakers list.
- Legacy multi-speaker scripts, unequal `originalText`, model, format and speed
  options now fail explicitly. Use `Input.multi` with ordered segments.
- API keys are mandatory. Custom RestClient/WebClient transports, extra headers,
  custom error handlers and whole-generation retry templates are rejected:
  these could bypass deadlines or duplicate recordings after download errors.
- The build now uses Boot 4 with Spring AI 2; the former Boot 3/AI 2 mix was
  incompatible. Applications on Boot 3 must upgrade before using this release.

## Demo and verification

The demo is billable and is **not run by tests**. After local installation,
set `FLOWSPEECH_API_KEY`, then intentionally run:

```bash
mvn -pl samples/flowspeech-tts-demo spring-boot:run \
  -Dspring-boot.run.arguments="Hello from FlowSpeech"
```

It writes `target/flowspeech-demo.wav`. For a no-credit packaged-JAR smoke test:

```bash
MAVEN_REPO="$HOME/.m2/repository" node verification/artifact-smoke.mjs
```

The script uses `JAVA_HOME` when set and loads packaged 0.2.1 JARs, starts
loopback-only fake API/storage servers and verifies auto-configuration and
credential-free downloads. This is a local synthetic check, not a production
generation or Maven Central publication check.

[API documentation](https://flowspeech.io/docs/public-api) and
[OpenAPI](https://flowspeech.io/openapi.json).
