package com.studyflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = StudyFlowPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE9FF),
    onPrimaryContainer = Color(0xFF332383),
    secondary = Color(0xFF625C78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE5F5),
    onSecondaryContainer = Color(0xFF312B44),
    tertiary = Color(0xFF356555),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EFE4),
    onTertiaryContainer = Color(0xFF214E40),
    background = StudyFlowBackground,
    onBackground = StudyFlowInk,
    surface = Color.White,
    onSurface = StudyFlowInk,
    surfaceVariant = Color(0xFFF0EEF6),
    onSurfaceVariant = Color(0xFF5F5D70),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F5FA),
    surfaceContainer = Color(0xFFF0EEF6),
    surfaceContainerHigh = Color(0xFFEAE8F2),
    surfaceContainerHighest = Color(0xFFE4E1EC),
    outline = Color(0xFF7B788B),
    outlineVariant = Color(0xFFE5E2ED),
    error = Color(0xFFAC3434),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF832828),
)

private val StudyFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StudyFlowTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStudyFlowColors provides StudyFlowColors()) {
        MaterialTheme(
            colorScheme = LightColors,
            typography = StudyFlowTypography,
            shapes = StudyFlowShapes,
            content = content,
        )
    }
}
