# AI BPMN Generator — Implementation Plan

> Convert natural-language business-process descriptions into **executable BPMN 2.0 workflows compatible with Camunda 8** (Zeebe).
>
> Status: **Planning / architecture only** — no code in this document.
>
> **Stack:** Java 21 · Spring Boot 3.5 · Maven · Camunda 8 Java APIs · Jackson. Single Maven module, kept deliberately small for a hackathon.

---

## 1. Goals & Non-Goals

### Goals
- Take a free-text description of a business process and produce **valid, deployable Camunda 8 BPMN XML**.
- Use a clean, testable pipeline: **NL → JSON IR → deterministic BPMN generation → validation before output**.
- Make the output easy to verify (download `.bpmn`, open in Camunda Modeler, optional in-page preview).
- (Stretch) Deploy the workflow to a running Camunda 8 cluster and start an instance live during the demo.

### Non-Goals (for the hackathon)
- Full BPMN 2.0 coverage. Camunda 8 executes a *subset*; we target that subset only.
- Production auth / multi-tenancy / persistence.
- A custom front-end framework (no React/SPA/monorepo). A single optional static HTML page is the most UI we add.
- Implementing job workers / connector runtimes — we generate the *model* (task types), not the workers that execute them.

---

## 2. Key Domain Constraints (why the design looks the way it does)

1. **Camunda 8 executes a *subset* of BPMN 2.0.** Elements must carry Zeebe extension data, e.g. a Service Task needs a Zeebe task definition (`zeebe:taskDefinition[type]`), gateways need FEEL conditions on outgoing flows. Generic BPMN is not enough.
2. **BPMN XML has two coupled parts:** the *semantic* model (`bpmn:process`) **and** the *diagram interchange* (`bpmndi:BPMNDiagram` with x/y/width/height for every shape and edge). Coordinates must exist or the diagram won't render.
3. **LLMs are unreliable at emitting large, schema-perfect XML directly** (bad namespaces, missing ids, dangling flow refs).
4. **Conditions/expressions use FEEL**, not Java/JS.

**Consequence — the central design bet (unchanged):** the LLM does **not** generate BPMN XML directly. It generates a small, strict **Intermediate Representation (IR)** in JSON (deserialized with Jackson, validated against a JSON Schema). Deterministic Java code then compiles the IR into Camunda-8 BPMN, and the BPMN builder produces the diagram coordinates for us.

```
        fuzzy / probabilistic                         deterministic / verifiable
  ┌──────────────────────────────┐   ┌──────────────────────────────────────────────────────┐
  NL text ──LLM──▶ IR (JSON) ──schema-validate──▶ IR→BPMN compiler ──▶ BPMN + DI ──validate/deploy-check──▶ output
  └──────────────────────────────┘   │  (Camunda zeebe-bpmn-model fluent builder, auto-layout) │
                                      └──────────────────────────────────────────────────────┘
```

> **Why this matters for the Java choice:** the Camunda BPMN model builder (`io.camunda:zeebe-bpmn-model`) is a *fluent Java API* that both knows the Zeebe extension elements **and auto-generates the diagram coordinates (DI)** as you build. That single library replaces what needed three separate JS libraries (`bpmn-moddle` + `zeebe-bpmn-moddle` + `bpmn-auto-layout`) in the earlier draft — a strong justification for the Java stack and a big simplification.

---

## 3. Overall Architecture

### 3.1 High-level component view (single Spring Boot app)

