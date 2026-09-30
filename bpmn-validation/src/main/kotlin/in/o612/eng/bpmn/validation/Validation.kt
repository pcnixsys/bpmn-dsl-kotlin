package `in`.o612.eng.bpmn.validation

import `in`.o612.eng.bpmn.model.ActivityNode
import `in`.o612.eng.bpmn.model.BoundaryEventNode
import `in`.o612.eng.bpmn.model.CollaborationDefinition
import `in`.o612.eng.bpmn.model.DiagramLayout
import `in`.o612.eng.bpmn.model.EmbeddedSubprocess
import `in`.o612.eng.bpmn.model.EndEvent
import `in`.o612.eng.bpmn.model.ExclusiveGateway
import `in`.o612.eng.bpmn.model.FlowNode
import `in`.o612.eng.bpmn.model.GatewayNode
import `in`.o612.eng.bpmn.model.InclusiveGateway
import `in`.o612.eng.bpmn.model.ParallelGateway
import `in`.o612.eng.bpmn.model.ProcessDefinition
import `in`.o612.eng.bpmn.model.SequenceFlow
import `in`.o612.eng.bpmn.model.StartEvent

/**
 * Structured diagnostics — data, not exceptions. Every check produces a
 * `Diagnostic` with a stable machine-readable `code` (tests assert on codes,
 * CI can suppress a specific warning, docs can reference BPMN-FLOW-001 without
 * depending on prose).
 *
 * ERROR  = the output would be invalid or undeployable.
 * WARNING = structurally legal but suspicious (unreachable node, boundary
 *           event with no handler, laneless node).
 *
 * The validator collects *all* diagnostics and returns them in a deterministic
 * order — never throws on the first problem.
 *
 * Limit (stated, not hidden): this validates structure, references, scope,
 * direction, and lane/containment invariants — it does NOT simulate token
 * semantics. Proving an arbitrary process can't deadlock is a model-checking
 * problem, out of scope here.
 */

enum class Severity { ERROR, WARNING }

data class Diagnostic(
    /** Stable machine-readable code, e.g. "BPMN-FLOW-001". */
    val code: String,
    val severity: Severity,
    val message: String,
    /** Element the diagnostic is attached to. */
    val elementId: String? = null,
    /** Other elements involved, e.g. both ends of an unresolved reference. */
    val relatedIds: List<String> = emptyList(),
    /** Practical suggestion for fixing the problem. */
    val remediation: String? = null,
)

/** All diagnostic codes emitted by [BpmnValidator]. */
object DiagnosticCodes {
    // identifiers
    const val BLANK_ID = "BPMN-ID-001"
    const val DUPLICATE_ID = "BPMN-ID-002"

    // scope / flow semantics
    const val NO_START_EVENT = "BPMN-FLOW-001"
    const val NO_END_EVENT = "BPMN-FLOW-002"
    const val FLOW_ENDPOINT = "BPMN-FLOW-003"
    const val FLOW_DIRECTION = "BPMN-FLOW-004"
    const val UNREACHABLE_NODE = "BPMN-FLOW-005"
    const val DEAD_END_NODE = "BPMN-FLOW-006"

    // gateways / conditions
    const val DEFAULT_FLOW_INVALID = "BPMN-GW-001"
    const val MISSING_CONDITION = "BPMN-GW-002"
    const val CONDITION_FORBIDDEN = "BPMN-GW-003"

    // boundary events
    const val BOUNDARY_TARGET = "BPMN-BND-001"
    const val BOUNDARY_NO_OUTGOING = "BPMN-BND-002"

    // lanes
    const val LANE_REF_UNRESOLVED = "BPMN-LANE-001"
    const val LANE_DUPLICATE_MEMBER = "BPMN-LANE-002"
    const val LANELESS_NODE = "BPMN-LANE-003"

    // collaboration / messages
    const val MSG_REF_UNRESOLVED = "BPMN-MSG-001"
    const val MSG_FLOW_ENDPOINT = "BPMN-MSG-002"
    const val MSG_FLOW_SAME_POOL = "BPMN-MSG-003"
    const val MSG_FLOW_GATEWAY = "BPMN-MSG-004"
    const val NO_EXECUTABLE = "BPMN-COLLAB-001"
    const val PARTICIPANT_PROCESS_UNRESOLVED = "BPMN-COLLAB-002"

