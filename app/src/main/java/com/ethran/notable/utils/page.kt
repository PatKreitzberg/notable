package com.ethran.notable.utils


import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.db.PageRepository
import com.ethran.notable.db.Stroke
import com.ethran.notable.modals.A4_HEIGHT
import com.ethran.notable.modals.A4_WIDTH
import io.shipbook.shipbooksdk.Log
import android.graphics.Color
import android.graphics.Paint
import com.ethran.notable.TAG
import com.ethran.notable.db.BookRepository
import com.ethran.notable.modals.A4_HEIGHT
import com.ethran.notable.modals.A4_WIDTH


fun drawCanvas(context: Context, pageId: String): Bitmap {
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Exporting is done on main thread.")

    val pages = PageRepository(context)
    val (page, strokes) = pages.getWithStrokeById(pageId)
    val (_, images) = pages.getWithImageById(pageId)

    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50

    val height = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
    val width = strokeWidth.coerceAtLeast(SCREEN_WIDTH)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw background
    drawBg(canvas, page.nativeTemplate, 0)

    // Draw strokes
    for (stroke in strokes) {
        drawStroke(canvas, stroke, IntOffset(0, 0))
    }
    for (image in images) {
        drawImage(context, canvas, image, IntOffset(0, 0))
    }
    return bitmap
}

fun PdfDocument.writePage(context: Context, number: Int, repo: PageRepository, id: String) {
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Exporting is done on main thread.")

    val (page, strokes) = repo.getWithStrokeById(id)
    val (_, images) = repo.getWithImageById(id)

    // Check if the page belongs to a notebook with pagination enabled
    val notebookId = page.notebookId
    val isPaginationEnabled = if (notebookId != null) {
        val notebook = BookRepository(context).getById(notebookId)
        notebook?.usePagination ?: false
    } else false

    val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH

    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50

    if (isPaginationEnabled) {
        // For paginated notebook, create multiple PDF pages based on letter page size
        val pageWidth = SCREEN_WIDTH
        val pageHeight = PaginationConstants.calculatePageHeight(pageWidth)

        // Calculate how many pages we need based on content
        val contentHeight = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
        val totalPages = ((contentHeight + PaginationConstants.PAGE_GAP - 1) /
                (pageHeight + PaginationConstants.PAGE_GAP)) + 1

        for (i in 0 until totalPages) {
            val pageTop = PaginationConstants.getPageTopPosition(i, pageHeight)
            val pageBottom = pageTop + pageHeight

            // Create a PDF page with standard size
            val documentPage = startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, number + i).create())
            val canvas = documentPage.canvas
            canvas.scale(scaleFactor, scaleFactor)

            // Draw white background
            canvas.drawColor(Color.WHITE)

            // Draw content for this specific page
            drawBg(canvas, page.nativeTemplate, pageTop)

            // Draw only strokes that fall within this page's bounds
            strokes.forEach { stroke ->
                if (stroke.top < pageBottom && stroke.bottom > pageTop) {
                    // Offset the stroke to appear in the correct position on this PDF page
                    val offsetStroke = stroke.copy(
                        points = stroke.points.map { pt ->
                            pt.copy(y = pt.y - pageTop)
                        },
                        top = stroke.top - pageTop,
                        bottom = stroke.bottom - pageTop
                    )
                    drawStroke(canvas, offsetStroke, IntOffset(0, 0))
                }
            }

            // Draw only images that fall within this page's bounds
            images.forEach { image ->
                if (image.y < pageBottom && image.y + image.height > pageTop) {
                    // Offset the image to appear in the correct position on this PDF page
                    val offsetImage = image.copy(
                        y = image.y - pageTop
                    )
                    drawImage(context, canvas, offsetImage, IntOffset(0, 0))
                }
            }

            finishPage(documentPage)
        }
    } else {
        // Original non-paginated approach
        val contentHeight = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
        val pageHeight = (contentHeight * scaleFactor).toInt()
        val contentWidth = strokeWidth.coerceAtLeast(SCREEN_WIDTH)

        val documentPage = startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, pageHeight, number).create())

        // Center content on the A4 page
        val offsetX = (A4_WIDTH - (contentWidth * scaleFactor)) / 2
        val offsetY = (A4_HEIGHT - (contentHeight * scaleFactor)) / 2

        documentPage.canvas.scale(scaleFactor, scaleFactor)
        drawBg(documentPage.canvas, page.nativeTemplate, 0, scaleFactor)

        for (stroke in strokes) {
            drawStroke(documentPage.canvas, stroke, IntOffset(0, 0))
        }

        for (image in images) {
            drawImage(context, documentPage.canvas, image, IntOffset(0, 0))
        }

        finishPage(documentPage)
    }
}

/**
 * Converts a URI to a Bitmap using the provided [context] and [uri].
 *
 * @param context The context used to access the content resolver.
 * @param uri The URI of the image to be converted to a Bitmap.
 * @return The Bitmap representation of the image, or null if conversion fails.
 * https://medium.com/@munbonecci/how-to-display-an-image-loaded-from-the-gallery-using-pick-visual-media-in-jetpack-compose-df83c78a66bf
 */
fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        // Obtain the content resolver from the context
        val contentResolver: ContentResolver = context.contentResolver

        // Since the minimum SDK is 29, we can directly use ImageDecoder to decode the Bitmap
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    } catch (e: SecurityException) {
        Log.e(TAG, "SecurityException: ${e.message}", e)
        null
    } catch (e: ImageDecoder.DecodeException) {
        Log.e(TAG, "DecodeException: ${e.message}", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected error: ${e.message}", e)
        null
    }
}