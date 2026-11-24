package com.payra.gateway.storage

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

internal enum class SmsJobState {
    QUEUED,
    SENDING,
    SENT,
    FAILED,
    PARTIAL,
    UNKNOWN,
    TOO_LONG,
}

@Entity(
    tableName = "sms_jobs",
    primaryKeys = ["serverUrl", "id"],
    indices = [
        Index(value = ["state", "serverUrl"]),
        Index(value = ["serverUrl", "finishedAtMillis"]),
    ],
)
internal data class SmsJob(
    val serverUrl: String,
    val id: String,
    val recipient: String,
    val message: String,
    @ColumnInfo(defaultValue = "'QUEUED'")
    val state: SmsJobState = SmsJobState.QUEUED,
    val attemptId: String? = null,
    val startedAtMillis: Long? = null,
    val partCount: Int? = null,
    val finishedAtMillis: Long? = null,
    val errorCode: Int? = null,
    @ColumnInfo(defaultValue = "1")
    val attemptNumber: Int = 1,
    @ColumnInfo(defaultValue = "0")
    val retryable: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val reportFrozen: Boolean = false,
)

internal data class SmsJobOutcome(
    val state: SmsJobState,
    val partCount: Int?,
    val errorCode: Int?,
)

internal data class SmsQueueOverview(
    val queuedCount: Int,
    val isSending: Boolean,
    @Embedded(prefix = "outcome_") val latestOutcome: SmsJobOutcome?,
)

internal data class SmsReportKey(val id: String, val attemptNumber: Int)

internal data class PendingSmsReport(
    val id: String,
    val attemptNumber: Int,
    val state: SmsJobState,
    val partCount: Int?,
    val errorCode: Int?,
    val retryable: Boolean,
)
