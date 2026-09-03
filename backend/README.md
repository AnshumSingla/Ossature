# backend — Spring Boot session orchestrator

Owns sessions, the WebSocket connection, Postgres persistence / event log, and the bridge to
the Python agent (PRD §6.2). **Never calls an LLM.**

## Requires JDK 21

The project targets Java 21 (Spring Boot 4). If `java -version` shows 11, install Temurin 21
and set `JAVA_HOME` before building.

## Run

```bash
docker compose up -d postgres        # from the repo root
./mvnw spring-boot:run               # http://localhost:8080
```

Config lives in `src/main/resources/application.yml`; every value has an env-var override
(`POSTGRES_*`, `AGENT_BASE_URL`, `BACKEND_PORT`, …).

## Layout

```
config/    AppProperties, WebSocketConfig, WebConfig (CORS), AgentClientConfig
domain/    JPA entities + enums (User, Question, InterviewSession, SessionEvent, InterimSummary)
repo/      Spring Data repositories
web/       REST controllers (SessionController, QuestionController) + ProblemDetail handler
ws/        InterviewWebSocketHandler, SessionSocketRegistry, protocol/ (typed frames)
service/   SessionService, InterviewFlowService, EventLogService, QuestionBankService
agent/     AgentClient (RestClient) + AgentDtos
resources/db/migration/  Flyway V1 schema, V2 seed questions
```

## Key endpoints

- `POST /api/sessions` — create a session (kicks off the graph)
- `GET  /api/sessions/{id}` / `?userId=` — fetch / list
- `GET  /api/sessions/{id}/debrief` — final debrief JSON
- `GET  /api/questions` — approved question bank
- `WS   /ws/interview?sessionId=…&lastSeq=…` — the interview channel (PRD §6.4)

## Tests

`./mvnw test` needs JDK 21 and a running Postgres (the generated `@SpringBootTest` loads the
full context). Add Testcontainers for hermetic runs.
