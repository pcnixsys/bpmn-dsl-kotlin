package `in`.o612.eng.bpmn.layout

import `in`.o612.eng.bpmn.model.ActivityNode
import `in`.o612.eng.bpmn.model.BoundaryEventNode
import `in`.o612.eng.bpmn.model.Bounds
import `in`.o612.eng.bpmn.model.CollaborationDefinition
import `in`.o612.eng.bpmn.model.DiagramLayout
import `in`.o612.eng.bpmn.model.EmbeddedSubprocess
import `in`.o612.eng.bpmn.model.EndEvent
import `in`.o612.eng.bpmn.model.FlowNode
import `in`.o612.eng.bpmn.model.GatewayNode
import `in`.o612.eng.bpmn.model.IntermediateCatchMessageEvent
import `in`.o612.eng.bpmn.model.Point
import `in`.o612.eng.bpmn.model.ProcessDefinition
import `in`.o612.eng.bpmn.model.SequenceFlow
import `in`.o612.eng.bpmn.model.StartEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * Produces BPMN DI geometry for a collaboration — two maps keyed by *semantic
 * element id*: `shapes` (bounds) and `edges` (waypoints). The engine never
 * invents elements; `bpmnElement` references in the output are resolvable by
 * construction.
 */
interface DiagramLayoutEngine {
    fun layout(collaboration: CollaborationDefinition): DiagramLayout
}

/**
 * A deterministic, structurally valid *baseline* layout — the main path flows
 * left to right in longest-path columns, lanes are rows, pools stack
 * vertically, boundary events pin to their hosts, edges route orthogonally.
 *
 * What it deliberately does not do (Part 7's honest list): crossing
 * minimization, node-avoidance routing, user-edited coordinates. Beautiful
 * diagrams still come from a human modeler or ELK; this exists to make
 * generated workflows inspectable.
 */
class LayeredLayoutEngine : DiagramLayoutEngine {

    companion object {
        const val TASK_W = 120.0
        const val TASK_H = 80.0
        const val GATEWAY = 50.0
        const val EVENT = 36.0
        const val BOUNDARY = 30.0
        const val COL_GAP = 90.0
        const val LANE_HEADER = 30.0
        const val LANE_V_PAD = 24.0
        const val SLOT_GAP = 12.0
        const val POOL_PAD = 12.0
        const val POOL_BOTTOM_PAD = 30.0    // reserves the return channel for loop edges
        const val POOL_GAP = 110.0          // space between pools for message flows
        const val SUB_PAD = 18.0
        const val START_X = 60.0
        const val START_Y = 60.0
        const val LABEL_W = 120.0
        const val LABEL_H = 14.0
        const val LABEL_GAP = 4.0

        const val COL_W = TASK_W + COL_GAP  // 210px column pitch
        const val SLOT_H = TASK_H + SLOT_GAP // 92px slot pitch inside a lane
    }

    override fun layout(collaboration: CollaborationDefinition): DiagramLayout =
        LayoutRun().layout(collaboration)

    /** Per-call state — keeps the engine itself stateless and re-entrant. */
    private class LayoutRun {
        val shapes = LinkedHashMap<String, Bounds>()
        val edges = LinkedHashMap<String, List<Point>>()
        val labels = LinkedHashMap<String, Bounds>()

        /** subprocess id → interior node bounds in subprocess-local coords. */
        private val subLocalBounds = HashMap<String, Map<String, Bounds>>()

        /** subprocess id → absolute bounds (set after placement). */
        private val subAbsBounds = HashMap<String, Bounds>()

        /** subprocess id → its interior column assignment. */
        private val subCols = HashMap<String, Map<String, Int>>()

        fun layout(c: CollaborationDefinition): DiagramLayout {
            var poolTop = START_Y
            for (participant in c.participants) {
                val proc = c.processes.find { it.id == participant.processRef } ?: continue
                poolTop = layoutPool(participant.id, proc, poolTop)
            }
            // message flows route across pools — after every shape exists
            for (mf in c.messageFlows) {
                val s = shapes[mf.sourceRef] ?: continue
                val t = shapes[mf.targetRef] ?: continue
                edges[mf.id] = routeMessageFlow(s, t)
            }
            return DiagramLayout(shapes = shapes, edges = edges, labels = labels)
        }

