package com.ethran.notable.utils

// Constants for pagination
object PaginationConstants {
    // Letter size ratio (11:8.5)
    const val LETTER_HEIGHT_TO_WIDTH_RATIO = 11.0f / 8.5f

    // Space between pages (in pixels)
    const val PAGE_GAP = 30

    // Calculate page height based on width and ratio
    fun calculatePageHeight(width: Int): Int {
        return (width * LETTER_HEIGHT_TO_WIDTH_RATIO).toInt()
    }

    // Calculate which page number a Y coordinate falls into
    fun getPageNumberForPosition(y: Int, pageHeight: Int): Int {
        if (y < 0) return 0

        val pageWithGap = pageHeight + PAGE_GAP
        return y / pageWithGap
    }

    // Calculate the top Y coordinate for a given page number
    fun getPageTopPosition(pageNumber: Int, pageHeight: Int): Int {
        return pageNumber * (pageHeight + PAGE_GAP)
    }
}