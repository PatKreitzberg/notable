package com.ethran.notable.classes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.toRect
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.db.Image
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.Page
import com.ethran.notable.db.Stroke
import com.ethran.notable.utils.imageBounds
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.DEFAULT_PPI
import com.ethran.notable.modals.PaperFormat
import com.ethran.notable.utils.PaginationConstants
import com.ethran.notable.utils.strokeBounds
import com.ethran.notable.utils.drawBg
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.drawStroke
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis
import kotlin.math.ceil

class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val id: String,
    val width: Int,
    var viewWidth: Int,
    var viewHeight: Int
) {

    var windowedBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
    var windowedCanvas = Canvas(windowedBitmap)
    var strokes = listOf<Stroke>()
    private var strokesById: HashMap<String, Stroke> = hashMapOf()
    var images = listOf<Image>()
    private var imagesById: HashMap<String, Image> = hashMapOf()
    var scroll by mutableIntStateOf(0) // is observed by ui
    private val saveTopic = MutableSharedFlow<Unit>()

    var height by mutableIntStateOf(viewHeight) // is observed by ui

    // Pagination related properties
    var usePagination: Boolean = false
    var pageHeight: Int = 0

    var pageFromDb = AppRepository(context).pageRepository.getById(id)
    private var notebookInfo = if (pageFromDb?.notebookId != null)
        AppRepository(context).bookRepository.getById(pageFromDb?.notebookId!!)
    else null

    private var dbStrokes = AppDatabase.getDatabase(context).strokeDao()
    private var dbImages = AppDatabase.getDatabase(context).ImageDao()


    init {
        coroutineScope.launch {
            saveTopic.debounce(1000).collect {
                launch { persistBitmap() }
                launch { persistBitmapThumbnail() }
            }
        }

        windowedCanvas.drawColor(Color.WHITE)

        // Get global app settings for device PPI
        val appSettings = AppRepository(context).kvProxy.get("APP_SETTINGS", AppSettings.serializer())
        val devicePpi = appSettings?.devicePpi ?: DEFAULT_PPI

        // Initialize pagination setting from notebook
        usePagination = notebookInfo?.usePagination ?: false

        // Get paper format from the notebook or use default
        val paperFormat = notebookInfo?.paperFormat ?: PaperFormat.A4

        pageHeight = if (usePagination) {
            PaginationConstants.calculatePageHeight(viewWidth, paperFormat)
        } else {
            0 // Not used when pagination is off
        }

        drawBg(windowedCanvas, pageFromDb?.nativeTemplate!!, scroll)

        val isCached = loadBitmap()
        initFromPersistLayer(isCached)

        coroutineScope.launch {
            delay(100) // Short delay to ensure view is measured
            if (usePagination) {
                pageHeight = PaginationConstants.calculatePageHeight(viewWidth)
                windowedCanvas.drawColor(Color.WHITE)
                drawArea(Rect(0, 0, windowedCanvas.width, windowedCanvas.height))
                persistBitmapDebounced()
            }
        }
    }


    private fun indexStrokes() {
        coroutineScope.launch {
            strokesById = hashMapOf(*strokes.map { s -> s.id to s }.toTypedArray())
        }
    }

    private fun indexImages() {
        coroutineScope.launch {
            imagesById = hashMapOf(*images.map { img -> img.id to img }.toTypedArray())
        }
    }

    private fun initFromPersistLayer(isCached: Boolean) {
        // pageInfos
        // TODO page might not exists yet
        val page = AppRepository(context).pageRepository.getById(id)
        scroll = page!!.scroll

        coroutineScope.launch {
            val timeToLoad = measureTimeMillis {
                val pageWithStrokes = AppRepository(context).pageRepository.getWithStrokeById(id)
                val pageWithImages = AppRepository(context).pageRepository.getWithImageById(id)
                strokes = pageWithStrokes.strokes
                images = pageWithImages.images
                indexStrokes()
                indexImages()
                computeHeight()

                if (!isCached) {
                    // we draw and cache
                    drawBg(windowedCanvas, page.nativeTemplate, scroll)
                    drawArea(Rect(0, 0, windowedCanvas.width, windowedCanvas.height))
                    persistBitmap()
                    persistBitmapThumbnail()
                }
            }
            Log.i(TAG, "initializing from persistent layer took ${timeToLoad}ms")
        }

        //TODO: Images loading
    }

    fun addStrokes(strokesToAdd: List<Stroke>) {
        strokes += strokesToAdd
        strokesToAdd.forEach {
            val bottomPlusPadding = it.bottom + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding.toInt()
        }

        saveStrokesToPersistLayer(strokesToAdd)
        indexStrokes()

        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        indexStrokes()
        computeHeight()

        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return strokeIds.map { s -> strokesById[s] }
    }

    private fun saveStrokesToPersistLayer(strokes: List<Stroke>) {
        dbStrokes.create(strokes)
    }

    private fun saveImagesToPersistLayer(image: List<Image>) {
        dbImages.create(image)
    }


    fun addImage(imageToAdd: Image) {
        images += listOf(imageToAdd)
        val bottomPlusPadding = imageToAdd.y + imageToAdd.height + 50
        if (bottomPlusPadding > height) height = bottomPlusPadding

        saveImagesToPersistLayer(listOf(imageToAdd))
        indexImages()

        persistBitmapDebounced()
    }

    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        imageToAdd.forEach {
            val bottomPlusPadding = it.y + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        saveImagesToPersistLayer(imageToAdd)
        indexImages()

        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        indexImages()
        computeHeight()

        persistBitmapDebounced()
    }

    fun getImage(imageId: String): Image? {
        return imagesById[imageId]
    }

    fun getImages(imageIds: List<String>): List<Image?> {
        return imageIds.map { i -> imagesById[i] }
    }


    private fun computeHeight() {
        if (strokes.isEmpty() && images.isEmpty()) {
            height = viewHeight
            return
        }

        val maxStrokeBottom = if (strokes.isNotEmpty()) strokes.maxOf { it.bottom } else 0f
        val maxImageBottom = if (images.isNotEmpty()) images.maxOf { it.y + it.height } else 0
        val maxContentBottom = max(maxStrokeBottom.toInt(), maxImageBottom) + 50

        if (usePagination) {
            // For pagination, ensure height is based on complete pages
            val pageWithGap = pageHeight + PaginationConstants.PAGE_GAP
            val totalPages = ceil(maxContentBottom.toFloat() / pageHeight).toInt()
            height = (totalPages * pageWithGap).coerceAtLeast(viewHeight)
        } else {
            // For non-pagination, use the normal height calculation
            height = maxContentBottom.coerceAtLeast(viewHeight)
        }
    }


    fun computeWidth(): Int {
        if (strokes.isEmpty()) {
            return viewWidth
        }
        val maxStrokeRight = strokes.maxOf { it.right }.plus(50)
        return max(maxStrokeRight.toInt(), viewWidth)
    }

    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) {
        AppRepository(context).strokeRepository.deleteAll(strokeIds)
    }

    private fun removeImagesFromPersistLayer(imageIds: List<String>) {
        AppRepository(context).imageRepository.deleteAll(imageIds)
    }

    private fun loadBitmap(): Boolean {
        val imgFile = File(context.filesDir, "pages/previews/full/$id")
        val imgBitmap: Bitmap?
        if (imgFile.exists()) {
            imgBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            if (imgBitmap != null) {
                windowedCanvas.drawBitmap(imgBitmap, 0f, 0f, Paint())
                Log.i(TAG, "Page rendered from cache")
                // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
                if (imgBitmap.height == windowedCanvas.height && imgBitmap.width == windowedCanvas.width) {
                    return true
                } else {
                    Log.i(TAG, "Image preview does not fit canvas area - redrawing")
                }
            } else {
                Log.i(TAG, "Cannot read cache image")
            }
        } else {
            Log.i(TAG, "Cannot find cache image")
        }
        return false
    }

    private fun persistBitmap() {
        val file = File(context.filesDir, "pages/previews/full/$id")
        Files.createDirectories(Path(file.absolutePath).parent)
        val os = BufferedOutputStream(FileOutputStream(file))
        windowedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        os.close()
    }

    private fun persistBitmapThumbnail() {
        val file = File(context.filesDir, "pages/previews/thumbs/$id")
        Files.createDirectories(Path(file.absolutePath).parent)
        val os = BufferedOutputStream(FileOutputStream(file))
        val ratio = windowedBitmap.height.toFloat() / windowedBitmap.width.toFloat()
        Bitmap.createScaledBitmap(windowedBitmap, 500, (500 * ratio).toInt(), false)
            .compress(Bitmap.CompressFormat.JPEG, 80, os)
        os.close()
    }

    // ignored strokes are used in handleSelect
    // TODO: find way for selecting images
    fun drawArea(
        area: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val activeCanvas = canvas ?: windowedCanvas
        val pageArea = Rect(
            area.left,
            area.top + scroll,
            area.right,
            area.bottom + scroll
        )

        activeCanvas.save()
        activeCanvas.clipRect(area)
        activeCanvas.drawColor(Color.BLACK)

        val timeToDraw = measureTimeMillis {
            // Draw the background for each visible page section
            if (usePagination) {
                drawPaginatedBackground(activeCanvas, pageArea)
            } else {
                drawBg(activeCanvas, pageFromDb?.nativeTemplate ?: "blank", scroll)
            }

            val appSettings = KvProxy(context).get("APP_SETTINGS", AppSettings.serializer())

            if (appSettings?.debugMode == true) {
                // Draw the gray edge of the rectangle
                val redPaint = Paint().apply {
                    color = Color.GRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                activeCanvas.drawRect(area, redPaint)
            }

            // Trying to find what throws error when drawing quickly
            try {
                images.forEach { image ->
                    if (ignoredImageIds.contains(image.id)) return@forEach
                    Log.i(TAG, "PageView.kt: drawing image!")
                    val bounds = imageBounds(image)
                    // if stroke is not inside page section
                    if (!bounds.toRect().intersect(pageArea)) return@forEach
                    drawImage(context, activeCanvas, image, IntOffset(0, -scroll))
                }
            } catch (e: Exception) {
                Log.e(TAG, "PageView.kt: Drawing images failed: ${e.message}", e)

                val errorMessage = if (e.message?.contains("does not have permission") == true) {
                    "Permission error: Unable to access image."
                } else {
                    "Failed to load images."
                }
                coroutineScope.launch {
                    SnackState.globalSnackFlow.emit(
                        SnackConf(
                            text = errorMessage,
                            duration = 3000,
                        )
                    )
                }
            }

            try {
                strokes.forEach { stroke ->
                    if (ignoredStrokeIds.contains(stroke.id)) return@forEach
                    val bounds = strokeBounds(stroke)
                    // if stroke is not inside page section
                    if (!bounds.toRect().intersect(pageArea)) return@forEach

                    drawStroke(
                        activeCanvas, stroke, IntOffset(0, -scroll)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "PageView.kt: Drawing strokes failed: ${e.message}", e)
                coroutineScope.launch {
                    SnackState.globalSnackFlow.emit(
                        SnackConf(
                            text = "Error drawing strokes",
                            duration = 3000,
                        )
                    )
                }
            }
        }
        //Log.i(TAG, "Drew area in ${timeToDraw}ms")
        activeCanvas.restore()
    }

    /**
     * Draws paginated background for the specified visible area.
     * This optimized version only draws the pages that are currently visible in the given area.
     *
     * @param canvas The canvas to draw on
     * @param visibleArea The area that is currently visible and needs to be drawn
     */
    fun drawPaginatedBackground(canvas: Canvas, visibleArea: Rect) {
        if (!usePagination) {
            drawBg(canvas, pageFromDb?.nativeTemplate ?: "blank", scroll)
            return
        }

        // Calculate which pages are visible in the current viewport
        val startPageNumber = PaginationConstants.getPageNumberForPosition(visibleArea.top, pageHeight)
        val endPageNumber = PaginationConstants.getPageNumberForPosition(visibleArea.bottom, pageHeight)

        // Draw each visible page with gap between them
        for (pageNumber in startPageNumber..endPageNumber) {
            val pageTop = PaginationConstants.getPageTopPosition(pageNumber, pageHeight)
            val pageBottom = pageTop + pageHeight

            // Define the area for this page
            val pageRect = Rect(
                visibleArea.left,
                Math.max(pageTop - scroll, visibleArea.top),
                visibleArea.right,
                Math.min(pageBottom - scroll, visibleArea.bottom)
            )

            // Only draw if page is visible in current view
            if (pageRect.bottom > pageRect.top) {
                canvas.save()
                canvas.clipRect(pageRect)

                // Draw the page background
                drawBg(canvas, pageFromDb?.nativeTemplate ?: "blank", scroll)

                // Draw a border around the page
                val borderPaint = Paint().apply {
                    color = Color.LTGRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                canvas.drawRect(pageRect, borderPaint)

                canvas.restore()
            }

            // Draw gap between pages if not the last page
            if (pageNumber < endPageNumber) {
                val gapTop = pageBottom - scroll
                val gapBottom = gapTop + PaginationConstants.PAGE_GAP

                // Only draw the gap if it's in the visible area
                if (gapBottom > visibleArea.top && gapTop < visibleArea.bottom) {
                    val gapRect = Rect(
                        visibleArea.left,
                        Math.max(gapTop, visibleArea.top),
                        visibleArea.right,
                        Math.min(gapBottom, visibleArea.bottom)
                    )

                    canvas.save()
                    canvas.clipRect(gapRect)
                    canvas.drawColor(Color.LTGRAY)
                    canvas.restore()
                }
            }
        }
    }

    fun updateScroll(_delta: Int) {
        var delta = _delta

        // Basic boundary check - can't scroll above the top
        if (scroll + delta < 0) {
            delta = -scroll
        }

        // Apply scroll without additional constraints
        scroll += delta

        // scroll bitmap
        val tmp = windowedBitmap.copy(windowedBitmap.config!!, false)

        if (usePagination) {
            // For pagination, redraw the entire view
            windowedCanvas.drawColor(Color.WHITE)
            drawArea(Rect(0, 0, windowedCanvas.width, windowedCanvas.height))
        } else {
            // Standard background drawing
            drawBg(windowedCanvas, pageFromDb?.nativeTemplate ?: "blank", scroll)
            windowedCanvas.drawBitmap(tmp, 0f, -delta.toFloat(), Paint())
            tmp.recycle()

            // where is the new rendering area starting?
            val canvasOffset = if (delta > 0) windowedCanvas.height - delta else 0

            drawArea(
                area = Rect(
                    0,
                    canvasOffset,
                    windowedCanvas.width,
                    canvasOffset + abs(delta)
                ),
            )
        }

        persistBitmapDebounced()
        saveToPersistLayer()
    }

    // updates page setting in db, (for instance type of background)
    // and redraws page to vew.
    fun updatePageSettings(page: Page) {
        AppRepository(context).pageRepository.update(page)
        pageFromDb = AppRepository(context).pageRepository.getById(id)
        drawArea(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
        persistBitmapDebounced()
    }

    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            viewWidth = newWidth
            viewHeight = newHeight

            // Update pagination page height if enabled
            if (usePagination) {
                // Get the notebook to get the paper format
                val notebook = if (pageFromDb?.notebookId != null) {
                    AppRepository(context).bookRepository.getById(pageFromDb?.notebookId!!)
                } else null

                // Get the paper format from the notebook or use default
                val paperFormat = notebook?.paperFormat ?: PaperFormat.A4

                pageHeight = PaginationConstants.calculatePageHeight(newWidth, paperFormat)
            }

            // Recreate bitmap and canvas with new dimensions
            windowedBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            windowedCanvas = Canvas(windowedBitmap)
            drawArea(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
            persistBitmapDebounced()
        }
    }

    fun updatePagination(isPaginationEnabled: Boolean) {
        // If the setting hasn't changed, do nothing
        if (usePagination == isPaginationEnabled) return

        // Calculate the page height based on the current width
        val newPageHeight = PaginationConstants.calculatePageHeight(viewWidth)
        val pageGap = PaginationConstants.PAGE_GAP

        // If we're turning pagination OFF (need to fill in gaps)
        if (!isPaginationEnabled && usePagination) {
            Log.i(TAG, "Removing pagination gaps - collapsing content")

            // Create transformed strokes with gaps removed
            val transformedStrokes = mutableListOf<Stroke>()

            strokes.forEach { stroke ->
                // Determine which page this stroke is on
                val pageNum = (stroke.top / (newPageHeight + pageGap)).toInt()

                // Remove the gaps that were added by pagination
                val adjustedPoints = stroke.points.map { point ->
                    val gapsToRemove = pageNum * pageGap
                    point.copy(y = point.y - gapsToRemove)
                }

                // Calculate the bounds for this adjusted stroke
                val top = adjustedPoints.minOf { it.y }
                val bottom = adjustedPoints.maxOf { it.y }
                val left = adjustedPoints.minOf { it.x }
                val right = adjustedPoints.maxOf { it.x }

                // Create adjusted stroke
                val adjustedStroke = stroke.copy(
                    points = adjustedPoints,
                    top = top,
                    bottom = bottom,
                    left = left,
                    right = right
                )

                transformedStrokes.add(adjustedStroke)
            }

            // Transform images - remove gaps
            val transformedImages = mutableListOf<Image>()

            images.forEach { image ->
                // Determine which page this image is on
                val pageNum = (image.y / (newPageHeight + pageGap)).toInt()
                val gapsToRemove = pageNum * pageGap
                val adjustedY = image.y - gapsToRemove

                transformedImages.add(image.copy(y = adjustedY))
            }

            // Replace the existing strokes and images with transformed ones
            if (transformedStrokes.isNotEmpty()) {
                removeStrokesFromPersistLayer(strokes.map { it.id })
                strokes = transformedStrokes
                saveStrokesToPersistLayer(transformedStrokes)
                indexStrokes()
            }

            if (transformedImages.isNotEmpty()) {
                removeImagesFromPersistLayer(images.map { it.id })
                images = transformedImages
                saveImagesToPersistLayer(transformedImages)
                indexImages()
            }
        }

        // Update pagination state
        usePagination = isPaginationEnabled

        // Update page height
        pageHeight = if (isPaginationEnabled) newPageHeight else 0

        // Recalculate height based on content
        computeHeight()

        // Redraw the page
        windowedCanvas.drawColor(Color.WHITE)
        drawArea(Rect(0, 0, windowedCanvas.width, windowedCanvas.height))

        // Persist changes
        persistBitmapDebounced()

        // Log the change
        Log.i(TAG, "Pagination toggled to: $isPaginationEnabled")
    }



    private fun persistBitmapDebounced() {
        coroutineScope.launch {
            saveTopic.emit(Unit)
        }
    }

    private fun saveToPersistLayer() {
        coroutineScope.launch {
            AppRepository(context).pageRepository.updateScroll(id, scroll)
            pageFromDb = AppRepository(context).pageRepository.getById(id)
        }
    }
}