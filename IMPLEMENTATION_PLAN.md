# AI BPMN Generator — Implementation Plan

> Convert natural-language business-process descriptions into **executable BPMN 2.0 workflows compatible with Camunda 8** (Zeebe).
>
> Status: **Planning / architecture only** — no code in this document. This plan is optimized for a hackathon: it front-loads a thin, demoable MVP and defers the harder pieces to later phases.

---

## 1. Goals & Non-Goals

### Goals
- Take a free-text description of a business process and produce **valid, deployable Camunda 8 BPMN XML**.
- Render the generated diagram visually and let the user refine it (re-prompt or manual edit).
- Validate output so the demo never shows a diagram that Zeebe would reject.
- (Stretch) Deploy the workflow to a running Camunda 8 cluster and start an instance live during the demo.

### Non-Goals (for the hackathon)
- Full BPMN 2.0 coverage. Camunda 8 only executes a subset; we target that subset only.
- Production auth / multi-tenancy / persistence of user accounts.
- Building a full BPMN modeler from scratch (we reuse `bpmn-js`).
- Generating connector implementations / job workers (we generate the *model*, not the runtime workers).

---

## 2. Key Domain Constraints (why the design looks the way it does)

These constraints drive every architectural decision:

1. **Camunda 8 executes a *subset* of BPMN 2.0.** Elements must carry Zeebe-specific extension attributes, e.g. a Service Task needs `zeebe:taskDefinition[type]`, gateways need FEEL conditions on outgoing flows, etc. Generic BPMN is not enough.
2. **BPMN XML has two coupled parts:** the *semantic* model (`bpmn:process` with elements/flows) **and** the *diagram interchange* (`bpmndi:BPMNDiagram` with x/y/width/height for every shape and edge). An LLM is good at the former and bad at the latter (it cannot reliably invent non-overlapping coordinates).
3. **LLMs are unreliable at emitting large, schema-perfect XML directly.** Hand them a 200-line XML target and they drift: invalid namespaces, missing `id`s, dangling flow references.
4. **Conditions/expressions use FEEL**, not Java/JS. Gateway routing and I/O mappings must be FEEL.

**Consequence — the central design bet:** the LLM does **not** generate BPMN XML directly. Instead it generates a small, strict **Intermediate Representation (IR)** in JSON (validated against a JSON Schema). Deterministic code then compiles IR → BPMN XML, and a deterministic auto-layout step produces the diagram coordinates. This isolates the "creative/fuzzy" step (NL → structured intent) from the "must-be-correct" step (structure → executable XML).

```
        fuzzy / probabilistic                 deterministic / verifiable
  ┌──────────────────────────────┐   ┌───────────────────────────────────────┐
  NL text ──LLM──▶ IR (JSON) ──validate──▶ BPMN semantic XML ──auto-layout──▶ BPMN + DI ──lint/deploy-check──▶ output
  └──────────────────────────────┘   └───────────────────────────────────────┘
```

---

## 3. Overall Architecture

### 3.1 High-level component view

```
                          ┌────────────────────────────────────────────────────────┐
                          │                        Frontend (Web)                    │
                          │  React + Vite                                            │
                          │  ┌───────────────┐   ┌───────────────────────────────┐  │
                          │  │ Prompt / Chat │   │ bpmn-js viewer + properties   │  │
                          │  │  panel        │   │ panel (render, edit, download)│  │
                          │  └───────┬───────┘   └───────────────▲───────────────┘  │
                          └──────────┼───────────────────────────┼──────────────────┘
                                     │ POST /api/generate         │ BPMN XML + warnings
                                     ▼                            │
   ┌─────────────────────────────────────────────────────────────────────────────────┐
   │                               Backend API (Node/TS)                                │
   │                                                                                     │
   │   ┌───────────────┐   ┌──────────────┐   ┌───────────────┐   ┌──────────────────┐ │
   │   │ Orchestrator  │──▶│ LLM Adapter  │──▶│ IR Validator  │──▶│ BPMN Compiler    │ │
   │   │ (request flow)│   │ (structured  │   │ + Repair loop │   │ (IR → BPMN XML   │ │
   │   │               │   │  output)     │   │               │   │  w/ zeebe ext.)  │ │
   │   └───────┬───────┘   └──────────────┘   └───────────────┘   └────────┬─────────┘ │
   │           │                                                            │           │
   │           │                ┌──────────────┐   ┌───────────────┐       ▼           │
   │           └───────────────▶│ Validator     │◀──│ Auto-layout   │◀── BPMN (no DI)  │
   │                            │ (bpmnlint +   │   │ (DI coords)   │                   │
   │                            │  zeebe rules) │   └───────────────┘                   │
   │                            └──────┬───────┘                                        │
   │                                   │  valid BPMN + lint report                      │
   │                                   ▼                                                │
   │                            ┌──────────────────────────┐                           │
   │                            │ Deploy Connector (stretch)│──▶ Camunda 8 / Zeebe      │
   │                            └──────────────────────────┘                           │
   └─────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
                            ┌─────────────────────────────────────┐
                            │ Camunda 8 (SaaS or self-managed)     │
                            │  Zeebe gateway · Operate · Tasklist   │
                            └─────────────────────────────────────┘
```

