package com.studyflow.ui.demo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.studyflow.R
import com.studyflow.data.local.database.StudyFlowDatabase
import com.studyflow.data.repository.RoomTaskRepository
import com.studyflow.domain.model.TaskContent
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.conflict.*
import com.studyflow.ui.navigation.StudyFlowNavigation
import com.studyflow.ui.tasks.TasksViewModel
import com.studyflow.ui.theme.StudyFlowTheme
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Debug-only manual test harness. Never opens studyflow.db. */
class UiDemoActivity : ComponentActivity() {
    private lateinit var session: DemoSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this, viewModelFactory { initializer { DemoSession(applicationContext) } })[DemoSession::class.java]
        setContent {
            StudyFlowTheme {
                var mode by rememberSaveable { mutableStateOf("menu") }
                val seeded by session.seeded.collectAsStateWithLifecycle()
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Text(stringResource(R.string.ui_demo_notice), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { if (mode == "menu") finish() else mode = "menu" }) {
                            Text(stringResource(R.string.ui_demo_close))
                        }
                        Box(Modifier.weight(1f)) {
                            when (mode) {
                                "home" -> StudyFlowNavigation(session.tasks)
                                "edit", "delete", "long" -> key(mode) {
                                    var choice by rememberSaveable { mutableStateOf<ConflictSide?>(null) }
                                    var confirmed by rememberSaveable { mutableStateOf(false) }
                                    Column {
                                        if (confirmed) Text(stringResource(R.string.ui_demo_selected), Modifier.padding(16.dp))
                                        ConflictScreen(conflictExample(mode == "delete", mode == "long").copy(selected = choice),
                                            onSelect = { choice = it; confirmed = false }, onKeepSelected = { confirmed = true },
                                            onBack = { mode = "menu" })
                                    }
                                }
                                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.ui_demo_title), style = MaterialTheme.typography.titleLarge)
                                    Button(onClick = { mode = "home" }) { Text(stringResource(R.string.ui_demo_home)) }
                                    Button(onClick = session::seed, enabled = !seeded) {
                                        Text(stringResource(if (seeded) R.string.ui_demo_seeded else R.string.ui_demo_seed))
                                    }
                                    Button(onClick = session::seedDetails, enabled = !seeded) {
                                        Text(stringResource(R.string.detail_demo_seed))
                                    }
                                    Button(onClick = { mode = "edit" }) { Text(stringResource(R.string.ui_demo_edit_edit)) }
                                    Button(onClick = { mode = "delete" }) { Text(stringResource(R.string.ui_demo_delete_edit)) }
                                    Button(onClick = { mode = "long" }) { Text(stringResource(R.string.ui_demo_long)) }
                                }
                            }
                        }
                    }
                }
                BackHandler(enabled = mode in listOf("edit", "delete", "long")) { mode = "menu" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        session.tasks.refreshToday()
    }
}

private class DemoSession(context: Context) : ViewModel() {
    private val db = Room.inMemoryDatabaseBuilder(context, StudyFlowDatabase::class.java).build()
    private val fixtureTitles = listOf(R.string.ui_demo_yesterday, R.string.ui_demo_today,
        R.string.ui_demo_tomorrow, R.string.ui_demo_undated).map(context::getString)
    private val detailSample = TaskContent(context.getString(R.string.detail_sample_title),
        context.getString(R.string.detail_sample_description), SubjectCode.MOBILE)
    private val longDetailSample = detailSample.copy(
        title = context.getString(R.string.detail_sample_long_title).take(120),
        description = List(12) { context.getString(R.string.detail_sample_long_description) }.joinToString("\n\n").take(2_000),
        priority = TaskPriority.HIGH,
    )
    private val overdueTitle = context.getString(R.string.detail_sample_overdue_title)
    private val repo = RoomTaskRepository(db)
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    val tasks: TasksViewModel = ViewModelProvider.create(owner, viewModelFactory {
        initializer { TasksViewModel(repo) }
    })[TasksViewModel::class.java]
    private val mutableSeeded = MutableStateFlow(false)
    val seeded = mutableSeeded.asStateFlow()
    fun seed() {
        if (mutableSeeded.value) return
        mutableSeeded.value = true
        viewModelScope.launch {
            val today = LocalDate.now()
            repo.create(TaskContent(fixtureTitles[0], dueDate = today.minusDays(1)))
            repo.create(TaskContent(fixtureTitles[1], dueDate = today))
            repo.create(TaskContent(fixtureTitles[2], dueDate = today.plusDays(1)))
            repo.create(TaskContent(fixtureTitles[3]))
        }
    }
    fun seedDetails() {
        if (mutableSeeded.value) return
        mutableSeeded.value = true
        viewModelScope.launch {
            val today = LocalDate.now()
            repo.create(detailSample.copy(dueDate = today.plusDays(2)))
            repo.create(longDetailSample.copy(dueDate = today.plusDays(3)))
            repo.create(TaskContent(fixtureTitles[3], priority = TaskPriority.LOW))
            repo.create(detailSample.copy(title = overdueTitle, dueDate = today.minusDays(1)))
        }
    }
    override fun onCleared() { store.clear(); db.close() }
}
