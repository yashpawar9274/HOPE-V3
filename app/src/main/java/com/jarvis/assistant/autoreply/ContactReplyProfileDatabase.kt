package com.jarvis.assistant.autoreply

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ContactReplyProfile::class,
        DiscoveredReplyContact::class,
        ReplyEventMetadata::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ContactReplyProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): ContactReplyProfileDao

    companion object {
        @Volatile
        private var instance: ContactReplyProfileDatabase? = null

        fun getInstance(context: Context): ContactReplyProfileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContactReplyProfileDatabase::class.java,
                    "contact_reply_profiles.db"
                ).build().also { instance = it }
            }
    }
}