### 3.2 Recommended tech stack & rationale

| Layer | Choice | Why |
|---|---|---|
| Language | **TypeScript (Node 20)** end-to-end | The *canonical* BPMN/Camunda tooling (`bpmn-moddle`, `zeebe-bpmn-moddle`, `bpmnlint`, `bpmn-auto-layout`, `bpmn-js`, `@camunda8/sdk`) is JS/TS. One language = less glue, faster hackathon iteration. |
| Backend | **Fastify** (or Express) | Lightweight HTTP API; Fastify has first-class JSON-schema validation we already need for the IR. |
| LLM access | **Provider-agnostic adapter** (OpenAI + Anthropic), using **structured outputs / JSON-schema-constrained generation** | Keeps us from getting locked in; structured outputs dramatically reduce malformed IR. |
| IR validation | **Ajv** (JSON Schema) | Fast, gives precise error paths to feed back into a repair prompt. |
| BPMN build | **bpmn-moddle + zeebe-bpmn-moddle** | Programmatically build a guaranteed well-formed moddle tree and serialize to XML, instead of string-templating XML. |
| Layout | **bpmn-auto-layout** | Generates DI (coordinates) deterministically from the semantic model. |
| Lint | **bpmnlint + `bpmnlint-plugin-camunda-compat`** | Catches "this won't deploy to Camunda 8" issues before we ever call Zeebe. |
| Frontend | **React + Vite + bpmn-js + @bpmn-io/properties-panel** | bpmn-js is the same renderer Camunda Modeler uses; properties panel enables manual edits. |
| Deploy (stretch) | **@camunda8/sdk** (Zeebe gRPC / Camunda 8 REST) | Official SDK to deploy resources and start instances. |
| Monorepo | **pnpm workspaces** | Share the IR types + BPMN core between `web` and `api`. |
| Runtime for C8 (local) | **Camunda 8 Run** / docker-compose | One-command local cluster for the deploy demo. |

> Alternative considered: **Python (FastAPI) backend.** Rejected for the hackathon because BPMN serialization, auto-layout, and linting would require either reimplementation or shelling out to Node — strictly more work. If the team is Python-only, the fallback is a thin Node "bpmn-service" microservice that the Python API calls.

---

## 4. Proposed Project Structure

A pnpm monorepo. (Tree below is the *target*; only scaffolding is created in Phase 0.)

