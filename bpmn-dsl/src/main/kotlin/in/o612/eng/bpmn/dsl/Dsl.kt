package `in`.o612.eng.bpmn.dsl

import `in`.o612.eng.bpmn.model.ActivityNode
import `in`.o612.eng.bpmn.model.BoundaryErrorEvent
import `in`.o612.eng.bpmn.model.BoundaryEventNode
import `in`.o612.eng.bpmn.model.BoundaryMessageEvent
import `in`.o612.eng.bpmn.model.BoundaryTimerEvent
import `in`.o612.eng.bpmn.model.CollaborationDefinition
import `in`.o612.eng.bpmn.model.Documentation
import `in`.o612.eng.bpmn.model.EmbeddedSubprocess
import `in`.o612.eng.bpmn.model.EndEvent
import `in`.o612.eng.bpmn.model.EventBasedGateway
import `in`.o612.eng.bpmn.model.ExclusiveGateway
import `in`.o612.eng.bpmn.model.Expression
import `in`.o612.eng.bpmn.model.ExtensionElement
import `in`.o612.eng.bpmn.model.FlowNode
import `in`.o612.eng.bpmn.model.InclusiveGateway
import `in`.o612.eng.bpmn.model.IntermediateCatchMessageEvent
import `in`.o612.eng.bpmn.model.LaneDefinition
import `in`.o612.eng.bpmn.model.MessageDefinition
import `in`.o612.eng.bpmn.model.MessageFlow
import `in`.o612.eng.bpmn.model.ParallelGateway
import `in`.o612.eng.bpmn.model.ParticipantDefinition
import `in`.o612.eng.bpmn.model.ProcessDefinition
import `in`.o612.eng.bpmn.model.ProcessVariable
import `in`.o612.eng.bpmn.model.ReceiveTask
import `in`.o612.eng.bpmn.model.SendTask
import `in`.o612.eng.bpmn.model.SequenceFlow
import `in`.o612.eng.bpmn.model.ServiceTask
import `in`.o612.eng.bpmn.model.StartEvent
import `in`.o612.eng.bpmn.model.TimerDefinition
import `in`.o612.eng.bpmn.model.UserTask
import `in`.o612.eng.bpmn.model.VariableType

/**
 * The fluent authoring surface: lambdas with receivers that compile a BPMN
 * collaboration into the immutable AST in `model/`.
 *
 * The asymmetry is the design: builders are unashamedly mutable (`var`,
 * `mutableListOf`) because authoring order is imperative; the product is
 * rigidly immutable because validation, layout, and serialization downstream
 * must be deterministic and shareable. `build()` is `internal` and is called
 * exactly once when the author's lambda returns — builders never leak.
 *
 * No builder method ever writes XML, computes a coordinate, or validates a
 * reference. Builders only collect declarations.
 */

@DslMarker
annotation class BpmnDsl

/** Entry point: `bpmnCollaboration("enterpriseProcurement") { … }`. */
fun bpmnCollaboration(id: String, block: CollaborationBuilder.() -> Unit): CollaborationDefinition =
    CollaborationBuilder(id).apply(block).build()

/**
 * A scope that can own flow nodes: a process, a lane, or a subprocess.
 * A lane cannot own sequence flows — sequence flows belong to the process.
 */
@BpmnDsl
sealed class FlowNodeContainer {
    internal abstract fun register(node: FlowNode)

    fun startEvent(id: String, block: StartEventBuilder.() -> Unit = {}) =
        register(StartEventBuilder(id).apply(block).build())

    fun endEvent(id: String, block: EndEventBuilder.() -> Unit = {}) =
        register(EndEventBuilder(id).apply(block).build())

    fun userTask(id: String, block: UserTaskBuilder.() -> Unit = {}) =
        register(UserTaskBuilder(id, this).apply(block).build())

