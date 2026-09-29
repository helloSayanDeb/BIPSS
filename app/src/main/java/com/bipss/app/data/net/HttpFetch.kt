package com.bipss.app.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** A whole HTTP answer: its status code and body text. */
class HttpText(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Runs [request] and reads the whole answer, in a way a time limit can cut short.
 *
 * Cancelling the calling coroutine (a caller's time limit, a stopped worker)
 * cancels the HTTP call and returns at once, whatever stage the call is at,
 * DNS included. A blocking execute() inside withContext(IO) cannot be cut
 * short: a time limit around it only took effect once the call gave up by
 * itself, which held the location setup screen for two minutes against its
 * 20 s limit.
 */
suspend fun OkHttpClient.fetchText(request: Request): HttpText = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // Read here, on OkHttp's thread, so the caller never blocks on the body either.
            val read = runCatching { response.use { HttpText(it.code, it.body?.string()) } }
            if (cont.isActive) cont.resumeWith(read)
        }
    })
}
