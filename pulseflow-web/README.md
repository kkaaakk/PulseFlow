# PulseFlow Web Console

Vue 3 + TypeScript + Vite console for the PulseFlow event → profile → Campaign → delivery → attribution workflow.

## Local development

```powershell
npm install
npm run dev
```

The Vite proxy forwards `/api` to `http://localhost:8080`. Start the Spring Boot app with the existing local infrastructure, and enable the built-in fake AI provider when you want to use Copilot:

```powershell
cd ..\pulseflow
mvn spring-boot:run -pl pulseflow-boot -Dspring-boot.run.arguments="--pulseflow.ai.enabled=true --pulseflow.ai.mock-enabled=true"
```

The local login defaults are Operator ID `1024` and password `pulseflow-local`. Replace them with `PULSEFLOW_OPERATOR_PASSWORD` (and the related auth properties) outside a demo environment.

For a single-node local run, auth sessions use the in-memory Sa-Token store by default so User 360 can still use its MySQL realtime fallback when Redis is stopped. Set `PULSEFLOW_AUTH_SESSION_STORE=redis` when distributed sessions are required.

## Deterministic UI demo

The UI has an explicit demo-only mode for screenshots and E2E; it does not change the production API path.

```powershell
$env:VITE_DEMO_MODE = 'true'
npm run dev -- --host 127.0.0.1
```

## Verification

```powershell
npm run typecheck
npm run lint
npm run test
npm run build
npm run test:e2e
```