    fun serviceTask(id: String, block: ServiceTaskBuilder.() -> Unit = {}) =
        register(ServiceTaskBuilder(id, this).apply(block).build())

    fun receiveTask(id: String, block: ReceiveTaskBuilder.() -> Unit = {}) =
        register(ReceiveTaskBuilder(id, this).apply(block).build())

    fun sendTask(id: String, block: SendTaskBuilder.() -> Unit = {}) =
        register(SendTaskBuilder(id, this).apply(block).build())

    fun exclusiveGateway(id: String, block: ExclusiveGatewayBuilder.() -> Unit = {}) =
        register(ExclusiveGatewayBuilder(id).apply(block).build())

    fun parallelGateway(id: String, block: ParallelGatewayBuilder.() -> Unit = {}) =
        register(ParallelGatewayBuilder(id).apply(block).build())

    fun inclusiveGateway(id: String, block: InclusiveGatewayBuilder.() -> Unit = {}) =
        register(InclusiveGatewayBuilder(id).apply(block).build())

    fun eventBasedGateway(id: String, block: EventBasedGatewayBuilder.() -> Unit = {}) =
        register(EventBasedGatewayBuilder(id).apply(block).build())

    fun intermediateCatchMessageEvent(id: String, block: CatchMessageEventBuilder.() -> Unit = {}) =
        register(CatchMessageEventBuilder(id).apply(block).build())

    fun embeddedSubprocess(id: String, block: SubprocessBuilder.() -> Unit = {}) =
        register(SubprocessBuilder(id, this).apply(block).build())
}

/**
 * A scope that can own sequence flows: a process or a subprocess — never a
 * lane. If `lane { }` offered `sequenceFlow(...)`, the DSL would be teaching
 * a BPMN falsehood.
 */
@BpmnDsl
interface SequenceFlowContainer {
    fun registerFlow(flow: SequenceFlow)

    fun sequenceFlow(id: String, from: String, to: String, name: String? = null) =
        registerFlow(SequenceFlow(id = id, name = name, sourceRef = from, targetRef = to))

    fun conditionalFlow(id: String, from: String, to: String, condition: String, name: String? = null) =
        registerFlow(
            SequenceFlow(
                id = id, name = name, sourceRef = from, targetRef = to,
                condition = Expression(condition),
            ),
        )
}

/**
 * Receivers that can carry boundary events: activity builders and the
 * subprocess builder. Boundary events read as nested (`boundaryTimer` inside
 * the activity block) but register as *siblings* in the enclosing scope's node
 * list with `attachedToRef` pointing at the host — the shape BPMN serializes
 * as a `<bpmn:boundaryEvent>` element.
 *
 * Only receivers implementing this interface offer the functions, so
 * `intermediateCatchMessageEvent("x") { boundaryTimer("y") {} }` is a compile
 * error — matching the BPMN rule that boundary events attach to activities.
 */
@BpmnDsl
sealed interface BoundaryAttachable {
    /** The host node id boundary events attach to. */
    val boundaryHostId: String

    /** The scope that owns the produced boundary node (the enclosing scope). */
    val boundaryScope: FlowNodeContainer

    fun boundaryTimer(id: String, block: BoundaryTimerBuilder.() -> Unit) {
        boundaryScope.register(BoundaryTimerBuilder(id, boundaryHostId).apply(block).build())
    }

    fun boundaryMessage(id: String, message: String, block: BoundaryEventBuilder.() -> Unit = {}) {
        boundaryScope.register(BoundaryEventBuilder(id, boundaryHostId).apply(block).buildMessage(message))
    }

    fun boundaryError(id: String, errorRef: String? = null, block: BoundaryEventBuilder.() -> Unit = {}) {
        boundaryScope.register(BoundaryEventBuilder(id, boundaryHostId).apply(block).buildError(errorRef))
    }
}

/** Common id/name/documentation/extensions plumbing for node builders. */
@BpmnDsl
sealed class ElementBuilder {
    var name: String? = null
    var documentation: String? = null
    private val extensions = mutableListOf<ExtensionElement>()