    // diagram interchange
    const val DI_MISSING_SHAPE = "BPMN-DI-001"
    const val DI_BAD_BOUNDS = "BPMN-DI-002"
    const val DI_LANE_OUTSIDE_POOL = "BPMN-DI-003"
    const val DI_NODE_OUTSIDE_LANE = "BPMN-DI-004"
    const val DI_BOUNDARY_DETACHED = "BPMN-DI-005"
    const val DI_MISSING_EDGE = "BPMN-DI-006"
}

class BpmnValidator {

    fun validate(collaboration: CollaborationDefinition): List<Diagnostic> {
        val d = mutableListOf<Diagnostic>()
        validateIdentifiers(collaboration, d)
        for (process in collaboration.processes) {
            validateProcess(collaboration, process, d)
        }
        validateCollaboration(collaboration, d)
        return d.sortedWith(DIAGNOSTIC_ORDER)
    }

    private fun validateIdentifiers(c: CollaborationDefinition, d: MutableList<Diagnostic>) {
        val seen = LinkedHashMap<String, String>()

        fun check(id: String, kind: String) {
            if (id.isBlank()) {
                d += Diagnostic(
                    DiagnosticCodes.BLANK_ID, Severity.ERROR,
                    "$kind has a blank id; BPMN ids are required XML IDs",
                    remediation = "Give the element a non-empty id.",
                )
                return
            }
            val previous = seen.putIfAbsent(id, kind)
            if (previous != null) {
                d += Diagnostic(
                    DiagnosticCodes.DUPLICATE_ID, Severity.ERROR,
                    "Duplicate id '$id' used by both $previous and $kind; " +
                        "BPMN ids are XML IDs and must be unique document-wide",
                    elementId = id,
                    remediation = "Rename one of the elements.",
                )
            }
        }

        check(c.id, "collaboration")
        c.participants.forEach { check(it.id, "participant") }
        c.processes.forEach { p ->
            check(p.id, "process")
            p.lanes.forEach { check(it.id, "lane") }
            p.allNodes().forEach { check(it.id, "flow node") }
            p.allSequenceFlows().forEach { check(it.id, "sequence flow") }
        }
        c.messages.forEach { check(it.id, "message") }
        c.messageFlows.forEach { check(it.id, "message flow") }
    }

    private fun validateProcess(
        c: CollaborationDefinition,
        p: ProcessDefinition,
        d: MutableList<Diagnostic>,
    ) {
        validateScope(p.id, p.nodes, p.sequenceFlows, d)
        validateLanes(p, d)
        // Embedded subprocesses are closed scopes with their own start/end
        // requirements — validate each interior independently.
        for (sub in p.nodes.filterIsInstance<EmbeddedSubprocess>()) {
            validateSubprocess(sub, d)
        }
    }

    private fun validateSubprocess(sub: EmbeddedSubprocess, d: MutableList<Diagnostic>) {
        validateScope(sub.id, sub.nodes, sub.sequenceFlows, d)
        for (nested in sub.nodes.filterIsInstance<EmbeddedSubprocess>()) {
            validateSubprocess(nested, d)
        }
    }

