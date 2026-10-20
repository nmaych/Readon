package com.readon.reader.data

import java.util.Locale

/**
 * How the shelf is divided into groups, and how a batch selection behaves.
 *
 * Pure functions in the `data` package, with no Android or Compose types, for the
 * same reason [ShelfLabels] is: the interesting failures here are arithmetic and
 * ordering, they are silent, and `tools/check-book-logic.cmd` can assert them on
 * the desktop. The ones that matter:
 *
 *  * **A favourite is pinned, not moved.** A book that is both in 科幻 and starred
 *    must appear under the pinned 收藏 heading *and* stay in 科幻, or the group
 *    the user put it in silently loses a member every time they star it.
 *  * **A group that differs only by whitespace is one group.** "科幻" typed on
 *    one day and "科幻 " on another must not produce two headings that look
 *    identical, because the user cannot tell them apart to fix it.
 *  * **A selected id that is no longer on the shelf is dropped.** Deleting the
 *    selected books leaves their ids in the selection, and a re-download would
 *    then arrive already selected -- a delete button aimed at books the user did
 *    not choose.
 */
object ShelfGroups {

    /** The heading books without a [Book.group] are listed under. */
    const val DEFAULT_NAME = "未分组"

    /** The heading the pinned books are listed under. */
    const val FAVORITE_NAME = "收藏"

    /**
     * The longest group name accepted.
     *
     * A group name is a heading in a 96 dp-wide grid, so anything longer than this
     * is ellipsised anyway -- but a *stored* name that long would also make the
     * group picker's rows unreadable. Truncating on the way in is the honest fix;
     * refusing the name would just lose the user's typing.
     */
    const val MAX_NAME_LENGTH = 16

