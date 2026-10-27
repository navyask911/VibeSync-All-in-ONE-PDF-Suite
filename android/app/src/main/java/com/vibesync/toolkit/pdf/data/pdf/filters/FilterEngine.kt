package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Facade that runs all CPU-heavy filter work on Dispatchers.Default. */
class FilterEngine(
    filters: List<ScanFilter> = listOf(
        MagicColorFilter(),
        BwFilter(),
        LightenFilter(),
        NoShadowFilter(),
        ScreenFilter(),
        CleanMarksFilter()
    )
) {
    private val filtersById: Map<FilterId, ScanFilter> = filters.associateBy { it.id }

    init {
        require(filtersById.size == filters.size) { "Each FilterId must have exactly one implementation." }
        require(filtersById.keys.containsAll(FilterId.values().toList())) {
            "The filter engine must register all six filter implementations."
        }
    }

    fun availableFilters(): List<FilterId> = FilterId.values().toList()

    suspend fun apply(
        id: FilterId,
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit = {}
    ): IntArray = withContext(Dispatchers.Default) {
        FilterMath.validate(pixels, width, height)
        currentCoroutineContext().ensureActive()
        FilterMath.report(0f, onProgress)
        val output = requireNotNull(filtersById[id]) { "No implementation registered for $id." }
            .apply(pixels, width, height) { progress ->
                FilterMath.report(progress, onProgress)
            }
        currentCoroutineContext().ensureActive()
        FilterMath.report(1f, onProgress)
        output
    }
}