        private fun layoutPool(participantId: String, p: ProcessDefinition, poolTop: Double): Double {
            // Step 1 — columns: longest-path depth from start events
            val cols = assignColumns(p.nodes, p.sequenceFlows)

            // Step 2 — rows: a node's row is its lane's declaration index
            val laneIndex = HashMap<String, Int>()
            p.lanes.forEachIndexed { i, lane -> lane.flowNodeRefs.forEach { laneIndex[it] = i } }

            val regular = p.nodes.filter { it !is BoundaryEventNode }
            val cell = LinkedHashMap<Pair<Int, Int>, MutableList<FlowNode>>()
            for (n in regular) {
                val col = cols[n.id] ?: 0
                val row = laneIndex[n.id] ?: 0
                cell.getOrPut(col to row) { mutableListOf() }.add(n)
            }

            val slotsPerLane = IntArray(p.lanes.size.coerceAtLeast(1)) { 1 }
            cell.forEach { (k, v) -> slotsPerLane[k.second] = max(slotsPerLane[k.second], v.size) }
            val laneHeights = slotsPerLane.map { it * SLOT_H + 2 * LANE_V_PAD }
            val laneTops = DoubleArray(laneHeights.size).also {
                var t = poolTop + POOL_PAD
                for (i in it.indices) { it[i] = t; t += laneHeights[i] }
            }

            // Step 3 — subprocess interiors recurse: children get local bounds
            // first, so the subprocess knows its own size
            for (sub in regular.filterIsInstance<EmbeddedSubprocess>()) {
                layoutSubprocessInterior(sub)
            }

            // Step 4 — place nodes in their (column, lane) cells; nodes in the
            // same cell stack vertically in slots
            var maxRight = START_X + LANE_HEADER
            for ((cellKey, cellNodes) in cell) {
                val (col, row) = cellKey
                cellNodes.forEachIndexed { slot, n ->
                    val (nw, nh) = nodeSize(n)
                    val b = Bounds(
                        x = START_X + LANE_HEADER + LANE_V_PAD + col * COL_W,
                        y = laneTops[row] + LANE_V_PAD + slot * SLOT_H + (SLOT_H - nh) / 2,
                        width = nw, height = nh,
                    )
                    shapes[n.id] = b
                    maxRight = max(maxRight, b.right)
                }
            }

            // Step 5 — pin boundary events to the host's bottom-right corner;
            // the half-offset is what makes the circle *intersect* the border
            for (b in p.nodes.filterIsInstance<BoundaryEventNode>()) {
                val host = shapes[b.attachedToRef] ?: continue
                shapes[b.id] = Bounds(
                    host.right - BOUNDARY / 2, host.bottom - BOUNDARY / 2, BOUNDARY, BOUNDARY,
                )
            }

            // Step 6 — lane bounds wrap the content; the pool wraps the lanes
            val laneWidth = maxRight - (START_X + LANE_HEADER) + LANE_V_PAD
            for ((i, lane) in p.lanes.withIndex()) {
                shapes[lane.id] = Bounds(
                    START_X + LANE_HEADER, laneTops[i], laneWidth, laneHeights[i],
                )
            }
            val poolHeight = laneHeights.sum() + POOL_PAD + POOL_BOTTOM_PAD
            val poolBounds = Bounds(START_X, poolTop, LANE_HEADER + laneWidth + POOL_PAD, poolHeight)
            shapes[participantId] = poolBounds

            // subprocess children become absolute only now that the parent is placed
            for (sub in regular.filterIsInstance<EmbeddedSubprocess>()) {
                placeSubprocessChildren(sub)
            }

            // labels for named gateways + events (rendered below the shape)
            for (n in p.allNodes()) {
                if (n.name == null) continue
                if (n is GatewayNode || n is BoundaryEventNode || n is IntermediateCatchMessageEvent) {
                    val b = shapes[n.id] ?: continue
                    labels[n.id] = Bounds(b.centerX - LABEL_W / 2, b.bottom + LABEL_GAP, LABEL_W, LABEL_H)
                }
            }

            // Step 8 — sequence flows: orthogonal doglegs forward, the
            // per-pool return channel at the bottom for loops
            val channelY = poolBounds.bottom - POOL_BOTTOM_PAD / 2
            for (f in p.sequenceFlows) {
                edges[f.id] = routeSequenceFlow(f, cols, channelY)
            }
            for (sub in p.nodes.filterIsInstance<EmbeddedSubprocess>()) {
                routeSubprocessInterior(sub)
            }

            return poolBounds.bottom + POOL_GAP
        }

        private fun layoutSubprocessInterior(sub: EmbeddedSubprocess) {
            // nested subprocesses size first — their interior bounds feed ours
            for (nested in sub.nodes.filterIsInstance<EmbeddedSubprocess>()) {
                layoutSubprocessInterior(nested)
            }
            val cols = assignColumns(sub.nodes, sub.sequenceFlows)
            subCols[sub.id] = cols

            var w = 0.0
            var h = 0.0
            val local = LinkedHashMap<String, Bounds>()
            for (n in sub.nodes.filter { it !is BoundaryEventNode }) {
                val col = cols[n.id] ?: 0
                val (nw, nh) = nodeSize(n)
                val b = Bounds(col * COL_W, 0.0, nw, nh)   // relative to subprocess origin
                local[n.id] = b
                w = max(w, b.right)
                h = max(h, b.bottom)
            }
            for (b in sub.nodes.filterIsInstance<BoundaryEventNode>()) {
                val host = local[b.attachedToRef] ?: continue
                local[b.id] = Bounds(host.right - BOUNDARY / 2, host.bottom - BOUNDARY / 2, BOUNDARY, BOUNDARY)
            }
            subLocalBounds[sub.id] = local
        }

