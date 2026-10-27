# Changelog

## Unreleased

- Filter reflected key/URL values from API error codes and traces and terminal job errors; retain generic messages and recovery IDs.
- Do not retry received permanent HTTP errors or redirects when reading their response body fails.
- Retry non-JSON HTTP 429 responses using the received cooldown and original submission identity; retain HTTP status and recovery metadata without echoing gateway bodies. Local fix only; existing public artifacts are unchanged.

## 0.2.0 - 2026-10-26 (prepared, not published)

- Migrate synchronous TTS to public API v1 create/poll/download jobs.
- Add quota, voices, explicit multi-speaker input, recovery and cancellation.
- Preserve simple model calls and local-base64 compatibility methods.
- Add stable idempotency, bounded retry/polling, total/request deadlines,
  streaming download byte limits and strict credential isolation.
- Reject unsupported options and unsafe custom transports.
- Require paid API access and a server-side key.
- Align Spring AI 2.0.0 with Spring Boot 4.0.6; target Java 17 bytecode.
- Add fake-HTTP lifecycle/security tests and packaged-JAR verification.
- Preserve the production quota subscription object and optional legacy entitlement.
- Validate complete RIFF/PCM chunk structure, not just the WAV signature.
- Retain Retry-After cooldown when headers arrive but the response body fails.
- Snapshot and validate async input before publisher creation so caller
  mutations cannot change the POST body while reusing the same UUID.
- Add mutable-source resubscription regressions and packaged-JAR replay checks.

## 0.1.0-SNAPSHOT

- Initial source-only Spring AI integration.