```
ai-bpmn-generator/
├── IMPLEMENTATION_PLAN.md
├── README.md
├── package.json                  # workspace root
├── pnpm-workspace.yaml
├── docker-compose.yml            # Camunda 8 Run (Phase 3 / stretch)
├── .env.example                  # LLM keys, Zeebe connection
│
├── packages/
│   ├── ir/                       # shared IR contract
│   │   ├── src/
│   │   │   ├── schema.ts         # JSON Schema for the IR
│   │   │   ├── types.ts          # TS types generated from / matching schema
│   │   │   └── examples/         # golden IR examples for few-shot + tests
│   │   └── package.json
│   │
│   └── bpmn-core/                # IR → BPMN, layout, validation (pure, testable)
│       ├── src/
│       │   ├── compiler/         # IR → bpmn-moddle tree → XML
│       │   │   ├── elements/     # builders: task, gateway, event, flow...
│       │   │   └── zeebeExt.ts   # zeebe extension attributes
│       │   ├── layout/           # auto-layout wrapper (DI)
│       │   ├── validate/         # bpmnlint config + deploy-readiness checks
│       │   └── index.ts
│       ├── test/                 # snapshot tests: IR → XML, lint passes
│       └── package.json
│
├── apps/
│   ├── api/                      # backend orchestration
│   │   ├── src/
│   │   │   ├── server.ts
│   │   │   ├── routes/
│   │   │   │   ├── generate.ts   # POST /api/generate  (NL → BPMN)
│   │   │   │   ├── refine.ts     # POST /api/refine     (BPMN + NL → BPMN)
│   │   │   │   └── deploy.ts     # POST /api/deploy     (stretch)
│   │   │   ├── llm/
│   │   │   │   ├── adapter.ts    # provider-agnostic interface
│   │   │   │   ├── openai.ts
│   │   │   │   ├── anthropic.ts
│   │   │   │   └── prompts/      # system prompt, few-shot, repair prompt
│   │   │   ├── orchestrator.ts   # generate → validate → compile → layout → lint
│   │   │   └── camunda/deploy.ts # @camunda8/sdk wrapper (stretch)
│   │   └── package.json
│   │
│   └── web/                      # frontend
│       ├── src/
│       │   ├── App.tsx
│       │   ├── components/
│       │   │   ├── PromptPanel.tsx
│       │   │   ├── BpmnCanvas.tsx     # bpmn-js wrapper
│       │   │   ├── PropertiesPanel.tsx
│       │   │   └── ValidationReport.tsx
│       │   └── api/client.ts
│       └── package.json
│
└── .github/workflows/ci.yml      # typecheck, lint, test
```

---

## 5. Main Components & Responsibilities

| # | Component | Responsibility | Key inputs → outputs |
|---|---|---|---|
| 1 | **Frontend (web)** | Capture the NL prompt, render the BPMN diagram (`bpmn-js`), surface validation warnings, allow manual edits + download `.bpmn`. | NL text → calls API; BPMN XML → rendered diagram |
| 2 | **API / Orchestrator** | Coordinate the pipeline; own the request lifecycle, timeouts, error mapping. | HTTP request → BPMN XML + lint report |
| 3 | **LLM Adapter** | Provider-agnostic call that returns **IR JSON** via structured output; holds prompt templates + few-shot examples. | NL (+ optional existing IR) → IR JSON |
| 4 | **IR Validator + Repair loop** | Validate IR against JSON Schema; on failure, feed errors back to the LLM (bounded retries) to self-correct. | IR JSON → valid IR (or error) |
| 5 | **BPMN Compiler** | Deterministically build a `bpmn-moddle` tree from IR, attaching Zeebe extensions; serialize to XML (no DI yet). | valid IR → BPMN semantic XML |
| 6 | **Auto-layout** | Compute DI coordinates for all shapes/edges so the diagram renders cleanly. | BPMN (no DI) → BPMN (with DI) |
| 7 | **Validator (lint/deploy-readiness)** | Run `bpmnlint` with Camunda-compat rules; classify issues (error/warning); block invalid output. | BPMN XML → pass/fail + report |
| 8 | **Deploy Connector** *(stretch)* | Deploy resource to Zeebe and optionally start an instance; return process key / instance id. | BPMN XML → Camunda deployment result |
| 9 | **IR contract (`packages/ir`)** | Single source of truth for the schema/types shared across LLM, compiler, and tests. | — |

### 5.1 The Intermediate Representation (IR) — sketch

A compact, Camunda-8-constrained JSON. Example shape (illustrative, not final):

```jsonc
{
  "id": "invoice_approval",
  "name": "Invoice Approval",
  "elements": [
    { "id": "start",    "type": "startEvent",   "name": "Invoice received" },
    { "id": "review",   "type": "userTask",     "name": "Review invoice", "assignee": "=manager" },
    { "id": "gw_amount","type": "exclusiveGateway", "name": "Amount > 1000?" },
    { "id": "approve",  "type": "serviceTask",  "name": "Auto-approve", "taskDefinitionType": "auto-approve" },
    { "id": "end_ok",   "type": "endEvent",     "name": "Approved" },
    { "id": "end_no",   "type": "endEvent",     "name": "Rejected" }
  ],
  "flows": [
    { "from": "start",  "to": "review" },
    { "from": "review", "to": "gw_amount" },
    { "from": "gw_amount", "to": "approve", "condition": "=amount > 1000" },
    { "from": "gw_amount", "to": "end_no",  "isDefault": true },
    { "from": "approve", "to": "end_ok" }
  ]
}
```

