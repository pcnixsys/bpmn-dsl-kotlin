package `in`.o612.eng.bpmn.example

import `in`.o612.eng.bpmn.dsl.bpmnCollaboration
import `in`.o612.eng.bpmn.model.CollaborationDefinition

/**
 * The series' running example — enterprise procurement and supplier
 * onboarding (Part 4). ~250 lines of DSL expanding to ~51 KB of BPMN 2.0.
 *
 * Buyer Organization — executable pool deployed to Flowable; six lanes.
 * Supplier — non-executable pool; external participant reachable only through
 * message flows.
 *
 * Design decisions carried here (per the series blueprint):
 * - Conditional approvals use an inclusive gateway pair with a default
 *   bypass — a parallel split would deadlock when a branch is suppressed.
 * - Waiting for supplier messages is modeled with receive tasks, not catch
 *   events — boundary events cannot attach to events.
 * - supplierActionDecision: SUBSTITUTE loops back to sendRfq; cancel ends at
 *   procurementCancelled.
 * - The supplier pool uses an event-based gateway (awaitBuyerResponse) — the
 *   supplier cannot know which message arrives first.
 * - PO rejection is an interrupting boundary message event on
 *   waitForAcknowledgement, routed to resolveSupplierFailure.
 */
fun enterpriseProcurementCollaboration(): CollaborationDefinition =
    bpmnCollaboration("enterpriseProcurement") {
        name = "Enterprise Procurement and Supplier Onboarding"

        participant("buyerOrganization") {
            name = "Buyer Organization"
            executable = true

            process("buyerProcurementProcess") {
                name = "Buyer Procurement Process"

                variables {
                    string("requisitionId", required = true)
                    string("requesterId", required = true)
                    string("departmentCode")
                    string("supplierId", required = true)
                    decimal("estimatedAmount", required = true)
                    string("currency")
                    boolean("regulatedPurchase")
                    string("validationStatus")
                    boolean("budgetApprovalRequired")
                    boolean("legalApprovalRequired")
                    string("budgetApprovalStatus")
                    string("legalApprovalStatus")
                    string("quotationStatus")
                    decimal("quotedAmount")
                    string("purchaseOrderId")
                    string("acknowledgementStatus")
                    string("invoiceId")
                    string("threeWayMatchStatus")
                    string("exceptionReason")
                    integer("escalationCount")
                    string("supplierAction")
                }

                lane("requestingDepartment") {
                    name = "Requesting Department"

                    startEvent("requisitionCreated") { name = "Requisition created" }
                    userTask("submitRequisition") {
                        name = "Submit Purchase Requisition"
                        formKey = "purchase-requisition-form"
                    }
                    userTask("correctRequisition") {
                        name = "Correct Purchase Requisition"
                        formKey = "purchase-requisition-correction-form"
                    }
                }

                lane("procurementAutomation") {
                    name = "Procurement Automation"

                    serviceTask("validateRequisition") {
                        name = "Validate Requisition and Supplier"
                        delegate = "requisitionValidationDelegate"
                    }
                    exclusiveGateway("validationDecision") {
                        name = "Requisition valid?"
                        defaultFlow = "flowInvalidToCorrect"
                    }
                    inclusiveGateway("approvalsSplit") {
                        name = "Required approvals"
                        defaultFlow = "flowApprovalsBypass"
                    }
                    inclusiveGateway("approvalsJoin") { name = "Approvals complete" }

                    sendTask("sendRfq") {
                        name = "Send Request for Quotation"
                        messageRef = "rfqRequested"
                    }
                    receiveTask("waitForQuotation") {
                        name = "Wait for Supplier Quotation"
                        messageRef = "supplierQuotationReceived"
                        boundaryTimer("quotationSlaExceeded") {
                            name = "72h supplier SLA"
                            duration = "PT72H"
                            cancelActivity = true
                        }
                    }
                    sendTask("sendSupplierReminder") { name = "Send Supplier Reminder" }
                    exclusiveGateway("quotationRetryDecision") {
                        name = "Retry quotation wait?"
                        defaultFlow = "flowEscalateSupplier"
                    }
                    sendTask("requestRenegotiation") {
                        name = "Request Quotation Renegotiation"
                        messageRef = "quotationRenegotiationRequested"
                    }
                    serviceTask("createPurchaseOrder") {
                        name = "Create Purchase Order"
                        delegate = "purchaseOrderCreationDelegate"
                    }
                    receiveTask("waitForAcknowledgement") {
                        name = "Wait for Order Acknowledgement"
                        messageRef = "purchaseOrderAcknowledged"
                        boundaryTimer("acknowledgementSlaExceeded") {
                            name = "48h ack SLA"
                            duration = "PT48H"
                            cancelActivity = true
                        }
                        boundaryMessage("purchaseOrderRejectedEvent", message = "purchaseOrderRejected") {
                            name = "PO rejected"
                            cancelActivity = true
                        }
                    }
                    serviceTask("performThreeWayMatch") {
                        name = "Perform Three-Way Match"
                        delegate = "threeWayMatchDelegate"
                    }
                    exclusiveGateway("threeWayMatchDecision") {
                        name = "Match successful?"
                        defaultFlow = "flowMatchToException"
                    }
                    serviceTask("authorizePayment") {
                        name = "Authorize Payment"
                        delegate = "paymentAuthorizationDelegate"
                    }
                    endEvent("procurementCompleted") { name = "Procurement completed" }
                    endEvent("procurementCancelled") { name = "Procurement cancelled" }
                }

                lane("finance") {
                    name = "Finance and Budget Control"
                    userTask("approveBudget") {
                        name = "Approve Budget"
                        candidateGroup = "finance-approvers"
                    }
                }

                lane("legal") {
                    name = "Legal and Compliance"
                    userTask("reviewLegalCompliance") {
                        name = "Review Legal and Compliance"
                        candidateGroup = "legal-reviewers"
                    }
                }

                lane("procurement") {
                    name = "Procurement Team"
                    userTask("evaluateQuotation") {
                        name = "Evaluate Supplier Quotation"
                        candidateGroup = "procurement-officers"
                    }
                    userTask("resolveSupplierEscalation") {
                        name = "Resolve Supplier Escalation"
                        candidateGroup = "procurement-officers"
                    }
                    userTask("resolveSupplierFailure") {
                        name = "Resolve Supplier Rejection or Failure"
                        candidateGroup = "procurement-officers"
                    }
                    exclusiveGateway("quotationDecision") {
                        name = "Quotation acceptable?"
                        defaultFlow = "flowRejectToFailure"
                    }
                    exclusiveGateway("supplierActionDecision") {
                        name = "Supplier outcome?"
                        defaultFlow = "flowCancelProcurement"
                    }
                }

                lane("accountsPayable") {
                    name = "Accounts Payable"

                    receiveTask("receiveInvoice") {
                        name = "Receive Supplier Invoice"
                        messageRef = "supplierInvoiceSubmitted"
                    }
                    embeddedSubprocess("invoiceExceptionHandling") {
                        name = "Invoice Exception Handling"

                        startEvent("exceptionStart")
                        userTask("investigateMismatch") {
                            name = "Investigate Match Failure"
                            candidateGroup = "accounts-payable"
                        }
                        userTask("resolveSupplierInvoiceIssue") {
                            name = "Resolve Supplier Invoice Issue"
                            candidateGroup = "procurement-officers"
                        }
                        endEvent("exceptionResolved") { name = "Exception resolved" }

                        sequenceFlow("flowExceptionStart", "exceptionStart", "investigateMismatch")
                        sequenceFlow("flowInvestigateToResolve", "investigateMismatch", "resolveSupplierInvoiceIssue")
                        sequenceFlow("flowResolveToEnd", "resolveSupplierInvoiceIssue", "exceptionResolved")
                    }
                }

                // Spine: requisition intake and validation loop
                sequenceFlow("flowStartToSubmit", "requisitionCreated", "submitRequisition")
                sequenceFlow("flowSubmitToValidate", "submitRequisition", "validateRequisition")
                sequenceFlow("flowCorrectToValidate", "correctRequisition", "validateRequisition")
                sequenceFlow("flowValidateToDecision", "validateRequisition", "validationDecision")
                sequenceFlow("flowInvalidToCorrect", "validationDecision", "correctRequisition", name = "invalid")
                conditionalFlow(
                    "flowValidToApprovals", "validationDecision", "approvalsSplit",
                    "\${validationStatus == 'VALID'}", name = "valid",
                )

                // Conditional parallel approvals — inclusive split/join pair
                conditionalFlow(
                    "flowBudgetBranch", "approvalsSplit", "approveBudget",
                    "\${budgetApprovalRequired}", name = "budget approval",
                )
                conditionalFlow(
                    "flowLegalBranch", "approvalsSplit", "reviewLegalCompliance",
                    "\${legalApprovalRequired}", name = "legal review",
                )
                sequenceFlow("flowApprovalsBypass", "approvalsSplit", "approvalsJoin", name = "none required")
                sequenceFlow("flowBudgetToJoin", "approveBudget", "approvalsJoin")
                sequenceFlow("flowLegalToJoin", "reviewLegalCompliance", "approvalsJoin")
                sequenceFlow("flowApprovalsToRfq", "approvalsJoin", "sendRfq")

                // Quotation wait: 72h SLA, reminder, bounded retry
                sequenceFlow("flowRfqToWait", "sendRfq", "waitForQuotation")
                sequenceFlow("flowQuotationToEval", "waitForQuotation", "evaluateQuotation")
                sequenceFlow("flowSlaToReminder", "quotationSlaExceeded", "sendSupplierReminder")
                sequenceFlow("flowReminderToRetry", "sendSupplierReminder", "quotationRetryDecision")
                conditionalFlow(
                    "flowRetryWait", "quotationRetryDecision", "waitForQuotation",
                    "\${escalationCount < 2}", name = "retry",
                )
                sequenceFlow("flowEscalateSupplier", "quotationRetryDecision", "resolveSupplierEscalation", name = "escalate")
                sequenceFlow("flowEscalationToEval", "resolveSupplierEscalation", "evaluateQuotation")

                // Quotation outcome
                sequenceFlow("flowEvalToDecision", "evaluateQuotation", "quotationDecision")
                conditionalFlow(
                    "flowAcceptableToPo", "quotationDecision", "createPurchaseOrder",
                    "\${quotationStatus == 'ACCEPTABLE'}", name = "acceptable",
                )
                conditionalFlow(
                    "flowRenegotiate", "quotationDecision", "requestRenegotiation",
                    "\${quotationStatus == 'RENEGOTIATE'}", name = "renegotiate",
                )
                sequenceFlow("flowRejectToFailure", "quotationDecision", "resolveSupplierFailure", name = "reject")
                sequenceFlow("flowRenegotiationToWait", "requestRenegotiation", "waitForQuotation")

                // PO acknowledgement, SLA and rejection handling
                sequenceFlow("flowPoToWaitAck", "createPurchaseOrder", "waitForAcknowledgement")
                sequenceFlow("flowAckToInvoice", "waitForAcknowledgement", "receiveInvoice")
                sequenceFlow("flowAckSlaToFailure", "acknowledgementSlaExceeded", "resolveSupplierFailure")
                sequenceFlow("flowPoRejectedToFailure", "purchaseOrderRejectedEvent", "resolveSupplierFailure")
                sequenceFlow("flowFailureToDecision", "resolveSupplierFailure", "supplierActionDecision")
                conditionalFlow(
                    "flowSubstituteSupplier", "supplierActionDecision", "sendRfq",
                    "\${supplierAction == 'SUBSTITUTE'}", name = "substitute supplier",
                )
                sequenceFlow("flowCancelProcurement", "supplierActionDecision", "procurementCancelled", name = "cancel")

                // Invoice → three-way match → payment (exception loop back to re-match)
                sequenceFlow("flowInvoiceToMatch", "receiveInvoice", "performThreeWayMatch")
                sequenceFlow("flowMatchToDecision", "performThreeWayMatch", "threeWayMatchDecision")
                conditionalFlow(
                    "flowMatchOkToPayment", "threeWayMatchDecision", "authorizePayment",
                    "\${threeWayMatchStatus == 'MATCHED'}", name = "matched",
                )
                sequenceFlow("flowMatchToException", "threeWayMatchDecision", "invoiceExceptionHandling", name = "mismatch")
                sequenceFlow("flowExceptionToRematch", "invoiceExceptionHandling", "performThreeWayMatch")
                sequenceFlow("flowPaymentToDone", "authorizePayment", "procurementCompleted")
            }
        }

        participant("supplier") {
            name = "Supplier"
            executable = false

            process("supplierInteractionProcess") {
                name = "Supplier Interaction Process"

                lane("supplierSales") {
                    name = "Supplier Sales"
                    startEvent("supplierEngagementStarted") { name = "Engagement started" }
                    receiveTask("receiveRfq") {
                        name = "Receive RFQ"
                        messageRef = "rfqRequested"
                    }
                    userTask("prepareQuotation") { name = "Prepare Quotation" }
                    sendTask("sendQuotation") {
                        name = "Send Supplier Quotation"
                        messageRef = "supplierQuotationReceived"
                    }
                    eventBasedGateway("awaitBuyerResponse") { name = "Await buyer response" }
                    receiveTask("receiveRenegotiation") {
                        name = "Receive Renegotiation Request"
                        messageRef = "quotationRenegotiationRequested"
                    }
                }

                lane("supplierFulfilment") {
                    name = "Supplier Fulfilment"
                    receiveTask("receivePurchaseOrder") {
                        name = "Receive Purchase Order"
                        messageRef = "purchaseOrderIssued"
                    }
                    exclusiveGateway("poAcceptanceDecision") {
                        name = "Accept PO?"
                        defaultFlow = "flowRejectPo"
                    }
                    sendTask("sendOrderAcknowledgement") {
                        name = "Send Order Acknowledgement"
                        messageRef = "purchaseOrderAcknowledged"
                    }
                    sendTask("sendPurchaseOrderRejection") {
                        name = "Send Purchase Order Rejection"
                        messageRef = "purchaseOrderRejected"
                    }
                    sendTask("sendInvoice") {
                        name = "Send Invoice"
                        messageRef = "supplierInvoiceSubmitted"
                    }
                    endEvent("supplierCompleted") { name = "Supplier completed" }
                }

                sequenceFlow("flowSupplierStart", "supplierEngagementStarted", "receiveRfq")
                sequenceFlow("flowRfqToPrepare", "receiveRfq", "prepareQuotation")
                sequenceFlow("flowPrepareToSend", "prepareQuotation", "sendQuotation")
                sequenceFlow("flowSendToAwait", "sendQuotation", "awaitBuyerResponse")
                sequenceFlow("flowAwaitToRenegotiation", "awaitBuyerResponse", "receiveRenegotiation")
                sequenceFlow("flowAwaitToPo", "awaitBuyerResponse", "receivePurchaseOrder")
                sequenceFlow("flowRenegotiationToPrepare", "receiveRenegotiation", "prepareQuotation")
                sequenceFlow("flowPoToDecision", "receivePurchaseOrder", "poAcceptanceDecision")
                conditionalFlow(
                    "flowAcceptPo", "poAcceptanceDecision", "sendOrderAcknowledgement",
                    "\${poAccepted}", name = "accept",
                )
                sequenceFlow("flowRejectPo", "poAcceptanceDecision", "sendPurchaseOrderRejection", name = "reject")
                sequenceFlow("flowAckToInvoiceSend", "sendOrderAcknowledgement", "sendInvoice")
                sequenceFlow("flowInvoiceToDone", "sendInvoice", "supplierCompleted")
                sequenceFlow("flowRejectionToDone", "sendPurchaseOrderRejection", "supplierCompleted")
            }
        }

        message("rfqRequested")
        message("supplierQuotationReceived")
        message("quotationRenegotiationRequested")
        message("purchaseOrderIssued")
        message("purchaseOrderAcknowledged")
        message("purchaseOrderRejected")
        message("supplierInvoiceSubmitted")

        messageFlow("msgRfqToSupplier", from = "sendRfq", to = "receiveRfq", message = "rfqRequested")
        messageFlow("msgQuotationToBuyer", from = "sendQuotation", to = "waitForQuotation", message = "supplierQuotationReceived")
        messageFlow("msgRenegotiationToSupplier", from = "requestRenegotiation", to = "receiveRenegotiation", message = "quotationRenegotiationRequested")
        messageFlow("msgPoToSupplier", from = "createPurchaseOrder", to = "receivePurchaseOrder", message = "purchaseOrderIssued")
        messageFlow("msgAckToBuyer", from = "sendOrderAcknowledgement", to = "waitForAcknowledgement", message = "purchaseOrderAcknowledged")
        messageFlow("msgPoRejectionToBuyer", from = "sendPurchaseOrderRejection", to = "purchaseOrderRejectedEvent", message = "purchaseOrderRejected")
        messageFlow("msgInvoiceToBuyer", from = "sendInvoice", to = "receiveInvoice", message = "supplierInvoiceSubmitted")
    }
