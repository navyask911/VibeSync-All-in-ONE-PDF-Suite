package com.vibesync.toolkit.pdf.data.pdf.filters

import com.vibesync.toolkit.pdf.R

/** Stable identifiers for the six on-device scan filters. */
enum class FilterId(val labelRes: Int) {
    MAGIC_COLOR(R.string.scan_filter_magic_color),
    BW(R.string.scan_filter_bw),
    LIGHTEN(R.string.scan_filter_lighten),
    NO_SHADOW(R.string.scan_filter_no_shadow),
    SCREEN(R.string.scan_filter_screen),
    CLEAN_MARKS(R.string.scan_filter_clean_marks)
}
