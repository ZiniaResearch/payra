package com.payra.gateway.storage

import androidx.room.Entity

@Entity(tableName = "sms_jobs", primaryKeys = ["serverUrl", "id"])
internal data class SmsJob(
    val serverUrl: String,
    val id: String,
    val recipient: String,
    val message: String,
)
