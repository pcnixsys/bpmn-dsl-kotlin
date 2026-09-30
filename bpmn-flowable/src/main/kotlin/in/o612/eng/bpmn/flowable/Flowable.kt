package `in`.o612.eng.bpmn.flowable

import `in`.o612.eng.bpmn.model.ActivityNode
import `in`.o612.eng.bpmn.model.ExtensionElement
import `in`.o612.eng.bpmn.model.FlowNode
import `in`.o612.eng.bpmn.model.ServiceTask
import `in`.o612.eng.bpmn.model.UserTask
import `in`.o612.eng.bpmn.xml.BpmnNs
import `in`.o612.eng.bpmn.xml.EngineExtensionSerializer

/**
 * The Flowable adapter: DSL metadata → `flowable:*` XML. The core model stays
 * engine-neutral — `delegate = "requisitionValidationDelegate"` is a string in
 * `ActivityNode.metadata`; only this object decides it becomes
 * `flowable:delegateExpression="${requisitionValidationDelegate}"`.
 *
 * Swap the adapter and the same DSL emits Camunda-flavored XML — that's the
 * whole point of the `EngineExtensionSerializer` seam.
 */
object FlowableExtensionSerializer : EngineExtensionSerializer {

    override fun namespaces() = mapOf("flowable" to BpmnNs.FLOWABLE)

    override fun elementAttributes(node: FlowNode): Map<String, String> {
        val m = (node as? ActivityNode)?.metadata ?: return emptyMap()
        return buildMap {
            when (node) {
                is ServiceTask -> {
                    // "beanName" → "${beanName}" is adapter logic, not DSL syntax
                    m["delegate"]?.let { put("flowable:delegateExpression", "\${$it}") }
                    m["class"]?.let { put("flowable:class", it) }
                    m["expression"]?.let { put("flowable:expression", it) }
                }
                is UserTask -> {
                    m["formKey"]?.let { put("flowable:formKey", it) }
                    m["assignee"]?.let { put("flowable:assignee", it) }
                    m["candidateGroup"]?.let { put("flowable:candidateGroups", it) }
                    m["candidateUsers"]?.let { put("flowable:candidateUsers", it) }
                }
                else -> Unit
            }
        }
    }

    override fun extensionElements(node: FlowNode): List<ExtensionElement> = emptyList()
}
