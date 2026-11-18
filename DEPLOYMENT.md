# Deploying GoEV (Netlify frontend + Render backend, free tiers)

```
Browser ──> Netlify (static pages: /, /v2g)
   │            config.js -> window.GOEV_API_BASE = "https://goev-backend.onrender.com"
   └──────> Render web service (Docker, Spring Boot)  /api/**, /ws (SockJS)
                ├─ PostgreSQL (your own, e.g. Neon)
                └─ Redis (Upstash)
```

MQTT is switched off (`MQTT_ENABLED=false`) for the hosted demo: the dashboard's own
"place EVCS" / "send request" controls drive the system, so no broker or devices are needed.

The pages still live in `src/main/resources` (Spring Boot serves them locally, unchanged).
`frontend/build.mjs` copies them into `frontend/dist` for Netlify and writes `config.js`.

## 1. Backend on Render

1. Create a **new** Postgres database for GoEV and copy its connection string
   (`postgresql://...`). Neon's `channel_binding=require` is stripped automatically.
2. Create a **new** Upstash Redis database and copy its `rediss://...` URL.
3. Render → **New → Blueprint** → select the repo. `render.yaml` creates the `goev-backend`
   Docker web service (free) and asks for `DATABASE_URL`, `REDIS_URL` and `CORS_ALLOWED_ORIGINS`
   (use a placeholder for the last one until Netlify gives you a URL).
4. When it is live, `https://<service>.onrender.com/api/v2g/scenarios` returns JSON.

All backend variables are documented in [`.env.example`](.env.example).

## 2. Frontend on Netlify

1. Netlify → **Add new site → Import an existing project** → select the repo.
   `netlify.toml` already sets the build command (`node frontend/build.mjs`) and publish dir (`frontend/dist`).
2. Site configuration → **Environment variables** → add
   `GOEV_API_URL = https://<service>.onrender.com` (no trailing slash). The build fails on purpose if it is missing.
3. Deploy. Then copy the site URL into Render's `CORS_ALLOWED_ORIGINS`; Render redeploys on save.

See [`frontend/.env.example`](frontend/.env.example).

## Free-tier caveats

- **Cold starts:** free Render services sleep after 15 min idle; the first request takes about 30–60 s.
  The dashboard retries, so the first load may look empty briefly.
- **No persistent disk:** the V2G research ledger (`var/v2g/ledger.json`) is lost on restart/redeploy.
  EV/EVCS data in Postgres and Redis are kept.
- **Upstash free tier** has a monthly command limit; the dashboard's live state is small, so a demo stays well inside it.
- **Memory:** 512 MB. Measured locally under a 512 MB limit: research mode ~120 MB, full mode ~230 MB at idle.
  The JVM flags live in `JAVA_OPTS` in the `Dockerfile`.
- **Devices (later):** to connect the Python/Raspberry Pi clients in `dev-clients/`, set `MQTT_ENABLED=true`
  plus `MQTT_BROKER_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD` (e.g. a free HiveMQ Cloud broker).

## Test the production setup locally

```sh
docker compose up -d postgres redis
docker build -t goev-backend .
docker run --rm -p 10000:10000 -e PORT=10000 \
  -e DATABASE_URL=postgres://goev:goev@host.docker.internal:5433/goev \
  -e REDIS_URL=redis://host.docker.internal:6379 \
  -e MQTT_ENABLED=false \
  -e CORS_ALLOWED_ORIGINS=http://localhost:8888 goev-backend

GOEV_API_URL=http://localhost:10000 node frontend/build.mjs
npx serve frontend/dist -l 8888            # or any static server
```
