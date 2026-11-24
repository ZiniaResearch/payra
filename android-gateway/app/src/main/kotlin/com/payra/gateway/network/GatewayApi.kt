package com.payra.gateway.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

internal sealed interface ClaimResult {
    data class Job(val id: String, val recipient: String, val message: String) : ClaimResult
    data object Empty : ClaimResult
    data class Failure(val reason: String, val retryable: Boolean = false) : ClaimResult
}

internal class GatewayApi(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun claimJob(server: GatewayServer): ClaimResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(server.claimUrl)
            .post(RequestBody.EMPTY)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(ClaimResult.Failure(e.javaClass.simpleName, retryable = true))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { readClaim(it) }
                } catch (error: IOException) {
                    ClaimResult.Failure(error.javaClass.simpleName, retryable = true)
                } catch (_: JSONException) {
                    ClaimResult.Failure("Invalid job response")
                }
                continuation.resume(result)
            }
        })
    }

    private fun readClaim(response: Response): ClaimResult {
        when (val code = response.code) {
            204 -> return ClaimResult.Empty
            200 -> Unit
            else -> return ClaimResult.Failure("HTTP $code", retryable = code in 500..599)
        }

        val job = JSONObject(response.body.string())
        val id = job.opt("id") as? String
        val recipient = job.opt("to") as? String
        val message = job.opt("text") as? String
        return if (id.isNullOrBlank() || recipient.isNullOrBlank() || message.isNullOrBlank()) {
            ClaimResult.Failure("Job ID, recipient, or message is missing or invalid")
        } else {
            ClaimResult.Job(id, recipient, message)
        }
    }
}
