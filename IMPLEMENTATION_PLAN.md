# AI BPMN Generator — Implementation Plan

> Convert natural-language business-process descriptions into **executable BPMN 2.0 workflows compatible with Camunda 8** (Zeebe).
>
> Status: **Planning / architecture only** — no code in this document.
>
> **Stack:** Java 21 · Spring Boot 3.5 · Maven · Camunda 8 Java APIs · Jackson. Single Maven module, kept deliberately small for a hackathon.

---

## 1. Goals & Non-Goals

### Goals
- Take a free-text description of a business process and produce a **valid Camunda 8 BPMN file that opens and edits cleanly in Camunda Modeler**. This is the primary goal.
- Use a clean, testable pipeline: **NL → JSON IR → deterministic BPMN generation → validation before output**.
- Make the output easy to verify (download `.bpmn`, open in Camunda Modeler, optional in-page preview).

### Non-Goals (for the hackathon)
- **Deploying to / running on a live Camunda 8 cluster (Zeebe) and viewing instances in Operate.** Explicitly out of scope for the MVP — deferred to *Future work* (§12). The MVP produces a file, it does not run it.
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
  NL text ──LLM──▶ IR (JSON) ──schema-validate──▶ IR→BPMN compiler ──▶ BPMN + DI ──model-validate──▶ output (.bpmn)
  └──────────────────────────────┘   │  (Camunda zeebe-bpmn-model fluent builder, auto-layout) │
                                      └──────────────────────────────────────────────────────┘
