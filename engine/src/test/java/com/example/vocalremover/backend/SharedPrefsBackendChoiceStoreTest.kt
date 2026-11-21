package com.example.vocalremover.backend

import android.content.Context
import com.example.vocalremover.ExecutionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsBackendChoiceStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = SharedPrefsBackendChoiceStore(context)

    @Test
    fun `save then load returns the same choice`() {
        val choice = BackendChoice(
            key = "1|A",
            chosen = ExecutionBackend.QNN,
            failed = mapOf(ExecutionBackend.WEBGPU to "SNR 3.1 dB"),
            timingsMs = mapOf(ExecutionBackend.QNN to 250L),
            inProgress = ExecutionBackend.CPU,
            unexplainedExits = mapOf(ExecutionBackend.QNN to 1),
        )
        store.save(choice)
        assertEquals(choice, store.load())
    }

    @Test
    fun `nothing saved loads null`() {
        assertNull(store.load())
    }

    @Test
    fun `corrupt json loads null`() {
        context.getSharedPreferences("backend_choice", Context.MODE_PRIVATE)
            .edit().putString("choice", "{not json").commit()
        assertNull(store.load())
    }
}
