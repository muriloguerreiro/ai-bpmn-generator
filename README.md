# AI BPMN Generator

Convert natural-language business-process descriptions into **executable BPMN 2.0 workflows compatible with Camunda 8** (Zeebe).

Architecture & roadmap: see [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md).

Pipeline: **NL → JSON IR → deterministic BPMN generation → validation → `.bpmn`** (open in Camunda Modeler).

## Stack
- Java 21 · Spring Boot 3.5 · Maven
- `io.camunda:zeebe-bpmn-model` — IR → BPMN with Zeebe extensions + automatic diagram layout (DI)
- Jackson (IR) · `com.networknt:json-schema-validator` (IR validation)

## Requirements
- JDK 21
- Maven 3.6+

## Run
```bash
mvn spring-boot:run
```

Then:
```bash
# health check
curl http://localhost:8080/health

# Phase 0 demo: hard-coded IR -> downloadable .bpmn
curl http://localhost:8080/api/demo -o invoice_approval.bpmn
```
Open the downloaded `.bpmn` in Camunda Modeler.

## Test
```bash
mvn test
```

## Status
- **Phase 0 (done):** scaffold; hard-coded `ProcessIr` compiles to valid BPMN via `GET /api/demo`.
- **Phase 1 (next):** `POST /api/generate` — NL description → `.bpmn` via the LLM.

See the implementation plan for later phases and out-of-scope/future work.
