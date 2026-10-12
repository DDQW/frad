package app.frad.chat.chat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block], which blocks in I/O that coroutine cancellation can't interrupt (a socket's
 * accept/read/write, a gomobile stream call); if the coroutine is cancelled meanwhile, [close]
 * runs, which makes the blocked call fail instead of hanging on - so cancelling a transfer
 * really stops it, on both ends.
 */
suspend fun <T> closingOnCancel(close: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            runCatching(close)
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}
