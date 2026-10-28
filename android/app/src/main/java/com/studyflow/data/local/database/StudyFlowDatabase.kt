package com.studyflow.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.studyflow.data.local.converter.TaskConverters
import com.studyflow.data.local.dao.TaskDao
import com.studyflow.data.local.entity.TaskEntity

@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
@TypeConverters(TaskConverters::class)
abstract class StudyFlowDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        fun create(context: Context): StudyFlowDatabase = Room.databaseBuilder(
            context.applicationContext, StudyFlowDatabase::class.java, "studyflow.db",
        ).build()
    }
}