```
   Client (curl / browser / optional static page)
        │  POST /api/generate   { "description": "..." }
        ▼
   ┌──────────────────────────────────────────────────────────────────────────┐
   │                    Spring Boot 3.5 application (Java 21)                    │
   │                                                                            │
   │   GenerateController (REST)                                                 │
   │        │                                                                    │
   │        ▼                                                                    │
   │   GenerationService  ── orchestrates the pipeline ──────────────────────┐  │
   │        │                                                                 │  │
   │        ▼                  ▼                    ▼                  ▼       │  │
   │   LlmClient ──────▶ IrValidator ──────▶ IrToBpmnCompiler ──▶ BpmnValidator  │
   │   (RestClient to   (Jackson +          (zeebe-bpmn-model    (model validation │
   │    OpenAI/Anthropic json-schema-        fluent builder +     + optional Zeebe  │
   │    JSON output)     validator)          Zeebe extensions,    deploy check)     │
   │                                         auto DI/layout)                   │  │
   │                                                                          │  │
   │   CamundaDeployService (stretch) ── CamundaClient/ZeebeClient ───────────┘  │
   │        │                                                                    │
   └────────┼────────────────────────────────────────────────────────────────┘
            │ deploy / start instance (stretch)
            ▼
   ┌─────────────────────────────────────────────┐
   │ Camunda 8 (SaaS or self-managed via Docker)  │
   │  Zeebe gateway · Operate · Tasklist           │
   └─────────────────────────────────────────────┘
```

### 3.2 Tech stack & rationale

| Concern | Choice | Why |
|---|---|---|
| Language / build | **Java 21**, **Maven**, **Spring Boot 3.5** | As requested. Spring Boot 3.5 needs Java 17+; Java 21 LTS is a good baseline. Single module = minimal ceremony. |
| Web layer | **Spring Web (MVC)** | One `@RestController` with `POST /api/generate`. No extra framework. |
| IR (de)serialization | **Jackson** (bundled with Spring Boot) | Map LLM JSON ↔ Java records cleanly. |
| IR validation | **`com.networknt:json-schema-validator`** | Validate IR against a JSON Schema (the Java analog of Ajv); precise error paths for the repair loop. |
| BPMN generation + layout | **`io.camunda:zeebe-bpmn-model`** (`io.camunda.zeebe.model.bpmn.Bpmn`) | Fluent builder with native **Zeebe extension** support (`.zeebeJobType(...)`, user tasks, conditions) **and automatic DI/layout generation**. Core enabler of the deterministic step. |
| LLM access | **Spring `RestClient`** calling OpenAI/Anthropic with JSON-mode output | No heavy dependency; keep it simple. (`langchain4j` is an optional upgrade if structured-output ergonomics are wanted — not needed for MVP.) |
| Camunda 8 client (stretch) | **`io.camunda:spring-boot-starter-camunda-sdk`** (`CamundaClient`) | Official Spring SDK to deploy resources + start instances. Underlying: `zeebe-client-java`. |
| Tests | **JUnit 5** + Spring Boot Test | Unit-test `IrToBpmnCompiler` (golden IR → expected BPMN) independent of the LLM. |
| Local Camunda 8 (stretch) | **Camunda 8 Run** / docker-compose | One-command cluster for the live-deploy demo. |
| UI (optional, not a framework) | A **single static `index.html`** served from `src/main/resources/static/`, using **bpmn-js via CDN** to preview the returned XML | Visual proof for the demo with **no build step, no React, no monorepo**. Entirely optional — MVP works headless (REST + downloadable `.bpmn`). |

> **Removed from the previous draft** (per the simplification request): React, pnpm monorepo, Fastify/Node API, `bpmn-moddle`/`zeebe-bpmn-moddle`, `bpmn-auto-layout`, Ajv, `bpmnlint`. Their responsibilities are now covered by Java equivalents above (notably `zeebe-bpmn-model` for build+layout, `json-schema-validator` for IR validation, and an actual Zeebe deploy as the strongest deploy-readiness check).

---

## 4. Proposed Project Structure

A single standard Maven + Spring Boot module. (Tree is the *target*; only scaffolding is created in Phase 0.)

