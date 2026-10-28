package com.studyflow.ui.tasks

import android.content.res.Resources
import com.studyflow.R
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.Task
import com.studyflow.domain.model.TaskPriority
import com.studyflow.domain.model.LocalSyncState
import java.time.LocalDate

object SampleTasks {
    /** Debug/preview fixture only. Application startup never seeds these into Room. */
    fun create(resources: Resources, today: LocalDate): List<TaskItem> = listOf(
        TaskItem(
            task = Task(
                id = "00000000-0000-4000-8000-000000000001",
                title = resources.getString(R.string.sample_database_review),
                subjectCode = SubjectCode.DATABASE,
                priority = TaskPriority.HIGH,
                dueDate = today.minusDays(2),
            ),
            syncStatus = LocalSyncState.PENDING,
        ),
        TaskItem(
            task = Task(
                id = "00000000-0000-4000-8000-000000000002",
                title = resources.getString(R.string.sample_mobile_ui),
                subjectCode = SubjectCode.MOBILE,
                priority = TaskPriority.HIGH,
                dueDate = today,
            ),
            syncStatus = LocalSyncState.PENDING,
        ),
        TaskItem(
            task = Task(
                id = "00000000-0000-4000-8000-000000000003",
                title = resources.getString(R.string.sample_database_exercises),
                subjectCode = SubjectCode.DATABASE,
                priority = TaskPriority.MEDIUM,
                dueDate = today,
            ),
            syncStatus = LocalSyncState.SYNCED,
        ),
        TaskItem(
            task = Task(
                id = "00000000-0000-4000-8000-000000000004",
                title = resources.getString(R.string.sample_weekly_plan),
                subjectCode = SubjectCode.GENERAL,
                priority = TaskPriority.LOW,
                dueDate = today,
                isCompleted = true,
            ),
            syncStatus = LocalSyncState.SYNCED,
        ),
        TaskItem(
            task = Task(
                id = "00000000-0000-4000-8000-000000000005",
                title = resources.getString(R.string.sample_mobile_report),
                subjectCode = SubjectCode.MOBILE,
                priority = TaskPriority.MEDIUM,
                dueDate = today.plusDays(3),
            ),
            syncStatus = LocalSyncState.PENDING,
        ),
        TaskItem(
            task = Task(
                id = "00000000-0000-4000-8000-000000000006",
                title = resources.getString(R.string.sample_reading),
                subjectCode = SubjectCode.OTHER,
                priority = TaskPriority.LOW,
                dueDate = null,
            ),
            syncStatus = LocalSyncState.SYNCED,
        ),
    )
}
