package com.nononsenseapps.feeder.ui.text

import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import java.io.IOException
import java.io.Reader

class FooConverter(
        private val source: Reader,
        private val parser: Parser
) : ContentHandler {
    private val displayElements = mutableListOf<DisplayElement>()
    private val lastElement: DisplayElement
        get() = displayElements.last()

    fun convert(): List<DisplayElement> {
        parser.contentHandler = this
        try {
            parser.parse(InputSource(source))
        } catch (e: IOException) {
            // We are reading from a string. There should not be IO problems.
            throw RuntimeException(e)
        } catch (e: SAXException) {
            // TagSoup doesn't throw parse exceptions.
            throw RuntimeException(e)
        }

        return displayElements
    }

    override fun setDocumentLocator(locator: Locator?) {
    }

    override fun startDocument() {
        // Reset state
        displayElements.clear()
        displayElements.add(ParagraphTextElement())
    }

    override fun endDocument() {
        displayElements.lastOrNull()?.close()
    }

    override fun startPrefixMapping(prefix: String?, uri: String?) {
    }

    override fun endPrefixMapping(prefix: String?) {
    }

    override fun ignorableWhitespace(ch: CharArray?, start: Int, length: Int) {
    }

    override fun processingInstruction(target: String?, data: String?) {
    }

    override fun skippedEntity(name: String?) {
    }

    override fun startElement(uri: String, localName: String, qName: String, atts: Attributes) {
        lastElement.startTag(localName.toLowerCase(), atts)?.let { nextElement ->
            displayElements.add(nextElement)
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        lastElement.endTag(localName.toLowerCase())?.let { nextElement ->
            displayElements.add(nextElement)
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        lastElement.characters(ch, start, length)
    }
}

sealed class DisplayElement {
    /**
     * Returns an element when a different element should be pushed to the list
     */
    abstract fun startTag(tag: String, attributes: Attributes): DisplayElement?


    /**
     * Returns an element when a different element should be pushed to the list
     */
    abstract fun endTag(tag: String): DisplayElement?

    /**
     * Characters to add to the buffer if the element handles text
     */
    abstract fun characters(chars: CharArray, start: Int, length: Int)

    /**
     * Called in the rare case that an open tag is left when document is closed
     */
    open fun close() {}

    /**
     * Returns true if there is visible content - false otherwise
     */
    abstract val isVisible: Boolean
}

class BlackholeElement(
        val nextTextElement: ParagraphTextElement
) : DisplayElement() {
    private var ignoreDepth = 0

    override fun startTag(tag: String, attributes: Attributes): DisplayElement? {
        // All nested tags goes to the black hole
        ignoreDepth += 1

        return null
    }

    override fun endTag(tag: String): DisplayElement? {
        return when {
            // End of the black holed tag
            ignoreDepth == 0 -> nextTextElement
            ignoreDepth > 0 -> {
                ignoreDepth -= 1
                null
            }
            else -> {
                error("ignore depth is less than 0: $ignoreDepth. This is a programmer error.")
            }
        }
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        // Black hole
    }

    override val isVisible: Boolean = false

}

data class ImageElement(
        val src: String?,
        val width: Int?,
        val height: Int?,
        val alt: String?,
        val link: String?,
        val nextTextElement: ParagraphTextElement
) : DisplayElement() {
    constructor(
            attributes: Attributes,
            nextTextElement:
            ParagraphTextElement,
            relativeLink: String?
    ) : this(
            src = attributes.getValue("", "src"),
            width = attributes.getValue("", "width")?.toIntOrNull(),
            height = attributes.getValue("", "height")?.toIntOrNull(),
            alt = attributes.getValue("", "alt"),
            link = relativeLink,
            nextTextElement = nextTextElement
    )

    private var ignoreDepth = 0

    override fun startTag(tag: String, attributes: Attributes): DisplayElement? {
        // All nested tags goes to the black hole
        ignoreDepth += 1

        return null
    }

    override fun endTag(tag: String): DisplayElement? {
        return when {
            // End of the black holed tag
            ignoreDepth == 0 -> nextTextElement
            ignoreDepth > 0 -> {
                ignoreDepth -= 1
                null
            }
            else -> {
                error("ignore depth is less than 0: $ignoreDepth. This is a programmer error.")
            }
        }
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        // Black hole
    }

    override val isVisible: Boolean = src?.isNotBlank() == true
}


open class ParagraphTextElement(
        initialPlaceholders: List<Placeholder> = emptyList(),
        open val nextTextElement: ParagraphTextElement? = null
) : DisplayElement() {
    protected var stringBuilder = StringBuilder()

    private val nextIndex: Int
        get() = stringBuilder.length


    protected val placeholders = mutableListOf<Placeholder>().apply {
        addAll(initialPlaceholders)
    }

    protected val spans = mutableListOf<Placeholder>()

    override fun close() {
        while (placeholders.isNotEmpty()) {
            end(placeholders.last().javaClass)
        }
    }

    override val isVisible: Boolean
        get() = stringBuilder.isNotBlank()

    private fun closeTagsAndReturnEmptyClone(): ParagraphTextElement {
        val initialPlaceholders = mutableListOf<Placeholder>().also { list ->
            list.addAll(placeholders.map { it.copyWithZeroStartIndex() })
        }

        this.close()

        return ParagraphTextElement(
                initialPlaceholders = initialPlaceholders,
                nextTextElement = nextTextElement
        )
    }

    private fun endParagraph(): ParagraphTextElement? {
        return when {
            nextTextElement != null -> {
                close()
                nextTextElement
            }
            else -> closeTagsAndReturnEmptyClone()
        }
    }

    protected open fun startParagraph(): ParagraphTextElement? {
        val initialPlaceholders = mutableListOf<Placeholder>().also { list ->
            list.addAll(placeholders.map { it.copyWithZeroStartIndex() })
        }

        val myClone = closeTagsAndReturnEmptyClone()

        return ParagraphTextElement(
                initialPlaceholders = initialPlaceholders,
                nextTextElement = myClone
        )
    }

    protected open fun startFormattedParagraph(): FormattedTextElement? {
        val initialPlaceholders = mutableListOf<Placeholder>().also { list ->
            list.addAll(placeholders.map { it.copyWithZeroStartIndex() })
        }

        val myClone = closeTagsAndReturnEmptyClone()

        return FormattedTextElement(
                initialPlaceholders = initialPlaceholders,
                nextTextElement = myClone
        )
    }

    override fun startTag(tag: String, attributes: Attributes): DisplayElement? {
        when (tag) {
            "strong", "b" -> start(Bold(nextIndex))
            "em", "cite", "dfn", "i" -> start(Italic(nextIndex))
            "big" -> start(Big(nextIndex))
            "small" -> start(Small(nextIndex))
            "font" -> start(Font(attributes, nextIndex))
            "tt" -> start(Monospace(nextIndex))
            "a" -> start(Href(attributes, nextIndex))
            "u" -> start(Underline(nextIndex))
            "sup" -> start(Super(nextIndex))
            "sub" -> start(Sub(nextIndex))
            "code" -> start(Code(nextIndex))
        }

        return when (tag) {
            "img" -> ImageElement(
                    attributes,
                    relativeLink = placeholders.filterIsInstance<Href>().lastOrNull()?.relativeUrl,
                    nextTextElement = closeTagsAndReturnEmptyClone()
            )
            "p", "div" -> startParagraph()
            "blockquote" -> TODO("blockquote - indented paragraph with a possible 'cite' source URL in attributes")
            "h1", "h2", "h3", "h4", "h5", "h6" -> TODO("header")
            "ul" -> TODO("unorderd list")
            "ol" -> TODO("ordered list")
            "pre" -> startFormattedParagraph()
            "iframe" -> TODO("iframe")
            "table" -> TODO("table")
            "style", "script" -> BlackholeElement(nextTextElement = closeTagsAndReturnEmptyClone())
            "li" -> TODO("probably ignore list items here then?")
            "tr", "td", "th" -> TODO("probably ignore table items here then?")
            else -> {
                // keep going
                null
            }
        }
    }

    override fun endTag(tag: String): DisplayElement? {
        when (tag) {
            "br" -> handleBr()
            "strong", "b" -> end<Bold>()
            "em", "cite", "dfn", "i" -> end<Italic>()
            "big" -> end<Big>()
            "small" -> end<Small>()
            "font" -> end<Font>()
            "tt" -> end<Monospace>()
            "a" -> end<Href>()
            "u" -> end<Underline>()
            "sup" -> end<Super>()
            "sub" -> end<Sub>()
            "code" -> end<Code>()
        }

        return when (tag) {
            "p", "div" -> endParagraph()
            else -> null
        }
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        for (index in start until (start + length)) {
            val char = chars[index]
            val prev = stringBuilder.lastOrNull()

            when {
                char.isWhitespace() && prev?.isWhitespace() == false -> {
                    stringBuilder.append(' ')
                }
                !char.isWhitespace() -> {
                    stringBuilder.append(char)
                }
            }
        }
    }

    private fun handleBr() {
        stringBuilder.lastOrNull()?.let { last ->
            if (last != '\n') {
                stringBuilder.append('\n')
            }
        }
    }


    private fun start(placeholder: Placeholder) {
        placeholders.add(placeholder)
    }

    private fun <T : Placeholder> end(klass: Class<T>) {
        placeholders.filterIsInstance(klass)
                .lastOrNull()
                ?.let { placeholder ->
                    placeholders.remove(placeholder)

                    if (placeholder.startIndex < nextIndex) {
                        spans.add(placeholder.copyWithEndIndex(nextIndex))
                    }
                }
    }

    private inline fun <reified T : Placeholder> end() = end(T::class.java)

    override fun equals(other: Any?): Boolean {
        return this === other
    }

    override fun hashCode(): Int {
        return System.identityHashCode(this)
    }

    override fun toString(): String {
        return "PARAGRAPH('${stringBuilder.toString().take(15)}')"
    }
}

class FormattedTextElement(
        override val nextTextElement: ParagraphTextElement,
        initialPlaceholders: List<Placeholder>
) : ParagraphTextElement(initialPlaceholders, nextTextElement) {

    override fun startParagraph(): ParagraphTextElement? {
        val initialPlaceholders = mutableListOf<Placeholder>().also { list ->
            list.addAll(placeholders.map { it.copyWithZeroStartIndex() })
        }

        this.close()

        return ParagraphTextElement(
                initialPlaceholders = initialPlaceholders,
                nextTextElement = FormattedTextElement(
                        initialPlaceholders = initialPlaceholders,
                        nextTextElement = nextTextElement
                )
        )
    }

    override fun startFormattedParagraph(): FormattedTextElement? {
        val initialPlaceholders = mutableListOf<Placeholder>().also { list ->
            list.addAll(placeholders.map { it.copyWithZeroStartIndex() })
        }

        this.close()

        return FormattedTextElement(
                initialPlaceholders = initialPlaceholders,
                nextTextElement = FormattedTextElement(
                        initialPlaceholders = initialPlaceholders,
                        nextTextElement = nextTextElement
                )
        )
    }

    override fun endTag(tag: String): DisplayElement? {
        return when (tag) {
            "pre" -> {
                close()
                nextTextElement
            }
            else -> super.endTag(tag)
        }
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        for (index in start until (start + length)) {
            val char = chars[index]
            stringBuilder.append(char)
        }
    }

    override fun equals(other: Any?): Boolean {
        return this === other
    }

    override fun hashCode(): Int {
        return System.identityHashCode(this)
    }

    override fun toString(): String {
        return "FORMATTED('${stringBuilder.toString().take(15)}')"
    }
}

sealed class Placeholder {
    abstract val startIndex: Int

    // Non-inclusive
    abstract val endIndex: Int
    abstract fun copyWithZeroStartIndex(): Placeholder
    abstract fun copyWithEndIndex(endIndex: Int): Placeholder

    val isClosed: Boolean
        get() = endIndex > -1

    val isOpen: Boolean
        get() = !isClosed
}

data class Bold(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Italic(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Underline(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Big(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Small(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Monospace(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Super(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Sub(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Code(
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Font(
        val color: String?,
        val face: String?,
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    constructor(attributes: Attributes, startIndex: Int) : this(
            color = attributes.getValue("", "color"),
            face = attributes.getValue("", "face"),
            startIndex = startIndex
    )

    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}

data class Href(
        val relativeUrl: String?,
        override val startIndex: Int,
        override val endIndex: Int = -1
) : Placeholder() {
    constructor(
            attributes: Attributes,
            startIndex: Int
    ) : this(
            relativeUrl = attributes.getValue("", "href"),
            startIndex = startIndex
    )

    override fun copyWithZeroStartIndex() = copy(startIndex = 0, endIndex = -1)
    override fun copyWithEndIndex(endIndex: Int) = copy(endIndex = endIndex)
}