```
ai-bpmn-generator/
├── IMPLEMENTATION_PLAN.md
├── README.md
├── pom.xml
├── docker-compose.yml                  # Camunda 8 Run (Phase 3 / stretch)
├── .env.example  (or application-local.yaml)   # LLM key, Zeebe connection
│
└── src/
    ├── main/
    │   ├── java/com/example/bpmngen/
    │   │   ├── AiBpmnGeneratorApplication.java
    │   │   ├── api/
    │   │   │   ├── GenerateController.java      # POST /api/generate, /api/refine
    │   │   │   └── dto/                         # request/response records
    │   │   ├── orchestration/
    │   │   │   └── GenerationService.java       # generate→validate→compile→validate
    │   │   ├── llm/
    │   │   │   ├── LlmClient.java               # interface
    │   │   │   ├── OpenAiLlmClient.java         # RestClient impl
    │   │   │   └── PromptTemplates.java         # system prompt, few-shot, repair
    │   │   ├── ir/
    │   │   │   ├── ProcessIr.java               # records: ProcessIr, Element, Flow
    │   │   │   ├── ElementType.java             # enum = Camunda-8 subset we support
    │   │   │   └── IrValidator.java             # json-schema-validator
    │   │   ├── bpmn/
    │   │   │   ├── IrToBpmnCompiler.java        # zeebe-bpmn-model fluent builder
    │   │   │   ├── ZeebeExtensions.java         # task defs, io mappings, conditions
    │   │   │   └── BpmnValidator.java           # model validation + deploy-readiness
    │   │   └── deploy/
    │   │       └── CamundaDeployService.java    # CamundaClient (stretch)
    │   └── resources/
    │       ├── application.yaml
    │       ├── schema/ir-schema.json            # IR JSON Schema
    │       ├── prompts/                         # system + few-shot examples
    │       └── static/index.html                # optional bpmn-js (CDN) preview
    └── test/
        └── java/com/example/bpmngen/bpmn/
            └── IrToBpmnCompilerTest.java        # golden IR → BPMN assertions
```

---

## 5. Main Components & Responsibilities

| # | Component | Responsibility | Inputs → outputs |
|---|---|---|---|
| 1 | **GenerateController** | REST entry point; map DTOs; error handling. | HTTP `{description}` → `{bpmnXml, irUsed, report}` |
| 2 | **GenerationService** | Orchestrate pipeline; bounded repair retries; timeouts. | request → BPMN XML + report |
| 3 | **LlmClient** (`OpenAiLlmClient`) | Call the LLM with system prompt + few-shot, request JSON output, return raw IR JSON. | NL (+ optional existing IR) → IR JSON string |
| 4 | **IrValidator** | Deserialize with Jackson; validate against `ir-schema.json`; produce precise error list for repair. | IR JSON → `ProcessIr` (or errors) |
| 5 | **IrToBpmnCompiler** | Deterministically build BPMN from `ProcessIr` using the `zeebe-bpmn-model` fluent builder; the builder auto-generates DI/layout. | `ProcessIr` → `BpmnModelInstance` → XML |
| 6 | **ZeebeExtensions** | Attach Zeebe specifics (service-task job type, user-task assignment, FEEL conditions, I/O mappings). | model + IR detail → enriched model |
| 7 | **BpmnValidator** | Validate the model (`Bpmn.validateModel`) and, when configured, a real Zeebe deploy as the ground-truth deploy-readiness check. | BPMN → pass/fail + report |
| 8 | **CamundaDeployService** *(stretch)* | Deploy resource and optionally start an instance via `CamundaClient`. | BPMN → process key / instance id |
| 9 | **ProcessIr / ElementType** | Single source of truth for the IR contract; the `ElementType` enum encodes exactly the Camunda-8 subset we support per phase. | — |

### 5.1 The Intermediate Representation (IR) — sketch

Modeled as Java **records**, (de)serialized by Jackson, validated against a JSON Schema. Example JSON (illustrative):

```jsonc
{
  "id": "invoice_approval",
  "name": "Invoice Approval",
  "elements": [
    { "id": "start",     "type": "startEvent",       "name": "Invoice received" },
    { "id": "review",    "type": "userTask",         "name": "Review invoice", "assignee": "=manager" },
    { "id": "gw_amount", "type": "exclusiveGateway", "name": "Amount > 1000?" },
    { "id": "approve",   "type": "serviceTask",      "name": "Auto-approve", "jobType": "auto-approve" },
    { "id": "end_ok",    "type": "endEvent",         "name": "Approved" },
    { "id": "end_no",    "type": "endEvent",         "name": "Rejected" }
  ],
  "flows": [
    { "from": "start",     "to": "review" },
    { "from": "review",    "to": "gw_amount" },
    { "from": "gw_amount", "to": "approve", "condition": "=amount > 1000" },
    { "from": "gw_amount", "to": "end_no",  "isDefault": true },
    { "from": "approve",   "to": "end_ok" }
  ]
}
```

