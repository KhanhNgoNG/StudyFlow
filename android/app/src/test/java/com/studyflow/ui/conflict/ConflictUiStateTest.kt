package com.studyflow.ui.conflict

import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ConflictUiStateTest {
    private val content = ConflictTaskSnapshot("Title", "Description", SubjectCode.MOBILE,
        LocalDate.of(2026, 10, 28), TaskPriority.HIGH, false)

    @Test fun differencesCoverAllSixFieldsAndSelectionStartsEmpty() {
        val state = ConflictUiState(ConflictVersion.Present(content), ConflictVersion.Present(content.copy(
            title = "Other", description = "Updated", subject = SubjectCode.DATABASE,
            dueDate = null, priority = TaskPriority.LOW, completed = true,
        )))
        assertNull(state.selected)
        assertEquals(ConflictField.entries.toSet(), state.differentFields)
        assertFalse(state.isDeleteEdit)
    }

    @Test fun identicalFieldsAreNotHighlightedAndSelectionDoesNotAlterSnapshots() {
        val state = ConflictUiState(ConflictVersion.Present(content), ConflictVersion.Present(content.copy(priority = TaskPriority.MEDIUM)))
        assertEquals(setOf(ConflictField.PRIORITY), state.differentFields)
        val selected = state.copy(selected = ConflictSide.SERVER)
        assertEquals(state.server, selected.server)
        assertEquals(state.device, selected.device)
        assertNull(state.selected)
    }

    @Test fun eitherDeletedSideIsExplicitRatherThanAnEmptyTask() {
        val state = ConflictUiState(ConflictVersion.Deleted, ConflictVersion.Present(content))
        assertTrue(state.isDeleteEdit)
        assertTrue(state.differentFields.isEmpty())
        assertTrue(state.copy(device = state.server, server = state.device).isDeleteEdit)
        assertFalse(state.copy(server = ConflictVersion.Deleted).isDeleteEdit)
    }
}