    fun extension(prefix: String? = null, name: String, vararg attributes: Pair<String, String>) {
        extensions += ExtensionElement(prefix = prefix, name = name, attributes = attributes.toMap())
    }

    internal fun collectExtensions(): List<ExtensionElement> = extensions.toList()
    internal fun doc(): Documentation? = documentation?.let(::Documentation)
}

class StartEventBuilder internal constructor(private val id: String) : ElementBuilder() {
    /** Set for a message start event. */
    var messageRef: String? = null

    internal fun build() =
        StartEvent(id, name, doc(), messageRef, collectExtensions())
}

class EndEventBuilder internal constructor(private val id: String) : ElementBuilder() {
    /** Set for an error end event: references a <bpmn:error> id. */
    var errorRef: String? = null

    internal fun build() =
        EndEvent(id, name, doc(), errorRef, collectExtensions())
}

class CatchMessageEventBuilder internal constructor(private val id: String) : ElementBuilder() {
    var messageRef: String? = null

    internal fun build() = IntermediateCatchMessageEvent(
        id, name, doc(),
        requireNotNull(messageRef) {
            "intermediateCatchMessageEvent '$id' requires messageRef"
        },
        collectExtensions(),
    )
}

class BoundaryTimerBuilder internal constructor(
    private val id: String,
    private val attachedToRef: String,
) : ElementBuilder() {
    /** Exactly one of duration/cycle/date — TimerDefinition enforces it. */
    var duration: String? = null
    var cycle: String? = null
    var date: String? = null

    /** true = interrupting (the attached activity is cancelled). */
    var cancelActivity: Boolean = true

    internal fun build() = BoundaryTimerEvent(
        id, name, attachedToRef, cancelActivity,
        TimerDefinition(duration = duration, cycle = cycle, date = date),
        doc(), collectExtensions(),
    )
}

class BoundaryEventBuilder internal constructor(
    private val id: String,
    private val attachedToRef: String,
) : ElementBuilder() {
    var cancelActivity: Boolean = true

    internal fun buildMessage(messageRef: String) = BoundaryMessageEvent(
        id, name, attachedToRef, cancelActivity, messageRef, doc(), collectExtensions(),
    )

    internal fun buildError(errorRef: String?) = BoundaryErrorEvent(
        id, name, attachedToRef, errorRef, doc(), collectExtensions(),
    )
}

@BpmnDsl
sealed class ActivityBuilder(
    private val nodeId: String,
    private val scope: FlowNodeContainer,
) : ElementBuilder(), BoundaryAttachable {
    override val boundaryHostId: String get() = nodeId
    override val boundaryScope: FlowNodeContainer get() = scope
}

class UserTaskBuilder internal constructor(
    private val id: String,
    scope: FlowNodeContainer,
) : ActivityBuilder(id, scope) {
    var formKey: String? = null
    var assignee: String? = null
    var candidateGroup: String? = null
    var candidateUsers: String? = null

    internal fun build() = UserTask(
        id, name, doc(),
        metadata = buildMap {
            formKey?.let { put("formKey", it) }
            assignee?.let { put("assignee", it) }
            candidateGroup?.let { put("candidateGroup", it) }
            candidateUsers?.let { put("candidateUsers", it) }
        },
        collectExtensions(),
    )
}

class ServiceTaskBuilder internal constructor(
    private val id: String,
    scope: FlowNodeContainer,
) : ActivityBuilder(id, scope) {
    /** Logical delegate name; the engine adapter decides how to serialize it. */
    var delegate: String? = null
    var delegateClass: String? = null
    var expression: String? = null

    internal fun build() = ServiceTask(
        id, name, doc(),
        metadata = buildMap {
            delegate?.let { put("delegate", it) }
            delegateClass?.let { put("class", it) }
            expression?.let { put("expression", it) }
        },
        collectExtensions(),
    )
}

