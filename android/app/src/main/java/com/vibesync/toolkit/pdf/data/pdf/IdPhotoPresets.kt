package com.vibesync.toolkit.pdf.data.pdf

import com.vibesync.toolkit.pdf.R

enum class IdPhotoPresets(val widthPx: Int, val heightPx: Int, val labelRes: Int) {
    STANDARD_35x45(413, 531, R.string.id_photo_preset_35x45),
    STANDARD_2x2in(600, 600, R.string.id_photo_preset_2x2),
    STANDARD_35x35(413, 413, R.string.id_photo_preset_35x35),
    PASSPORT_US(602, 602, R.string.id_photo_preset_us_passport);

    val dpi: Int get() = 300
}
