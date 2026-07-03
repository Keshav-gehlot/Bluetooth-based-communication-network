package com.meshchat.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 3, exportSchema = false)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
