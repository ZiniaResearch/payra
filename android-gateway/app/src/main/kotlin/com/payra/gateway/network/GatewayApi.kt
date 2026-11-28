package com.payra.gateway.network

import com.payra.gateway.storage.PendingSmsReport
import com.payra.gateway.storage.SmsJobState
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

internal sealed interface ClaimResult {
    data class Job(val id: String, val attemptNumber: Int, val recipient: String, val message: String) : ClaimResult
    data object Empty : ClaimResult
    data class Failure(val reason: String, val retryable: Boolean = false) : ClaimResult
}

internal sealed interface ReportResult {
    data object Acknowledged : ReportResult
    data class Failure(val reason: String, val retryable: Boolean = false) : ReportResult
}

internal class GatewayApi(baseClient: OkHttpClient, private val gatewayKey: String) {
    private val client = baseClient.newBuilder()
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun claimJob(server: GatewayServer): ClaimResult {
        val request = authenticatedRequest(server.claimUrl)
            .post(RequestBody.EMPTY)
            .build()
        return try {
            execute(request, ::readClaim)
        } catch (error: IOException) {
            ClaimResult.Failure(error.javaClass.simpleName, retryable = true)
        } catch (_: JSONException) {
            ClaimResult.Failure("Invalid job response")
        }
    }

    suspend fun reportResult(server: GatewayServer, report: PendingSmsReport): ReportResult {
        val status = when (report.state) {
            SmsJobState.SENT, SmsJobState.FAILED, SmsJobState.PARTIAL, SmsJobState.UNKNOWN ->
                report.state.name.lowercase()
            SmsJobState.TOO_LONG -> "failed"
            SmsJobState.QUEUED, SmsJobState.SENDING -> return ReportResult.Failure("SMS result is pending")
        }
        val body = JSONObject()
            .put("attempt", report.attemptNumber)
            .put("status", status)
            .toString()
            .toRequestBody(JSON)
        val request = authenticatedRequest(server.resultUrl(report.id)).post(body).build()
        return try {
            execute(request) { response -> readAcknowledgment(response, report) }
        } catch (error: IOException) {
            ReportResult.Failure(error.javaClass.simpleName, retryable = true)
        } catch (_: JSONException) {
            ReportResult.Failure("Invalid acknowledgment")
        }
    }

    private fun authenticatedRequest(url: HttpUrl) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $gatewayKey")

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use(read) })
            }
        })
    }

    private fun readClaim(response: Response): ClaimResult {
        when (val code = response.code) {
            204 -> return ClaimResult.Empty
            200 -> Unit
            else -> return ClaimResult.Failure("HTTP $code", retryable = isRetryableStatus(code))
        }

        val job = JSONObject(response.body.string())
        val id = job.opt("id") as? String
        val attemptNumber = job.opt("attempt") as? Int
        val recipient = job.opt("to") as? String
        val message = job.opt("text") as? String
        return if (id.isNullOrBlank() || attemptNumber == null || attemptNumber < 1 ||
            recipient.isNullOrBlank() || message.isNullOrBlank()
        ) {
            ClaimResult.Failure("Job ID, attempt, recipient, or message is missing or invalid")
        } else {
            ClaimResult.Job(id, attemptNumber, recipient, message)
        }
    }

    private fun readAcknowledgment(response: Response, report: PendingSmsReport): ReportResult {
        val code = response.code
        if (code != 200) return ReportResult.Failure("HTTP $code", retryable = isRetryableStatus(code))
        val responseBody = JSONObject(response.body.string())
        if (responseBody.opt("id") == report.id && responseBody.opt("attempt") == report.attemptNumber) {
            return ReportResult.Acknowledged
        }
        return ReportResult.Failure("Acknowledgment did not match this attempt")
    }

    private fun isRetryableStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
