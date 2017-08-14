package org.jetbrains.ktor.jetty

import org.eclipse.jetty.io.*
import org.eclipse.jetty.util.*
import org.jetbrains.ktor.cio.*
import java.io.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

internal class EndPointWriteChannel(private val endPoint: EndPoint) : WriteChannel {
    @Volatile
    private var handler: Continuation<Unit>? = null

    private val callback = object : Callback {
        override fun succeeded() {
            Handler.getAndSet(this@EndPointWriteChannel, null)?.resume(Unit)
        }

        override fun failed(x: Throwable?) {
            Handler.getAndSet(this@EndPointWriteChannel, null)?.let { continuation ->
                continuation.resumeWithException(if (x is IOException) ChannelWriteException("EndPoint write() failed", x) else (x ?: Exception("EndPoint write() failed without exception")))
            }
        }
    }

    suspend override fun write(src: ByteBuffer) {
        if (!src.hasRemaining()) return

        return suspendCoroutine { continuation ->
            if (!Handler.compareAndSet(this, null, continuation)) throw IllegalStateException("Write operation is already pending")

            try {
                endPoint.write(callback, src)
            } catch (exception: IOException) {
                throw ChannelWriteException(exception = exception)
            }
        }
    }

    suspend override fun flush() {
    }

    override fun close() {
        endPoint.close()
        Handler.getAndSet(this, null)?.resumeWithException(ChannelWriteException("Channel closed via close()", ClosedChannelException()))
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val Handler: AtomicReferenceFieldUpdater<EndPointWriteChannel, Continuation<Unit>?> =
                AtomicReferenceFieldUpdater.newUpdater(EndPointWriteChannel::class.java,
                        Continuation::class.java,
                        EndPointWriteChannel::handler.name) as AtomicReferenceFieldUpdater<EndPointWriteChannel, Continuation<Unit>?>
    }
}