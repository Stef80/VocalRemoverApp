package com.example.vocalremover.backend

import ai.onnxruntime.OrtException
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.ExecutionBackend.CPU
import com.example.vocalremover.ExecutionBackend.QNN
import com.example.vocalremover.ExecutionBackend.WEBGPU
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProbingBackendSelectorTest {

    private class FakeProber(
        private val results: Map<ExecutionBackend, ProbeResult> = emptyMap(),
        private val throwing: Set<ExecutionBackend> = emptySet(),
        private val error: Throwable = OrtException("boom"),
    ) : BackendProber {
        val calls = mutableListOf<ExecutionBackend>()
        var onProbe: (ExecutionBackend) -> Unit = {}
        var prepareError: Throwable? = null
        var prepareCalls = 0

        override suspend fun prepare() {
            prepareCalls++
            prepareError?.let { throw it }
        }

        override suspend fun probe(backend: ExecutionBackend): ProbeResult {
            calls += backend
            onProbe(backend)
            yield()
            if (backend in throwing) throw error
            return results.getValue(backend)
        }
    }

    private class InMemoryStore(var value: BackendChoice? = null) : BackendChoiceStore {
        /** inProgress value of each saveNow (synchronous) write. */
        val durableWrites = mutableListOf<ExecutionBackend?>()

        override fun load() = value
        override fun save(choice: BackendChoice) {
            value = choice
        }

        override fun saveNow(choice: BackendChoice) {
            durableWrites += choice.inProgress
            value = choice
        }
    }

    private fun passed(ms: Long) = ProbeResult.Passed(ms, 40.0)

    private fun selector(
        candidates: List<ExecutionBackend>,
        prober: FakeProber,
        store: InMemoryStore = InMemoryStore(),
        key: String = "1|A",
        crash: Boolean? = false,
    ) = ProbingBackendSelector(candidates, key, store, prober) { crash }

    private val all = listOf(QNN, WEBGPU, CPU)

    @Test
    fun `picks fastest passing backend`() = runTest {
        val prober = FakeProber(mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000)))
        assertEquals(QNN, selector(all, prober).current())
    }

    @Test
    fun `quality beats speed`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(mapOf(WEBGPU to ProbeResult.Rejected("SNR 3.1 dB"), CPU to passed(6000)))
        assertEquals(CPU, selector(listOf(WEBGPU, CPU), prober, store).current())
        assertTrue(store.value!!.failed.getValue(WEBGPU).contains("SNR"))
    }

    @Test
    fun `exception during probe rejects candidate`() = runTest {
        val prober = FakeProber(
            mapOf(WEBGPU to passed(1500), CPU to passed(6000)),
            throwing = setOf(QNN),
        )
        assertEquals(WEBGPU, selector(all, prober).current())
    }

    @Test
    fun `nothing passes falls back to cpu`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(all.associateWith { ProbeResult.Rejected("bad") })
        assertEquals(CPU, selector(all, prober, store).current())
        assertEquals(CPU, store.value!!.chosen)
    }

    @Test
    fun `stored choice skips probe`() = runTest {
        val store = InMemoryStore(BackendChoice("1|A", chosen = WEBGPU))
        val prober = FakeProber()
        val sel = selector(all, prober, store)
        assertTrue(sel.isReady())
        assertEquals(WEBGPU, sel.current())
        assertTrue(prober.calls.isEmpty())
    }

    @Test
    fun `fingerprint change invalidates choice and failures`() = runTest {
        val store = InMemoryStore(
            BackendChoice("1|A", chosen = WEBGPU, failed = mapOf(QNN to "crash")),
        )
        val prober = FakeProber(mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000)))
        val sel = selector(all, prober, store, key = "1|B")
        assertFalse(sel.isReady())
        assertEquals(QNN, sel.current())
        assertEquals(listOf(QNN, WEBGPU), prober.calls)
    }

    @Test
    fun `concurrent current calls share one probe`() = runTest {
        val prober = FakeProber(mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000)))
        val sel = selector(all, prober)
        val results = listOf(async { sel.current() }, async { sel.current() }).awaitAll()
        assertEquals(listOf(QNN, QNN), results)
        assertEquals(listOf(QNN, WEBGPU), prober.calls)
    }

    @Test
    fun `reportFailure returns next fastest passing and persists`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000)))
        val sel = selector(all, prober, store)
        assertEquals(QNN, sel.current())
        assertEquals(WEBGPU, sel.reportFailure(QNN, RuntimeException("x")))
        assertTrue(store.value!!.failed.containsKey(QNN))
        assertEquals(WEBGPU, sel.current())
        assertEquals(CPU, sel.reportFailure(WEBGPU, RuntimeException("y")))
        assertEquals(CPU, sel.reportFailure(CPU, RuntimeException("z")))
        assertFalse(store.value!!.failed.containsKey(CPU))
    }

    @Test
    fun `native crash marker blacklists backend`() = runTest {
        val store = InMemoryStore(BackendChoice("1|A", inProgress = QNN))
        val prober = FakeProber(mapOf(WEBGPU to passed(1500), CPU to passed(6000)))
        assertEquals(WEBGPU, selector(all, prober, store, crash = true).current())
        assertFalse(prober.calls.contains(QNN))
        assertTrue(store.value!!.failed.containsKey(QNN))
    }

    @Test
    fun `user-killed probe does not blacklist backend`() = runTest {
        val store = InMemoryStore(BackendChoice("1|A", inProgress = QNN))
        val prober = FakeProber(mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000)))
        assertEquals(QNN, selector(all, prober, store, crash = false).current())
        assertTrue(prober.calls.contains(QNN))
    }

    @Test
    fun `unknown exit needs two consecutive markers`() = runTest {
        val store = InMemoryStore(BackendChoice("1|A", inProgress = QNN))
        val results = mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000))

        // First marker: QNN is retried; its counter is 1 while the retry runs.
        val first = FakeProber(results)
        var counterDuringRetry: Int? = null
        first.onProbe = { if (it == QNN) counterDuringRetry = store.value!!.unexplainedExits[QNN] }
        assertEquals(QNN, selector(all, first, store, crash = null).current())
        assertEquals(1, counterDuringRetry)

        // Second marker with the counter still at 1: QNN is dropped.
        store.value = BackendChoice("1|A", inProgress = QNN, unexplainedExits = mapOf(QNN to 1))
        val second = FakeProber(results)
        assertEquals(WEBGPU, selector(all, second, store, crash = null).current())
        assertFalse(second.calls.contains(QNN))
    }

    @Test
    fun `cpu crash is only logged`() = runTest {
        val store = InMemoryStore(BackendChoice("1|A", inProgress = CPU))
        val prober = FakeProber(all.associateWith { ProbeResult.Rejected("bad") })
        assertEquals(CPU, selector(all, prober, store, crash = true).current())
        assertFalse(prober.calls.contains(CPU))
        assertFalse(store.value!!.failed.containsKey(CPU))
    }

    @Test
    fun `markCompleted clears marker and counter`() {
        val store = InMemoryStore(
            BackendChoice("1|A", chosen = QNN, unexplainedExits = mapOf(QNN to 1)),
        )
        val sel = selector(all, FakeProber(), store)
        sel.markInProgress(QNN)
        assertEquals(QNN, store.value!!.inProgress)
        sel.markCompleted(QNN)
        assertNull(store.value!!.inProgress)
        assertTrue(store.value!!.unexplainedExits.isEmpty())
    }

    @Test
    fun `cancelled probe clears its marker`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(mapOf(QNN to passed(250)))
        prober.onProbe = { throw kotlinx.coroutines.CancellationException("left") }
        try {
            selector(all, prober, store).current()
            org.junit.Assert.fail("CancellationException expected")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertNull(store.value!!.inProgress)
        assertFalse(store.value!!.failed.containsKey(QNN))
    }

    @Test
    fun `fastest wins over list order`() = runTest {
        val prober = FakeProber(mapOf(WEBGPU to passed(1500), QNN to passed(250), CPU to passed(6000)))
        assertEquals(QNN, selector(listOf(WEBGPU, QNN, CPU), prober).current())
    }

    @Test
    fun `reportFailure picks next fastest not next in list`() = runTest {
        val store = InMemoryStore()
        // WEBGPU is fastest but listed second: after its failure the next in list would be CPU.
        val prober = FakeProber(mapOf(QNN to passed(1500), WEBGPU to passed(250)))
        val sel = selector(listOf(QNN, WEBGPU, CPU), prober, store)
        assertEquals(WEBGPU, sel.current())
        val next = sel.reportFailure(WEBGPU, RuntimeException("x"))
        assertEquals(QNN, next)
        assertEquals(next, store.value!!.chosen)
    }

    @Test
    fun `cpu is not probed when an accelerator passes`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(mapOf(QNN to ProbeResult.Rejected("bad"), WEBGPU to passed(1500)))
        val sel = selector(all, prober, store)
        assertEquals(WEBGPU, sel.current())
        assertEquals(0, prober.calls.count { it == CPU })
        assertFalse(store.value!!.timingsMs.containsKey(CPU))
        // CPU remains the fallback once the only passing accelerator fails.
        assertEquals(CPU, sel.reportFailure(WEBGPU, RuntimeException("x")))
    }

    @Test
    fun `cpu is probed when no accelerator passes`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(
            mapOf(QNN to ProbeResult.Rejected("bad"), WEBGPU to ProbeResult.Rejected("SNR 3 dB"), CPU to passed(6000)),
        )
        assertEquals(CPU, selector(all, prober, store).current())
        assertEquals(1, prober.calls.count { it == CPU })
        assertEquals(6000L, store.value!!.timingsMs[CPU])
    }

    @Test
    fun `native link error during probe rejects candidate`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(
            mapOf(CPU to passed(6000)),
            throwing = setOf(QNN),
            error = UnsatisfiedLinkError("libQnn.so"),
        )
        assertEquals(CPU, selector(listOf(QNN, CPU), prober, store).current())
        assertTrue(store.value!!.failed.getValue(QNN).contains("UnsatisfiedLinkError"))
    }

    @Test
    fun `io failure during a probe is inconclusive and probed again on the next start`() = runTest {
        val store = InMemoryStore()
        val results = mapOf(WEBGPU to passed(1500), CPU to passed(6000))
        val first = FakeProber(results, throwing = setOf(QNN), error = java.io.IOException("No space left on device"))
        val sel = selector(all, first, store)

        // This run uses the best backend that did pass, without persisting it.
        assertEquals(WEBGPU, sel.current())
        assertFalse(store.value!!.failed.containsKey(QNN))
        assertNull(store.value!!.chosen)
        assertNull(store.value!!.inProgress)
        // Same process: no second probe.
        assertTrue(sel.isReady())
        assertEquals(WEBGPU, sel.current())
        assertEquals(listOf(QNN, WEBGPU), first.calls)

        // Next start: QNN is probed again and, once it passes, the choice is persisted.
        val second = FakeProber(results + (QNN to passed(250)))
        val next = selector(all, second, store)
        assertFalse(next.isReady())
        assertEquals(QNN, next.current())
        assertTrue(second.calls.contains(QNN))
        assertEquals(QNN, store.value!!.chosen)
    }

    @Test
    fun `out of memory during a probe is not persisted as a rejection`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(mapOf(CPU to passed(6000)), throwing = setOf(QNN), error = OutOfMemoryError("session"))
        assertEquals(CPU, selector(listOf(QNN, CPU), prober, store).current())
        assertFalse(store.value!!.failed.containsKey(QNN))
        assertNull(store.value!!.chosen)
    }

    @Test
    fun `backend output failure during a probe is persisted`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(
            mapOf(CPU to passed(6000)),
            throwing = setOf(WEBGPU),
            error = com.example.vocalremover.BackendOutputException("silent chunk"),
        )
        assertEquals(CPU, selector(listOf(WEBGPU, CPU), prober, store).current())
        assertTrue(store.value!!.failed.getValue(WEBGPU).contains("silent chunk"))
        assertEquals(CPU, store.value!!.chosen)
    }

    @Test
    fun `failed preparation aborts the probe without persisting and retries on the next start`() = runTest {
        val store = InMemoryStore()
        val results = mapOf(QNN to passed(250), WEBGPU to passed(1500), CPU to passed(6000))
        val first = FakeProber(results).apply { prepareError = java.io.IOException("No space left on device") }
        val sel = selector(all, first, store)

        assertEquals(CPU, sel.current())
        assertTrue(first.calls.isEmpty())
        assertNull(store.value)
        // The rest of this process stays on CPU without retrying.
        assertEquals(CPU, sel.current())
        assertEquals(1, first.prepareCalls)

        val second = FakeProber(results)
        assertEquals(QNN, selector(all, second, store).current())
        assertEquals(QNN, store.value!!.chosen)
    }

    @Test
    fun `reportFailure after an inconclusive probe does not persist the choice`() = runTest {
        val store = InMemoryStore()
        val prober = FakeProber(
            mapOf(QNN to passed(250), CPU to passed(6000)),
            throwing = setOf(WEBGPU),
            error = java.io.IOException("io"),
        )
        val sel = selector(all, prober, store)
        assertEquals(QNN, sel.current())
        assertEquals(CPU, sel.reportFailure(QNN, OrtException("x")))
        assertTrue(store.value!!.failed.containsKey(QNN))
        assertNull(store.value!!.chosen)
        assertEquals(CPU, sel.current())
    }

    @Test
    fun `in progress marker is written synchronously`() = runTest {
        val store = InMemoryStore()
        val sel = selector(all, FakeProber(mapOf(QNN to passed(250), WEBGPU to passed(1500))), store)
        sel.current()
        assertEquals(listOf<ExecutionBackend?>(QNN, WEBGPU), store.durableWrites)
        sel.markInProgress(QNN)
        assertEquals(QNN, store.durableWrites.last())
    }
}
