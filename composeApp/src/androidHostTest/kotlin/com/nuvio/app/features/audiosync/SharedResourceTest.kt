package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.SharedResource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedResourceTest {
    @Test
    fun readinessDoesNotWaitForSlowNativeInitializationAndConcurrentPlayersShareOneLoad() {
        val scheduler = ManualScheduler()
        val workers = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(1)
        val continueLoading = CountDownLatch(1)
        val loads = AtomicInteger()
        val resource = Any()
        val cache = SharedResource<Any>(scheduler, 180_000) {}
        try {
            val first = workers.submit<Any> {
                cache.acquire {
                    loads.incrementAndGet()
                    entered.countDown()
                    check(continueLoading.await(5, TimeUnit.SECONDS))
                    resource
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // The readiness query completes while creation remains explicitly blocked.
            assertFalse(workers.submit<Boolean> { cache.isLoaded }.get(2, TimeUnit.SECONDS))
            val second = workers.submit<Any> { cache.acquire { loads.incrementAndGet(); Any() } }
            continueLoading.countDown()
            assertSame(resource, first.get(5, TimeUnit.SECONDS))
            assertSame(resource, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
            cache.release()
            cache.release()
        } finally {
            continueLoading.countDown()
            workers.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun releasesOnlyAfterEveryPlayerReturnsItsReference() {
        val scheduler = ManualScheduler()
        val closed = ArrayList<Any>()
        val cache = SharedResource<Any>(scheduler, 180_000, closed::add)
        try {
            val model = cache.acquire { Any() }
            assertSame(model, cache.acquire { error("already loaded") })
            cache.release()
            assertEquals(0, scheduler.tasks.size)
            cache.release()
            assertEquals(1, scheduler.tasks.size)
            assertTrue(cache.isLoaded)
            scheduler.tasks[0].run()
            assertFalse(cache.isLoaded)
            assertEquals(listOf(model), closed)
            assertTrue(cache.acquire { Any() } !== model)
            cache.release()
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun aCancelledIdleTaskCannotCloseAReacquiredOrNewlyIdleModel() {
        val scheduler = ManualScheduler()
        val closed = ArrayList<Any>()
        val cache = SharedResource<Any>(scheduler, 180_000, closed::add)
        try {
            val model = cache.acquire { Any() }
            cache.release()
            cache.acquire { error("already loaded") }
            // Cancellation may race a task that has already started.
            scheduler.tasks[0].run()
            assertTrue(cache.isLoaded)
            cache.release()
            scheduler.tasks[0].run()
            assertTrue(cache.isLoaded)
            assertTrue(closed.isEmpty())
            scheduler.tasks[1].run()
            assertEquals(listOf(model), closed)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun failedInitializationCanBeRetriedWithoutLeakingAReference() {
        val scheduler = ManualScheduler()
        val closed = AtomicInteger()
        val cache = SharedResource<Any>(scheduler, 180_000) { closed.incrementAndGet() }
        try {
            assertFailsWith<IllegalStateException> { cache.acquire { error("native init failed") } }
            assertFalse(cache.isLoaded)
            cache.acquire { Any() }
            cache.release()
            scheduler.tasks.single().run()
            assertEquals(1, closed.get())
        } finally {
            scheduler.shutdownNow()
        }
    }

    /** Lets tests deliberately run even a cancelled callback, without waiting for real time. */
    private class ManualScheduler : ScheduledThreadPoolExecutor(1) {
        val tasks = ArrayList<Runnable>()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            assertEquals(180_000L, unit.toMillis(delay))
            tasks += command
            return super.schedule(command, 1, TimeUnit.DAYS)
        }
    }
}
