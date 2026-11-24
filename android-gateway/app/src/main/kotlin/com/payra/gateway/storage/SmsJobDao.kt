package com.payra.gateway.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SmsJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: SmsJob): Long

    @Query("SELECT COUNT(*) FROM sms_jobs WHERE serverUrl = :serverUrl")
    fun observeCount(serverUrl: String): Flow<Int>
}
