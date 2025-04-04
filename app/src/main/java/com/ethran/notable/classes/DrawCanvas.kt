package com.ethran.notable.classes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.TAG
import com.ethran.notable.db.Image
import com.ethran.notable.db.ImageRepository
import com.ethran.notable.db.StrokeRepository
import com.ethran.notable.db.handleSelect
import com.ethran.notable.db.selectImage
import com.ethran.notable.db.selectImagesAndStrokes
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.Eraser
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Operation
import com.ethran.notable.utils.PaginationConstants
import com.ethran.notable.utils.Pen
import com.ethran.notable.utils.PlacementMode
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.convertDpToPixel
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.handleDraw
import com.ethran.notable.utils.handleErase
import com.ethran.notable.utils.handleLine
import com.ethran.notable.utils.penToStroke
import com.ethran.notable.utils.pointsToPath
import com.ethran.notable.utils.selectPaint
import com.ethran.notable.utils.uriToBitmap
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread


val pressure = EpdController.getMaxTouchPressure()

// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val state: EditorState,
    val page: PageView,
    val history: History
) : SurfaceView(context) {
    private val strokeHistoryBatch = mutableListOf<String>()
//    private val commitHistorySignal = MutableSharedFlow<Unit>()

    // List of rects that define the drawable areas (for pagination)
    private var drawableRects = mutableListOf<Rect>()

    companion object {
        var forceUpdate = MutableSharedFlow<Rect?>()
        var refreshUi = MutableSharedFlow<Unit>()
        var isDrawing = MutableSharedFlow<Boolean>()
        var restartAfterConfChange = MutableSharedFlow<Unit>()

        // before undo we need to commit changes
        val commitHistorySignal = MutableSharedFlow<Unit>()
        val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

        // used for checking if commit was completed
        var commitCompletion = CompletableDeferred<Unit>()

        // It might be bad idea, but plan is to insert graphic in this, and then take it from it
        // There is probably better way
        var addImageByUri = MutableStateFlow<Uri?>(null)
        var rectangleToSelect = MutableStateFlow<Rect?>(null)
        var drawingInProgress = Mutex()
    }

    fun getActualState(): EditorState {
        return this.state
    }

    private val inputCallback: RawInputCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        /**
         * Handle drawing input from the stylus, adjusting for zoom if necessary.
         * When zoomed, touch coordinates need to be transformed to match the document coordinates.
         */
        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "onRawDrawingTouchPointListReceived started")

            // When zoomed, we need to transform the touch points
            val adjustedPoints = if (state.zoomScale != 1.0f) {
                // Create a new touch point list with transformed coordinates
                TouchPointList().apply {
                    val centerX = this@DrawCanvas.width / 2f
                    val centerY = this@DrawCanvas.height / 2f

                    for (point in plist.points) {
                        // Adjust for zoom by reversing the zoom transformation
                        // First translate to make the center the origin
                        val relativeX = point.x - centerX
                        val relativeY = point.y - centerY

                        // Scale by the inverse of the zoom factor
                        val scaledX = relativeX / state.zoomScale
                        val scaledY = relativeY / state.zoomScale

                        // Translate back to original coordinate system
                        val adjustedX = scaledX + centerX
                        val adjustedY = scaledY + centerY

                        // Create a new touch point with the adjusted coordinates
                        val adjustedPoint = TouchPoint(
                            adjustedX,
                            adjustedY,
                            point.pressure,
                            point.size,
                            point.tiltX,
                            point.tiltY,
                            point.timestamp
                        )

                        this.add(adjustedPoint)
                    }
                }
            } else {
                // When not zoomed, use the original points
                plist
            }

            // Check if any point is in a non-drawable area (gap between pages)
            if (page.usePagination) {
                val pageHeight = page.pageHeight
                val pageGap = PaginationConstants.PAGE_GAP

                // Check each touch point to see if it falls in a gap
                for (point in adjustedPoints.points) {
                    val y = point.y + page.scroll
                    val page_num = y / (pageHeight + pageGap)
                    val page_relative_y = y - page_num * (pageHeight + pageGap)

                    // If the point is in a gap between pages, ignore the entire stroke
                    if (page_relative_y > pageHeight) {
                        // Point is in a gap - ignore this entire stroke
                        return
                    }
                }
            }

            // Now use the adjusted touch points for drawing operations
            if (getActualState().mode == Mode.Draw) {
                val newThread = System.currentTimeMillis()
                Log.d(
                    TAG,
                    "Got to new thread ${Thread.currentThread().name}, in ${newThread - startTime}}"
                )
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        Log.d(TAG, "lock obtained in ${lock - startTime} ms")

                        // Use the adjusted points for drawing
                        handleDraw(
                            this@DrawCanvas.page,
                            strokeHistoryBatch,
                            getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                            getActualState().penSettings[getActualState().pen.penName]!!.color,
                            getActualState().pen,
                            adjustedPoints.points
                        )
                        val drawEndTime = System.currentTimeMillis()
                        Log.d(TAG, "Drawing operation took ${drawEndTime - startTime} ms")
                    }
                    coroutineScope.launch {
                        commitHistorySignal.emit(Unit)
                    }

                    val endTime = System.currentTimeMillis()
                    Log.d(
                        TAG,
                        "onRawDrawingTouchPointListReceived completed in ${endTime - startTime} ms"
                    )
                }
            } else thread {
                if (getActualState().mode == Mode.Erase) {
                    // For eraser, also adjust touch points for zoom
                    val adjustedErasePoints = if (state.zoomScale != 1.0f) {
                        val centerX = this@DrawCanvas.width / 2f
                        val centerY = this@DrawCanvas.height / 2f

                        adjustedPoints.points.map { point ->
                            // Create SimplePointF with adjusted coordinates
                            SimplePointF(point.x, point.y + page.scroll)
                        }
                    } else {
                        plist.points.map { SimplePointF(it.x, it.y + page.scroll) }
                    }

                    handleErase(
                        this@DrawCanvas.page,
                        history,
                        adjustedErasePoints,
                        eraser = getActualState().eraser
                    )
                    drawCanvasToView()
                    refreshUi()
                }

                if (getActualState().mode == Mode.Select) {
                    // For select mode, also adjust touch points for zoom
                    val adjustedSelectPoints = if (state.zoomScale != 1.0f) {
                        adjustedPoints.points.map { point ->
                            SimplePointF(point.x, point.y + page.scroll)
                        }
                    } else {
                        plist.points.map { SimplePointF(it.x, it.y + page.scroll) }
                    }

                    handleSelect(
                        coroutineScope,
                        this@DrawCanvas.page,
                        getActualState(),
                        adjustedSelectPoints
                    )
                    drawCanvasToView()
                    refreshUi()
                }

                if (getActualState().mode == Mode.Line) {
                    // Check if line would cross a gap between pages
                    val shouldDrawLine = if (page.usePagination) {
                        val firstPoint = adjustedPoints.points.first()
                        val lastPoint = adjustedPoints.points.last()

                        // Convert to page-relative coordinates
                        val firstY = firstPoint.y + page.scroll
                        val lastY = lastPoint.y + page.scroll

                        // Calculate which pages these points are on
                        val firstPage = (firstY / (page.pageHeight + PaginationConstants.PAGE_GAP)).toInt()
                        val lastPage = (lastY / (page.pageHeight + PaginationConstants.PAGE_GAP)).toInt()

                        // Only allow lines within the same page
                        firstPage == lastPage
                    } else {
                        // Always allow lines when pagination is off
                        true
                    }

                    if (shouldDrawLine) {
                        // draw line
                        handleLine(
                            page = this@DrawCanvas.page,
                            historyBucket = strokeHistoryBatch,
                            strokeSize = getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                            color = getActualState().penSettings[getActualState().pen.penName]!!.color,
                            pen = getActualState().pen,
                            touchPoints = adjustedPoints.points  // Use the adjusted points
                        )
                        //make it visible
                        drawCanvasToView()
                        refreshUi()
                    }
                }
            }
        }



        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
            if (plist == null) return

            // Apply the same coordinate transformation for eraser that we do for pen
            val adjustedPoints = if (state.zoomScale != 1.0f) {
                // Create a list of adjusted points
                val centerX = page.viewWidth / 2f
                val centerY = page.viewHeight / 2f

                plist.points.map { point ->
                    // Adjust for zoom by reversing the zoom transformation
                    // First translate to make the center the origin
                    val relativeX = point.x - centerX
                    val relativeY = point.y - centerY

                    // Scale by the inverse of the zoom factor
                    val scaledX = relativeX / state.zoomScale
                    val scaledY = relativeY / state.zoomScale

                    // Translate back to original coordinate system
                    val adjustedX = scaledX + centerX
                    val adjustedY = scaledY + centerY

                    // Create a SimplePointF with the adjusted coordinates
                    SimplePointF(adjustedX, adjustedY + page.scroll)
                }
            } else {
                // If not zoomed, use original points
                plist.points.map { SimplePointF(it.x, it.y + page.scroll) }
            }

            handleErase(
                this@DrawCanvas.page,
                history,
                adjustedPoints,
                eraser = getActualState().eraser
            )
            drawCanvasToView()
            refreshUi()
        }

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
        }

        override fun onPenActive(point: TouchPoint?) {
            super.onPenActive(point)
        }
    }

    private val touchHelper by lazy {
        referencedSurfaceView = this.hashCode().toString()
        TouchHelper.create(this, inputCallback)
    }

    fun init() {
        Log.i(TAG, "Initializing Canvas")

        val surfaceView = this

        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surface created $holder")
                // set up the drawing surface
                updateActiveSurface()
                // This is supposed to let the ui update while the old surface is being unmounted
                coroutineScope.launch {
                    forceUpdate.emit(null)
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                Log.i(TAG, "surface changed $holder")
                drawCanvasToView()
                updatePenAndStroke()
                refreshUi()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(
                    TAG,
                    "surface destroyed ${
                        this@DrawCanvas.hashCode()
                    } - ref $referencedSurfaceView"
                )
                holder.removeCallback(this)
                if (referencedSurfaceView == this@DrawCanvas.hashCode().toString()) {
                    touchHelper.closeRawDrawing()
                }
            }
        }
        this.holder.addCallback(surfaceCallback)
    }

    fun registerObservers() {
        // observe forceUpdate
        coroutineScope.launch {
            forceUpdate.collect { zoneAffected ->
                Log.v(TAG + "Observer", "Force update zone $zoneAffected")

                if (zoneAffected != null) page.drawArea(
                    area = Rect(
                        zoneAffected.left,
                        zoneAffected.top - page.scroll,
                        zoneAffected.right,
                        zoneAffected.bottom - page.scroll
                    ),
                )
                refreshUiSuspend()
            }
        }

        // observe refreshUi
        coroutineScope.launch {
            refreshUi.collect {
                Log.v(TAG + "Observer", "Refreshing UI!")
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            isDrawing.collect {
                Log.v(TAG + "Observer", "drawing state changed!")
                state.isDrawing = it
            }
        }


        coroutineScope.launch {
            addImageByUri.drop(1).collect { imageUri ->
                Log.v(TAG + "Observer", "Received image!")

                if (imageUri != null) {
                    handleImage(imageUri)
                } //else
//                    Log.i(TAG, "Image uri is empty")
            }
        }
        coroutineScope.launch {
            rectangleToSelect.drop(1).collect {
                selectRectangle(it)
            }
        }


        // observe restartcount
        coroutineScope.launch {
            restartAfterConfChange.collect {
                Log.v(TAG + "Observer", "Configuration changed!")
                init()
                drawCanvasToView()
            }
        }

        // observe pen and stroke size
        coroutineScope.launch {
            snapshotFlow { state.pen }.drop(1).collect {
                Log.v(TAG + "Observer", "pen change: ${state.pen}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.penSettings.toMap() }.drop(1).collect {
                Log.v(TAG + "Observer", "pen settings change: ${state.penSettings}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.eraser }.drop(1).collect {
                Log.v(TAG + "Observer", "eraser change: ${state.eraser}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }

        // observe is drawing
        coroutineScope.launch {
            snapshotFlow { state.isDrawing }.drop(1).collect {
                Log.v(TAG + "Observer", "isDrawing change: ${state.isDrawing}")
                updateIsDrawing()
            }
        }

        // observe toolbar open
        coroutineScope.launch {
            snapshotFlow { state.isToolbarOpen }.drop(1).collect {
                Log.v(TAG + "Observer", "istoolbaropen change: ${state.isToolbarOpen}")
                updateActiveSurface()
            }
        }

        // observe mode
        coroutineScope.launch {
            snapshotFlow { getActualState().mode }.drop(1).collect {
                Log.v(TAG + "Observer", "mode change: ${getActualState().mode}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }

        // observe pagination
        coroutineScope.launch {
            snapshotFlow { page.usePagination }.collect {
                Log.v(TAG + "Observer", "pagination change: ${page.usePagination}")
                updateActiveSurface() // Update drawable areas when pagination changes
                refreshUiSuspend()
            }
        }

        // Observe scroll changes to update the drawable areas
        coroutineScope.launch {
            snapshotFlow { page.scroll }.collect {
                Log.v(TAG + "Observer", "scroll change: ${page.scroll}")
                if (page.usePagination) {
                    updateActiveSurface() // Update drawable areas when scroll changes
                }
            }
        }

        coroutineScope.launch {
            //After 500ms add to history strokes
            commitHistorySignal.debounce(500).collect {
                Log.v(TAG + "Observer", "Commiting to history")
                commitToHistory()
            }
        }
        coroutineScope.launch {
            commitHistorySignalImmediately.collect() {
                commitToHistory()
                commitCompletion.complete(Unit)
            }
        }
    }

    /**
     * Updates the drawableRects to respect zoom level.
     * When zoomed out, this restricts drawing area to ensure users can't draw outside normal margins.
     *
     * @param exclusionHeight Height of area excluded from drawing (e.g., toolbar)
     */
    fun updateDrawableAreas(exclusionHeight: Int) {
        drawableRects.clear()

        if (page.usePagination) {
            val pageHeight = page.pageHeight
            val pageGap = PaginationConstants.PAGE_GAP
            val scroll = page.scroll
            val visibleHeight = page.viewHeight

            // Calculate which pages are in view considering the current scroll position
            val firstVisiblePage = (scroll / (pageHeight + pageGap)).toInt()
            val lastVisiblePage = ((scroll + visibleHeight) / (pageHeight + pageGap)).toInt() + 1

            // Create a limit rect for each visible page
            for (pageNum in firstVisiblePage..lastVisiblePage) {
                // Calculate page top and bottom in screen coordinates (accounting for scroll)
                val pageTopInDoc = pageNum * (pageHeight + pageGap)
                val pageBottomInDoc = pageTopInDoc + pageHeight

                // Convert to screen coordinates
                val pageTopOnScreen = pageTopInDoc - scroll
                val pageBottomOnScreen = pageBottomInDoc - scroll

                // Only include if the page is visible (not completely off-screen)
                if (pageBottomOnScreen > 0 && pageTopOnScreen < visibleHeight) {
                    // Adjust for toolbar and screen bounds
                    val adjustedTop = maxOf(pageTopOnScreen, exclusionHeight)
                    val adjustedBottom = minOf(pageBottomOnScreen, visibleHeight)

                    // Only add if there's actually drawable area (not just toolbar)
                    if (adjustedBottom > adjustedTop) {
                        drawableRects.add(Rect(0, adjustedTop, page.viewWidth, adjustedBottom))
                    }
                }
            }

            // If no drawable areas were created (edge case), create a default one
            if (drawableRects.isEmpty()) {
                drawableRects.add(Rect(0, exclusionHeight, page.viewWidth, page.viewHeight))
                Log.w(TAG, "No drawable areas found, using default")
            }
        } else {
            // Without pagination, just one drawable area for the whole surface
            drawableRects.add(Rect(0, exclusionHeight, page.viewWidth, page.viewHeight))
        }

        // When zoomed out (scale < 1.0), restrict the drawable area to prevent drawing outside normal margins
        if (state.zoomScale < 1.0f) {
            val newDrawableRects = mutableListOf<Rect>()

            for (rect in drawableRects) {
                // Calculate the center of the screen
                val centerX = page.viewWidth / 2
                val centerY = (page.viewHeight - exclusionHeight) / 2 + exclusionHeight

                // Calculate the visible area at the current zoom level
                val visibleWidth = (page.viewWidth * state.zoomScale).toInt()
                val visibleHeight = ((page.viewHeight - exclusionHeight) * state.zoomScale).toInt()

                // Calculate the adjusted rect, accounting for zoom
                val adjustedRect = Rect(
                    centerX - visibleWidth / 2,  // Left
                    maxOf(centerY - visibleHeight / 2, exclusionHeight),  // Top
                    centerX + visibleWidth / 2,  // Right
                    centerY + visibleHeight / 2   // Bottom
                )

                // Intersect with the original rect to get the final drawable area
                val finalRect = Rect(rect)
                finalRect.intersect(adjustedRect)

                // Only add the rect if it's valid
                if (finalRect.width() > 0 && finalRect.height() > 0) {
                    newDrawableRects.add(finalRect)
                }
            }

            // Replace with the adjusted rects
            if (newDrawableRects.isNotEmpty()) {
                drawableRects.clear()
                drawableRects.addAll(newDrawableRects)
            }
        }
    }


    private suspend fun selectRectangle(rectToSelect: Rect?) {
        if (rectToSelect != null) {
            Log.d(TAG + "Observer", "position of image $rectToSelect")
            rectToSelect.top += page.scroll
            rectToSelect.bottom += page.scroll
            // Query the database to find an image that coincides with the point
            val imagesToSelect = withContext(Dispatchers.IO) {
                ImageRepository(context).getImagesInRectangle(rectToSelect, page.id)
            }
            val strokesToSelect = withContext(Dispatchers.IO) {
                StrokeRepository(context).getStrokesInRectangle(rectToSelect, page.id)
            }
            rectangleToSelect.value = null
            if (imagesToSelect.isNotEmpty() || strokesToSelect.isNotEmpty()) {
                selectImagesAndStrokes(coroutineScope, page, state, imagesToSelect, strokesToSelect)
            } else {
                SnackState.globalSnackFlow.emit(
                    SnackConf(
                        text = "There isn't anything.",
                        duration = 3000,
                    )
                )
            }
        }
    }

    private fun commitToHistory() {
        if (strokeHistoryBatch.size > 0) history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteStroke(strokeHistoryBatch.map { it })
            )
        )
        strokeHistoryBatch.clear()
        //testing if it will help with undo hiding strokes.
        drawCanvasToView()
    }

    private fun refreshUi() {
        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!state.isDrawing) {
            Log.w(TAG, "Not in drawing mode, skipping refreshUI")
            return
        }
        if (drawingInProgress.isLocked)
            Log.w(TAG, "Drawing is still in progress there might be a bug.")

        drawCanvasToView()

        // reset screen freeze
        // if in scribble mode, the screen want refresh
        // So to update interface we need to disable, and re-enable
        touchHelper.setRawDrawingEnabled(false)
        touchHelper.setRawDrawingEnabled(true)
        // screen won't freeze until you actually stoke
    }

    suspend fun refreshUiSuspend() {
        // Do not use, if refresh need to be preformed without delay.
        // This function waits for strokes to be fully rendered.
        if (!state.isDrawing) {
            waitForDrawing()
            drawCanvasToView()
            Log.w(TAG, "Not in drawing mode -- refreshUi ")
            return
        }
        if (Looper.getMainLooper().isCurrentThread) {
            Log.i(
                TAG,
                "refreshUiSuspend() is called from the main thread, it might not be a good idea."
            )
        }
        waitForDrawing()
        drawCanvasToView()
        touchHelper.setRawDrawingEnabled(false)
        if (drawingInProgress.isLocked)
            Log.w(TAG, "Lock was acquired during refreshing UI. It might cause errors.")
        touchHelper.setRawDrawingEnabled(true)
    }

    private suspend fun waitForDrawing() {
        withTimeoutOrNull(3000) {
            // Just to make sure wait 1ms before checking lock.
            delay(1)
            // Wait until drawingInProgress is unlocked before proceeding
            while (drawingInProgress.isLocked) {
                delay(5)
            }
        } ?: Log.e(TAG, "Timeout while waiting for drawing lock. Potential deadlock.")
    }

    private fun handleImage(imageUri: Uri) {
        // Convert the image to a software-backed bitmap
        val imageBitmap = uriToBitmap(context, imageUri)?.asImageBitmap()
        if (imageBitmap == null)
            coroutineScope.launch {
                SnackState.globalSnackFlow.emit(
                    SnackConf(
                        text = "There was an error during image processing.",
                        duration = 3000,
                    )
                )
            }
        val softwareBitmap =
            imageBitmap?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, true)
        if (softwareBitmap != null) {
            addImageByUri.value = null

            // Get the image dimensions
            val imageWidth = softwareBitmap.width
            val imageHeight = softwareBitmap.height

            // Calculate the center position for the image relative to the page dimensions
            val centerX = (page.viewWidth - imageWidth) / 2
            var centerY = (page.viewHeight - imageHeight) / 2 + page.scroll

            // If pagination is enabled, make sure the image is placed on a valid page area
            if (page.usePagination) {
                val pageHeight = page.pageHeight
                val pageGap = PaginationConstants.PAGE_GAP

                // Calculate which page this Y position falls on
                val pageNum = centerY / (pageHeight + pageGap)

                // Calculate Y position relative to the start of this page
                val pageRelativeY = centerY - pageNum * (pageHeight + pageGap)

                // If the image would overlap a gap, adjust its position to be fully on a page
                if (pageRelativeY + imageHeight > pageHeight) {
                    // Place at the next page's top if image would cross a gap
                    centerY = (pageNum + 1) * (pageHeight + pageGap) + (pageHeight - imageHeight) / 2
                }
            }

            val imageToSave = Image(
                x = centerX,
                y = centerY,
                height = imageHeight,
                width = imageWidth,
                uri = imageUri.toString(),
                pageId = page.id
            )
            drawImage(
                context, page.windowedCanvas, imageToSave, IntOffset(0, -page.scroll)
            )
            selectImage(coroutineScope, page, state, imageToSave)
            // image will be added to database when released, the same as with paste element.
            state.selectionState.placementMode = PlacementMode.Paste
        } else {
            // Handle cases where the bitmap could not be created
            Log.e("ImageProcessing", "Failed to create software bitmap from URI.")
        }
    }


    /**
     * Draws the canvas to view, applying zoom transformations if needed.
     * This displays the current canvas content on screen with proper zoom level.
     */
    fun drawCanvasToView() {
        val canvas = this.holder.lockCanvas() ?: return

        // Clear the canvas
        canvas.drawColor(Color.WHITE)

        // Apply zoom transformation if not at 100%
        if (state.zoomScale != 1.0f) {
            // Save the canvas state before transformations
            canvas.save()

            // Find the center point of the view
            val centerX = page.viewWidth / 2f
            val centerY = page.viewHeight / 2f

            // Apply zoom transformation centered on the view
            canvas.translate(centerX, centerY)
            canvas.scale(state.zoomScale, state.zoomScale)
            canvas.translate(-centerX, -centerY)
        }

        // Draw the main content bitmap
        canvas.drawBitmap(page.windowedBitmap, 0f, 0f, Paint())

        // Draw selection if in select mode
        if (getActualState().mode == Mode.Select) {
            // render selection
            if (getActualState().selectionState.firstPageCut != null) {
                Log.i(TAG, "render cut")
                val path = pointsToPath(getActualState().selectionState.firstPageCut!!.map {
                    SimplePointF(
                        it.x, it.y - page.scroll
                    )
                })
                canvas.drawPath(path, selectPaint)
            }
        }

        // Restore canvas state if zoom was applied
        if (state.zoomScale != 1.0f) {
            canvas.restore()

            // Show zoom indicator in top-right corner
            val zoomText = "${(state.zoomScale * 100).toInt()}%"
            val textPaint = Paint().apply {
                color = Color.BLACK
                textSize = 28f
                textAlign = Paint.Align.RIGHT
            }

            // Draw zoom indicator background
            val textX = canvas.width - 20f
            val textY = 40f
            val textWidth = textPaint.measureText(zoomText)
            val textBgPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            canvas.drawRect(
                textX - textWidth - 10,
                textY - 30,
                textX + 10,
                textY + 10,
                textBgPaint
            )

            // Draw zoom text
            canvas.drawText(zoomText, textX, textY, textPaint)
        }

        // Finish rendering
        this.holder.unlockCanvasAndPost(canvas)
    }


    private suspend fun updateIsDrawing() {
        Log.i(TAG, "Update is drawing: ${state.isDrawing}")
        if (state.isDrawing) {
            touchHelper.setRawDrawingEnabled(true)
        } else {
            // Check if drawing is completed
            waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvasToView()
            touchHelper.setRawDrawingEnabled(false)
        }
    }


    fun updatePenAndStroke() {
        Log.i(TAG, "Update pen and stroke")
        when (state.mode) {
            Mode.Draw -> touchHelper.setStrokeStyle(penToStroke(state.pen))
                ?.setStrokeWidth(state.penSettings[state.pen.penName]!!.strokeSize)
                ?.setStrokeColor(state.penSettings[state.pen.penName]!!.color)

            Mode.Erase -> {
                when (state.eraser) {
                    Eraser.PEN -> touchHelper.setStrokeStyle(penToStroke(Pen.MARKER))
                        ?.setStrokeWidth(30f)
                        ?.setStrokeColor(Color.GRAY)

                    Eraser.SELECT -> touchHelper.setStrokeStyle(penToStroke(Pen.BALLPEN))
                        ?.setStrokeWidth(3f)
                        ?.setStrokeColor(Color.GRAY)
                }
            }

            Mode.Select -> touchHelper.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)

            Mode.Line -> {
            }
        }
    }

    fun updateActiveSurface() {
        Log.i(TAG, "Update editable surface")

        val exclusionHeight =
            if (state.isToolbarOpen) convertDpToPixel(40.dp, context).toInt() else 0

        touchHelper.setRawDrawingEnabled(false)
        touchHelper.closeRawDrawing()

        // Update drawable areas for pagination
        updateDrawableAreas(exclusionHeight)

        // Set exclude rect for toolbar
        val toolbarExcludeRect = Rect(0, 0, page.viewWidth, exclusionHeight)

        if (page.usePagination) {
            // For pagination, set each page as a separate limit rect
            touchHelper.setLimitRect(drawableRects)
                .setExcludeRect(listOf(toolbarExcludeRect))
                .openRawDrawing()
        } else {
            // For non-pagination, use the whole area
            touchHelper.setLimitRect(
                mutableListOf(
                    Rect(
                        0, 0, page.viewWidth, page.viewHeight
                    )
                )
            ).setExcludeRect(listOf(toolbarExcludeRect))
                .openRawDrawing()
        }

        touchHelper.setRawDrawingEnabled(true)
        updatePenAndStroke()

        refreshUi()
    }


}