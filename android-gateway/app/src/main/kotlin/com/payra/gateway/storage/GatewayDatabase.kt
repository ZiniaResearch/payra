package com.payra.gateway.storage

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SmsJob::class, SmsSendPart::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class GatewayDatabase : RoomDatabase() {
    abstract fun smsJobs(): SmsJobDao

    companion object {
        @Volatile
        private var instance: GatewayDatabase? = null

        fun getInstance(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GatewayDatabase::class.java,
                "gateway_jobs.db",
            ).build().also { instance = it }
        }
    }
}
