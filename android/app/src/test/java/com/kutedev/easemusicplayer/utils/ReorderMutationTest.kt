package com.kutedev.easemusicplayer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReorderMutationTest {
    @Test
    fun buildReorderMutation_returnsFinalNeighborAnchorsAfterForwardMove() {
        val mutation = buildReorderMutation(
            items = listOf("a", "b", "c", "d"),
            fromIndex = 1,
            toIndex = 3,
            idOf = { it },
        )

        requireNotNull(mutation)
        assertEquals(listOf("a", "c", "d", "b"), mutation.reorderedItems)
        assertEquals("b", mutation.commit.movedId)
        assertEquals("d", mutation.commit.previousId)
        assertNull(mutation.commit.nextId)
    }

    @Test
    fun buildReorderMutation_returnsFinalNeighborAnchorsAfterBackwardMove() {
        val mutation = buildReorderMutation(
            items = listOf("a", "b", "c", "d"),
            fromIndex = 3,
            toIndex = 1,
            idOf = { it },
        )

        requireNotNull(mutation)
        assertEquals(listOf("a", "d", "b", "c"), mutation.reorderedItems)
        assertEquals("d", mutation.commit.movedId)
        assertEquals("a", mutation.commit.previousId)
        assertEquals("b", mutation.commit.nextId)
    }

    @Test
    fun buildReorderMutation_returnsNullWhenMoveDoesNothing() {
        assertNull(
            buildReorderMutation(
                items = listOf("a", "b"),
                fromIndex = 1,
                toIndex = 1,
                idOf = { it },
            )
        )
        assertNull(
            buildReorderMutation(
                items = listOf("a", "b"),
                fromIndex = -1,
                toIndex = 0,
                idOf = { it },
            )
        )
        assertNull(
            buildReorderMutation(
                items = listOf("a", "b"),
                fromIndex = 0,
                toIndex = 2,
                idOf = { it },
            )
        )
    }
}
