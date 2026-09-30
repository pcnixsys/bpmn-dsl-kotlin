package `in`.o612.eng.bpmn

import `in`.o612.eng.bpmn.example.enterpriseProcurementCollaboration
import `in`.o612.eng.bpmn.flowable.FlowableExtensionSerializer
import `in`.o612.eng.bpmn.layout.LayeredLayoutEngine
import `in`.o612.eng.bpmn.validation.BpmnValidator
import `in`.o612.eng.bpmn.validation.Severity
import `in`.o612.eng.bpmn.xml.BpmnXmlWriter
import java.io.File

/**
 * The pipeline in miniature: build → validate → lay out → validate DI → write.
 * Validation gates generation — no XML is written unless both passes are clean.
 */
fun main() {
    val collaboration = enterpriseProcurementCollaboration()

    val validator = BpmnValidator()
    val diagnostics = validator.validate(collaboration)
    diagnostics.forEach {
        println("[${it.severity}] ${it.code} ${it.elementId ?: "-"}: ${it.message}")
    }
    check(diagnostics.none { it.severity == Severity.ERROR }) {
        "Validation failed — see diagnostics above"
    }

    val layout = LayeredLayoutEngine().layout(collaboration)
    val diDiags = validator.validateDiagram(collaboration, layout)
    diDiags.forEach {
        println("[${it.severity}] ${it.code} ${it.elementId ?: "-"}: ${it.message}")
    }
    check(diDiags.none { it.severity == Severity.ERROR }) {
        "Diagram validation failed — see diagnostics above"
    }

    val xml = BpmnXmlWriter(FlowableExtensionSerializer).writeToString(collaboration, layout)
    val out = File("build/procurement.bpmn").apply { parentFile?.mkdirs() }
    out.writeText(xml)
    println("wrote ${out.path} (${xml.length} chars)")
}
