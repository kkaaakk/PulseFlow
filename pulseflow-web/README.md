# PulseFlow Web Console

Vue 3 + TypeScript + Vite console for the PulseFlow event → profile → Campaign → delivery → attribution workflow.

## Local development

Run `.\start.ps1` from the repository root for the complete Docker stack, including the web console at `http://127.0.0.1:5173`. For frontend source development, stop the Docker web service with `docker compose stop web` before using the same port locally.

```powershell
npm install
npm run dev
```

The Vite proxy forwards `/api` to `http://localhost:8080`. Start the Spring Boot app with the existing local infrastructure:

```powershell
cd ..\pulseflow
mvn spring-boot:run -pl pulseflow-boot -am
```

The local login defaults are Operator ID `1024` and password `pulseflow-local`. Replace them with `PULSEFLOW_OPERATOR_PASSWORD` (and the related auth properties) outside a demo environment.

Auth sessions use Redis by default, including in the Docker stack. Set `PULSEFLOW_AUTH_SESSION_STORE=memory` explicitly for isolated single-node tests that stop Redis while checking the User 360 MySQL fallback.

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