        private fun placeSubprocessChildren(sub: EmbeddedSubprocess) {
            val sb = shapes[sub.id] ?: return
            val local = subLocalBounds[sub.id] ?: return
            subAbsBounds[sub.id] = sb
            for ((id, lb) in local) {
                shapes[id] = Bounds(sb.x + SUB_PAD + lb.x, sb.y + SUB_PAD + lb.y, lb.width, lb.height)
            }
            for (nested in sub.nodes.filterIsInstance<EmbeddedSubprocess>()) {
                placeSubprocessChildren(nested)
            }
        }

        private fun routeSubprocessInterior(sub: EmbeddedSubprocess) {
            val sb = subAbsBounds[sub.id] ?: return
            val cols = subCols[sub.id].orEmpty()
            // the return channel lives inside the subprocess's own padding
            val channelY = sb.bottom - SUB_PAD / 2
            for (f in sub.sequenceFlows) {
                edges[f.id] = routeSequenceFlow(f, cols, channelY)
            }
            for (nested in sub.nodes.filterIsInstance<EmbeddedSubprocess>()) {
                routeSubprocessInterior(nested)
            }
        }

        /**
         * Longest-path layering: each node's column is its longest distance
         * from a start event. The on-stack guard terminates back edges (loops)
         * instead of recursing forever; boundary events skip columns entirely
         * (they belong to their host, not the grid); unreachable nodes append
         * at the right.
         */
        private fun assignColumns(nodes: List<FlowNode>, flows: List<SequenceFlow>): Map<String, Int> {
            val outgoing = flows.groupBy { it.sourceRef }
            val dist = HashMap<String, Int>()
            val onStack = HashSet<String>()

            fun dfs(u: String, d: Int) {
                if (u in onStack) return                       // back edge: ignore
                if ((dist[u] ?: -1) >= d) return               // already have a longer path
                dist[u] = d
                onStack += u
                for (f in outgoing[u].orEmpty()) dfs(f.targetRef, d + 1)
                onStack -= u
            }

            nodes.filterIsInstance<StartEvent>().forEach { dfs(it.id, 0) }
            var next = (dist.values.maxOrNull() ?: 0) + 1
            for (n in nodes) {
                if (n is BoundaryEventNode) continue
                if (n.id !in dist) dist[n.id] = next++
            }
            return dist
        }

        /**
         * Forward edges dogleg through the mid-column gap; backward/loop edges
         * exit the source's bottom edge, ride the pool's return channel, and
         * re-enter the target's bottom edge. The channel is *per pool* — a
         * global channel drops edges below the supplier pool.
         */
        private fun routeSequenceFlow(f: SequenceFlow, cols: Map<String, Int>, channelY: Double): List<Point> {
            val s = shapes[f.sourceRef] ?: return emptyList()
            val t = shapes[f.targetRef] ?: return emptyList()
            val srcCol = cols[f.sourceRef] ?: 0
            val tgtCol = cols[f.targetRef] ?: 0

            if (tgtCol > srcCol || (tgtCol == srcCol && t.x >= s.right)) {
                val srcOut = Point(s.right, s.centerY)
                val tgtIn = Point(t.x, t.centerY)
                if (abs(s.centerY - t.centerY) < 1 && t.x >= s.right) return listOf(srcOut, tgtIn)
                val mx = (s.right + t.x) / 2
                return listOf(srcOut, Point(mx, srcOut.y), Point(mx, tgtIn.y), tgtIn)
            }
            // backward edge: bottom → return channel → bottom
            val sBottom = Point(s.centerX, s.bottom)
            val tBottom = Point(t.centerX, t.bottom)
            return listOf(sBottom, Point(sBottom.x, channelY), Point(tBottom.x, channelY), tBottom)
        }

        /**
         * Message flows cross the inter-pool gap vertically: exit the source's
         * bottom edge when the target is below (top edge when above), with a
         * dogleg at the gap's midpoint.
         */
        private fun routeMessageFlow(s: Bounds, t: Bounds): List<Point> {
            val below = t.y >= s.bottom
            val src = Point(s.centerX, if (below) s.bottom else s.y)
            val tgt = Point(t.centerX, if (below) t.y else t.bottom)
            if (abs(src.x - tgt.x) < 1) return listOf(src, tgt)
            val my = (src.y + tgt.y) / 2
            return listOf(src, Point(src.x, my), Point(tgt.x, my), tgt)
        }

        private fun nodeSize(n: FlowNode): Pair<Double, Double> = when (n) {
            is EmbeddedSubprocess -> {
                val interiorB = subLocalBounds[n.id]
                if (interiorB != null) {
                    val w = interiorB.values.maxOf { it.right }
                    val h = interiorB.values.maxOf { it.bottom }
                    (w + 2 * SUB_PAD) to (h + 2 * SUB_PAD)
                } else TASK_W to TASK_H
            }
            is StartEvent, is EndEvent, is IntermediateCatchMessageEvent -> EVENT to EVENT
            is BoundaryEventNode -> BOUNDARY to BOUNDARY
            is GatewayNode -> GATEWAY to GATEWAY
            is ActivityNode -> TASK_W to TASK_H
        }
    }
}
