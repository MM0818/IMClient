package com.example.myapplication.database.im

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myapplication.database.im.dao.ConversationDao
import com.example.myapplication.database.im.dao.MessageDao
import com.example.myapplication.database.im.entity.ConversationEntity
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.database.im.entity.MessageFtsEntity

/**
 * IM数据库
 * 包含messages和conversations双表
 */
@Database(
    entities = [MessageEntity::class, ConversationEntity::class, MessageFtsEntity::class],
    version = 6,
    exportSchema = false
)
abstract class IMDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: IMDatabase? = null

        fun getDatabase(context: Context): IMDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IMDatabase::class.java,
                    "im_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
