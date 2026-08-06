package com.ossm.remote.updater

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateRepositoryPeriodicCheckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createRepository(): UpdateRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "updater.preferences_pb") }
        )
        return UpdateRepository(mockk<Context>(), dataStore)
    }

    @Test
    fun `periodic check is disabled by default`() = runTest {
        assertFalse(createRepository().periodicCheckEnabled.first())
    }

    @Test
    fun `periodic check setter round trips`() = runTest {
        val repository = createRepository()
        repository.setPeriodicCheckEnabled(true)
        assertTrue(repository.periodicCheckEnabled.first())
        repository.setPeriodicCheckEnabled(false)
        assertFalse(repository.periodicCheckEnabled.first())
    }
}
