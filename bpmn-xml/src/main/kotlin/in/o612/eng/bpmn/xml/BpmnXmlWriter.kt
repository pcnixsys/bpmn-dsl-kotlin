package `in`.o612.eng.bpmn.xml

import `in`.o612.eng.bpmn.model.ActivityNode
import `in`.o612.eng.bpmn.model.BoundaryErrorEvent
import `in`.o612.eng.bpmn.model.Bounds
import `in`.o612.eng.bpmn.model.BoundaryEventNode
import `in`.o612.eng.bpmn.model.BoundaryMessageEvent
import `in`.o612.eng.bpmn.model.BoundaryTimerEvent
import `in`.o612.eng.bpmn.model.CollaborationDefinition
import `in`.o612.eng.bpmn.model.DiagramLayout
import `in`.o612.eng.bpmn.model.EmbeddedSubprocess
import `in`.o612.eng.bpmn.model.EndEvent
import `in`.o612.eng.bpmn.model.EventBasedGateway
import `in`.o612.eng.bpmn.model.ExclusiveGateway
import `in`.o612.eng.bpmn.model.ExtensionElement
import `in`.o612.eng.bpmn.model.FlowNode
import `in`.o612.eng.bpmn.model.InclusiveGateway
import `in`.o612.eng.bpmn.model.IntermediateCatchMessageEvent
import `in`.o612.eng.bpmn.model.ParallelGateway
import `in`.o612.eng.bpmn.model.Point
import `in`.o612.eng.bpmn.model.ProcessDefinition
import `in`.o612.eng.bpmn.model.ReceiveTask
import `in`.o612.eng.bpmn.model.SendTask
import `in`.o612.eng.bpmn.model.SequenceFlow
import `in`.o612.eng.bpmn.model.ServiceTask
import `in`.o612.eng.bpmn.model.StartEvent
import `in`.o612.eng.bpmn.model.UserTask
import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/** XML namespaces used by the writer. */
object BpmnNs {
    const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
    const val BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI"
    const val DC = "http://www.omg.org/spec/DD/20100524/DC"
    const val DI = "http://www.omg.org/spec/DD/20100524/DI"
    const val XSI = "http://www.w3.org/2001/XMLSchema-instance"
    const val FLOWABLE = "http://flowable.org/bpmn"
    const val CAMUNDA = "http://camunda.org/schema/1.0/bpmn"

    /** This project's vendor-neutral extension namespace. */
    const val BPMDSL = "https://o612.in/bpmn/dsl"

    const val TARGET_NAMESPACE = "https://o612.in/bpmn/definitions"
}

/**
 * The vendor seam: how engine-specific attributes and elements enter otherwise
 * standard BPMN. [None] produces vendor-neutral XML; `FlowableExtensionSerializer`
 * adds the `flowable:` namespace, attributes, and extension elements — the same
 * SPI would carry a Camunda adapter.
 */
interface EngineExtensionSerializer {
    /** Extra prefix → namespace-uri declarations emitted on <bpmn:definitions>. */
    fun namespaces(): Map<String, String> = emptyMap()

    /** Prefixed attributes to add on a flow node's element (e.g. flowable:delegateExpression). */
    fun elementAttributes(node: FlowNode): Map<String, String> = emptyMap()

    /** Children of the node's <bpmn:extensionElements>. */
    fun extensionElements(node: FlowNode): List<ExtensionElement> = emptyList()

    object None : EngineExtensionSerializer
}

/**
 * CollaborationDefinition → BPMN 2.0 XML via JDK StAX.
 *
 * - Non-repairing mode: every element is a (prefix, local, ns) triple and every
 *   namespace is declared explicitly on <bpmn:definitions>. No string
 *   concatenation — StAX handles escaping.
 * - Deterministic: declaration-order iteration end to end, so the same model +
 *   layout produces byte-identical output — a reviewer's diff is a diff of intent.
 * - [layout] is optional; without it the file is deployable-but-blank (no DI).
 */
