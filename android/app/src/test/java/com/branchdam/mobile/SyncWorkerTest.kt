package com.branchdam.mobile

import android.content.Context
import androidx.work.WorkerParameters
import com.branchdam.mobile.service.SyncWorker
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncWorkerTest {

    @Test
    fun testWorkerConstructsWithMockedContext() {
        val context: Context = mock()
        val params: WorkerParameters = mock()
        whenever(params.runAttemptCount).thenReturn(0)
        val worker = SyncWorker(context, params)
        assertNotNull(worker)
    }
}
