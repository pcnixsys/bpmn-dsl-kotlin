package `in`.o612.eng.bpmn

import `in`.o612.eng.bpmn.dsl.bpmnCollaboration
import `in`.o612.eng.bpmn.example.enterpriseProcurementCollaboration
import `in`.o612.eng.bpmn.example.generatedDeploymentXml
import `in`.o612.eng.bpmn.flowable.FlowableExtensionSerializer
import `in`.o612.eng.bpmn.layout.LayeredLayoutEngine
import `in`.o612.eng.bpmn.model.BoundaryEventNode
import `in`.o612.eng.bpmn.model.BoundaryMessageEvent
import `in`.o612.eng.bpmn.model.CollaborationDefinition
import `in`.o612.eng.bpmn.model.EndEvent
import `in`.o612.eng.bpmn.model.ParticipantDefinition
import `in`.o612.eng.bpmn.model.ProcessDefinition
import `in`.o612.eng.bpmn.model.SequenceFlow
import `in`.o612.eng.bpmn.model.ServiceTask
import `in`.o612.eng.bpmn.model.StartEvent
import `in`.o612.eng.bpmn.validation.BpmnValidator
import `in`.o612.eng.bpmn.validation.DiagnosticCodes
import `in`.o612.eng.bpmn.validation.Severity
import `in`.o612.eng.bpmn.xml.BpmnXmlWriter
import `in`.o612.eng.bpmn.xml.BpmnNs
import `in`.o612.eng.bpmn.xml.EngineExtensionSerializer
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnDslTest {

    private val validator = BpmnValidator()

    @Test
    fun `dsl compiles to an immutable ast`() {
        val c = bpmnCollaboration("tiny") {
            participant("pool") {
                executable = true
                process("proc") {
                    lane("work") {
                        startEvent("start")
                        serviceTask("doWork") { delegate = "worker" }
                        endEvent("done")
                    }
                    sequenceFlow("f1", "start", "doWork")
                    sequenceFlow("f2", "doWork", "done")
                }
            }
        }
        val proc = c.processes.single()
        assertEquals(3, proc.nodes.size)                 // lane-owned nodes landed in the process
        assertTrue(proc.nodes[0] is StartEvent)
        assertEquals("worker", (proc.nodes[1] as ServiceTask).metadata["delegate"])
    }

    /** The phase-3 ledger: the running example's shape is the fixture contract. */
    @Test
    fun `procurement collaboration matches the ledger`() {
        val c = enterpriseProcurementCollaboration()

        assertEquals(2, c.participants.size)
        assertEquals(2, c.processes.size)
        assertEquals(7, c.messages.size)
        assertEquals(7, c.messageFlows.size)

        val buyer = c.processes.single { it.id == "buyerProcurementProcess" }
        val supplier = c.processes.single { it.id == "supplierInteractionProcess" }
        assertTrue(buyer.isExecutable)
        assertFalse(supplier.isExecutable)

        assertEquals(6, buyer.lanes.size)
        assertEquals(2, supplier.lanes.size)
        assertEquals(31, buyer.nodes.size)          // incl. 3 boundary events + subprocess shell
        assertEquals(35, buyer.allNodes().size)     // + 4 subprocess-interior nodes
        assertEquals(12, supplier.allNodes().size)
        assertEquals(37, buyer.sequenceFlows.size)  // 68 buyer flow elements = 31 nodes + 37 flows
        assertEquals(13, supplier.sequenceFlows.size)
        assertEquals(21, buyer.variables.size)

        val boundaries = buyer.allNodes().filterIsInstance<BoundaryEventNode>()
        assertEquals(3, boundaries.size)
        assertTrue(boundaries.all { it.cancelActivity })   // all interrupting
        assertEquals(
            setOf("waitForQuotation", "waitForAcknowledgement"),
            boundaries.map { it.attachedToRef }.toSet(),
        )
        assertEquals(2, boundaries.count { it.attachedToRef == "waitForAcknowledgement" })
    }

    /**
     * The happy path is the hardest test to write first: the full example is
     * the hardest thing the pipeline produces. Zero *warnings* is the
     * meaningful assertion — "validates with warnings" silently accrues debt.
     */
    @Test
    fun `procurement collaboration validates clean`() {
        val c = enterpriseProcurementCollaboration()
        val diags = BpmnValidator().validate(c)
        assertEquals(emptyList(), diags.filter { it.severity == Severity.ERROR },
            "unexpected errors: $diags")
        assertEquals(emptyList(), diags.filter { it.severity == Severity.WARNING },
            "unexpected warnings: $diags")
    }

    @Test
    fun `duplicate ids are rejected document-wide`() {
        val c = CollaborationDefinition(
            "c",
            participants = listOf(ParticipantDefinition("pa", processRef = "p")),
            processes = listOf(
                ProcessDefinition(
                    "p", isExecutable = true,
                    nodes = listOf(StartEvent("s"), StartEvent("s"), EndEvent("e")),
                    sequenceFlows = listOf(SequenceFlow("f1", sourceRef = "s", targetRef = "e")),
                ),
            ),
        )
        val diags = validator.validate(c)
        assertTrue(diags.any { it.code == DiagnosticCodes.DUPLICATE_ID && it.elementId == "s" })
    }

    @Test
    fun `unresolved flow endpoints are rejected`() {
        val c = bpmnCollaboration("c") {
            participant("p") {
                process("proc") {
                    startEvent("s")
                    endEvent("e")
                    sequenceFlow("f1", "s", "ghost")
                }
            }
        }
        val diags = validator.validate(c)
        assertTrue(diags.any { it.code == DiagnosticCodes.FLOW_ENDPOINT && it.elementId == "f1" })
    }

    @Test
    fun `conditions after a parallel gateway are rejected`() {
        val c = bpmnCollaboration("c") {
            participant("p") {
                process("proc") {
                    startEvent("s")
                    parallelGateway("g")
                    userTask("a") {}
                    endEvent("e")
                    sequenceFlow("f1", "s", "g")
                    conditionalFlow("f2", "g", "a", "x == 1")
                    sequenceFlow("f3", "a", "e")
                }
            }
        }
        val diags = validator.validate(c)
        assertTrue(diags.any { it.code == DiagnosticCodes.CONDITION_FORBIDDEN && it.elementId == "f2" })
    }

    @Test
    fun `unreachable nodes are flagged`() {
        val c = bpmnCollaboration("c") {
            participant("p") {
                process("proc") {
                    startEvent("s")
                    userTask("connected") {}
                    userTask("orphan") {}
                    endEvent("e")
                    sequenceFlow("f1", "s", "connected")
                    sequenceFlow("f2", "connected", "e")
                }
            }
        }
        val diags = validator.validate(c)
        assertTrue(diags.any {
            it.code == DiagnosticCodes.UNREACHABLE_NODE && it.elementId == "orphan"
        })
    }

    @Test
    fun `boundary events may not attach to events or gateways`() {
        // The DSL can't express this — boundary functions only exist on
        // activity builders. Construct the AST directly.
        val c = CollaborationDefinition(
            "c",
            participants = listOf(ParticipantDefinition("pa", processRef = "p")),
            processes = listOf(
                ProcessDefinition(
                    "p", isExecutable = true,
                    nodes = listOf(
                        StartEvent("s"),
                        ServiceTask("t"),
                        EndEvent("e"),
                        BoundaryMessageEvent("b", attachedToRef = "s", messageRef = "m"),
                    ),
                    sequenceFlows = listOf(
                        SequenceFlow("f1", sourceRef = "s", targetRef = "t"),
                        SequenceFlow("f2", sourceRef = "t", targetRef = "e"),
                        SequenceFlow("fb", sourceRef = "b", targetRef = "e"),
                    ),
                ),
            ),
            messages = listOf(`in`.o612.eng.bpmn.model.MessageDefinition("m")),
        )
        val diags = validator.validate(c)
        assertTrue(diags.any { it.code == DiagnosticCodes.BOUNDARY_TARGET && it.elementId == "b" })
    }

    @Test
    fun `message flows may not connect two nodes in the same pool`() {
        val c = bpmnCollaboration("c") {
            participant("p") {
                process("proc") {
                    startEvent("s")
                    sendTask("a") { messageRef = "m" }
                    receiveTask("b") { messageRef = "m" }
                    endEvent("e")
                    sequenceFlow("f1", "s", "a")
                    sequenceFlow("f2", "a", "b")
                    sequenceFlow("f3", "b", "e")
                }
            }
            message("m")
            messageFlow("mf", from = "a", to = "b", message = "m")
        }
        val diags = validator.validate(c)
        assertTrue(diags.any { it.code == DiagnosticCodes.MSG_FLOW_SAME_POOL && it.elementId == "mf" })
    }

    @Test
    fun `subprocess interiors validate as their own scope`() {
        val c = bpmnCollaboration("c") {
            participant("p") {
                process("proc") {
                    startEvent("s")
                    endEvent("e")
                    embeddedSubprocess("sub") {
                        userTask("inner") {}
                    }
                    sequenceFlow("f1", "s", "sub")
                    sequenceFlow("f2", "sub", "e")
                }
            }
        }
        val diags = validator.validate(c)
        assertTrue(diags.any { it.code == DiagnosticCodes.NO_START_EVENT && it.elementId == "sub" })
        assertTrue(diags.any { it.code == DiagnosticCodes.NO_END_EVENT && it.elementId == "sub" })
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(org.xml.sax.InputSource(StringReader(xml)))

    @Test
    fun `generated semantic xml parses and carries the structure`() {
        val xml = BpmnXmlWriter(EngineExtensionSerializer.None)
            .writeToString(enterpriseProcurementCollaboration())
        val doc = parse(xml)

        val defs = doc.getElementsByTagNameNS(BpmnNs.BPMN, "definitions").item(0)
        assertNotNull(defs)
        assertEquals("enterpriseProcurement_definitions",
            defs.attributes.getNamedItem("id").nodeValue)
        assertEquals(BpmnNs.TARGET_NAMESPACE,
            defs.attributes.getNamedItem("targetNamespace").nodeValue)
        // vendor-neutral: no flowable namespace or attributes without the adapter
        assertFalse(xml.contains("flowable"))

        val proc = doc.getElementsByTagNameNS(BpmnNs.BPMN, "process")
        assertEquals(2, proc.length)
        assertEquals("true", proc.item(0).attributes.getNamedItem("isExecutable").nodeValue)
        assertEquals("false", proc.item(1).attributes.getNamedItem("isExecutable").nodeValue)

        assertEquals(7, doc.getElementsByTagNameNS(BpmnNs.BPMN, "message").length)
        assertEquals(7, doc.getElementsByTagNameNS(BpmnNs.BPMN, "messageFlow").length)
        assertEquals(2, doc.getElementsByTagNameNS(BpmnNs.BPMN, "laneSet").length)
        assertEquals(8, doc.getElementsByTagNameNS(BpmnNs.BPMN, "lane").length)

        // boundary event semantics: attachedToRef + a formal timer definition
        val boundary = doc.getElementsByTagNameNS(BpmnNs.BPMN, "boundaryEvent")
        assertEquals(3, boundary.length)
        val timerDef = doc.getElementsByTagNameNS(BpmnNs.BPMN, "timerEventDefinition")
        assertEquals(2, timerDef.length)
        val dur = doc.getElementsByTagNameNS(BpmnNs.BPMN, "timeDuration").item(0)
        assertEquals("bpmn:tFormalExpression",
            dur.attributes.getNamedItemNS(BpmnNs.XSI, "type").nodeValue)
        assertEquals("PT72H", dur.textContent.trim())

        // variable metadata rides inside bpmndsl extension elements
        val vars = doc.getElementsByTagNameNS(BpmnNs.BPMDSL, "variable")
        assertEquals(21, vars.length)

        // conditions serialize as formal expressions
        val conds = doc.getElementsByTagNameNS(BpmnNs.BPMN, "conditionExpression")
        assertTrue(conds.length > 0)
    }

    @Test
    fun `layout satisfies every di invariant on the example`() {
        val c = enterpriseProcurementCollaboration()
        val layout = LayeredLayoutEngine().layout(c)
        val diags = BpmnValidator().validateDiagram(c, layout)
        assertEquals(emptyList(), diags.filter { it.severity == Severity.ERROR },
            "di errors: $diags")
    }

    @Test
    fun `layout is deterministic and complete`() {
        val c = enterpriseProcurementCollaboration()
        val a = LayeredLayoutEngine().layout(c)
        val b = LayeredLayoutEngine().layout(c)
        assertEquals(a, b)   // same input → byte-identical geometry

        // semantic → DI: every node has a shape, every flow an edge
        for (n in c.processes.flatMap { it.allNodes() }) {
            assertTrue(n.id in a.shapes, "no shape for ${n.id}")
        }
        for (f in c.processes.flatMap { it.allSequenceFlows() }) {
            assertTrue(f.id in a.edges && a.edges.getValue(f.id).size >= 2,
                "no edge for ${f.id}")
        }
        for (mf in c.messageFlows) {
            assertTrue(mf.id in a.edges && a.edges.getValue(mf.id).size >= 2,
                "no edge for ${mf.id}")
        }
        // the reference coordinate contract (Part 10 excerpt)
        assertEquals(
            `in`.o612.eng.bpmn.model.Bounds(1794.0, 242.0, 120.0, 80.0),
            a.shapes["waitForQuotation"],
        )
    }

    /**
     * The expensive property: *every* semantic element has DI geometry, and
     * every DI element references a real semantic id. Both directions matter —
     * catching only one lets a node exist in XML but not the diagram, or a
     * shape exist for nothing.
     */
    @Test
    fun `generated xml parses and di references resolve`() {
        val c = enterpriseProcurementCollaboration()
        val layout = LayeredLayoutEngine().layout(c)
        val xml = BpmnXmlWriter(FlowableExtensionSerializer).writeToString(c, layout)
        val doc = parse(xml)

        // collect every semantic id in the document
        val ids = mutableSetOf<String>()
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            all.item(i).attributes?.getNamedItem("id")?.nodeValue?.let(ids::add)
        }

        // DI → semantic: every bpmnElement reference resolves
        val shapes = doc.getElementsByTagNameNS(BpmnNs.BPMNDI, "BPMNShape")
        val edges = doc.getElementsByTagNameNS(BpmnNs.BPMNDI, "BPMNEdge")
        for (i in 0 until shapes.length + edges.length) {
            val el = if (i < shapes.length) shapes.item(i) else edges.item(i - shapes.length)
            val ref = el.attributes.getNamedItem("bpmnElement").nodeValue
            assertTrue(ref in ids, "BPMN DI element references missing semantic id $ref")
        }

        // semantic → DI: every visible node has a shape; every flow has a route
        for (n in c.processes.flatMap { it.allNodes() }) {
            assertTrue(n.id in layout.shapes, "no shape for ${n.id}")
        }
        val allFlowIds = c.processes.flatMap { p -> p.allSequenceFlows().map { it.id } } +
            c.messageFlows.map { it.id }
        for (id in allFlowIds) {
            assertTrue(id in layout.edges, "no edge for $id")
        }
    }

    @Test
    fun `generation is deterministic`() {
        val a = BpmnXmlWriter(FlowableExtensionSerializer)
            .writeToString(enterpriseProcurementCollaboration(), layout(enterpriseProcurementCollaboration()))
        val b = BpmnXmlWriter(FlowableExtensionSerializer)
            .writeToString(enterpriseProcurementCollaboration(), layout(enterpriseProcurementCollaboration()))
        assertEquals(a, b)
    }

    private fun layout(c: `in`.o612.eng.bpmn.model.CollaborationDefinition) =
        LayeredLayoutEngine().layout(c)

    @Test
    fun `diagram validation catches a missing shape`() {
        val c = enterpriseProcurementCollaboration()
        val layout = LayeredLayoutEngine().layout(c)
        val broken = layout.copy(shapes = layout.shapes - "sendRfq")   // drop one shape
        val diags = validator.validateDiagram(c, broken)
        assertTrue(diags.any {
            it.code == DiagnosticCodes.DI_MISSING_SHAPE && it.elementId == "sendRfq"
        })
    }

    @Test
    fun `flowable adapter emits vendor attributes only when present`() {
        val c = enterpriseProcurementCollaboration()
        val layout = LayeredLayoutEngine().layout(c)

        val withAdapter = BpmnXmlWriter(FlowableExtensionSerializer).writeToString(c, layout)
        assertTrue(withAdapter.contains("xmlns:flowable=\"${BpmnNs.FLOWABLE}\""))
        assertTrue(withAdapter.contains(
            "flowable:delegateExpression=\"\${requisitionValidationDelegate}\""))
        assertTrue(withAdapter.contains("flowable:candidateGroups=\"finance-approvers\""))
        assertTrue(withAdapter.contains("flowable:formKey=\"purchase-requisition-form\""))

        val without = BpmnXmlWriter(EngineExtensionSerializer.None).writeToString(c, layout)
        assertFalse(without.contains("flowable"))
    }

    @Test
    fun `deployment helper produces deployable executable-pool xml`() {
        val xml = generatedDeploymentXml()
        assertTrue(xml.contains("isExecutable=\"true\""))
        assertTrue(xml.contains("isExecutable=\"false\""))
        assertTrue(xml.contains("flowable:delegateExpression"))
    }
}
