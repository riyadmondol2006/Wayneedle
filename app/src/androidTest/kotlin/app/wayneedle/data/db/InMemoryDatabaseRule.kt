package app.wayneedle.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/** Opens a fresh in-memory [WayneedleDatabase] per test and closes it afterwards. */
class InMemoryDatabaseRule : ExternalResource() {
    lateinit var database: WayneedleDatabase
        private set

    override fun before() {
        database = WayneedleDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>())
    }

    override fun after() {
        database.close()
    }
}
