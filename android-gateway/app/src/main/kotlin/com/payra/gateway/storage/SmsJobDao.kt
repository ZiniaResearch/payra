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
    abstract suspend fun insert(job: SmsJob): Long

    @Query("""
        SELECT
            (SELECT COUNT(*) FROM sms_jobs WHERE serverUrl = :serverUrl AND state = 'QUEUED') AS queuedCount,
            EXISTS (SELECT 1 FROM sms_jobs WHERE serverUrl = :serverUrl AND state = 'SENDING') AS isSending,
            outcome.state AS outcome_state,
            outcome.partCount AS outcome_partCount,
            outcome.errorCode AS outcome_errorCode
        FROM (SELECT 1)
        LEFT JOIN sms_jobs AS outcome ON outcome.rowid = (
            SELECT rowid FROM sms_jobs WHERE serverUrl = :serverUrl AND finishedAtMillis IS NOT NULL
            ORDER BY finishedAtMillis DESC, rowid DESC LIMIT 1
        )
    """)
    abstract fun observeOverview(serverUrl: String): Flow<SmsQueueOverview>

    // An existing send stays first even if the operator changes servers.
    @Query("""
        SELECT * FROM sms_jobs WHERE rowid = COALESCE(
            (SELECT rowid FROM sms_jobs WHERE state = 'SENDING' LIMIT 1),
            (SELECT rowid FROM sms_jobs WHERE serverUrl = :serverUrl AND state = 'QUEUED' ORDER BY rowid LIMIT 1)
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
        UPDATE sms_jobs SET state = :state, partCount = :partCount, finishedAtMillis = :now
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
        UPDATE sms_jobs SET state = :state, errorCode = :errorCode, finishedAtMillis = :now
        WHERE serverUrl = :serverUrl AND id = :id AND attemptId = :attemptId
            AND state IN ('SENDING', 'UNKNOWN')
    """)
    abstract suspend fun finishAttempt(
        serverUrl: String,
        id: String,
        attemptId: String,
        state: SmsJobState,
        errorCode: Int?,
        now: Long,
    ): Int

    @Query("""
        SELECT partCount FROM sms_jobs WHERE serverUrl = :serverUrl AND id = :id AND attemptId = :attemptId
            AND state IN ('SENDING', 'UNKNOWN')
    """)
    protected abstract suspend fun resultPartCount(serverUrl: String, id: String, attemptId: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPart(result: SmsSendPart): Long

    @Query("""
        SELECT COUNT(*) AS received,
            COUNT(CASE WHEN resultCode <> :successCode THEN 1 END) AS failed,
            MIN(CASE WHEN resultCode <> :successCode THEN resultCode END) AS errorCode
        FROM sms_send_parts WHERE attemptId = :attemptId
    """)
    protected abstract suspend fun partTotals(attemptId: String, successCode: Int): SmsPartTotals

    @Transaction
    open suspend fun recordPartResult(
        serverUrl: String,
        id: String,
        part: SmsSendPart,
        successCode: Int,
        now: Long,
    ): SmsJobState? {
        val expectedParts = resultPartCount(serverUrl, id, part.attemptId) ?: return null
        if (part.partIndex !in 0 until expectedParts || insertPart(part) == -1L) return null

        val totals = partTotals(part.attemptId, successCode)
        if (totals.received < expectedParts) return null
        val state = when {
            totals.failed == 0 -> SmsJobState.SENT
            totals.failed < totals.received -> SmsJobState.PARTIAL
            else -> SmsJobState.FAILED
        }
        finishAttempt(serverUrl, id, part.attemptId, state, totals.errorCode, now)
        return state
    }
}
