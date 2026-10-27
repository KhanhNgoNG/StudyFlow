package com.studyflow.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val StudyFlowPurple = Color(0xFF6355D9)
internal val StudyFlowBackground = Color(0xFFFAFAFC)
internal val StudyFlowInk = Color(0xFF17172B)

data class StudyFlowColors(
    val subjectMobile: Color = Color(0xFFEDE8FF),
    val subjectOnMobile: Color = Color(0xFF493893),
    val subjectDatabase: Color = Color(0xFFE0F2ED),
    val subjectOnDatabase: Color = Color(0xFF225C4A),
    val subjectGeneral: Color = Color(0xFFFFEED5),
    val subjectOnGeneral: Color = Color(0xFF76501A),
    val subjectOther: Color = Color(0xFFE5EEFC),
    val subjectOnOther: Color = Color(0xFF365B87),
)

val LocalStudyFlowColors = staticCompositionLocalOf { StudyFlowColors() }
