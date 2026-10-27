package com.studyflow

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.studyflow.ui.navigation.StudyFlowNavigation
import com.studyflow.ui.tasks.SampleTasks
import com.studyflow.ui.tasks.TasksViewModel
import com.studyflow.ui.theme.StudyFlowTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var tasksViewModel: TasksViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            // API 26 cannot draw dark navigation icons; give its white icons a dark scrim.
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.rgb(23, 23, 43)),
        )
        tasksViewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                // Executed only when this Activity's ViewModelStore needs a new instance.
                TasksViewModel(SampleTasks.create(applicationContext.resources, LocalDate.now()))
            }
        })[TasksViewModel::class.java]

        setContent {
            StudyFlowTheme {
                val state by tasksViewModel.uiState.collectAsStateWithLifecycle()
                StudyFlowNavigation(
                    tasksState = state,
                    onQueryChange = tasksViewModel::onQueryChange,
                    onFilterChange = tasksViewModel::onFilterChange,
                    onToggleCompleted = tasksViewModel::toggleCompleted,
                    onClearConditions = tasksViewModel::clearConditions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate overdue labels after a date change without recreating sample data.
        tasksViewModel.refreshToday()
    }
}