    /**
     * Canonical form of a group name, or null for "no group".
     *
     * Trims, collapses runs of whitespace, drops control characters and caps the
     * length. Blank input is null rather than `""`, so "no group" has exactly one
     * representation -- otherwise a book whose group was set to "" and a book with
     * no group at all would land in different sections that both render as
     * 未分组.
     */
    fun normalize(name: String?): String? {
        if (name == null) return null
        val cleaned = buildString {
            var lastSpace = false
            for (c in name) {
                if (c.isWhitespace()) {
                    if (!lastSpace && isNotEmpty()) append(' ')
                    lastSpace = true
                } else if (c.code >= 0x20) {
                    append(c)
                    lastSpace = false
                }
            }
        }.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_NAME_LENGTH) {
            // Count in code points so a truncation never splits an emoji or a
            // surrogate pair into an unpaired half.
            val end = cleaned.offsetByCodePoints(0, cleaned.codePointCount(0, cleaned.length)
                .coerceAtMost(MAX_NAME_LENGTH))
            cleaned.substring(0, end).trim()
        } else {
            cleaned
        }
    }

    /** The heading a book belongs under: its own group, or [DEFAULT_NAME]. */
    fun groupOf(book: Book): String = normalize(book.group) ?: DEFAULT_NAME

    /** Whether the book is in the pinned section. */
    fun isFavorite(book: Book): Boolean = book.favorite

    /**
     * The groups that currently exist, for the "加入书组" picker.
     *
     * Derived from the books rather than stored separately: a group is exactly the
     * set of books that name it, so a group with no books is not a group -- it is
     * a heading with nothing under it, which is worse than not offering it.
     *
     * The default group is in the list on purpose: [DEFAULT_NAME] is a real
     * destination ("move these out of the group they are in"), and the picker
     * labels it 移出书组. Leaving it out would make "un-group" the one action the
     * batch bar cannot perform.
     *
     * Sorted so the picker's rows do not reorder themselves between openings.
     */
    fun names(books: List<Book>): List<String> =
        (books.map { groupOf(it) }.distinct() + DEFAULT_NAME)
            .distinct()
            .sortedWith { a, b -> a.compareTo(b, ignoreCase = true) }

    /** The order books are listed in, both inside a group and across the shelf. */
    enum class Order(val label: String) {
        Recent("最近阅读"),
        Added("最近添加"),
        Title("书名"),
        Author("分类"),
        ;

        companion object {
            fun fromName(name: String?): Order =
                entries.firstOrNull { it.name == name } ?: Recent
        }
    }

    /** [books] in [order]. Stable, and total, so a re-sort cannot shuffle equals. */
    fun order(books: List<Book>, order: Order): List<Book> = when (order) {
        // A book never opened has lastReadAt == 0 and sorts last rather than
        // first: "最近阅读" with the never-read books on top is backwards.
        Order.Recent -> books.sortedWith(
            compareByDescending<Book> { it.lastReadAt }.thenBy { it.title }
        )
        Order.Added -> books.sortedWith(
            compareByDescending<Book> { it.downloadedAt }.thenBy { it.title }
        )
        Order.Title -> books.sortedBy { it.title }
        // 分类 is the folder the file came from, which the home screen already
        // groups by; on the shelf it is the second-most useful axis after the
        // user's own groups.
        Order.Author -> books.sortedWith(compareBy({ it.folder }, { it.title }))
    }

    /**
     * One heading and the books under it.
     *
     * @property name the heading, already canonical.
     * @property pinned true for the 收藏 section, which is drawn with a mark and
     *   sits above every other section.
     * @property default true for the 未分组 section, which always sits last: a
     *   catch-all above the groups the user made would bury them.
     */
    data class Section(
        val name: String,
        val books: List<Book>,
        val pinned: Boolean = false,
        val default: Boolean = false,
    )

    /**
     * The shelf, divided into the sections it is drawn as.
     *
     * Order:
     *
     *  1. **收藏**, if anything is starred, holding every starred book regardless
     *     of group.
     *  2. **Each named group**, alphabetically.
     *  3. **未分组** last.
     *
     * A starred book therefore appears twice -- once pinned and once in its own
     * group -- which is the point of pinning: the pinned section answers "what am
     * I reading" without taking the book out of where it belongs. Empty sections
     * are dropped, and a shelf with no books has no sections at all.
     */
    fun sections(books: List<Book>, order: Order): List<Section> {
        if (books.isEmpty()) return emptyList()

        val sorted = order(books, order)
        val favorites = sorted.filter { it.favorite }

        // Every book is listed under its own group, favourites included: the
        // pinned section is an *additional* copy, not a move. Filtering them out
        // here would silently empty the group the user filed them in.
        val byGroup = LinkedHashMap<String, MutableList<Book>>()
        for (book in sorted) {
            byGroup.getOrPut(groupOf(book)) { mutableListOf() }.add(book)
        }

        val named = byGroup.keys
            .filter { it != DEFAULT_NAME }
            .sortedWith { a, b -> a.compareTo(b, ignoreCase = true) }

        val out = ArrayList<Section>(named.size + 2)
        if (favorites.isNotEmpty()) {
            out += Section(FAVORITE_NAME, favorites, pinned = true)
        }
        for (name in named) {
            val members = byGroup[name].orEmpty()
            if (members.isNotEmpty()) out += Section(name, members)
        }
        byGroup[DEFAULT_NAME]?.takeIf { it.isNotEmpty() }?.let {
            out += Section(DEFAULT_NAME, it, default = true)
        }
        return out
    }

    /**
     * Whether a section's heading is worth drawing.
     *
     * A shelf where every book is ungrouped produces exactly one section, and a
     * lone "未分组" heading over the whole grid is noise: it names the absence of
     * a decision, and there is nothing to compare it with. The moment there is a
     * second section -- a group, or the 收藏 section -- the default heading
     * becomes the thing that says "the rest of these are unfiled", and is drawn.
     *
     * Kept here rather than in the screen so the rule is asserted on the desktop
     * with the rest of the section arithmetic.
     */
    fun showsHeading(section: Section, sectionCount: Int): Boolean =
        !(section.default && sectionCount <= 1)

    // -----------------------------------------------------------------------
    //  Batch selection
    // -----------------------------------------------------------------------

    /**
     * Add or remove one book from the selection.
     *
     * A `Set<String>` of ids rather than a set of `Book`: the shelf is re-read
     * after every mutation, so the records in hand are replaced while a selection
     * is open. An id survives that; a `Book` would not, and a selection holding
     * stale records would act on books that no longer exist.
     */
    fun toggle(current: Set<String>, id: String): Set<String> =
        if (id in current) current - id else current + id

    /**
     * Select everything, or clear if everything is already selected.
     *
     * The "or clear" half is what makes the header's 全选 button usable as a
     * single toggle: pressing it twice returns to where it started instead of
     * doing nothing the second time.
     */
    fun toggleAll(current: Set<String>, ids: Collection<String>): Set<String> {
        val all = ids.toSet()
        return if (all.isNotEmpty() && current.containsAll(all)) emptySet() else all
    }

    /** Whether every one of [ids] is selected (and there is at least one). */
    fun allSelected(current: Set<String>, ids: Collection<String>): Boolean =
        ids.isNotEmpty() && current.containsAll(ids)

    /**
     * Drop ids that are no longer on the shelf.
     *
     * Called after every mutation. Without it, deleting the selected books leaves
     * their ids selected; the shelf reloads, and the *next* book to occupy one of
     * those ids -- a re-download, a re-import of the same title -- arrives
     * already selected, one tap away from being deleted by a 删除 aimed at
     * something else.
     */
    fun prune(current: Set<String>, ids: Collection<String>): Set<String> {
        val live = ids.toSet()
        return current.filterTo(LinkedHashSet()) { it in live }
    }

    /** The selection as a stable, sorted list, so the UI does not flicker between orders. */
    fun ordered(current: Set<String>): List<String> = current.sorted()

    /** "已选 3 本" -- the count in the selection bar. */
    fun selectionLabel(count: Int): String = "已选 $count 本"

    /**
     * Names a user may not create, because the shelf already draws a heading
     * with that name.
     *
     * 收藏 and 未分组 are *structural* headings: one is the pinned section and
     * the other is where unfiled books go. A user-made group with either name
     * would render as two headings that look identical, holding different books,
     * with no way to tell which is which -- so the picker refuses the name and
     * says why, rather than silently accepting it or silently renaming it.
     */
    val RESERVED: List<String> = listOf(FAVORITE_NAME, DEFAULT_NAME)

    /** Whether [name] is one of the structural headings. */
    fun isReserved(name: String?): Boolean {
        val n = normalize(name) ?: return false
        return RESERVED.any { it.equals(n, ignoreCase = true) }
    }

    /** The book's heading, and the star state the batch bar should offer. */
    data class FavoriteAction(val label: String, val favorite: Boolean)

    /**
     * What the batch bar's 收藏 button should say and do for a selection.
     *
     * 取消收藏 rather than 收藏 when every selected book is already starred: the
     * button then does what it says, and a user who presses it twice gets the
     * star back off instead of a second no-op. Over a *mixed* selection it stays
     * 收藏 and stars the rest -- un-starring the ones already starred would be a
     * destructive guess, and the wrong guess is not visible until the 收藏
     * section has lost books the user never touched.
     *
     * Label and direction come from this one function so they cannot drift: a
     * button reading 取消收藏 that stars the selection is the classic version of
     * that bug.
     */
    fun favoriteAction(selected: List<Book>): FavoriteAction {
        val allStarred = selected.isNotEmpty() && selected.all { it.favorite }
        return if (allStarred) FavoriteAction("取消收藏", false)
        else FavoriteAction("收藏", true)
    }

    /** The batch button's label; see [favoriteAction]. */
    fun favoriteActionLabel(selected: List<Book>): String = favoriteAction(selected).label

    /** The direction [favoriteActionLabel]'s button applies; see [favoriteAction]. */
    fun favoriteActionValue(selected: List<Book>): Boolean = favoriteAction(selected).favorite

    /** The label for a group heading: the name and how many books are under it. */
    fun headingLabel(name: String, count: Int): String =
        if (count <= 0) name else "$name · $count"

    /** Lower-case comparison key, used by the picker's filter. */
    fun matches(name: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }
}