class ReceiveTaskBuilder internal constructor(
    private val id: String,
    scope: FlowNodeContainer,
) : ActivityBuilder(id, scope) {
    var messageRef: String? = null

    internal fun build() =
        ReceiveTask(id, name, doc(), messageRef, metadata = emptyMap(), collectExtensions())
}

class SendTaskBuilder internal constructor(
    private val id: String,
    scope: FlowNodeContainer,
) : ActivityBuilder(id, scope) {
    var messageRef: String? = null

    internal fun build() =
        SendTask(id, name, doc(), messageRef, metadata = emptyMap(), collectExtensions())
}

class ExclusiveGatewayBuilder internal constructor(private val id: String) : ElementBuilder() {
    /** Id of the sequence flow taken when every condition evaluates false. */
    var defaultFlow: String? = null

    internal fun build() = ExclusiveGateway(id, name, doc(), defaultFlow, collectExtensions())
}

class ParallelGatewayBuilder internal constructor(private val id: String) : ElementBuilder() {
    // No defaultFlow — a parallel gateway takes all branches unconditionally.
    internal fun build() = ParallelGateway(id, name, doc(), collectExtensions())
}

class InclusiveGatewayBuilder internal constructor(private val id: String) : ElementBuilder() {
    var defaultFlow: String? = null

    internal fun build() = InclusiveGateway(id, name, doc(), defaultFlow, collectExtensions())
}

class EventBasedGatewayBuilder internal constructor(private val id: String) : ElementBuilder() {
    internal fun build() = EventBasedGateway(id, name, doc(), collectExtensions())
}

/**
 * Embedded subprocess: a closed scope with its own start/end events and
 * sequence flows. Also a legal boundary host — `boundaryTimer` inside its
 * block attaches to the subprocess and registers in the enclosing scope.
 */
@BpmnDsl
class SubprocessBuilder internal constructor(
    private val id: String,
    private val outerScope: FlowNodeContainer,
) : FlowNodeContainer(), SequenceFlowContainer, BoundaryAttachable {
    var name: String? = null
    var documentation: String? = null
    private val nodes = mutableListOf<FlowNode>()
    private val flows = mutableListOf<SequenceFlow>()

    override fun register(node: FlowNode) {
        nodes += node
    }

    override fun registerFlow(flow: SequenceFlow) {
        flows += flow
    }

    // Boundary events declared in the subprocess block attach to the
    // subprocess itself and live in the *enclosing* scope, not the interior.
    override val boundaryHostId: String get() = id
    override val boundaryScope: FlowNodeContainer get() = outerScope

    internal fun build() = EmbeddedSubprocess(
        id, name, documentation?.let(::Documentation),
        nodes.toList(), flows.toList(),
    )
}

/**
 * The lane trick: nodes declared inside a lane block *look* owned by the lane
 * but register in the enclosing process scope — the lane only records the id
 * reference. Membership by reference for nodes declared outside is available
 * via `contains("id")`.
 */
@BpmnDsl
class LaneBuilder internal constructor(
    private val id: String,
    private val nodeScope: FlowNodeContainer,
) : FlowNodeContainer() {
    var name: String? = null
    private val refs = mutableListOf<String>()

    /** Nodes declared inside a lane block register in the enclosing scope. */
    override fun register(node: FlowNode) {
        nodeScope.register(node)   // the node belongs to the process…
        if (node !is BoundaryEventNode) {
            refs += node.id        // …the lane records the reference; boundary
        }                          // events render on their host's lane instead
    }

    /** Membership by reference, for nodes declared outside the lane block. */
    fun contains(vararg nodeIds: String) {
        refs += nodeIds
    }

    internal fun build() = LaneDefinition(
        id = id, name = name,
        flowNodeRefs = refs.toList(),
    )
}

