package com.example.myapplication.database.im.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

/**
 * 消息全文检索虚拟表（FTS4）
 * contentEntity 关联 messages 表，Room 自动同步数据
 * unicode61 tokenizer 支持中英文分词
 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @ColumnInfo(name = "content")
    val content: String
)
