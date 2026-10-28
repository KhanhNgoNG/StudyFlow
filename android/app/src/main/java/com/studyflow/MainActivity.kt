package com.studyflow

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.studyflow.ui.navigation.StudyFlowNavigation
import com.studyflow.ui.tasks.TasksViewModel
import com.studyflow.ui.theme.StudyFlowTheme

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
                TasksViewModel((application as StudyFlowApplication).taskRepository)
            }
        })[TasksViewModel::class.java]

        setContent {
            StudyFlowTheme {
                StudyFlowNavigation(viewModel = tasksViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate overdue labels after a date change without writing to the database.
        tasksViewModel.refreshToday()
    }
}
