# bpmn-dsl-kotlin

A type-safe Kotlin internal DSL that authors BPMN 2.0 collaborations:
declarations compile to an immutable AST, get validated, laid out, and
serialized to BPMN semantic XML + BPMN DI that renders in bpmn-js-class viewers
and deploys the executable participant to Flowable.

**Supporting material for the o612 tutorial series**
[Building a Type-Safe Kotlin DSL for BPMN 2.0](../../o612/src/content/tutorials/series/kotlin-bpmn-dsl.json)
(chapters: `o612/src/content/tutorials/chapters/kotlin-bpmn-dsl/`).
The running example is a two-pool enterprise procurement collaboration
(buyer ↔ supplier) described in Part 4.

## Status

**All ten phases implemented and green.** Nothing committed.

- `./gradlew build` — all 7 modules, 19 tests, 0 failures
- `./gradlew :bpmn-example:run` → `bpmn-example/build/procurement.bpmn`
  (55 KB, zero validation diagnostics)
- `node scripts/render-verify.mjs` — bpmn-moddle parses with 0 warnings;
  bpmn-js renders 153 elements / ~147 KB SVG with 0 warnings in headless
  Chromium (`build/procurement.png`)

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `bpmn-model` | Immutable AST + `DiagramLayout`/`Bounds`/`Point` | — |
| `bpmn-dsl` | `bpmnCollaboration`, `@BpmnDsl` builders | `bpmn-model` |
| `bpmn-validation` | `BpmnValidator`, `Diagnostic`, `DiagnosticCodes` | `bpmn-model` |
| `bpmn-layout` | `DiagramLayoutEngine`, `LayeredLayoutEngine` | `bpmn-model` |
| `bpmn-xml` | `BpmnXmlWriter`, `EngineExtensionSerializer`, `BpmnNs` | `bpmn-model` |
| `bpmn-flowable` | `FlowableExtensionSerializer` | `bpmn-xml` |
| `bpmn-example` | `enterpriseProcurementCollaboration()`, `Main.kt`, `Deploy.kt` | all |

The library proper has **zero third-party runtime dependencies** — XML
serialization uses JDK StAX. `bpmn-js`/`bpmn-moddle`/Playwright are
verification tooling only (`package.json`).

## Usage

```kotlin
val c = enterpriseProcurementCollaboration()                    // DSL → AST
val diags = BpmnValidator().validate(c)                         // semantic checks
val layout = LayeredLayoutEngine().layout(c)                    // → BPMN DI
val xml = BpmnXmlWriter(FlowableExtensionSerializer)            // → BPMN 2.0 XML
    .writeToString(c, layout)
```

Author a new collaboration with `bpmnCollaboration("id") { participant("p") {
executable = true; process("proc") { lane("l") { …nodes… } …flows… } } }` —
see `bpmn-example/…/Procurement.kt` for the complete pattern.

## API stability contract

- **AST = stable layer.** A new BPMN element is a `FlowNode` subtype + a
  builder + a writer `when` arm + a `nodeSize` case — all additive. Renaming
  or removing a type is a major-version break.
- **DSL = public API.** New builder functions are additive; changing existing
  signatures breaks consumers.
- **Element ids are schema.** `sourceRef`/`targetRef`/`defaultFlow`/
  `attachedToRef`/`processRef`/`messageRef`/DI `bpmnElement` all reference
  ids — renaming an id is a schema change, not a refactor. Version bumps get
  a new collaboration id or a documented migration.

## Security boundary

`conditionalFlow` and activity `metadata` accept free-form strings that land
in executable XML — an EL-injection sink if the DSL input is untrusted. This
DSL consumes *trusted programs written by the team*; untrusted authoring
requires a constrained schema + validated compilation step, never raw strings
into `Expression` bodies.

