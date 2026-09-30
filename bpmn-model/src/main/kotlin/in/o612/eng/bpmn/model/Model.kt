package `in`.o612.eng.bpmn.model

/**
 * Immutable BPMN 2.0 AST — the seam between authoring and output.
 *
 * The DSL (dsl/Dsl.kt) collects declarations into mutable builders and freezes
 * them into these types; validation, layout, and XML serialization all consume
 * this model. Everything here is a `val` or a `List`, and elements reference
 * each other by *id* — never by object reference — so the model is a tree with
 * resolvable edges, not a cyclic object graph.
 *
 * The sealed hierarchy encodes BPMN's own category system: `boundaryTimer` can
 * only attach to an `ActivityNode`, a `ParallelGateway` has no `default` flow,
 * a `BoundaryErrorEvent` is always interrupting — invalid states are
 * unrepresentable at the type level before the validator ever runs.
 */

/** Element documentation — <bpmn:documentation> text. */
data class Documentation(val text: String)

/** A formal expression, defaulting to JUEL (Flowable/Camunda EL). */
data class Expression(
    val body: String,
    val language: String = "juel",
)

/**
 * A generic extension element: <prefix:name attrs>text children</prefix:name>.
 * Written recursively by the XML writer; `prefix` must resolve against the
 * namespaces contributed by the EngineExtensionSerializer (or bpmn itself).
 */
data class ExtensionElement(
    val name: String,
    val prefix: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val text: String? = null,
    val children: List<ExtensionElement> = emptyList(),
)

/**
 * BPMN timer: exactly one of duration (ISO-8601, e.g. "PT72H"), cycle, or date.
 * The init-require makes multi-valued timers unrepresentable — the DSL does
 * not need to check.
 */
data class TimerDefinition(
    val duration: String? = null,
    val cycle: String? = null,
    val date: String? = null,
) {
    init {
        require(listOfNotNull(duration, cycle, date).size == 1) {
            "TimerDefinition requires exactly one of duration, cycle, or date"
        }
    }

    companion object {
        fun duration(value: String) = TimerDefinition(duration = value)
        fun cycle(value: String) = TimerDefinition(cycle = value)
        fun date(value: String) = TimerDefinition(date = value)
    }
}

/** Process-variable types serialized as bpmndsl:variable/@type. */
enum class VariableType(val serialized: String) {
    STRING("string"),
    DECIMAL("decimal"),
    BOOLEAN("boolean"),
    INTEGER("integer"),
}

/**
 * Authoring metadata: BPMN 2.0 standardizes no variable declaration, so these
 * serialize into a <bpmndsl:processVariables> extension block — documentation
 * that travels with the definition; engines ignore it.
 */
data class ProcessVariable(
    val name: String,
    val type: VariableType,
    val required: Boolean = false,
)

/** Everything with a stable BPMN id. */
sealed interface BpmnElement {
    val id: String
    val name: String?
    val documentation: Documentation?
}

/** Anything that can sit on a sequence flow: events, activities, gateways. */
sealed interface FlowNode : BpmnElement {
    val extensions: List<ExtensionElement>
}

sealed interface EventNode : FlowNode

/**
 * An activity performs work and can carry boundary events. Boundary events may
 * only attach to activities — never to events or gateways.
 */
sealed interface ActivityNode : FlowNode {
    /** Engine-agnostic authoring metadata (form key, delegate ref, groups). */
    val metadata: Map<String, String>
}

sealed interface GatewayNode : FlowNode {
    /** Id of the flow taken when no condition applies; null for parallel. */
    val defaultFlow: String?
}

/**
 * Boundary events alone carry attachedToRef and cancelActivity — they are
 * entered via attachment to their host, not via incoming sequence flow.
 */
sealed interface BoundaryEventNode : EventNode {
    val attachedToRef: String

    /** true = interrupting (the attached activity is cancelled). */
    val cancelActivity: Boolean
}