@BpmnDsl
class ProcessBuilder internal constructor(private val id: String) :
    FlowNodeContainer(), SequenceFlowContainer {
    var name: String? = null
    var documentation: String? = null
    private val nodes = mutableListOf<FlowNode>()
    private val flows = mutableListOf<SequenceFlow>()
    private val lanes = mutableListOf<LaneDefinition>()
    private var vars = emptyList<ProcessVariable>()

    override fun register(node: FlowNode) {
        nodes += node
    }

    override fun registerFlow(flow: SequenceFlow) {
        flows += flow
    }

    fun lane(id: String, block: LaneBuilder.() -> Unit = {}) {
        lanes += LaneBuilder(id, this).apply(block).build()
    }

    /** Process variables — authoring metadata, not BPMN. */
    fun variables(block: VariablesBuilder.() -> Unit) {
        vars = VariablesBuilder().apply(block).build()
    }

    internal fun build(isExecutable: Boolean) = ProcessDefinition(
        id = id, name = name, isExecutable = isExecutable,
        lanes = lanes.toList(), nodes = nodes.toList(),
        sequenceFlows = flows.toList(), variables = vars,
        documentation = documentation?.let(::Documentation),
    )
}

@BpmnDsl
class VariablesBuilder internal constructor() {
    private val vars = mutableListOf<ProcessVariable>()

    fun string(name: String, required: Boolean = false) {
        vars += ProcessVariable(name, VariableType.STRING, required)
    }

    fun decimal(name: String, required: Boolean = false) {
        vars += ProcessVariable(name, VariableType.DECIMAL, required)
    }

    fun boolean(name: String, required: Boolean = false) {
        vars += ProcessVariable(name, VariableType.BOOLEAN, required)
    }

    fun integer(name: String, required: Boolean = false) {
        vars += ProcessVariable(name, VariableType.INTEGER, required)
    }

    internal fun build(): List<ProcessVariable> = vars.toList()
}

@BpmnDsl
class ParticipantBuilder internal constructor(private val id: String) {
    var name: String? = null

    /** Becomes isExecutable on the *process* element in BPMN XML. */
    var executable: Boolean = false
    var documentation: String? = null

    private var process: ProcessDefinition? = null

    fun process(id: String, block: ProcessBuilder.() -> Unit) {
        check(process == null) { "participant '$id' already declares a process" }
        process = ProcessBuilder(id).apply(block).build(isExecutable = executable)
    }

    internal fun build(): Pair<ParticipantDefinition, ProcessDefinition?> =
        ParticipantDefinition(
            id = id, name = name,
            documentation = documentation?.let(::Documentation),
            processRef = process?.id,
        ) to process
}

@BpmnDsl
class CollaborationBuilder internal constructor(private val id: String) {
    var name: String? = null
    var documentation: String? = null
    private val participants = mutableListOf<ParticipantDefinition>()
    private val processes = mutableListOf<ProcessDefinition>()
    private val messages = mutableListOf<MessageDefinition>()
    private val messageFlows = mutableListOf<MessageFlow>()

    /** Produces two AST objects: the participant and the process it references. */
    fun participant(id: String, block: ParticipantBuilder.() -> Unit) {
        val (participant, process) = ParticipantBuilder(id).apply(block).build()
        participants += participant
        process?.let(processes::add)
    }

    fun message(id: String, name: String? = null) {
        messages += MessageDefinition(id = id, name = name)
    }

    fun messageFlow(id: String, from: String, to: String, message: String, name: String? = null) {
        messageFlows += MessageFlow(
            id = id, name = name, sourceRef = from, targetRef = to, messageRef = message,
        )
    }

    internal fun build() = CollaborationDefinition(
        id = id, name = name,
        participants = participants.toList(),
        processes = processes.toList(),
        messages = messages.toList(),
        messageFlows = messageFlows.toList(),
        documentation = documentation?.let(::Documentation),
    )
}
