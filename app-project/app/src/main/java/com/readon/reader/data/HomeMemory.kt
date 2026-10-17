package com.readon.reader.data

/**
 * What the user was looking at on 主页, so going away and coming back is not a
 * reset.
 *
 * ## Why this is a saved record and not `remember`
 *
 * `remember` already survives a tab switch, because 主页 is recomposed from
 * scratch but the tab index keeps the *screen* conceptually in place... except it
 * does not. `AppShell` swaps the whole subtree with a `when (tab)`, so leaving 主页
 * disposes it, and `remember`/`rememberSaveable` state inside it goes with it.
 * `rememberSaveable` would survive rotation but still lose the state on a tab
 * switch, because the composable is genuinely removed rather than saved.
 *
 * So the memory lives outside the composition entirely, in [Prefs], and is
 * restored when the screen appears. That also makes it survive the process being
 * killed, which is the difference between "remembered my place" and "remembered my
 * place until the phone needed the memory".
 *
 * ## What is remembered, and what is deliberately not
 *
 * Remembered: the search query, the selected collection, the scroll position, and
 * *which* library those belonged to. Not remembered: which books were loaded --
 * that is [OnlineLibrary]'s job and it re-lists on its own.
 *
 * The ordering matters more than it looks. Restoring the scroll position before
 * the listing has loaded would clamp it to zero, because a `LazyListState` cannot
 * scroll past the items that exist. And restoring a query from a *different*
 * repository would silently hide the user's new library behind a search they typed
 * against the old one. Both are handled by [restorable].
 */
data class HomeMemory(
    /**
     * The library these values belong to, as `owner/repo@branch`.
     *
     * Compared rather than stored separately so a memory recorded against one
     * repository cannot be applied to another. Empty means "never saved".
     */
    val sourceKey: String = "",
    /** The search box's text. */
    val query: String = "",
    /** The selected collection, or null for 全部. */
    val collection: String? = null,
    /** Preserved item index of the top of the list. */
    val scrollIndex: Int = 0,
    /**
     * Pixel offset past [scrollIndex] of the top item.
     *
     * Kept as well as the index so a restore lands where the user was rather than
     * at the top of the row they had half-scrolled past. Clamped on restore,
     * because the item may have grown or shrunk since.
     */
    val scrollOffset: Int = 0,
)

/**
 * Whether [memory] may be applied to a listing of [sourceKey].
 *
 * Three separate reasons to say no, and each one is a visible bug if ignored:
 *
 *  * **A different library.** Applying 《三体》's query to a repository that has
 *    never held it shows an empty list, which reads as "the library is broken".
 *  * **A collection that no longer exists.** A folder can be renamed or deleted;
 *    filtering by its old name then hides everything. The caller checks this
 *    against the listing it actually got (see [collectionAvailable]).
 *  * **Nonsense scroll values.** Prefs is a text file a user can edit, and a
 *    negative index would throw inside `LazyListState`.
 *
 * @param sourceKey the library currently configured, as `owner/repo@branch`.
 */
fun HomeMemory.restorable(sourceKey: String): Boolean =
    this.sourceKey.isNotBlank() &&
        this.sourceKey == sourceKey &&
        scrollIndex >= 0 &&
        scrollOffset >= 0

/**
 * Whether [collection] still exists in [available] -- or whether no collection is
 * selected, which is always restorable.
 *
 * Null-safe on purpose: "全部" is not a missing collection, it is the absence of
 * one, and conflating the two would make every launch forget the filter.
 */
fun collectionAvailable(collection: String?, available: Collection<String>): Boolean =
    collection == null || collection in available

/**
 * The (index, offset) pair to hand back to a list, clamped to what actually exists.
 *
 * A `LazyListState` throws on an index past the end, and the listing legitimately
 * changes between visits -- a book may have been added or deleted. Clamping is the
 * honest answer: land as close to where the user was as the new listing allows.
 *
 * @param itemCount how many items the new list actually has.
 */
fun HomeMemory.clampedPosition(itemCount: Int): Pair<Int, Int> {
    if (itemCount <= 0) return 0 to 0
    val index = scrollIndex.coerceIn(0, itemCount - 1)
    // An offset only means something on the exact item it was recorded on; after
    // clamping, the user is on a different row anyway, so start at its top.
    val offset = if (index == scrollIndex) scrollOffset.coerceAtLeast(0) else 0
    return index to offset
}

/**
 * Whether a remembered state is worth restoring at all.
 *
 * A memory of "no search, no filter, scrolled to the top" is the default state, and
 * writing it back is harmless but pointless -- except that it is not entirely
 * harmless: it would mark the memory as freshly saved on every visit and make a user
 * who deliberately scrolled back to the top indistinguishable from one who never
 * left. The distinction only matters for the save debounce, but the check belongs
 * with the data rather than with the timer.
 *
 * [sourceKey] deliberately does **not** count. It is metadata about which library the
 * other fields belong to, not something the user expressed -- and since every save
 * writes it, counting it would make every default view "worth remembering" and make
 * the check meaningless. What counts is the three things the user actually chose: a
 * search, a filter, or a scroll position.
 */
fun HomeMemory.isMeaningful(): Boolean =
    query.isNotBlank() || collection != null || scrollIndex > 0 || scrollOffset > 0
