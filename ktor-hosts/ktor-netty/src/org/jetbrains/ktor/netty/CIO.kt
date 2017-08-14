package org.jetbrains.ktor.netty

import io.netty.channel.*
import io.netty.util.concurrent.*
import io.netty.util.concurrent.Future
import kotlinx.coroutines.experimental.*
import org.jetbrains.ktor.cio.*
import java.io.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.*

suspend fun <T> Future<T>.suspendAwait(): T {
    return suspendAwait { t, c ->
        c.resumeWithException(t)
    }
}

suspend fun <T> Future<T>.suspendWriteAwait(): T {
    return suspendAwait { t, c ->
        if (t is IOException) c.resumeWithException(ChannelWriteException("Write future failed", t))
        else c.resumeWithException(t)
    }
}

suspend fun <T> Future<T>.suspendAwait(exception: (Throwable, Continuation<T>) -> Unit): T {
    if (isDone) return try { get() } catch (t: Throwable) { throw t.unwrap() }

    return suspendCancellableCoroutine { continuation ->
        addListener(CoroutineListener(this, continuation, exception))
    }
}

internal object NettyDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return !context[CurrentContextKey]!!.context.executor().inEventLoop()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val nettyContext = context[CurrentContextKey]!!.context
        nettyContext.executor().execute(block)
    }

    class CurrentContext(val context: ChannelHandlerContext) : AbstractCoroutineContextElement(CurrentContextKey)
    object CurrentContextKey : CoroutineContext.Key<CurrentContext>
}

private class CoroutineListener<T, F : Future<T>>(private val future: F,
                                                  private val continuation: CancellableContinuation<T>,
                                                  private val exception: (Throwable, Continuation<T>) -> Unit
) : GenericFutureListener<F>, DisposableHandle {
    init {
        continuation.disposeOnCompletion(this)
    }

    override fun operationComplete(future: F) {
        val value = try {
            future.get()
        } catch (t: Throwable) {
            exception(t.unwrap(), continuation)
            return
        }

        continuation.resume(value)
    }

    override fun dispose() {
        future.removeListener(this)
        if (continuation.isCancelled) future.cancel(false)
    }
}

private tailrec fun Throwable.unwrap(): Throwable = if (this is ExecutionException && cause != null) cause!!.unwrap() else this