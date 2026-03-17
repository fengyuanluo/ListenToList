package com.kutedev.easemusicplayer.utils

data class ReorderCommit<ID>(
    val movedId: ID,
    val previousId: ID?,
    val nextId: ID?,
)

data class ReorderMutation<T, ID>(
    val reorderedItems: List<T>,
    val commit: ReorderCommit<ID>,
)

fun <T, ID> buildReorderMutation(
    items: List<T>,
    fromIndex: Int,
    toIndex: Int,
    idOf: (T) -> ID,
): ReorderMutation<T, ID>? {
    if (
        fromIndex == toIndex ||
        fromIndex !in items.indices ||
        toIndex !in items.indices
    ) {
        return null
    }

    val reorderedItems = items.toMutableList().apply {
        val moved = removeAt(fromIndex)
        add(toIndex, moved)
    }
    val movedItem = reorderedItems[toIndex]

    return ReorderMutation(
        reorderedItems = reorderedItems,
        commit = ReorderCommit(
            movedId = idOf(movedItem),
            previousId = reorderedItems.getOrNull(toIndex - 1)?.let(idOf),
            nextId = reorderedItems.getOrNull(toIndex + 1)?.let(idOf),
        ),
    )
}
