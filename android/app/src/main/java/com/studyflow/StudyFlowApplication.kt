package com.studyflow

import android.app.Application
import com.studyflow.data.local.database.StudyFlowDatabase
import com.studyflow.data.repository.RoomTaskRepository
import com.studyflow.data.repository.TaskRepository

class StudyFlowApplication : Application() {
    private val database by lazy { StudyFlowDatabase.create(this) }
    val taskRepository: TaskRepository by lazy { RoomTaskRepository(database) }
}
