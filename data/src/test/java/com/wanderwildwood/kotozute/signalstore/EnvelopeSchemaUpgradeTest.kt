package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The envelope table's shape after an upgrade from any version, against a fresh one.
 *
 * Worked on the statements themselves, the way [ProtocolDatabase.onUpgrade] runs them: a step
 * that creates the table creates today's shape, and an `ADD COLUMN` already there is skipped.
 * There is no SQLite in these tests, so the columns are read out of the SQL.
 */
class EnvelopeSchemaUpgradeTest {

    private val createEnvelope = Regex("""CREATE TABLE envelope\s*\(""")

    private fun columnsOf(create: String): Set<String> =
        create.substringAfter("(").substringBeforeLast(")")
            .lines()
            .map { it.substringBefore("--").trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(" ") }
            .toSet()

    private val fresh = columnsOf(ProtocolStoreSchema.ENVELOPE)

    /** The envelope columns each step adds, by version. */
    private fun addedAt(version: Int): List<String> =
        ProtocolStoreSchema.MIGRATIONS[version].orEmpty().mapNotNull { sql ->
            ProtocolStoreSchema.ADD_COLUMN.find(sql)
                ?.takeIf { it.groupValues[1].equals("envelope", true) }
                ?.groupValues?.get(2)
        }

    /** As [ProtocolDatabase.onUpgrade] would leave the table, from a store at [from]. */
    private fun upgradeFrom(from: Int, startingWith: Set<String>?): Set<String> {
        var table = startingWith?.toMutableSet()
        for (version in (from + 1)..ProtocolStoreSchema.VERSION) {
            val steps = ProtocolStoreSchema.MIGRATIONS[version]
            assertNotNull("no step for v$version", steps)
            steps!!.forEach { sql ->
                if (createEnvelope.containsMatchIn(sql)) table = columnsOf(sql).toMutableSet()
                val add = ProtocolStoreSchema.ADD_COLUMN.find(sql)
                if (add != null && add.groupValues[1].equals("envelope", true)) {
                    val t = table ?: error("v$version adds to an envelope table that does not exist")
                    t += add.groupValues[2]
                }
            }
        }
        return table.orEmpty()
    }

    /** The shape a store at [version] has: today's, without what later steps add. */
    private fun shapeAt(version: Int): Set<String>? {
        if (version < 2) return null
        val later = ((version + 1)..ProtocolStoreSchema.VERSION).flatMap { addedAt(it) }.toSet()
        return fresh - later
    }

    @Test
    fun `v44 adds the build stamp, and v43 is left as it shipped`() {
        assertEquals(true, ProtocolStoreSchema.VERSION >= 44)
        assertEquals(listOf("unprocessed_build", "unprocessed_waits_for"), addedAt(44))
        assertEquals(
            listOf(
                "ALTER TABLE envelope ADD COLUMN unprocessed BLOB;",
                "ALTER TABLE envelope ADD COLUMN unprocessed_attempts INTEGER NOT NULL DEFAULT 0;",
                "ALTER TABLE envelope ADD COLUMN unprocessed_next_at INTEGER NOT NULL DEFAULT 0;"
            ),
            ProtocolStoreSchema.MIGRATIONS[43]
        )
    }

    @Test
    fun `v45 only adds the history transfer table, and touches no envelope`() {
        assertEquals(45, ProtocolStoreSchema.VERSION)
        assertEquals(listOf(ProtocolStoreSchema.HISTORY_TRANSFER), ProtocolStoreSchema.MIGRATIONS[45])
        assertEquals(true, ProtocolStoreSchema.HISTORY_TRANSFER in ProtocolStoreSchema.ALL)
        assertEquals(emptyList<String>(), addedAt(45))
    }

    @Test
    fun `a v43 store upgrades to the fresh shape`() {
        val v43 = shapeAt(43)!!
        assertEquals(false, "unprocessed_build" in v43)
        assertEquals(true, "unprocessed_next_at" in v43)
        assertEquals(fresh, upgradeFrom(43, v43))
    }

    @Test
    fun `so does a store from every older version`() {
        (1 until ProtocolStoreSchema.VERSION).forEach { from ->
            assertEquals("from v$from", fresh, upgradeFrom(from, shapeAt(from)))
        }
    }

    @Test
    fun `every envelope column a step adds is in the fresh table`() {
        (2..ProtocolStoreSchema.VERSION).flatMap { addedAt(it) }.forEach { column ->
            assertEquals("$column missing from ENVELOPE", true, column in fresh)
        }
    }
}