Why an IR instead of raw XML:
- **Validatable**: a JSON Schema catches structural errors deterministically.
- **Repairable**: schema errors map cleanly to a follow-up "fix this" prompt.
- **Stable interface**: the compiler is unit-testable independent of the LLM.
- **Editable**: re-prompts mutate IR, not fragile XML strings.

The IR's allowed `type` enum is *exactly* the Camunda-8-supported element set we choose to support per phase — this is how we keep the LLM inside Zeebe's executable subset.

---

## 6. Data Flow

### 6.1 Generation (MVP path)

```
1. User types: "When an invoice arrives, a manager reviews it. If amount > 1000,
   auto-approve; otherwise reject."  ──▶ POST /api/generate
2. Orchestrator → LLM Adapter (system prompt + few-shot + JSON-schema constraint)
3. LLM returns IR (JSON)
4. IR Validator (Ajv):
      ✓ valid  → continue
      ✗ invalid → send errors back to LLM (repair), retry up to N times
5. BPMN Compiler: IR → bpmn-moddle tree (+ zeebe:* extensions) → semantic XML
6. Auto-layout: add BPMNDiagram / DI coordinates
7. Validator: bpmnlint (+ camunda-compat). Errors → (optionally) one repair pass; warnings pass through
8. Response: { bpmnXml, irUsed, lintReport } ──▶ Frontend
9. Frontend renders XML in bpmn-js; shows warnings; offers Download / Edit / Deploy
```

### 6.2 Refinement loop

```
User: "Add a 2-day timer escalation on the review task."
  ──▶ POST /api/refine { currentIr, instruction }
  ──▶ LLM mutates IR ──▶ (same validate → compile → layout → lint pipeline) ──▶ updated BPMN
```

### 6.3 Deploy (stretch)

```
User clicks Deploy ──▶ POST /api/deploy { bpmnXml }
  ──▶ @camunda8/sdk deployResource ──▶ Zeebe ──▶ returns processDefinitionKey
  ──▶ (optional) createProcessInstance ──▶ view in Operate
```

---

## 7. Implementation Phases

### Phase 0 — Scaffolding (hour 0–2)
- Monorepo (pnpm), `packages/ir`, `packages/bpmn-core`, `apps/api`, `apps/web`.
- CI: typecheck + lint + test. `.env.example`. Pre-commit hooks (lint/format).
- **Exit:** `pnpm dev` runs an empty web + api; a hardcoded BPMN string renders in `bpmn-js`.

### Phase 1 — MVP: NL → valid linear BPMN (the demo backbone)
Scope of elements: `startEvent`, `endEvent`, `userTask`, `serviceTask` (with `zeebe:taskDefinition`), sequence flows. **No gateways yet.**
- IR schema v1 + Ajv validation.
- LLM adapter (one provider) with structured output + 2–3 few-shot examples.
- BPMN compiler for the v1 element set + auto-layout.
- `POST /api/generate`; frontend prompt panel + canvas + Download `.bpmn`.
- Snapshot tests: golden IR → expected XML; lint passes.
- **Exit / demo:** type a linear process, see a correct rendered diagram, download a `.bpmn` that opens cleanly in Camunda Desktop Modeler.

### Phase 2 — Branching & richer semantics
- Add to IR/compiler: `exclusiveGateway` + `parallelGateway`, **FEEL conditions**, default flows, `userTask` assignment, basic `zeebe:ioMapping` (input/output variables).
- Add events: timer & message intermediate/boundary events (escalations).
- `bpmnlint` + camunda-compat wired in; **repair loop** on both IR-schema and lint errors.
- **Exit:** branching processes with conditions deploy-check clean; refinement endpoint works.

