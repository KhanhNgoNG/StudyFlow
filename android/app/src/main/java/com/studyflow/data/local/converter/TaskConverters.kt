package com.studyflow.data.local.converter

import androidx.room.TypeConverter
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate

class TaskConverters {
    @TypeConverter fun dateToIso(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun isoToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun subjectToCode(value: SubjectCode): String = value.name
    @TypeConverter fun codeToSubject(value: String): SubjectCode = SubjectCode.valueOf(value)
    @TypeConverter fun priorityToCode(value: TaskPriority): String = value.name
    @TypeConverter fun codeToPriority(value: String): TaskPriority = TaskPriority.valueOf(value)
    @TypeConverter fun syncToCode(value: LocalSyncState): String = value.name
    @TypeConverter fun codeToSync(value: String): LocalSyncState = LocalSyncState.valueOf(value)
}
