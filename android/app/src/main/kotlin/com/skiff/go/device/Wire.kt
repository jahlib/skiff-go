// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.device

import android.os.SystemClock
import com.skiff.go.proto.Cmd
import com.skiff.go.proto.Frame
import com.skiff.go.proto.Req
import com.skiff.go.proto.frame
import com.skiff.go.proto.replyMatches
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The request queue to the FM-1 (editor.html Link): one request in flight, the next sent when its reply comes (the
 * device holds a single incoming frame and drops one that arrives before it answered). A reply is taken only when it
 * answers the request in flight ([replyMatches]); pushes go to [onPush] at any time.
 *
 * A request with a key replaces the queued one with the same key (its new args win, both callers get the one reply):
 * a knob being turned has one SET in flight and at most one queued, always carrying the latest value, so a drag never
 * builds a backlog.
 */
class Wire(
    private val send: (ByteArray) -> Unit,
    scope: CoroutineScope,
    private val onPush: (Frame) -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private class Item(var cmd: Int, var args: IntArray, val key: String?, val timeout: Long, var retries: Int,
                       val reply: CompletableDeferred<IntArray> = CompletableDeferred())

    private val lock = Any()
    private val q = ArrayDeque<Item>()
    @Volatile private var cur: Item? = null
    @Volatile private var waiter: CompletableDeferred<IntArray>? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile var closed = false
        private set
    /** When the last frame went out (0: never); the firmware ends WATCH 3 s after the last request. */
    @Volatile var lastSent = 0L
        private set

    val idle: Boolean get() = synchronized(lock) { cur == null && q.isEmpty() }

    fun hasQueued(key: String): Boolean = synchronized(lock) { q.any { it.key == key } || cur?.key == key }

    init {
        scope.launch {
            for (u in signal) pump()
        }
    }

    suspend fun request(req: Req, timeout: Long = 300, retries: Int = 1, key: String? = null): IntArray {
        val reply = synchronized(lock) {
            if (closed) throw IOException("closed")
            val old = if (key != null) q.firstOrNull { it.key == key } else null
            if (old != null) {
                old.cmd = req.cmd; old.args = req.args
                old.reply
            } else Item(req.cmd, req.args, key, timeout, retries).also { q.addLast(it) }.reply
        }
        signal.trySend(Unit)
        return reply.await()
    }

    private suspend fun pump() {
        while (true) {
            val item = synchronized(lock) { q.removeFirstOrNull()?.also { cur = it } } ?: return
            var res: IntArray? = null
            var err: Throwable? = null
            while (!closed) {
                val w = CompletableDeferred<IntArray>()
                waiter = w
                try {
                    send(frame(item.cmd, item.args))
                    lastSent = now()
                } catch (e: Exception) {
                    err = e
                    break
                }
                res = withTimeoutOrNull(item.timeout) { w.await() }
                if (res != null || item.retries-- <= 0) break
            }
            waiter = null
            synchronized(lock) { cur = null }
            if (res != null) item.reply.complete(res)
            else item.reply.completeExceptionally(err ?: IOException(if (closed) "closed" else "timeout (cmd ${item.cmd})"))
        }
    }

    /** A frame from the device. */
    fun receive(f: Frame) {
        if (f.cmd in Cmd.PUSH) { onPush(f); return }
        val c = cur ?: return
        if (f.cmd == c.cmd && replyMatches(c.cmd, c.args, f.args)) waiter?.complete(f.args)
    }

    fun close() {
        val pending = synchronized(lock) {
            closed = true
            (q.toList() + listOfNotNull(cur)).also { q.clear() }
        }
        pending.forEach { it.reply.completeExceptionally(IOException("closed")) }
        waiter?.cancel()
        signal.close()
    }
}
