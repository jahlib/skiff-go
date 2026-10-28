// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.device

import com.skiff.go.proto.Cmd
import com.skiff.go.proto.Frame
import com.skiff.go.proto.Requests
import com.skiff.go.proto.unframe
import com.skiff.go.proto.v14enc
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sent = CopyOnWriteArrayList<Frame>()
        val pushes = CopyOnWriteArrayList<Frame>()
        val link = Wire({ sent.add(unframe(it)!!) }, scope, { pushes.add(it) }, now = { 0L })
        suspend fun awaitSent(n: Int) = withTimeout(2000) { while (sent.size < n) delay(2) }
        fun close() { link.close(); scope.cancel() }
    }

    @Test fun oneInFlightAndAnswersMatched() = runBlocking {
        val r = Rig()
        val a = async { r.link.request(Requests.set(0, 33, 10)) }
        val b = async { r.link.request(Requests.set(0, 34, 20)) }
        r.awaitSent(1)
        delay(30)
        assertEquals(1, r.sent.size)                  // the second waits for the first's reply
        r.link.receive(Frame(Cmd.SET, intArrayOf(0, 34) + v14enc(20)))   // a stray reply: not the one in flight
        delay(30)
        assertEquals(1, r.sent.size)
        r.link.receive(Frame(Cmd.SET, intArrayOf(0, 33) + v14enc(10)))
        r.awaitSent(2)
        r.link.receive(Frame(Cmd.SET, intArrayOf(0, 34) + v14enc(20)))
        assertArrayEquals(intArrayOf(0, 33) + v14enc(10), a.await())
        assertArrayEquals(intArrayOf(0, 34) + v14enc(20), b.await())
        r.close()
    }

    @Test fun keyedRequestsCoalesce() = runBlocking {
        val r = Rig()
        val first = async { r.link.request(Requests.set(0, 1, 1), key = "k") }
        r.awaitSent(1)
        val second = async { r.link.request(Requests.set(0, 1, 2), key = "k") }
        val third = async { r.link.request(Requests.set(0, 1, 3), key = "k") }
        delay(30)
        assertTrue(r.link.hasQueued("k"))
        r.link.receive(Frame(Cmd.SET, intArrayOf(0, 1) + v14enc(1)))
        r.awaitSent(2)
        assertArrayEquals(intArrayOf(0, 1) + v14enc(3), r.sent[1].args)   // the queued one carries the latest value
        r.link.receive(Frame(Cmd.SET, intArrayOf(0, 1) + v14enc(3)))
        first.await()
        assertArrayEquals(second.await(), third.await())
        delay(30)
        assertEquals(2, r.sent.size)
        r.close()
    }

    @Test fun timeoutRetriesThenFails() = runBlocking {
        val r = Rig()
        val res = runCatching { r.link.request(Requests.transport(), timeout = 40, retries = 1) }
        assertTrue(res.isFailure)
        assertEquals(2, r.sent.size)                  // sent, then once more
        r.close()
    }

    @Test fun pushesNeverAnswer() = runBlocking {
        val r = Rig()
        val a = async { r.link.request(Requests.get(0, 1)) }
        r.awaitSent(1)
        r.link.receive(Frame(Cmd.CHANGED, intArrayOf(0, 1) + v14enc(5)))
        delay(20)
        assertEquals(1, r.pushes.size)
        assertTrue(a.isActive)
        r.link.receive(Frame(Cmd.GET, intArrayOf(0, 1) + v14enc(6)))
        assertEquals(6, com.skiff.go.proto.Parse.param(a.await()).value)
        r.close()
    }
}
