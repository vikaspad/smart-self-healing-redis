# Smart Self-Healing API Gateway (Redis + Spring Boot +AI)

This project exposes a single endpoint (`POST /api/smart-call`) that proxies calls to an external API.
When the upstream API changes **URL** or **payload fields** and returns a 4xx/5xx, the gateway:

1. writes a *failure event* to a Redis Stream
2. a background worker consumes the event, **learns new mappings**, and **upserts** rules into Redis
3. subsequent calls are automatically **self-healed** by rewriting the URL and/or payload

It supports **two learning modes**:

* **Deterministic inference (default)**: parses common error messages like:
  `"Use /v2/createOrder with fields { name, age }"`
  and infers endpoint + field mappings by matching expected fields to payload keys.
* **LLM-backed inference (optional)**: if `OPENAI_API_KEY` is set, the worker will call OpenAI first.
  If the model output is empty/unparseable, it falls back to deterministic inference.

## Redis storage model

Per target (e.g., `v1/orders`):

* Endpoint rewrite (string): `selfheal:<target>:endpoint`  -> `"/v2/createOrder"`
* Field mappings (hash): `selfheal:<target>:field-mappings`
  * `custName -> name`
  * `custAge  -> age`
* Failure stream (events): `selfheal:failures`

Rules are **upserted** (HSET semantics), so the framework keeps learning new mappings over time.

## Run locally

### 1) Start Redis

```bash
docker run --rm -p 6379:6379 redis:7
```

### 2) Start the sample upstream API (provided as `localServer.js`) by copying the file to a folder and run the below command in cmd prompt

```bash
npm init -y && npm install express
node localServer.js
```

This launches on `http://localhost:8081`.

### 3) Run the Spring Boot app

```bash
mvn spring-boot:run
```

### 4) Try a failing call (v1 is deprecated)

```bash
curl -X POST http://localhost:8080/api/smart-call \
  -H 'Content-Type: application/json' \
  -d '{
        "target": "v1/orders",
        "payload": { "custName": "vikas", "custAge": 10 },
        "options": { "timeout": 5000, "retries": 2 }
      }'
```

First call fails and is pushed to Redis Stream. The `AiWorker` consumes it, learns:

* endpoint: `/v2/createOrder`
* mappings: `custName->name`, `custAge->age`

Then retry the same request; it should succeed and show `"healed": true`.

## Configuration

In `application.yml`:

* `external-api.base-url`: upstream base URL (default `http://localhost:8081`)
* `selfheal.failure-stream`: Redis stream name (default `selfheal:failures`)
* `openai.api-key`: uses env var `OPENAI_API_KEY` by default

**Note:**
1. Set OPENAI key via powershell "setx OPENAI_API_KEY "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx""
2. To download the Redis, you will need docker in your system.
3. To view the Redis content, please download 'Redis Insight' -- UI for Redis
