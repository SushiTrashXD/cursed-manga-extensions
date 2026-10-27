package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListingDto(val props: Props) {
    @Serializable
    class Props(
        val books: Books? = null,
        // Rankings list theirs as `podium` (top three, page 1 only) and `grid` instead.
        val podium: List<Book> = emptyList(),
        val grid: List<Book> = emptyList(),
    )

    @Serializable
    class Books(val data: List<Book> = emptyList())

    @Serializable
    class Book(val id: Int, val pageCount: Int? = null, val chapters: Int? = null)
}

@Serializable
class JsonLdDto(
    @SerialName("@type") val type: String? = null,
    val numberOfPages: Int? = null,
)

@Serializable
class TagPageDto(private val props: Props) {

    @Serializable
    class Props(val tags: Tags)

    @Serializable
    class Tags(
        val data: List<TagDto>,
        val lastPage: Int,
    )

    val tags get() = props.tags.data
    val lastPage get() = props.tags.lastPage
}

@Serializable
class TagDto(
    private val id: Int,
    private val name: String,
    val type: String,
) {
    internal fun toFilter() = TagFilter(id, name)
}

@Serializable
class BookPageDto(val props: Props) {
    @Serializable
    class Props(val book: Book)

    @Serializable
    class Book(val id: Int)
}

@Serializable
class ReaderDto(private val props: Props) {

    @Serializable
    class Props(
        val images: List<String?>,
        val avif: List<String?> = emptyList(),
    )

    // `avif` repeats the same pages (or is all null), so only one list is used.
    fun toPages(): List<Page> = props.images.filterNotNull().ifEmpty { props.avif.filterNotNull() }
        .mapIndexed { index, url -> Page(index, imageUrl = if (url.startsWith("//")) "https:$url" else url) }
}