Corresponding Java (sketch):

```java
record ProcessIr(String id, String name, List<Element> elements, List<Flow> flows) {}
record Element(String id, ElementType type, String name, String jobType, String assignee) {}
record Flow(String from, String to, String condition, boolean isDefault) {}
enum ElementType { startEvent, endEvent, userTask, serviceTask, exclusiveGateway, parallelGateway /* grows by phase */ }
```

Why the IR (unchanged rationale): **validatable** (JSON Schema), **repairable** (schema errors → targeted fix prompt), **stable/testable** (compiler tested independently of the LLM), and it **keeps the LLM inside Zeebe's executable subset** via the `ElementType` enum.

---

## 6. Data Flow

### 6.1 Generation (MVP path)

```
1. POST /api/generate { "description": "When an invoice arrives, a manager reviews it.
   If amount > 1000 auto-approve, otherwise reject." }
2. GenerationService → LlmClient (system prompt + few-shot, JSON output)
3. LLM returns IR JSON
4. IrValidator (Jackson + json-schema-validator):
      ✓ valid   → continue
      ✗ invalid → feed errors back to LLM (repair), retry up to N (e.g. 2)
5. IrToBpmnCompiler: ProcessIr → zeebe-bpmn-model fluent builder → BpmnModelInstance
      (Zeebe extensions attached; DI/layout auto-generated by the builder)
6. BpmnValidator: Bpmn.validateModel(...) [+ optional real Zeebe deploy check]
7. Serialize model → XML
8. Response { bpmnXml, irUsed, report } → client (download .bpmn / optional preview)
```

### 6.2 Refinement loop

```
POST /api/refine { currentIr, instruction:"Add a 2-day timer escalation on review" }
  → LLM mutates IR → (same validate → compile → validate pipeline) → updated BPMN
```

### 6.3 Deploy (stretch)

```
POST /api/deploy { bpmnXml }
  → CamundaClient.newDeployResourceCommand().addProcessModel(model,"process.bpmn").send().join()
  → (optional) createProcessInstance → view in Operate
```

---

## 7. Implementation Phases

### Phase 0 — Scaffolding (hour 0–2)
- `spring init` / Spring Initializr: Java 21, Spring Boot 3.5, Maven, Spring Web. Add `zeebe-bpmn-model`, `json-schema-validator`.
- `GET /health`, `application.yaml`, `.env.example`. Commit a minimal `README`.
- **Exit:** app boots; a hardcoded `ProcessIr` compiles to valid BPMN XML returned by a test endpoint.

### Phase 1 — MVP: NL → valid linear BPMN (the demo backbone)
Element subset: `startEvent`, `endEvent`, `userTask`, `serviceTask` (with Zeebe job type), sequence flows. **No gateways yet.**
- IR records + `ir-schema.json` + `IrValidator`.
- `LlmClient` (one provider) with JSON output + 2–3 few-shot examples.
- `IrToBpmnCompiler` for the v1 subset (builder handles layout).
- `POST /api/generate` returning BPMN XML; downloadable `.bpmn`.
- JUnit golden test: `ProcessIr` → expected BPMN; `Bpmn.validateModel` passes.
- **Exit / demo:** type a linear process, get BPMN that opens cleanly in Camunda Desktop Modeler.

### Phase 2 — Branching & richer semantics
- Add to IR/compiler: `exclusiveGateway` + `parallelGateway`, **FEEL conditions**, default flows, user-task assignment, basic I/O mappings.
- Add timer & message events (escalations).
- **Repair loop** on both IR-schema and model-validation errors.
- **Exit:** branching processes validate clean; `/api/refine` works.

### Phase 3 — Live Camunda 8 deployment (high-impact demo moment)
- `docker-compose` Camunda 8 Run (Zeebe + Operate + Tasklist) **or** Camunda 8 SaaS creds.
- Add `spring-boot-starter-camunda-sdk`; `CamundaDeployService` deploys + starts an instance; surface ids + an Operate link.
- **Exit:** generate → deploy → start instance → show it in Operate, live.