### Phase 3 — Live Camunda 8 deployment (high-impact demo moment)
- `docker-compose` Camunda 8 Run (Zeebe + Operate + Tasklist) **or** Camunda 8 SaaS creds.
- `@camunda8/sdk` deploy + start instance; surface process/instance ids and an Operate link.
- **Exit:** generate → deploy → start instance → show it running in Operate, all live.

### Phase 4 — Polish (if time remains)
- Iterative chat UX, manual edits via properties panel saved back to IR.
- Few-shot example library expansion; ambiguity handling (clarifying questions).
- Generated Camunda Forms for user tasks; nicer validation UI; export bundle.

> **Hackathon priority order:** Phase 1 (must-have demo) → Phase 3 deploy moment (wow factor) → Phase 2 richness. If time is tight, a polished Phase 1 + a single live deploy beats a half-working Phase 2.

---

## 8. Validation Strategy (defense in depth)

Three gates, cheapest first:
1. **IR JSON Schema (Ajv)** — structural correctness before any XML exists.
2. **bpmnlint + camunda-compat** — "will Camunda 8 accept this?" without a cluster.
3. **Zeebe deploy (dry/real)** — ground truth; the engine itself accepts or rejects.

Each gate that fails can trigger a **bounded LLM repair pass** (feed the precise error back). Bound retries (e.g. ≤2) to control latency/cost and avoid infinite loops.

---

## 9. Risks, Mitigations & Assumptions

### Risks → mitigations
| Risk | Impact | Mitigation |
|---|---|---|
| LLM emits invalid/hallucinated BPMN | Demo shows broken diagram | IR indirection + deterministic compiler; never let the LLM write XML. |
| LLM produces structurally invalid IR | Pipeline fails | JSON-schema-constrained output + Ajv + bounded repair loop. |
| Diagram has no/overlapping coordinates | Unreadable diagram | `bpmn-auto-layout` generates DI deterministically. |
| Generated model not Camunda-8 executable | "Works as a picture, not in Zeebe" | camunda-compat lint + optional real deploy check; constrain IR `type` enum to the supported subset. |
| FEEL expressions wrong | Gateways misroute / deploy fails | Few-shot FEEL examples; validate via lint/deploy; keep Phase-1 condition-free. |
| LLM latency/cost | Slow demo, budget overrun | Cache, small models for IR, cap tokens, bound retries. |
| Ambiguous NL input | Wrong process | Phase 4 clarifying-questions; sensible defaults; show IR so user can correct. |
| Camunda 8 env setup eats hackathon time | No live deploy | Make deploy a *stretch*; MVP only needs downloadable `.bpmn` + Desktop Modeler. |
| Scope creep into full BPMN | Nothing finished | Phase the element set; explicitly cap supported types per phase. |
| Secrets (LLM/Zeebe) management | Blocked / leaked keys | `.env` + `.env.example`, never commit secrets; document required vars. |

### Assumptions
- An LLM API key (OpenAI and/or Anthropic) will be available via env vars.
- Target is **Camunda 8 (Zeebe)**, not Camunda 7 (different execution model & extensions).
- "Executable" = deploys to Zeebe and can start an instance; job-worker *implementations* for service tasks are out of scope (we emit task types, not workers).
- Team is comfortable with TypeScript (drives the stack choice).
- For the live-deploy stretch, either Docker is available locally or Camunda 8 SaaS credentials are provided.
- English-language input for the hackathon (multilingual is a later concern).

---

## 10. Open Questions (to confirm before/early in build)
1. LLM provider & model preference (OpenAI vs Anthropic) and budget?
2. Camunda 8 target for the live demo: **SaaS** (need credentials) or **self-managed** (Docker)?
3. How much manual editing matters vs pure NL generation (affects properties-panel investment)?
4. Required element coverage for judging (just tasks/gateways, or events/forms/sub-processes too)?
5. Single-shot generation or conversational refinement as the headline UX?

---

## 11. Suggested Demo Script (what we show the judges)
1. Paste a real-world process description in plain English.
2. Watch a clean, laid-out BPMN diagram appear in seconds.
3. Point out the Zeebe extension attributes / FEEL condition (it's *executable*, not just a picture).
4. Refine with one sentence ("add a 2-day escalation") and see the diagram update.
5. Click **Deploy** → show the process running in Camunda **Operate**.
6. Download the `.bpmn` and open it in Camunda Desktop Modeler to prove portability.
