package io.sentry.android.core

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.IHub
import io.sentry.ILogger
import io.sentry.ISentryExecutorService
import io.sentry.ProfilingTraceData
import io.sentry.SentryLevel
import io.sentry.SentryTracer
import io.sentry.TransactionContext
import io.sentry.test.getCtor
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidTransactionProfilerTest {
    private lateinit var context: Context

    private val className = "io.sentry.android.core.AndroidTransactionProfiler"
    private val ctorTypes = arrayOf(Context::class.java, SentryAndroidOptions::class.java, BuildInfoProvider::class.java)
    private val fixture = Fixture()

    private class Fixture {
        private val mockDsn = "http://key@localhost/proj"
        val buildInfo = mock<BuildInfoProvider> {
            whenever(it.sdkInfoVersion).thenReturn(Build.VERSION_CODES.LOLLIPOP)
        }
        val mockLogger = mock<ILogger>()
        val options = spy(SentryAndroidOptions()).apply {
            dsn = mockDsn
            profilesSampleRate = 1.0
            isDebug = true
            setLogger(mockLogger)
        }

        val hub: IHub = mock()

        lateinit var transaction1: SentryTracer
        lateinit var transaction2: SentryTracer
        lateinit var transaction3: SentryTracer

        fun getSut(context: Context, buildInfoProvider: BuildInfoProvider = buildInfo): AndroidTransactionProfiler {
            whenever(hub.options).thenReturn(options)
            transaction1 = SentryTracer(TransactionContext("", ""), hub)
            transaction2 = SentryTracer(TransactionContext("", ""), hub)
            transaction3 = SentryTracer(TransactionContext("", ""), hub)
            return AndroidTransactionProfiler(context, options, buildInfoProvider)
        }
    }

    @BeforeTest
    fun `set up`() {
        context = ApplicationProvider.getApplicationContext()
        AndroidOptionsInitializer.init(fixture.options, context, fixture.mockLogger, false, false)
        // Profiler doesn't start if the folder doesn't exists.
        // Usually it's generated when calling Sentry.init, but for tests we can create it manually.
        File(fixture.options.profilingTracesDirPath!!).mkdirs()
    }

    @AfterTest
    fun clear() {
        context.cacheDir.deleteRecursively()
    }

    @Test
    fun `when null param is provided, invalid argument is thrown`() {
        val ctor = className.getCtor(ctorTypes)

        assertFailsWith<IllegalArgumentException> {
            ctor.newInstance(arrayOf(null, mock<SentryAndroidOptions>(), mock()))
        }
        assertFailsWith<IllegalArgumentException> {
            ctor.newInstance(arrayOf(mock<Context>(), null, mock()))
        }
        assertFailsWith<IllegalArgumentException> {
            ctor.newInstance(arrayOf(mock<Context>(), mock<SentryAndroidOptions>(), null))
        }
    }

    @Test
    fun `profiler profiles current transaction`() {
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertEquals(fixture.transaction1.eventId.toString(), traceData!!.transactionId)
    }

    @Test
    fun `profiler works only on api 21+`() {
        val buildInfo = mock<BuildInfoProvider> {
            whenever(it.sdkInfoVersion).thenReturn(Build.VERSION_CODES.KITKAT)
        }
        val profiler = fixture.getSut(context, buildInfo)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)
    }

    @Test
    fun `profiler on profilesSampleRate=0 false`() {
        fixture.options.apply {
            profilesSampleRate = 0.0
        }
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)
    }

    @Test
    fun `profiler evaluates if profiling is enabled in options only on first transaction profiling`() {
        fixture.options.apply {
            profilesSampleRate = 0.0
        }

        // We create the profiler, and nothing goes wrong
        val profiler = fixture.getSut(context)
        verify(fixture.mockLogger, never()).log(SentryLevel.INFO, "Profiling is disabled in options.")

        // Regardless of how many times the profiler is started, the option is evaluated and logged only once
        profiler.onTransactionStart(fixture.transaction1)
        profiler.onTransactionStart(fixture.transaction1)
        verify(fixture.mockLogger, times(1)).log(SentryLevel.INFO, "Profiling is disabled in options.")
    }

    @Test
    fun `profiler evaluates profilingTracesDirPath options only on first transaction profiling`() {
        fixture.options.apply {
            cacheDirPath = null
        }

        // We create the profiler, and nothing goes wrong
        val profiler = fixture.getSut(context)
        verify(fixture.mockLogger, never()).log(
            SentryLevel.WARNING,
            "Disabling profiling because no profiling traces dir path is defined in options."
        )

        // Regardless of how many times the profiler is started, the option is evaluated and logged only once
        profiler.onTransactionStart(fixture.transaction1)
        profiler.onTransactionStart(fixture.transaction1)
        verify(fixture.mockLogger, times(1)).log(
            SentryLevel.WARNING,
            "Disabling profiling because no profiling traces dir path is defined in options."
        )
    }

    @Test
    fun `profiler evaluates profilingTracesHz options only on first transaction profiling`() {
        fixture.options.apply {
            profilingTracesHz = 0
        }

        // We create the profiler, and nothing goes wrong
        val profiler = fixture.getSut(context)
        verify(fixture.mockLogger, never()).log(
            SentryLevel.WARNING,
            "Disabling profiling because trace rate is set to %d",
            0
        )

        // Regardless of how many times the profiler is started, the option is evaluated and logged only once
        profiler.onTransactionStart(fixture.transaction1)
        profiler.onTransactionStart(fixture.transaction1)
        verify(fixture.mockLogger, times(1)).log(
            SentryLevel.WARNING,
            "Disabling profiling because trace rate is set to %d",
            0
        )
    }

    @Test
    fun `profiler on tracesDirPath null`() {
        fixture.options.apply {
            cacheDirPath = null
        }
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)
    }

    @Test
    fun `profiler on tracesDirPath empty`() {
        fixture.options.apply {
            cacheDirPath = null
        }
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)
    }

    @Test
    fun `profiler on profilingTracesHz 0`() {
        fixture.options.apply {
            profilingTracesHz = 0
        }
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)
    }

    @Test
    fun `profiler ignores profilingTracesIntervalMillis`() {
        fixture.options.apply {
            profilingTracesIntervalMillis = 0
        }
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNotNull(traceData)
    }

    @Test
    fun `onTransactionFinish works only if previously started`() {
        val profiler = fixture.getSut(context)
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)
    }

    @Test
    fun `onTransactionFinish returns timedOutData to the timed out transaction once, even after other transactions`() {
        val profiler = fixture.getSut(context)

        val executorService = mock<ISentryExecutorService>()
        val captor = argumentCaptor<Runnable>()
        whenever(executorService.schedule(captor.capture(), any())).thenReturn(null)
        whenever(fixture.options.executorService).thenReturn(executorService)
        // Start and finish first transaction profiling
        profiler.onTransactionStart(fixture.transaction1)

        // Set timed out data by calling the timeout scheduled job
        captor.firstValue.run()

        // Start and finish second transaction. Since profiler returned data, it means no other profiling is running
        profiler.onTransactionStart(fixture.transaction2)
        assertEquals(fixture.transaction2.eventId.toString(), profiler.onTransactionFinish(fixture.transaction2)!!.transactionId)

        // First transaction finishes: timed out data is returned
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertEquals(traceData!!.transactionId, fixture.transaction1.eventId.toString())

        // If first transaction is finished again, nothing is returned
        assertNull(profiler.onTransactionFinish(fixture.transaction1))
    }

    @Test
    fun `timedOutData has timeout truncation reason`() {
        val profiler = fixture.getSut(context)

        val executorService = mock<ISentryExecutorService>()
        val captor = argumentCaptor<Runnable>()
        whenever(executorService.schedule(captor.capture(), any())).thenReturn(null)
        whenever(fixture.options.executorService).thenReturn(executorService)

        // Start and finish first transaction profiling
        profiler.onTransactionStart(fixture.transaction1)

        // Set timed out data by calling the timeout scheduled job
        captor.firstValue.run()

        // First transaction finishes: timed out data is returned
        val traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertEquals(traceData!!.transactionId, fixture.transaction1.eventId.toString())
        assertEquals(ProfilingTraceData.TRUNCATION_REASON_TIMEOUT, traceData.truncationReason)
    }

    @Test
    fun `profiling stops and returns data only when the last transaction finishes`() {
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        profiler.onTransactionStart(fixture.transaction2)

        var traceData = profiler.onTransactionFinish(fixture.transaction2)
        assertNull(traceData)

        traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertEquals(fixture.transaction1.eventId.toString(), traceData!!.transactionId)
    }

    @Test
    fun `profiling records multiple concurrent transactions`() {
        val profiler = fixture.getSut(context)
        profiler.onTransactionStart(fixture.transaction1)
        profiler.onTransactionStart(fixture.transaction2)

        var traceData = profiler.onTransactionFinish(fixture.transaction1)
        assertNull(traceData)

        profiler.onTransactionStart(fixture.transaction3)
        traceData = profiler.onTransactionFinish(fixture.transaction3)
        assertNull(traceData)
        traceData = profiler.onTransactionFinish(fixture.transaction2)
        assertEquals(fixture.transaction2.eventId.toString(), traceData!!.transactionId)
        val expectedTransactions = listOf(
            fixture.transaction1.eventId.toString(),
            fixture.transaction3.eventId.toString(),
            fixture.transaction2.eventId.toString()
        )
        assertTrue(traceData.transactions.map { it.id }.containsAll(expectedTransactions))
        assertTrue(expectedTransactions.containsAll(traceData.transactions.map { it.id }))
    }
}