    private fun validateScope(
        scopeId: String,
        nodes: List<FlowNode>,
        flows: List<SequenceFlow>,
        d: MutableList<Diagnostic>,
    ) {
        val byId = nodes.associateBy { it.id }
        val outgoing = flows.groupBy { it.sourceRef }
        val incoming = flows.groupBy { it.targetRef }

        val hasStart = nodes.any { it is StartEvent }
        val hasEnd = nodes.any { it is EndEvent }
        if (!hasStart) {
            d += Diagnostic(
                DiagnosticCodes.NO_START_EVENT, Severity.ERROR,
                "Scope '$scopeId' has no start event; tokens cannot enter it",
                elementId = scopeId,
                remediation = "Add a startEvent to the scope.",
            )
        }
        if (!hasEnd) {
            d += Diagnostic(
                DiagnosticCodes.NO_END_EVENT, Severity.ERROR,
                "Scope '$scopeId' has no end event; tokens cannot leave it",
                elementId = scopeId,
                remediation = "Add an endEvent to the scope.",
            )
        }

        // Reference resolution — scope-local, so cross-scope smuggling fails:
        // a subprocess flow naming a parent node won't resolve in the
        // subprocess's own byId map, and vice versa.
        for (f in flows) {
            val srcOk = f.sourceRef in byId
            val tgtOk = f.targetRef in byId
            if (!srcOk || !tgtOk) {
                d += Diagnostic(
                    DiagnosticCodes.FLOW_ENDPOINT, Severity.ERROR,
                    "Sequence flow '${f.id}' has unresolved endpoint(s):" +
                        (if (srcOk) "" else " source '${f.sourceRef}'") +
                        (if (tgtOk) "" else " target '${f.targetRef}'") +
                        " — endpoints must be nodes in scope '$scopeId'",
                    elementId = f.id,
                    relatedIds = listOf(f.sourceRef, f.targetRef),
                    remediation = "Point both ends at nodes declared in this scope.",
                )
            }
        }

        // Direction rules
        for (n in nodes) {
            val out = outgoing[n.id].orEmpty()
            val inc = incoming[n.id].orEmpty()
            when (n) {
                is StartEvent ->
                    if (inc.isNotEmpty()) d += directionError(n.id, "start event", "incoming")
                is EndEvent ->
                    if (out.isNotEmpty()) d += directionError(n.id, "end event", "outgoing")
                is BoundaryEventNode -> {
                    if (inc.isNotEmpty()) d += directionError(n.id, "boundary event", "incoming")
                    if (out.isEmpty()) {
                        d += Diagnostic(
                            DiagnosticCodes.BOUNDARY_NO_OUTGOING, Severity.WARNING,
                            "Boundary event '${n.id}' has no outgoing sequence flow; " +
                                "it will fire and discard the token",
                            elementId = n.id,
                            remediation = "Route the boundary event to a handler node.",
                        )
                    }
                }
                else -> Unit
            }
        }

        // Boundary targets: the host must be an ActivityNode in this scope.
        for (b in nodes.filterIsInstance<BoundaryEventNode>()) {
            when (val host = byId[b.attachedToRef]) {
                null -> d += Diagnostic(
                    DiagnosticCodes.BOUNDARY_TARGET, Severity.ERROR,
                    "Boundary event '${b.id}' attaches to '${b.attachedToRef}', " +
                        "which does not exist in scope '$scopeId'",
                    elementId = b.id,
                    relatedIds = listOf(b.attachedToRef),
                    remediation = "Attach the boundary event to an activity in this scope.",
                )
                is ActivityNode -> Unit   // the only legal target
                else -> d += Diagnostic(
                    DiagnosticCodes.BOUNDARY_TARGET, Severity.ERROR,
                    "Boundary event '${b.id}' attaches to '${b.attachedToRef}', which is a " +
                        "${host::class.simpleName}; boundary events may only attach to " +
                        "activities (tasks and subprocesses)",
                    elementId = b.id,
                    relatedIds = listOf(b.attachedToRef),
                    remediation = "Attach the boundary event to an ActivityNode.",
                )
            }
        }

        // Condition legality — BPMN permits conditionExpression only on flows
        // leaving activities and exclusive/inclusive gateways. A conditioned
        // flow leaving a parallel gateway or an event is silently ignored by
        // engines: modeling intent that evaporates.
        for (f in flows) {
            if (f.condition == null) continue
            val src = byId[f.sourceRef] ?: continue   // unresolved already flagged
            val allowed = src is ActivityNode || src is ExclusiveGateway || src is InclusiveGateway
            if (!allowed) {
                d += Diagnostic(
                    DiagnosticCodes.CONDITION_FORBIDDEN, Severity.ERROR,
                    "Sequence flow '${f.id}' has a condition but leaves " +
                        "'${f.sourceRef}' (${src::class.simpleName}); conditions are " +
                        "only legal on flows leaving activities and exclusive/inclusive gateways",
                    elementId = f.id,
                    relatedIds = listOf(f.sourceRef),
                    remediation = "Move the decision into an exclusive or inclusive gateway, " +
                        "or drop the condition.",
                )
            }
        }

        // Gateway checks
        for (g in nodes.filterIsInstance<GatewayNode>()) {
            val out = outgoing[g.id].orEmpty()
            if (g is ExclusiveGateway || g is InclusiveGateway) {
                g.defaultFlow?.let { def ->
                    if (out.none { it.id == def }) {
                        d += Diagnostic(
                            DiagnosticCodes.DEFAULT_FLOW_INVALID, Severity.ERROR,
                            "Gateway '${g.id}' declares default flow '$def', which is not " +
                                "one of its outgoing flows",
                            elementId = g.id,
                            relatedIds = listOf(def),
                            remediation = "Point defaultFlow at an outgoing sequence flow id.",
                        )
                    }
                }
                if (out.size > 1) {
                    val undecided = out.filter { it.condition == null && it.id != g.defaultFlow }
                    if (undecided.isNotEmpty()) {
                        d += Diagnostic(
                            DiagnosticCodes.MISSING_CONDITION, Severity.WARNING,
                            "Gateway '${g.id}' is a split with unconditional non-default " +
                                "outgoing flow(s) ${undecided.map { it.id }} — they are always " +
                                "taken, which is probably not what the conditions intended",
                            elementId = g.id,
                            relatedIds = undecided.map { it.id },
                            remediation = "Give each non-default branch a condition, or mark " +
                                "one as defaultFlow.",
                        )
                    }
                }
            }
        }

        // Reachability — two passes: start events seed the walk, then boundary
        // events whose host is reachable join (they are entered via
        // attachment, not sequence flow). Skip when the scope has no start —
        // NO_START_EVENT already says everything this would.
        if (hasStart) {
            val reachable = reachableFrom(nodes, outgoing)
            for (n in nodes) {
                if (n.id !in reachable) {
                    d += Diagnostic(
                        DiagnosticCodes.UNREACHABLE_NODE, Severity.WARNING,
                        "Node '${n.id}' is unreachable from any start event in scope '$scopeId'",
                        elementId = n.id,
                        remediation = "Add a sequence flow reaching the node, or remove it.",
                    )
                }
            }
        }

        // Dead end — reverse walk from end events through incoming edges.
        // Boundary events participate naturally: they reach an end iff their
        // outgoing flows do. Boundary events with no outgoing are already
        // covered by BOUNDARY_NO_OUTGOING — don't double-report.
        if (hasEnd) {
            val toEnd = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            nodes.filterIsInstance<EndEvent>().forEach { stack.addLast(it.id) }
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!toEnd.add(cur)) continue
                incoming[cur].orEmpty().forEach { stack.addLast(it.sourceRef) }
            }
            for (n in nodes) {
                if (n is BoundaryEventNode && outgoing[n.id].orEmpty().isEmpty()) continue
                if (n.id !in toEnd) {
                    d += Diagnostic(
                        DiagnosticCodes.DEAD_END_NODE, Severity.WARNING,
                        "Node '${n.id}' can never reach an end event in scope '$scopeId'",
                        elementId = n.id,
                        remediation = "Give the node an outgoing path to an end event.",
                    )
                }
            }
        }
    }

    /**
     * Forward reachability seeded from start events, then from boundary events
     * whose host activity is reachable (they have no incoming edges by design —
     * the host's reachability is theirs).
     */
    private fun reachableFrom(
        nodes: List<FlowNode>,
        outgoing: Map<String, List<SequenceFlow>>,
    ): Set<String> {
        val reachable = mutableSetOf<String>()
        val stack = ArrayDeque<String>()

        fun drain() {
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!reachable.add(cur)) continue
                outgoing[cur].orEmpty().forEach { stack.addLast(it.targetRef) }
            }
        }

        nodes.filterIsInstance<StartEvent>().forEach { stack.addLast(it.id) }
        drain()
        nodes.filterIsInstance<BoundaryEventNode>()
            .filter { it.attachedToRef in reachable }
            .forEach { stack.addLast(it.id) }
        drain()
        return reachable
    }

    private fun directionError(id: String, kind: String, direction: String) = Diagnostic(
        DiagnosticCodes.FLOW_DIRECTION, Severity.ERROR,
        "A $kind cannot have $direction sequence flow(s) — '$id' violates that",
        elementId = id,
        remediation = "Remove the $direction flow(s) from '$id'.",
    )

    private fun validateLanes(p: ProcessDefinition, d: MutableList<Diagnostic>) {
        val topIds = p.nodes.mapTo(HashSet()) { it.id }
        val memberOf = HashMap<String, String>()   // nodeId -> laneId

        for (lane in p.lanes) {
            for (ref in lane.flowNodeRefs) {
                if (ref !in topIds) {
                    d += Diagnostic(
                        DiagnosticCodes.LANE_REF_UNRESOLVED, Severity.ERROR,
                        "Lane '${lane.id}' references '$ref', which is not a top-level " +
                            "flow node of process '${p.id}'",
                        elementId = lane.id,
                        relatedIds = listOf(ref),
                        remediation = "Declare the node in the lane block, or fix the reference.",
                    )
                    continue
                }
                val prev = memberOf.putIfAbsent(ref, lane.id)
                if (prev != null) {
                    d += Diagnostic(
                        DiagnosticCodes.LANE_DUPLICATE_MEMBER, Severity.ERROR,
                        "Node '$ref' is claimed by both lane '$prev' and lane '${lane.id}'",
                        elementId = ref,
                        relatedIds = listOf(prev, lane.id),
                        remediation = "A node belongs to exactly one lane.",
                    )
                }
            }
        }

        // Convention: every top-level node sits in a lane (our layout can't
        // place laneless nodes). Legal BPMN, so a warning — and boundary
        // events are exempt: they render attached to their host's lane.
        for (n in p.nodes) {
            if (n is BoundaryEventNode) continue
            if (n.id !in memberOf) {
                d += Diagnostic(
                    DiagnosticCodes.LANELESS_NODE, Severity.WARNING,
                    "Node '${n.id}' is in no lane of process '${p.id}'; it will not be " +
                        "assigned a lane row by the layout engine",
                    elementId = n.id,
                    remediation = "Declare the node inside a lane { } block or add it via contains().",
                )
            }
        }
    }

    private fun validateCollaboration(c: CollaborationDefinition, d: MutableList<Diagnostic>) {
        val messageIds = c.messages.mapTo(HashSet()) { it.id }
        val participantIds = c.participants.mapTo(HashSet()) { it.id }

        // node id -> owning participant id, plus node lookup for type checks
        val participantOfNode = HashMap<String, String>()
        val nodeById = HashMap<String, FlowNode>()
        for (p in c.participants) {
            val procRef = p.processRef ?: continue
            val proc = c.processes.find { it.id == procRef }
            if (proc == null) {
                d += Diagnostic(
                    DiagnosticCodes.PARTICIPANT_PROCESS_UNRESOLVED, Severity.ERROR,
                    "Participant '${p.id}' references process '$procRef', which does not exist",
                    elementId = p.id,
                    relatedIds = listOf(procRef),
                    remediation = "Declare a process with id '$procRef' inside the participant block.",
                )
                continue
            }
            proc.allNodes().forEach {
                participantOfNode[it.id] = p.id
                nodeById[it.id] = it
            }
        }

        for (mf in c.messageFlows) {
            mf.messageRef?.let { ref ->
                if (ref !in messageIds) {
                    d += Diagnostic(
                        DiagnosticCodes.MSG_REF_UNRESOLVED, Severity.ERROR,
                        "Message flow '${mf.id}' references message '$ref', which is not declared",
                        elementId = mf.id,
                        relatedIds = listOf(ref),
                        remediation = "Declare message(\"$ref\") on the collaboration.",
                    )
                }
            }

            val srcPool = participantOfNode[mf.sourceRef]
                ?: mf.sourceRef.takeIf { it in participantIds }
            val tgtPool = participantOfNode[mf.targetRef]
                ?: mf.targetRef.takeIf { it in participantIds }

            if (srcPool == null || tgtPool == null) {
                d += Diagnostic(
                    DiagnosticCodes.MSG_FLOW_ENDPOINT, Severity.ERROR,
                    "Message flow '${mf.id}' has endpoint(s) that resolve to nothing:" +
                        (if (srcPool == null) " source '${mf.sourceRef}'" else "") +
                        (if (tgtPool == null) " target '${mf.targetRef}'" else ""),
                    elementId = mf.id,
                    relatedIds = listOf(mf.sourceRef, mf.targetRef),
                    remediation = "Endpoints must be flow nodes or participants.",
                )
                continue
            }

            if (srcPool == tgtPool) {
                d += Diagnostic(
                    DiagnosticCodes.MSG_FLOW_SAME_POOL, Severity.ERROR,
                    "Message flow '${mf.id}' connects two elements inside participant " +
                        "'$srcPool'; message flows must cross participant boundaries " +
                        "(use a sequence flow inside one process)",
                    elementId = mf.id,
                    relatedIds = listOf(mf.sourceRef, mf.targetRef),
                    remediation = "Use sequenceFlow for same-pool connections.",
                )
            }

            // A gateway is control flow — it cannot send or receive a message.
            for (ep in listOf(mf.sourceRef, mf.targetRef)) {
                val node = nodeById[ep]
                if (node is GatewayNode) {
                    d += Diagnostic(
                        DiagnosticCodes.MSG_FLOW_GATEWAY, Severity.ERROR,
                        "Message flow '${mf.id}' uses gateway '$ep' as an endpoint; " +
                            "gateways cannot send or receive messages",
                        elementId = mf.id,
                        relatedIds = listOf(ep),
                        remediation = "Point the message flow at a task or event.",
                    )
                }
            }
        }

        if (c.processes.isNotEmpty() && c.processes.none { it.isExecutable }) {
            d += Diagnostic(
                DiagnosticCodes.NO_EXECUTABLE, Severity.WARNING,
                "No process in collaboration '${c.id}' is executable; the diagram " +
                    "is documentation-only and deploys nothing",
                elementId = c.id,
                remediation = "Set executable = true on the participant that should run.",
            )
        }
    }

    fun validateDiagram(c: CollaborationDefinition, layout: DiagramLayout): List<Diagnostic> {
        val d = mutableListOf<Diagnostic>()
        for (p in c.processes) {
            // every visible semantic node gets a shape
            for (n in p.allNodes()) {
                val shape = layout.shapes[n.id]
                if (shape == null) {
                    d += Diagnostic(
                        DiagnosticCodes.DI_MISSING_SHAPE, Severity.ERROR,
                        "Flow node '${n.id}' has no BPMNShape in the diagram",
                        elementId = n.id,
                        remediation = "Ensure the layout engine assigns bounds to every node.",
                    )
                    continue
                }
                if (shape.width <= 0 || shape.height <= 0) {
                    d += Diagnostic(
                        DiagnosticCodes.DI_BAD_BOUNDS, Severity.ERROR,
                        "Flow node '${n.id}' has non-positive bounds " +
                            "(${shape.width}x${shape.height}); viewers silently drop it",
                        elementId = n.id,
                        remediation = "Give the shape positive width and height.",
                    )
                }
            }

            // lane ⊆ participant containment + node ⊆ lane containment
            val participant = c.participants.find { it.processRef == p.id }
            for (lane in p.lanes) {
                val laneBounds = layout.shapes[lane.id] ?: continue
                val poolBounds = participant?.let { layout.shapes[it.id] }
                if (poolBounds != null && !poolBounds.contains(laneBounds)) {
                    d += Diagnostic(
                        DiagnosticCodes.DI_LANE_OUTSIDE_POOL, Severity.ERROR,
                        "Lane '${lane.id}' extends outside participant " +
                            "'${participant.id}'s pool bounds",
                        elementId = lane.id,
                        remediation = "Grow the pool or shrink the lane.",
                    )
                }
                for (ref in lane.flowNodeRefs) {
                    val nodeBounds = layout.shapes[ref] ?: continue
                    if (!laneBounds.contains(nodeBounds)) {
                        d += Diagnostic(
                            DiagnosticCodes.DI_NODE_OUTSIDE_LANE, Severity.ERROR,
                            "Node '$ref' is declared in lane '${lane.id}' but its shape " +
                                "lies outside the lane's bounds",
                            elementId = ref,
                            relatedIds = listOf(lane.id),
                            remediation = "Place the node inside its lane's bounds.",
                        )
                    }
                }
            }

            // boundary event must touch its host's border
            for (b in p.allNodes().filterIsInstance<BoundaryEventNode>()) {
                val bs = layout.shapes[b.id]
                val host = layout.shapes[b.attachedToRef]
                if (bs != null && host != null && !bs.intersects(host)) {
                    d += Diagnostic(
                        DiagnosticCodes.DI_BOUNDARY_DETACHED, Severity.ERROR,
                        "Boundary event '${b.id}' does not touch its host " +
                            "'${b.attachedToRef}' — it must intersect the activity's border",
                        elementId = b.id,
                        relatedIds = listOf(b.attachedToRef),
                        remediation = "Pin the boundary event onto the host's border.",
                    )
                }
            }

            for (f in p.allSequenceFlows()) checkEdge(f.id, layout, d)
        }
        for (mf in c.messageFlows) checkEdge(mf.id, layout, d)
        return d.sortedWith(DIAGNOSTIC_ORDER)
    }

    private fun checkEdge(id: String, layout: DiagramLayout, d: MutableList<Diagnostic>) {
        val waypoints = layout.edges[id]
        if (waypoints == null || waypoints.size < 2) {
            d += Diagnostic(
                DiagnosticCodes.DI_MISSING_EDGE, Severity.ERROR,
                "Flow '$id' has ${if (waypoints == null) "no" else "fewer than two"} " +
                    "waypoints in the diagram; viewers cannot route it",
                elementId = id,
                remediation = "Ensure the layout engine emits ≥2 waypoints per edge.",
            )
        }
    }

    private companion object {
        /** Deterministic ordering: same model → same list, same order. */
        val DIAGNOSTIC_ORDER: Comparator<Diagnostic> =
            compareBy({ it.severity }, { it.code }, { it.elementId })
    }
}