### Phase 4 — Polish (if time remains)
- Optional `static/index.html` bpmn-js preview; iterative chat UX.
- More few-shot examples; ambiguity handling (clarifying questions); generated Camunda Forms.

> **Hackathon priority order:** Phase 1 (must-have) → Phase 3 deploy moment (wow factor) → Phase 2 richness. A polished Phase 1 + one live deploy beats a half-working Phase 2.

---

## 8. Validation Strategy (defense in depth, cheapest first)

1. **IR JSON Schema** (`json-schema-validator`) — structural correctness before any XML exists.
2. **Model validation** (`Bpmn.validateModel`) — well-formed references/structure from the builder.
3. **Real Zeebe deploy** (stretch / when a cluster is available) — ground truth: the engine itself accepts or rejects.

Any failing gate can trigger a **bounded LLM repair pass** (feed the precise error back). Cap retries (e.g. ≤2) for latency/cost and to avoid loops.

> Note: the JS-era `bpmnlint`/camunda-compat plugin has no direct Maven equivalent; in this stack the equivalent confidence comes from the `zeebe-bpmn-model` builder (which only exposes Zeebe-valid constructs) plus an actual Zeebe deploy as the authoritative check.

---

## 9. Risks, Mitigations & Assumptions

| Risk | Impact | Mitigation |
|---|---|---|
| LLM emits invalid/hallucinated BPMN | Broken demo | IR indirection + deterministic compiler; LLM never writes XML. |
| LLM produces structurally invalid IR | Pipeline fails | JSON output + `json-schema-validator` + bounded repair loop. |
| Auto-layout from `zeebe-bpmn-model` looks rough on complex graphs | Ugly diagram | Acceptable for MVP; keep Phase-1 graphs simple; manual tidy in Modeler if needed. |
| Zeebe extension coverage gaps in the builder | Some constructs not expressible fluently | Fall back to adding raw extension elements via the model API; constrain `ElementType` to what we can emit. |
| FEEL expressions wrong | Misrouted gateways / deploy fails | Few-shot FEEL examples; validate via deploy; Phase-1 is condition-free. |
| No native LLM "structured output" in Java | Malformed JSON | Use provider JSON mode + schema validation + repair; optionally adopt `langchain4j` later. |
| LLM latency/cost | Slow demo | Small model for IR, cap tokens, bounded retries. |
| Camunda 8 env setup eats time | No live deploy | Deploy is a *stretch*; MVP only needs a downloadable `.bpmn`. |
| Scope creep into full BPMN | Nothing finished | Cap supported `ElementType`s per phase. |
| Secret management (LLM/Zeebe) | Blocked / leaked keys | Externalized config (`application-local.yaml` / env vars), never commit secrets. |

### Assumptions
- An LLM API key (OpenAI and/or Anthropic) is available via env/config.
- Target is **Camunda 8 (Zeebe)**, not Camunda 7.
- "Executable" = deploys to Zeebe and can start an instance; job-worker implementations are out of scope.
- Java 21 + Maven available; team is comfortable with Spring Boot.
- For the live-deploy stretch, Docker is available locally **or** Camunda 8 SaaS credentials are provided.
- English-language input for the hackathon.

---

## 10. Open Questions
1. LLM provider & model preference (OpenAI vs Anthropic) and budget?
2. Camunda 8 target for the live demo: **SaaS** (need credentials) or **self-managed** (Docker)?
3. Do we need the optional in-page bpmn-js preview, or is downloadable `.bpmn` + Camunda Modeler enough for the demo?
4. Required element coverage for judging (just tasks/gateways, or events/forms/sub-processes too)?
5. Single-shot generation or conversational refinement as the headline UX?

---

## 11. Suggested Demo Script
1. Paste a real-world process description in plain English (curl or the optional page).
2. Get back BPMN; open it in Camunda Modeler (or the in-page preview) — a clean, laid-out diagram.
3. Point out the Zeebe job type / FEEL condition (it's *executable*, not just a picture).
4. Refine with one sentence ("add a 2-day escalation") and regenerate.
5. Click/POST **Deploy** → show the process running in Camunda **Operate**.