```

> **Why this matters for the Java choice:** the Camunda BPMN model builder (`io.camunda:zeebe-bpmn-model`) is a *fluent Java API* that both knows the Zeebe extension elements **and auto-generates the diagram coordinates (DI)** as you build. That single library replaces what needed three separate JS libraries (`bpmn-moddle` + `zeebe-bpmn-moddle` + `bpmn-auto-layout`) in the earlier draft — a strong justification for the Java stack and a big simplification.
>
> **✅ Verified (spike, lib v8.5.6):** building `start → userTask → serviceTask().zeebeJobType(...) → endEvent` and calling `Bpmn.convertToString(model)` produced a complete `<bpmndi:BPMNDiagram>` with `dc:Bounds` (x/y/width/height) for every shape and `di:waypoint`s for every edge — **no manual layout code required**. (The Zeebe extension namespace is emitted with an auto prefix like `ns0:` rather than `zeebe:`; this is valid because Modeler resolves by namespace URI, and the prefix can be pinned cosmetically if desired.) No fallback layout library is needed.

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
   │   (RestClient to   (Jackson +          (zeebe-bpmn-model    (Bpmn.validate    │
   │    OpenAI/Anthropic json-schema-        fluent builder +     Model — no live  │
   │    JSON output)     validator)          Zeebe extensions,    cluster needed)  │
   │                                         auto DI/layout)                   │  │
   │                                                                          │  │
   └──────────────────────────────────────────────────────────────────────┼──┘
            │ returns BPMN XML (.bpmn)                                       │
            ▼                                                                │
   ┌─────────────────────────────────────────────┐  ◀──────────────────────┘
   │ Output: download .bpmn / optional in-page     │
   │ bpmn-js preview → open & edit in Camunda      │
   │ Modeler                                        │
   └─────────────────────────────────────────────┘

   (Live deploy to a Camunda 8 cluster + Operate = Future work, see §12 — not in MVP.)
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
| Tests | **JUnit 5** + Spring Boot Test | Unit-test `IrToBpmnCompiler` (golden IR → expected BPMN) independent of the LLM. |
| UI (optional, not a framework) | A **single static `index.html`** served from `src/main/resources/static/`, using **bpmn-js via CDN** to preview the returned XML | Visual proof for the demo with **no build step, no React, no monorepo**. Entirely optional — MVP works headless (REST + downloadable `.bpmn`). |

> **Removed from the previous draft** (per the simplification request): React, pnpm monorepo, Fastify/Node API, `bpmn-moddle`/`zeebe-bpmn-moddle`, `bpmn-auto-layout`, Ajv, `bpmnlint`. Their responsibilities are now covered by Java equivalents above (notably `zeebe-bpmn-model` for build+layout, `json-schema-validator` for IR validation). Live-deploy dependencies (`spring-boot-starter-camunda-sdk` / `zeebe-client-java`, Docker Camunda 8 Run) are deferred to *Future work* (§12) and are **not** MVP dependencies.

---

## 4. Proposed Project Structure

A single standard Maven + Spring Boot module. (Tree is the *target*; only scaffolding is created in Phase 0.)

```
ai-bpmn-generator/
├── IMPLEMENTATION_PLAN.md
├── README.md
├── pom.xml
├── .env.example  (or application-local.yaml)   # LLM key/config
│
└── src/
    ├── main/
    │   ├── java/com/example/bpmngen/
    │   │   ├── AiBpmnGeneratorApplication.java
    │   │   ├── api/
    │   │   │   ├── GenerateController.java      # POST /api/generate (the only endpoint)
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
    │   │   └── bpmn/
    │   │       ├── IrToBpmnCompiler.java        # zeebe-bpmn-model fluent builder
    │   │       ├── ZeebeExtensions.java         # task defs, io mappings, conditions
    │   │       └── BpmnValidator.java           # Bpmn.validateModel (offline)
    │   │   (deploy/ CamundaDeployService.java → Future work, §12 — not in MVP)
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
| 3 | **LlmClient** (`OpenAiLlmClient`) | Call the LLM with system prompt + few-shot, request JSON output, return raw IR JSON. | NL description → IR JSON string |
| 4 | **IrValidator** | Deserialize with Jackson; validate against `ir-schema.json`; produce precise error list for repair. | IR JSON → `ProcessIr` (or errors) |
| 5 | **IrToBpmnCompiler** | Deterministically build BPMN from `ProcessIr` using the `zeebe-bpmn-model` fluent builder; the builder auto-generates DI/layout. | `ProcessIr` → `BpmnModelInstance` → XML |
| 6 | **ZeebeExtensions** | Attach Zeebe specifics (service-task job type, user-task assignment, FEEL conditions, I/O mappings). | model + IR detail → enriched model |
| 7 | **BpmnValidator** | Validate the model offline with `Bpmn.validateModel` (well-formedness, references) — no running cluster needed. | BPMN → pass/fail + report |
| 8 | **ProcessIr / ElementType** | Single source of truth for the IR contract; the `ElementType` enum encodes exactly the Camunda-8 subset we support per phase. | — |

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
6. BpmnValidator: Bpmn.validateModel(...) (offline; no running cluster)
7. Serialize model → XML
8. Response: the `.bpmn` file (XML) → client downloads it and opens it in Camunda Modeler
```

> **The MVP exposes exactly one endpoint: `POST /api/generate`.** Conversational refinement / iterative editing (`/api/refine`) and live deployment (`/api/deploy` → Zeebe → Operate) are both **Future work** (§12), not part of the MVP data flow.

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
- The single `POST /api/generate` endpoint: NL description in → `.bpmn` file out (downloadable).
- JUnit golden test: `ProcessIr` → expected BPMN; `Bpmn.validateModel` passes.
- **Exit / demo:** type a linear process, download the `.bpmn`, open it cleanly in Camunda Modeler — the full end-to-end flow.

### Phase 2 — Branching & richer semantics
- Add to IR/compiler: `exclusiveGateway` + `parallelGateway`, **FEEL conditions**, default flows, user-task assignment, basic I/O mappings.
- Add timer & message events (escalations).
- **Repair loop** on both IR-schema and model-validation errors.
- **Exit:** branching processes generate and validate clean, still through the same single `/api/generate` endpoint.

### Phase 3 — Polish (if time remains)
- Optional `static/index.html` bpmn-js preview so judges see the diagram in-browser before opening Modeler.
- More few-shot examples; ambiguity handling (clarifying questions); optional generated Camunda Forms.
- (Conversational refinement / `/api/refine` lives in *Future work*, §12.)

> **Hackathon priority order:** Phase 1 (must-have: NL → valid `.bpmn` openable in Modeler) → Phase 2 (gateways/conditions/events make it impressive) → Phase 3 polish. Live cluster deployment is explicitly *Future work* (§12).

---

## 8. Validation Strategy (defense in depth, cheapest first)

1. **IR JSON Schema** (`json-schema-validator`) — structural correctness before any XML exists.
2. **Model validation** (`Bpmn.validateModel`) — well-formed references/structure from the builder, fully offline.
3. **Open in Camunda Modeler** — the practical acceptance test for the MVP: the file imports and is editable without errors.

Any failing gate (1–2) can trigger a **bounded LLM repair pass** (feed the precise error back). Cap retries (e.g. ≤2) for latency/cost and to avoid loops.

> Note: the JS-era `bpmnlint`/camunda-compat plugin has no direct Maven equivalent; in this stack the confidence comes from the `zeebe-bpmn-model` builder (which only exposes Zeebe-valid constructs) plus `Bpmn.validateModel`. A real Zeebe deploy as an authoritative gate is *Future work* (§12).

---

## 9. Risks, Mitigations & Assumptions

| Risk | Impact | Mitigation |
|---|---|---|
| LLM emits invalid/hallucinated BPMN | Broken demo | IR indirection + deterministic compiler; LLM never writes XML. |
| LLM produces structurally invalid IR | Pipeline fails | JSON output + `json-schema-validator` + bounded repair loop. |
| Auto-layout from `zeebe-bpmn-model` looks rough on complex graphs | Ugly diagram | Acceptable for MVP; keep Phase-1 graphs simple; manual tidy in Modeler if needed. |
| Zeebe extension coverage gaps in the builder | Some constructs not expressible fluently | Fall back to adding raw extension elements via the model API; constrain `ElementType` to what we can emit. |
| FEEL expressions wrong | Misrouted gateways; Modeler may warn | Few-shot FEEL examples; keep Phase-1 condition-free; rely on Modeler import for sanity. |
| No native LLM "structured output" in Java | Malformed JSON | Use provider JSON mode + schema validation + repair; optionally adopt `langchain4j` later. |
| LLM latency/cost | Slow demo | Small model for IR, cap tokens, bounded retries. |
| Scope creep into full BPMN | Nothing finished | Cap supported `ElementType`s per phase. |
| Secret management (LLM/Zeebe) | Blocked / leaked keys | Externalized config (`application-local.yaml` / env vars), never commit secrets. |

### Assumptions
- An LLM API key (OpenAI and/or Anthropic) is available via env/config.
- Target is **Camunda 8 (Zeebe)** dialect of BPMN, not Camunda 7.
- MVP success = the generated `.bpmn` opens and edits cleanly in Camunda Modeler (Zeebe-compatible markup); actually running it on a cluster is out of scope.
- Java 21 + Maven available; team is comfortable with Spring Boot.
- English-language input for the hackathon.

---

## 10. Open Questions
1. LLM provider & model preference (OpenAI vs Anthropic) and budget?
2. Do we need the optional in-page bpmn-js preview, or is downloadable `.bpmn` + Camunda Modeler enough for the demo?
3. Which Camunda Modeler do we target for the demo: **Desktop** or **Web** Modeler?
4. Required element coverage for judging (just tasks/gateways, or events/forms/sub-processes too)?
5. Any non-functional limits for `POST /api/generate` (max description length, request timeout)?

---

## 11. Suggested Demo Script
1. Paste a real-world process description in plain English (curl or the optional page).
2. Get back BPMN; open it in **Camunda Modeler** (or the in-page preview) — a clean, laid-out diagram.
3. Point out the Zeebe job type / FEEL condition in Modeler — it's valid Camunda 8 markup, **editable**, not just a picture.
4. Make a small manual tweak in Modeler to show the file is fully editable — the deliverable is a real, usable BPMN file.
5. (To regenerate, just submit a new description to `POST /api/generate` — there is no in-app refine in the MVP.)

---

## 12. Future Work (out of scope for the hackathon MVP)
- **Conversational refinement / iterative editing (`POST /api/refine`).** Accept `{ currentIr, instruction }`, have the LLM mutate the existing IR, and re-run the same validate → compile → validate pipeline to return updated BPMN — enabling "add a 2-day escalation"-style follow-ups without re-describing the whole process.
- **Deploy to a running Camunda 8 cluster + run instances in Operate.** Add `io.camunda:spring-boot-starter-camunda-sdk` (`CamundaClient`) and a `CamundaDeployService`; provide a local cluster via `docker-compose` (Camunda 8 Run: Zeebe + Operate + Tasklist) or Camunda 8 SaaS. Flow: `POST /api/deploy { bpmnXml }` → `newDeployResourceCommand().addProcessModel(...)` → optional `createProcessInstance` → Operate link. Adds a real Zeebe deploy as an authoritative validation gate.
- Job-worker / connector implementations so generated service tasks actually execute.
- Generated Camunda Forms for user tasks; richer element coverage (sub-processes, events).