data class StartEvent(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    /** Set for a message start event. */
    val messageRef: String? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : EventNode

data class EndEvent(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    /** Set for an error end event: references a <bpmn:error> id. */
    val errorRef: String? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : EventNode

data class IntermediateCatchMessageEvent(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    val messageRef: String,
    override val extensions: List<ExtensionElement> = emptyList(),
) : EventNode

data class BoundaryTimerEvent(
    override val id: String,
    override val name: String? = null,
    override val attachedToRef: String,
    override val cancelActivity: Boolean = true,
    val timer: TimerDefinition,
    override val documentation: Documentation? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : BoundaryEventNode

data class BoundaryMessageEvent(
    override val id: String,
    override val name: String? = null,
    override val attachedToRef: String,
    override val cancelActivity: Boolean = true,
    val messageRef: String,
    override val documentation: Documentation? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : BoundaryEventNode

/**
 * Boundary error events are always interrupting in BPMN 2.0 — cancelActivity
 * must be true, so it is not configurable here.
 */
data class BoundaryErrorEvent(
    override val id: String,
    override val name: String? = null,
    override val attachedToRef: String,
    val errorRef: String? = null,
    override val documentation: Documentation? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : BoundaryEventNode {
    override val cancelActivity: Boolean get() = true
}

data class UserTask(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    override val metadata: Map<String, String> = emptyMap(),
    override val extensions: List<ExtensionElement> = emptyList(),
) : ActivityNode

data class ServiceTask(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    override val metadata: Map<String, String> = emptyMap(),
    override val extensions: List<ExtensionElement> = emptyList(),
) : ActivityNode

data class ReceiveTask(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    val messageRef: String? = null,
    override val metadata: Map<String, String> = emptyMap(),
    override val extensions: List<ExtensionElement> = emptyList(),
) : ActivityNode

data class SendTask(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    val messageRef: String? = null,
    override val metadata: Map<String, String> = emptyMap(),
    override val extensions: List<ExtensionElement> = emptyList(),
) : ActivityNode

/**
 * The one composite activity. Its defining property is *scope*: nodes and
 * flows inside form a closed world — a sequence flow inside the subprocess can
 * only reference its own nodes, and vice versa. Owning separate lists makes
 * that structural, not conventional.
 */
data class EmbeddedSubprocess(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    val nodes: List<FlowNode> = emptyList(),
    val sequenceFlows: List<SequenceFlow> = emptyList(),
    override val metadata: Map<String, String> = emptyMap(),
    override val extensions: List<ExtensionElement> = emptyList(),
) : ActivityNode

data class ExclusiveGateway(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    /** Id of the sequence flow taken when every condition evaluates false. */
    override val defaultFlow: String? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : GatewayNode

data class ParallelGateway(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : GatewayNode {
    override val defaultFlow: String? get() = null
}

data class InclusiveGateway(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    override val defaultFlow: String? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : GatewayNode

data class EventBasedGateway(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    override val extensions: List<ExtensionElement> = emptyList(),
) : GatewayNode {
    override val defaultFlow: String? get() = null
}

data class SequenceFlow(
    override val id: String,
    override val name: String? = null,
    val sourceRef: String,
    val targetRef: String,
    val condition: Expression? = null,
    override val documentation: Documentation? = null,
) : BpmnElement

/**
 * An organizational partition of a diagram — "who is responsible", not "where
 * it runs". Holds *references* to nodes, not the nodes themselves.
 */
data class LaneDefinition(
    override val id: String,
    override val name: String? = null,
    /** Ids of flow nodes rendered inside this lane. References, not objects. */
    val flowNodeRefs: List<String> = emptyList(),
    override val documentation: Documentation? = null,
) : BpmnElement

data class ProcessDefinition(
    override val id: String,
    override val name: String? = null,
    val isExecutable: Boolean = false,
    val lanes: List<LaneDefinition> = emptyList(),
    val nodes: List<FlowNode> = emptyList(),
    val sequenceFlows: List<SequenceFlow> = emptyList(),
    val variables: List<ProcessVariable> = emptyList(),
    override val documentation: Documentation? = null,
) : BpmnElement {
    /** All flow nodes, including nodes nested inside embedded subprocesses. */
    fun allNodes(): List<FlowNode> =
        nodes + nodes.filterIsInstance<EmbeddedSubprocess>().flatMap { it.nodes }

    fun allSequenceFlows(): List<SequenceFlow> =
        sequenceFlows + nodes.filterIsInstance<EmbeddedSubprocess>().flatMap { it.sequenceFlows }
}

data class ParticipantDefinition(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
    /** References a ProcessDefinition id; null = black-box participant. */
    val processRef: String? = null,
) : BpmnElement

data class MessageDefinition(
    override val id: String,
    override val name: String? = null,
    override val documentation: Documentation? = null,
) : BpmnElement

data class MessageFlow(
    override val id: String,
    override val name: String? = null,
    val sourceRef: String,
    val targetRef: String,
    val messageRef: String? = null,
    override val documentation: Documentation? = null,
) : BpmnElement

/**
 * Top-level container. The flat `processes` list mirrors BPMN's own structure:
 * <bpmn:process> elements are siblings of <bpmn:collaboration> inside
 * <bpmn:definitions>, joined only by participant.processRef.
 */
data class CollaborationDefinition(
    override val id: String,
    override val name: String? = null,
    val participants: List<ParticipantDefinition> = emptyList(),
    val processes: List<ProcessDefinition> = emptyList(),
    val messages: List<MessageDefinition> = emptyList(),
    val messageFlows: List<MessageFlow> = emptyList(),
    override val documentation: Documentation? = null,
) : BpmnElement {
    fun processOf(participantId: String): ProcessDefinition? =
        participants.find { it.id == participantId }
            ?.let { p -> processes.find { it.id == p.processRef } }
}

data class Point(val x: Double, val y: Double)

data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2

    fun contains(inner: Bounds, tolerance: Double = 0.001): Boolean =
        inner.x >= x - tolerance && inner.y >= y - tolerance &&
            inner.right <= right + tolerance && inner.bottom <= bottom + tolerance

    fun intersects(other: Bounds): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y
}

/**
 * Geometry keyed by *semantic element id* — the layout engine never invents
 * elements, it only assigns coordinates to ids that already exist.
 */
data class DiagramLayout(
    /** Semantic element id → bounds (nodes, lanes, participants, events). */
    val shapes: Map<String, Bounds> = emptyMap(),
    /** Sequence-flow or message-flow id → ordered waypoints. */
    val edges: Map<String, List<Point>> = emptyMap(),
    val labels: Map<String, Bounds> = emptyMap(),
)
