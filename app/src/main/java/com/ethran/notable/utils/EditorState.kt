package com.ethran.notable.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.classes.PageView
import com.ethran.notable.datastore.EditorSettingCacheManager
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke

enum class Mode {
    Draw, Erase, Select, Line
}

class EditorState(val bookId: String? = null, val pageId: String, val pageView: PageView) {

    private val persistedEditorSettings = EditorSettingCacheManager.getEditorSettings()

    var mode by mutableStateOf(persistedEditorSettings?.mode ?: Mode.Draw) // should save
    var pen by mutableStateOf(persistedEditorSettings?.pen ?: Pen.BALLPEN) // should save
    var eraser by mutableStateOf(persistedEditorSettings?.eraser ?: Eraser.PEN) // should save
    var isDrawing by mutableStateOf(true)
    var isToolbarOpen by mutableStateOf(
        persistedEditorSettings?.isToolbarOpen ?: false
    ) // should save
    var penSettings by mutableStateOf(
        persistedEditorSettings?.penSettings ?: mapOf(
            Pen.BALLPEN.penName to PenSetting(5f, Color.BLACK),
            Pen.REDBALLPEN.penName to PenSetting(5f, Color.RED),
            Pen.BLUEBALLPEN.penName to PenSetting(5f, Color.BLUE),
            Pen.GREENBALLPEN.penName to PenSetting(5f, Color.GREEN),
            Pen.PENCIL.penName to PenSetting(5f, Color.BLACK),
            Pen.BRUSH.penName to PenSetting(5f, Color.BLACK),
            Pen.MARKER.penName to PenSetting(40f, Color.LTGRAY),
            Pen.FOUNTAIN.penName to PenSetting(5f, Color.BLACK)
        )
    )

    // Simple zoom state variables
    /**
     * Current zoom level as a scale factor.
     * 1.0f = 100% (normal view)
     * 0.5f = 50% (zoomed out)
     * 2.0f = 200% (zoomed in)
     */
    var zoomScale by mutableStateOf(1.0f)

    /**
     * Horizontal offset for panning when zoomed.
     * This controls how much the view is shifted horizontally.
     */
    var zoomOffsetX by mutableStateOf(0f)

    /**
     * Vertical offset for panning when zoomed.
     * This controls how much the view is shifted vertically.
     */
    var zoomOffsetY by mutableStateOf(0f)

    /**
     * Minimum zoom scale (50%)
     */
    val minZoom = 0.5f

    /**
     * Maximum zoom scale (200%)
     */
    val maxZoom = 2.0f

    /**
     * Resets zoom to default state (100% with no offset)
     */
    fun resetZoom() {
        zoomScale = 1.0f
        zoomOffsetX = 0f
        zoomOffsetY = 0f
    }

    /**
     * Ensures zoom and offsets stay within allowed ranges
     */
    fun normalizeZoom() {
        // Keep zoom within min/max bounds
        zoomScale = zoomScale.coerceIn(minZoom, maxZoom)

        // When at 100% zoom, no offset is needed
        if (zoomScale == 1.0f) {
            zoomOffsetX = 0f
            zoomOffsetY = 0f
        }
    }

    val selectionState = SelectionState()


}

// if state is Move then applySelectionDisplace() will delete original strokes(images in future)
enum class PlacementMode {
    Move,
    Paste
}

class SelectionState {
    var firstPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var secondPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var selectedStrokes by mutableStateOf<List<Stroke>?>(null)
    var selectedImages by mutableStateOf<List<Image>?>(null)
    var selectedBitmap by mutableStateOf<Bitmap?>(null)
    var selectionStartOffset by mutableStateOf<IntOffset?>(null)
    var selectionDisplaceOffset by mutableStateOf<IntOffset?>(null)
    var selectionRect by mutableStateOf<Rect?>(null)
    var placementMode by mutableStateOf<PlacementMode?>(null)

    fun reset() {
        selectedStrokes = null
        selectedImages = null
        secondPageCut = null
        firstPageCut = null
        selectedBitmap = null
        selectionStartOffset = null
        selectionRect = null
        selectionDisplaceOffset = null
        placementMode = null
    }
}