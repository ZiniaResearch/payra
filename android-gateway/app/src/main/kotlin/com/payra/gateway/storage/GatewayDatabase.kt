package com.payra.gateway.storage

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SmsJob::class, SmsSendPart::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class GatewayDatabase : RoomDatabase() {
    abstract fun smsJobs(): SmsJobDao

    companion object {
        @Volatile
        private var instance: GatewayDatabase? = null
        private val temporaryJobMigration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sms_jobs ADD COLUMN attemptNumber INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sms_jobs ADD COLUMN retryable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sms_jobs ADD COLUMN reportFrozen INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sms_jobs SET recipient = '', message = '' WHERE finishedAtMillis IS NOT NULL")
            }
        }

        fun getInstance(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GatewayDatabase::class.java,
                "gateway_jobs.db",
            ).addMigrations(temporaryJobMigration).build().also { instance = it }
        }
    }
}
