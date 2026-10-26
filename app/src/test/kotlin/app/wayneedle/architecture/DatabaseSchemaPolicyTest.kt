package app.wayneedle.architecture

import app.wayneedle.data.db.Migrations
import app.wayneedle.data.db.WayneedleDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JVM guard for the Room migration policy (see `Migrations`). The device-only migration test cannot run in
 * CI, so a version bump without its exported schema or its migration fails here, before any user's
 * history is at stake.
 */
class DatabaseSchemaPolicyTest {
    private val schemaDir = File(ProjectLayout.appDir, "schemas/${WayneedleDatabase::class.java.name}")

    @Test
    fun `every database version has its exported schema committed`() {
        for (version in 1..WayneedleDatabase.VERSION) {
            val schema = File(schemaDir, "$version.json")
            assertTrue(schema.isFile, "missing ${ProjectLayout.relativePath(schema)}: export and commit it")
        }
    }

    @Test
    fun `every version step has an explicit migration`() {
        for (version in 2..WayneedleDatabase.VERSION) {
            assertTrue(
                Migrations.all.any { it.startVersion == version - 1 && it.endVersion == version },
                "no Migration(${version - 1}, $version) in Migrations.all: trips would be lost on upgrade",
            )
        }
    }

    @Test
    fun `no schema is exported for a version the database does not declare`() {
        val versions = schemaDir.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
        assertTrue(
            versions.all {
                it <= WayneedleDatabase.VERSION
            },
            "schemas $versions vs VERSION ${WayneedleDatabase.VERSION}",
        )
    }
}
