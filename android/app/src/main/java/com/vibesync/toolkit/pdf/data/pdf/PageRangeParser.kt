package com.vibesync.toolkit.pdf.data.pdf

/**
 * Parses user-facing one-based page ranges (e.g. "1-10, 15, 20-25").
 */
internal object PageRangeParser {
    fun parse(input: String, pageCount: Int): List<IntRange> {
        require(pageCount > 0) { "The document has no pages." }
        val value = input.trim()
        if (value.isEmpty() || value.equals("all", ignoreCase = true)) {
            return listOf(1..pageCount)
        }

        return value.split(',').map { token ->
            val part = token.trim()
            require(part.isNotEmpty()) { "Empty page range." }
            val match = Regex("""^(\d+)(?:\s*-\s*(\d+))?$""").matchEntire(part)
                ?: throw IllegalArgumentException("Invalid page range: $part")
            val start = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid page number: $part")
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
            require(start >= 1 && end >= start && end <= pageCount) {
                "Page range $part must be between 1 and $pageCount."
            }
            start..end
        }
    }
}
