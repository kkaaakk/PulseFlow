# Agent PII Guardrail Contract

The Python Agent checks every user question, follow-up scope, investigation context, proposal model context and compact Java Tool observation before it enters the external LLM. The domain depends on the `PiiGuardrail` protocol. The composition root constructs one `OpenMedPiiGuardrail` for the process.

## Business Field Guard

Structured keys `userId`, `userIds`, `mobile`, `phone`, `email`, `address`, `idCard`, `idNumber`, `deviceId`, `imei`, `rawEvents`, `orderDetails`, `behaviourLogs`, `fullName`, and `realName` immediately block, including nested maps, lists and Pydantic aliases. Direct natural-language mentions block case-insensitively. A longer identifier such as `customerUserIdAlias` does not match merely because it contains `userId`. This check runs before OpenMed and also runs with the offline TestModel.

## Local Chinese PII

Nonblank text in real-model mode goes to OpenMed `extract_pii(..., lang="zh")` with a shared `ModelLoader` and the dedicated Chinese registry model `OpenMed/OpenMed-PII-Chinese-BigMed-Large-560M-v1`. Any returned entity blocks the whole model request with `pii_detected`. Ordinary Campaign text can pass when the model returns no entities. The library runs locally; input text is not sent to a PII API.

The loader warms before readiness and is reused for all checks. Synchronous inference is moved off the FastAPI event loop and serialized because concurrent access to one model pipeline is not guaranteed safe. The runtime uses `OpenMedConfig(local_only=True)` and a preloaded persistent cache. The Compose `agent-pii-model-init` service downloads missing artifacts to `pulseflow-openmed-cache`; the read-only Agent container mounts that cache read-only. TestModel skips downloading and inference while retaining the business guard.

## Fail-closed and privacy

Model load failure, missing/corrupt files, inference exceptions and malformed results block with the existing `pii_provider_unavailable` reason. Startup does not report ready if warming fails. `PiiBlockedError` contains only a safe reason. `pii.preflight` spans contain a result code; raw text, entity values, model results and exception messages stay out of API errors, logs and traces. The synthetic regression set and optional real OpenMed smoke check are used to measure false negatives and false positives; no detector guarantees complete coverage.
