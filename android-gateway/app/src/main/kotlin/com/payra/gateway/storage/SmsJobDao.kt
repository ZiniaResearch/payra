package com.payra.gateway.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class SmsJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(job: SmsJob): Long

    @Query("SELECT EXISTS (SELECT 1 FROM sms_jobs)")
    abstract suspend fun hasOutstandingJob(): Boolean

    @Transaction
    open suspend fun saveClaim(job: SmsJob): Boolean =
        !hasOutstandingJob() && insert(job) != -1L

    @Query("""
        SELECT job.serverUrl, job.state
        FROM (SELECT 1)
        LEFT JOIN sms_jobs AS job ON job.rowid = (
            SELECT rowid FROM sms_jobs
            ORDER BY CASE state WHEN 'SENDING' THEN 0 WHEN 'QUEUED' THEN 2 ELSE 1 END, rowid LIMIT 1
        )
    """)
    abstract fun observeCurrentJob(): Flow<CurrentSmsJob>

    // An existing send stays first even if the operator changes servers.
    @Query("""
        SELECT * FROM sms_jobs WHERE rowid = COALESCE(
            (SELECT rowid FROM sms_jobs WHERE state = 'SENDING' LIMIT 1),
            (SELECT rowid FROM sms_jobs WHERE serverUrl = :serverUrl AND state = 'QUEUED'
                AND NOT EXISTS (SELECT 1 FROM sms_jobs WHERE serverUrl = :serverUrl AND finishedAtMillis IS NOT NULL)
                ORDER BY rowid LIMIT 1)
        )
    """)
    abstract fun observeNextForSending(serverUrl: String): Flow<SmsJob?>

    @Query("""
        UPDATE sms_jobs SET state = 'SENDING', attemptId = :attemptId,
            startedAtMillis = :now, partCount = :partCount
        WHERE serverUrl = :serverUrl AND id = :id AND state = 'QUEUED'
            AND NOT EXISTS (SELECT 1 FROM sms_jobs WHERE state = 'SENDING')
    """)
    abstract suspend fun beginSend(serverUrl: String, id: String, attemptId: String, partCount: Int, now: Long): Int

    @Query("""
        UPDATE sms_jobs SET state = 'QUEUED', attemptId = NULL, startedAtMillis = NULL, partCount = NULL
        WHERE serverUrl = :serverUrl AND id = :id AND attemptId = :attemptId AND state = 'SENDING'
    """)
    abstract suspend fun returnToQueue(serverUrl: String, id: String, attemptId: String)

    @Query("""
        UPDATE sms_jobs SET state = :state, partCount = :partCount, finishedAtMillis = :now,
            recipient = '', message = ''
        WHERE serverUrl = :serverUrl AND id = :id AND state = 'QUEUED'
    """)
    abstract suspend fun rejectQueuedJob(
        serverUrl: String,
        id: String,
        state: SmsJobState,
        partCount: Int?,
        now: Long,
    )

    @Query("""
        UPDATE sms_jobs SET state = :state, errorCode = :errorCode, finishedAtMillis = :now,
            recipient = '', message = '', retryable = :retryable
        WHERE serverUrl = :serverUrl AND id = :id AND attemptId = :attemptId
            AND state IN ('SENDING', 'UNKNOWN') AND reportFrozen = 0
    """)
    abstract suspend fun finishAttempt(
        serverUrl: String,
        id: String,
        attemptId: String,
        state: SmsJobState,
        errorCode: Int?,
        now: Long,
        retryable: Boolean = false,
    ): Int

    @Query("""
        SELECT partCount FROM sms_jobs WHERE serverUrl = :serverUrl AND id = :id AND attemptId = :attemptId
            AND state IN ('SENDING', 'UNKNOWN') AND reportFrozen = 0
    """)
    protected abstract suspend fun resultPartCount(serverUrl: String, id: String, attemptId: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPart(result: SmsSendPart): Long

    @Query("""
        SELECT COUNT(*) AS received,
            COUNT(CASE WHEN resultCode <> :successCode THEN 1 END) AS failed,
            MIN(CASE WHEN resultCode <> :successCode THEN resultCode END) AS errorCode,
            COUNT(CASE WHEN resultCode IN (:temporaryErrorCodes) THEN 1 END) AS temporaryFailures
        FROM sms_send_parts WHERE attemptId = :attemptId
    """)
    protected abstract suspend fun partTotals(
        attemptId: String,
        successCode: Int,
        temporaryErrorCodes: List<Int>,
    ): SmsPartTotals

    @Transaction
    open suspend fun recordPartResult(
        serverUrl: String,
        id: String,
        part: SmsSendPart,
        successCode: Int,
        temporaryErrorCodes: List<Int>,
        now: Long,
    ): SmsJobState? {
        val expectedParts = resultPartCount(serverUrl, id, part.attemptId) ?: return null
        if (part.partIndex !in 0 until expectedParts || insertPart(part) == -1L) return null

        val totals = partTotals(part.attemptId, successCode, temporaryErrorCodes)
        if (totals.received < expectedParts) return null
        val state = when {
            totals.failed == 0 -> SmsJobState.SENT
            totals.failed < totals.received -> SmsJobState.PARTIAL
            else -> SmsJobState.FAILED
        }
        val retryable = state == SmsJobState.FAILED && totals.temporaryFailures == expectedParts
        finishAttempt(serverUrl, id, part.attemptId, state, totals.errorCode, now, retryable)
        return state
    }

    @Query("""
        SELECT id, attemptNumber FROM sms_jobs WHERE serverUrl = :serverUrl AND finishedAtMillis IS NOT NULL
        ORDER BY finishedAtMillis, rowid LIMIT 1
    """)
    abstract fun observePendingReport(serverUrl: String): Flow<SmsReportKey?>

    @Query("""
        UPDATE sms_jobs SET reportFrozen = 1 WHERE serverUrl = :serverUrl AND id = :id
            AND attemptNumber = :attemptNumber AND finishedAtMillis IS NOT NULL AND reportFrozen = 0
    """)
    protected abstract suspend fun markReportFrozen(serverUrl: String, id: String, attemptNumber: Int)

    @Query("""
        SELECT id, attemptNumber, state, partCount, errorCode, retryable FROM sms_jobs
        WHERE serverUrl = :serverUrl AND id = :id AND attemptNumber = :attemptNumber AND reportFrozen = 1
    """)
    protected abstract suspend fun readFrozenReport(serverUrl: String, id: String, attemptNumber: Int): PendingSmsReport?

    @Transaction
    open suspend fun freezeReport(serverUrl: String, key: SmsReportKey): PendingSmsReport? {
        markReportFrozen(serverUrl, key.id, key.attemptNumber)
        return readFrozenReport(serverUrl, key.id, key.attemptNumber)
    }

    @Query("""
        DELETE FROM sms_send_parts WHERE attemptId = (
            SELECT attemptId FROM sms_jobs WHERE serverUrl = :serverUrl AND id = :id
                AND attemptNumber = :attemptNumber AND reportFrozen = 1
        )
    """)
    protected abstract suspend fun deleteReportedParts(serverUrl: String, id: String, attemptNumber: Int)

    @Query("""
        DELETE FROM sms_jobs WHERE serverUrl = :serverUrl AND id = :id
            AND attemptNumber = :attemptNumber AND reportFrozen = 1
    """)
    protected abstract suspend fun deleteReportedJob(serverUrl: String, id: String, attemptNumber: Int): Int

    @Transaction
    open suspend fun acknowledgeReport(serverUrl: String, key: SmsReportKey): Boolean {
        deleteReportedParts(serverUrl, key.id, key.attemptNumber)
        return deleteReportedJob(serverUrl, key.id, key.attemptNumber) == 1
    }
}
