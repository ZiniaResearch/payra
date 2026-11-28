package com.payra.gateway.storage

import androidx.room.Entity

@Entity(tableName = "sms_send_parts", primaryKeys = ["attemptId", "partIndex"])
internal data class SmsSendPart(
    val attemptId: String,
    val partIndex: Int,
    val resultCode: Int,
)

internal data class SmsPartTotals(
    val received: Int,
    val failed: Int,
)
