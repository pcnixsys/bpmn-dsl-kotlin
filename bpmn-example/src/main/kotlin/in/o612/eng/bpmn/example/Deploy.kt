package `in`.o612.eng.bpmn.example

import `in`.o612.eng.bpmn.flowable.FlowableExtensionSerializer
import `in`.o612.eng.bpmn.layout.LayeredLayoutEngine
import `in`.o612.eng.bpmn.model.CollaborationDefinition
import `in`.o612.eng.bpmn.xml.BpmnXmlWriter

/**
 * Deployment seam for Flowable — kept dependency-free on purpose.
 *
 * The generated collaboration file can be deployed wholesale; Flowable deploys
 * only `isExecutable="true"` processes (the buyer pool). The supplier pool is
 * documentation of an external participant — its nodes need no delegates,
 * task handlers, or Spring beans because nothing executes them.
 *
 * `delegate` metadata resolves at runtime to Spring beans implementing
 * `JavaDelegate`; `candidateGroup` maps to Flowable identity groups;
 * `formKey` maps to a form definition. The DSL produces the references —
 * the application provides the referents.
 *
 * ### Minimal Spring Boot deployment (needs `flowable-spring-boot-starter-process`)
 *
 * ```kotlin
 * @SpringBootApplication
 * class ProcureApp
 *
 * fun deployGenerated(collaboration: CollaborationDefinition, repositoryService: RepositoryService) {
 *     repositoryService.createDeployment()
 *         .name("enterprise-procurement")
 *         .addString("procurement.bpmn", generatedDeploymentXml(collaboration))
 *         .deploy()
 * }
 * ```
 *
 * ### Or via Flowable's REST API — no Flowable dependency at all
 *
 * `./gradlew run` produces `build/procurement.bpmn`; then:
 *
 * ```bash
 * curl -u admin:test -F "file=@build/procurement.bpmn" \
 *      http://localhost:8080/flowable-rest/service/repository/deployments
 * ```
 *
 * Message flows are a contract surface, not transport — nothing in the file
 * *delivers* the RFQ; `sendRfq`'s delegate does. Each message flow needs a
 * delegate, listener, or external system to actually send or receive.
 */
fun generatedDeploymentXml(collaboration: CollaborationDefinition = enterpriseProcurementCollaboration()): String =
    BpmnXmlWriter(FlowableExtensionSerializer)
        .writeToString(collaboration, LayeredLayoutEngine().layout(collaboration))
