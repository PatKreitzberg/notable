package com.ethran.notable.utils

import com.ethran.notable.modals.PaperFormat

// Constants for pagination
object PaginationConstants {
    // Space between pages (in pixels)
    const val PAGE_GAP = 30

    // Calculate page height based on width, paper format, and ratio
    fun calculatePageHeight(width: Int, paperFormat: PaperFormat = PaperFormat.A4): Int {
        return (width * (paperFormat.heightInches / paperFormat.widthInches)).toInt()
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