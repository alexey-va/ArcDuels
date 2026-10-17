package ru.ruscrafting.duels.mysql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlRuntime
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class MySqlStatisticsRepositoryDrainTest : StringSpec({
    "terminal close retains the borrowed SQL lease until every accepted task leaves its operation" {
        val sql = Executors.newSingleThreadExecutor()
        val terminal = Executors.newSingleThreadExecutor()
        val released = AtomicBoolean()
        val entered = CountDownLatch(1)
        val allow = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
            error("This executor drain probe must not acquire a JDBC connection")
        } as DataSource
        val runtime = SqlRuntime.borrow(source, sql, sql, AutoCloseable { released.set(true) }, { error("Unexpected eviction") })
        val repository = MySqlStatisticsRepository(runtime)
        try {
            val accepted = runtime.executor.submit { entered.countDown(); check(allow.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val drain = CompletableFuture.runAsync({ closing.countDown(); repository.close() }, terminal)
            closing.await(5, TimeUnit.SECONDS) shouldBe true
            released.get() shouldBe false
            drain.isDone shouldBe false
            allow.countDown()
            drain.get(5, TimeUnit.SECONDS)
            accepted.join() shouldBe Unit
            released.get() shouldBe true
        } finally { allow.countDown(); repository.close(); sql.shutdownNow(); terminal.shutdownNow() }
    }
})
