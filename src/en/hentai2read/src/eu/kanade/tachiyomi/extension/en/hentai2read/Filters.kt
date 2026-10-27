package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal open class QueryPartFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value get() = options[state].second
}

internal class TypeFilter :
    QueryPartFilter(
        "Type",
        listOf(
            "All" to "",
            "Original" to "original",
            "Doujinshi" to "doujinshi",
            "Image sets" to "imageset",
            "Videos" to "video",
        ),
    )

internal class StatusFilter :
    QueryPartFilter(
        "Status",
        listOf(
            "Any status" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
        ),
    )

internal class SortFilter :
    QueryPartFilter(
        "Sort by",
        listOf(
            "Best match" to "",
            "Newest" to "newest",
            "Oldest" to "oldest",
            "Recently updated" to "latest",
            "Most read" to "most-viewed",
            "Highest rated" to "top-rated",
            "A to Z" to "alphabetical",
            "Most chapters" to "most-chapters",
            "Most pages" to "most-pages",
            "Fewest pages" to "fewest-pages",
        ),
    )

internal class TagFilter(val id: Int, name: String) : Filter.TriState(name)

internal class TagGroup(name: String, tags: List<TagFilter>) : Filter.Group<TagFilter>(name, tags)

// Artist, parody and character lists run to thousands of entries, so only these are offered.
internal val TAG_TYPES = listOf("category", "content")

internal fun hentai2readFilters(tags: List<TagDto>): FilterList {
    val filters = buildList<Filter<*>> {
        add(Filter.Header("Search also takes artist:name, tag:name and -tag:name. Quote names with spaces: artist:\"two words\""))
        add(Filter.Separator())
        add(TypeFilter())
        add(StatusFilter())
        add(SortFilter())

        TAG_TYPES.forEach { type ->
            val group = tags.filter { it.type == type }.map { it.toFilter() }.sortedBy { it.name.lowercase() }
            if (group.isNotEmpty()) add(TagGroup(type.replaceFirstChar { it.uppercase() }, group))
        }
    }
    return FilterList(filters)
}
