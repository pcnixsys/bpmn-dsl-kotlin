package `in`.o612.eng.bpmn

import `in`.o612.eng.bpmn.example.enterpriseProcurementCollaboration
import `in`.o612.eng.bpmn.layout.LayeredLayoutEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-file guard: a layout engine that silently shifts coordinates is a
 * regression generator. Any intentional change to layout constants or the
 * algorithm must regenerate `procurement.layout.txt` — a reviewed diff, not
 * silent drift.
 */
class GoldenTest {

    @Test
    fun `layout is stable`() {
        val layout = LayeredLayoutEngine().layout(enterpriseProcurementCollaboration())
        val actual = layout.shapes.entries.sortedBy { it.key }
            .joinToString("\n") { (k, b) -> "$k ${b.x},${b.y} ${b.width}x${b.height}" }
        val golden = File("src/test/resources/procurement.layout.txt").readText()
        assertEquals(golden.trim(), actual)
    }
}