class BpmnXmlWriter(
    private val engine: EngineExtensionSerializer = EngineExtensionSerializer.None,
) {

    fun writeToString(c: CollaborationDefinition, layout: DiagramLayout? = null): String {
        val sw = StringWriter()
        val raw = XMLOutputFactory.newFactory().createXMLStreamWriter(sw)
        val w = Xml(raw)

        raw.writeStartDocument("UTF-8", "1.0")
        w.elem("bpmn", "definitions", BpmnNs.BPMN) {
            // namespace declarations first — deterministic, and matches readers'
            // expectations (xmlns:* before other attributes)
            w.ns("bpmn", BpmnNs.BPMN)
            w.ns("bpmndi", BpmnNs.BPMNDI)
            w.ns("dc", BpmnNs.DC)
            w.ns("di", BpmnNs.DI)
            w.ns("xsi", BpmnNs.XSI)
            w.ns("bpmndsl", BpmnNs.BPMDSL)
            engine.namespaces().forEach { (prefix, uri) -> w.ns(prefix, uri) }

            w.attr("id", "${c.id}_definitions")
            c.name?.let { w.attr("name", it) }
            w.attr("targetNamespace", BpmnNs.TARGET_NAMESPACE)
            w.attr("exporter", "o612-bpmn-dsl")
            w.attr("exporterVersion", "1.0")

            c.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }

            writeMessages(c, w)
            writeErrors(c, w)
            writeCollaboration(c, w)
            for (p in c.processes) writeProcess(p, w)
            if (layout != null) writeDiagram(c, layout, w)
        }
        raw.writeEndDocument()
        raw.flush()
        return sw.toString().let { if (it.endsWith("\n")) it else "$it\n" }
    }

    private fun writeMessages(c: CollaborationDefinition, w: Xml) {
        for (m in c.messages) {
            w.leafOrElem("bpmn", "message", BpmnNs.BPMN, m.documentation != null) {
                w.attr("id", m.id)
                m.name?.let { w.attr("name", it) }
                m.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
            }
        }
    }

    /** <bpmn:error> declarations for every errorRef used by end/boundary events. */
    private fun writeErrors(c: CollaborationDefinition, w: Xml) {
        val errorRefs = linkedSetOf<String>()
        for (p in c.processes) {
            for (n in p.allNodes()) {
                when (n) {
                    is EndEvent -> n.errorRef?.let(errorRefs::add)
                    is BoundaryErrorEvent -> n.errorRef?.let(errorRefs::add)
                    else -> Unit
                }
            }
        }
        for (ref in errorRefs) {
            w.leaf("bpmn", "error", BpmnNs.BPMN, mapOf("id" to ref))
        }
    }

    private fun writeCollaboration(c: CollaborationDefinition, w: Xml) {
        w.elem("bpmn", "collaboration", BpmnNs.BPMN) {
            w.attr("id", c.id)
            c.name?.let { w.attr("name", it) }
            c.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
            for (p in c.participants) {
                w.leafOrElem("bpmn", "participant", BpmnNs.BPMN, p.documentation != null) {
                    w.attr("id", p.id)
                    p.name?.let { w.attr("name", it) }
                    p.processRef?.let { w.attr("processRef", it) }
                    p.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
                }
            }
            for (mf in c.messageFlows) {
                w.leafOrElem("bpmn", "messageFlow", BpmnNs.BPMN, mf.documentation != null) {
                    w.attr("id", mf.id)
                    mf.name?.let { w.attr("name", it) }
                    w.attr("sourceRef", mf.sourceRef)
                    w.attr("targetRef", mf.targetRef)
                    mf.messageRef?.let { w.attr("messageRef", it) }
                    mf.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
                }
            }
        }
    }

    private fun writeProcess(p: ProcessDefinition, w: Xml) {
        w.elem("bpmn", "process", BpmnNs.BPMN) {
            w.attr("id", p.id)
            p.name?.let { w.attr("name", it) }
            w.attr("isExecutable", p.isExecutable.toString())
            p.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }

            if (p.variables.isNotEmpty()) {
                w.elem("bpmn", "extensionElements", BpmnNs.BPMN) {
                    w.elem("bpmndsl", "processVariables", BpmnNs.BPMDSL) {
                        for (v in p.variables) {
                            val attrs = LinkedHashMap<String, String>()
                            attrs["name"] = v.name
                            attrs["type"] = v.type.serialized
                            if (v.required) attrs["required"] = "true"
                            w.leaf("bpmndsl", "variable", BpmnNs.BPMDSL, attrs)
                        }
                    }
                }
            }

            if (p.lanes.isNotEmpty()) {
                w.elem("bpmn", "laneSet", BpmnNs.BPMN) {
                    w.attr("id", "${p.id}_laneSet")
                    for (lane in p.lanes) {
                        w.elem("bpmn", "lane", BpmnNs.BPMN) {
                            w.attr("id", lane.id)
                            lane.name?.let { w.attr("name", it) }
                            lane.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
                            for (ref in lane.flowNodeRefs) {
                                w.textElem("bpmn", "flowNodeRef", BpmnNs.BPMN, ref)
                            }
                        }
                    }
                }
            }

            writeScopeNodes(p.nodes, p.sequenceFlows, w)
            writeScopeFlows(p.sequenceFlows, w)
        }
    }

    private fun writeScopeNodes(nodes: List<FlowNode>, flows: List<SequenceFlow>, w: Xml) {
        for (n in nodes) writeFlowNode(n, flows, w)
    }

    private fun writeFlowNode(n: FlowNode, flowsInScope: List<SequenceFlow>, w: Xml) {
        val tag = when (n) {
            is StartEvent -> "startEvent"
            is EndEvent -> "endEvent"
            is IntermediateCatchMessageEvent -> "intermediateCatchEvent"
            is BoundaryEventNode -> "boundaryEvent"
            is UserTask -> "userTask"
            is ServiceTask -> "serviceTask"
            is ReceiveTask -> "receiveTask"
            is SendTask -> "sendTask"
            is EmbeddedSubprocess -> "subProcess"
            is ExclusiveGateway -> "exclusiveGateway"
            is ParallelGateway -> "parallelGateway"
            is InclusiveGateway -> "inclusiveGateway"
            is EventBasedGateway -> "eventBasedGateway"
        }
        // exhaustive when — a new FlowNode subtype without a writer arm is a
        // compile error here, which is exactly the point
        w.elem("bpmn", tag, BpmnNs.BPMN) {
            w.attr("id", n.id)
            n.name?.let { w.attr("name", it) }
            if (n is BoundaryEventNode) {
                w.attr("attachedToRef", n.attachedToRef)
                if (!n.cancelActivity) w.attr("cancelActivity", "false")
            }
            when (n) {
                is ReceiveTask -> n.messageRef?.let { w.attr("messageRef", it) }
                is SendTask -> n.messageRef?.let { w.attr("messageRef", it) }
                is IntermediateCatchMessageEvent -> w.attr("messageRef", n.messageRef)
                is StartEvent -> n.messageRef?.let { w.attr("messageRef", it) }
                else -> Unit
            }
            if (n is ExclusiveGateway || n is InclusiveGateway) {
                n.defaultFlow?.let { w.attr("default", it) }
            }
            engine.elementAttributes(n).forEach { (k, v) -> w.attr(k, v) }

            n.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
            writeExtensions(n.extensions + engine.extensionElements(n), w)
            writeIncomingOutgoing(n, flowsInScope, w)

            if (n is EmbeddedSubprocess) {
                // closed scope: interior nodes and flows nest inside the element
                writeScopeNodes(n.nodes, n.sequenceFlows, w)
                writeScopeFlows(n.sequenceFlows, w)
            }
            writeEventDefinitions(n, w)
        }
    }

    /** incoming/outgoing are derived — never authored, so they can't drift. */
    private fun writeIncomingOutgoing(n: FlowNode, flowsInScope: List<SequenceFlow>, w: Xml) {
        flowsInScope.filter { it.targetRef == n.id }
            .forEach { w.textElem("bpmn", "incoming", BpmnNs.BPMN, it.id) }
        flowsInScope.filter { it.sourceRef == n.id }
            .forEach { w.textElem("bpmn", "outgoing", BpmnNs.BPMN, it.id) }
    }

    private fun writeEventDefinitions(n: FlowNode, w: Xml) {
        when (n) {
            is BoundaryTimerEvent -> {
                w.elem("bpmn", "timerEventDefinition", BpmnNs.BPMN) {
                    w.attr("id", "${n.id}_timer")
                    val (tag, value) = when {
                        n.timer.duration != null -> "timeDuration" to n.timer.duration
                        n.timer.cycle != null -> "timeCycle" to n.timer.cycle
                        else -> "timeDate" to n.timer.date
                    }
                    w.formalExpression(tag, value!!)
                }
            }
            is BoundaryMessageEvent ->
                w.messageEventDefinition("${n.id}_msgDef", n.messageRef)
            is BoundaryErrorEvent ->
                w.leaf("bpmn", "errorEventDefinition", BpmnNs.BPMN, linkedMapOf(
                    "id" to "${n.id}_errDef",
                ).also { n.errorRef?.let { ref -> it["errorRef"] = ref } })
            is IntermediateCatchMessageEvent ->
                w.messageEventDefinition("${n.id}_msgDef", n.messageRef)
            is StartEvent ->
                n.messageRef?.let { w.messageEventDefinition("${n.id}_msgDef", it) }
            is EndEvent ->
                n.errorRef?.let { ref ->
                    w.leaf("bpmn", "errorEventDefinition", BpmnNs.BPMN, linkedMapOf(
                        "id" to "${n.id}_errDef",
                        "errorRef" to ref,
                    ))
                }
            else -> Unit
        }
    }

    private fun writeScopeFlows(flows: List<SequenceFlow>, w: Xml) {
        for (f in flows) {
            w.elem("bpmn", "sequenceFlow", BpmnNs.BPMN) {
                w.attr("id", f.id)
                f.name?.let { w.attr("name", it) }
                w.attr("sourceRef", f.sourceRef)
                w.attr("targetRef", f.targetRef)
                f.documentation?.let { w.textElem("bpmn", "documentation", BpmnNs.BPMN, it.text) }
                f.condition?.let { cond ->
                    val attrs = mutableMapOf("xsi:type" to "bpmn:tFormalExpression")
                    if (cond.language != "juel") attrs["language"] = cond.language
                    w.textElem("bpmn", "conditionExpression", BpmnNs.BPMN, cond.body, attrs)
                }
            }
        }
    }

    private fun writeExtensions(extensions: List<ExtensionElement>, w: Xml) {
        if (extensions.isEmpty()) return
        w.elem("bpmn", "extensionElements", BpmnNs.BPMN) {
            extensions.forEach { writeExtensionElement(it, w) }
        }
    }

    private fun writeExtensionElement(e: ExtensionElement, w: Xml) {
        val ns = namespaceFor(e.prefix)
            ?: error(
                "Extension element '${e.name}' uses unbound prefix '${e.prefix}'; " +
                    "declare the namespace via EngineExtensionSerializer.namespaces()",
            )
        w.elem(e.prefix ?: "bpmn", e.name, ns) {
            e.attributes.forEach { (k, v) -> w.attr(k, v) }
            e.text?.let { w.text(it) }
            e.children.forEach { writeExtensionElement(it, w) }
        }
    }

    private fun namespaceFor(prefix: String?): String? = when (prefix) {
        null, "bpmn" -> BpmnNs.BPMN
        "bpmndi" -> BpmnNs.BPMNDI
        "dc" -> BpmnNs.DC
        "di" -> BpmnNs.DI
        "xsi" -> BpmnNs.XSI
        "bpmndsl" -> BpmnNs.BPMDSL
        else -> engine.namespaces()[prefix]
    }

    private fun writeDiagram(c: CollaborationDefinition, layout: DiagramLayout, w: Xml) {
        w.elem("bpmndi", "BPMNDiagram", BpmnNs.BPMNDI) {
            w.attr("id", "diagram_${c.id}")
            w.elem("bpmndi", "BPMNPlane", BpmnNs.BPMNDI) {
                w.attr("id", "plane_${c.id}")
                w.attr("bpmnElement", c.id)   // the plane covers the collaboration

                for (p in c.participants) {
                    layout.shapes[p.id]?.let { writeShape(p.id, it, layout, w, horizontal = true) }
                }
                for (proc in c.processes) {
                    for (lane in proc.lanes) {
                        layout.shapes[lane.id]?.let { writeShape(lane.id, it, layout, w, horizontal = true) }
                    }
                    writeNodeShapes(proc.nodes, layout, w)   // includes subprocess children
                }
                for (proc in c.processes) {
                    for (f in proc.allSequenceFlows()) {
                        layout.edges[f.id]?.let { writeEdge(f.id, it, layout, w) }
                    }
                }
                for (mf in c.messageFlows) {
                    layout.edges[mf.id]?.let { writeEdge(mf.id, it, layout, w) }
                }
            }
        }
    }

    private fun writeNodeShapes(nodes: List<FlowNode>, layout: DiagramLayout, w: Xml) {
        for (n in nodes) {
            layout.shapes[n.id]?.let { writeShape(n.id, it, layout, w) }
            if (n is EmbeddedSubprocess) writeNodeShapes(n.nodes, layout, w)
        }
    }

    private fun writeShape(
        semanticId: String,
        bounds: Bounds,
        layout: DiagramLayout,
        w: Xml,
        horizontal: Boolean = false,
    ) {
        w.elem("bpmndi", "BPMNShape", BpmnNs.BPMNDI) {
            w.attr("id", "shape_$semanticId")
            w.attr("bpmnElement", semanticId)   // semantic id, never the shape id
            if (horizontal) w.attr("isHorizontal", "true")
            writeDcBounds(bounds, w)
            writeLabel(semanticId, layout, w)
        }
    }

    private fun writeEdge(semanticId: String, waypoints: List<Point>, layout: DiagramLayout, w: Xml) {
        w.elem("bpmndi", "BPMNEdge", BpmnNs.BPMNDI) {
            w.attr("id", "edge_$semanticId")
            w.attr("bpmnElement", semanticId)
            for (pt in waypoints) {
                w.leaf("di", "waypoint", BpmnNs.DI, mapOf("x" to num(pt.x), "y" to num(pt.y)))
            }
            writeLabel(semanticId, layout, w)
        }
    }

    private fun writeDcBounds(bounds: Bounds, w: Xml) {
        w.leaf("dc", "Bounds", BpmnNs.DC, mapOf(
            "x" to num(bounds.x),
            "y" to num(bounds.y),
            "width" to num(bounds.width),
            "height" to num(bounds.height),
        ))
    }

    private fun writeLabel(semanticId: String, layout: DiagramLayout, w: Xml) {
        val labelBounds = layout.labels[semanticId] ?: return
        w.elem("bpmndi", "BPMNLabel", BpmnNs.BPMNDI) {
            writeDcBounds(labelBounds, w)
        }
    }

    /** "4410.0" → "4410"; "2911.5" stays "2911.5" — integers print bare. */
    private fun num(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private class Xml(val w: XMLStreamWriter) {
        private var depth = 0

        private fun indent() {
            w.writeCharacters("\n" + "  ".repeat(depth))
        }

        fun elem(prefix: String, local: String, ns: String, block: Xml.() -> Unit) {
            indent()
            w.writeStartElement(prefix, local, ns)
            depth++
            block()
            depth--
            indent()
            w.writeEndElement()
        }

        /** Element with attributes but no children — emits `<x …/>`. */
        fun leaf(prefix: String, local: String, ns: String, attrs: Map<String, String> = emptyMap()) {
            indent()
            w.writeEmptyElement(prefix, local, ns)
            attrs.forEach { (k, v) -> w.writeAttribute(k, v) }
        }

        /**
         * `elem` when [hasChildren], `leaf` otherwise — in the leaf case
         * [block] must only write attributes.
         */
        fun leafOrElem(
            prefix: String,
            local: String,
            ns: String,
            hasChildren: Boolean,
            block: Xml.() -> Unit,
        ) {
            if (hasChildren) {
                elem(prefix, local, ns, block)
            } else {
                indent()
                w.writeEmptyElement(prefix, local, ns)
                block()
            }
        }

        fun textElem(
            prefix: String,
            local: String,
            ns: String,
            text: String,
            attrs: Map<String, String> = emptyMap(),
        ) {
            indent()
            w.writeStartElement(prefix, local, ns)
            attrs.forEach { (k, v) -> w.writeAttribute(k, v) }
            w.writeCharacters(text)
            w.writeEndElement()
        }

        fun formalExpression(local: String, body: String) {
            textElem("bpmn", local, BpmnNs.BPMN, body, mapOf("xsi:type" to "bpmn:tFormalExpression"))
        }

        fun messageEventDefinition(id: String, messageRef: String) {
            leaf("bpmn", "messageEventDefinition", BpmnNs.BPMN,
                mapOf("id" to id, "messageRef" to messageRef))
        }

        fun attr(name: String, value: String) = w.writeAttribute(name, value)
        fun ns(prefix: String, uri: String) = w.writeNamespace(prefix, uri)
        fun text(t: String) = w.writeCharacters(t)
    }
}
