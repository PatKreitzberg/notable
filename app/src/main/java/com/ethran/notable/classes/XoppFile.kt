package com.ethran.notable.classes

import android.content.ContentValues
import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.ethran.notable.BuildConfig
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.db.BookRepository
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.Notebook
import com.ethran.notable.db.Page
import com.ethran.notable.db.PageRepository
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.StrokePoint
import com.ethran.notable.modals.DEFAULT_PPI
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.PaperFormat
import com.ethran.notable.utils.Pen
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


object XoppFile {
    // Get the pressure from the device
    private val maxPressure = EpdController.getMaxTouchPressure()

    // Default pressure factor (may need adjustment)
    private val pressureFactor = maxPressure / 2

    /**
     * Exports an entire book as a `.xopp` file.
     *
     * This method processes each page separately, writing the XML data
     * to a temporary file to prevent excessive memory usage. After all
     * pages are processed, the file is compressed into a `.xopp` format.
     *
     * @param context The application context.
     * @param bookId The ID of the book to export.
     */
    fun exportBook(context: Context, bookId: String) {
        if (Looper.getMainLooper().isCurrentThread)
            Log.w(TAG, "Exporting is done on main thread.")

        val book = BookRepository(context).getById(bookId)
            ?: return Log.e(TAG, "Book ID($bookId) not found")

        // Get app settings to determine device PPI
        val appSettings = KvProxy(context).get("APP_SETTINGS", AppSettings.serializer())
        val devicePpi = appSettings?.devicePpi ?: DEFAULT_PPI

        // Get paper format from the book or use default
        val paperFormat = book.paperFormat ?: PaperFormat.A4

        // Calculate scale factor based on paper format and device PPI
        val paperWidth = paperFormat.getWidthInPoints(devicePpi)
        val scaleFactor = paperWidth.toFloat() / SCREEN_WIDTH

        val fileName = book.title
        val tempFile = File(context.cacheDir, "$fileName.xml")

        BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(tempFile),
                Charsets.UTF_8
            )
        ).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<xournal creator=\"Notable ${BuildConfig.VERSION_NAME}\" version=\"0.4\">\n")

            book.pageIds.forEach { pageId ->
                writePage(context, pageId, writer, scaleFactor, paperFormat, devicePpi)
            }

            writer.write("</xournal>\n")
        }

        saveAsXopp(context, tempFile, fileName)
    }

    /**
     * Exports page as a `.xopp` file.
     */
    fun exportPage(context: Context, pageId: String) {
        // Get app settings to determine device PPI
        val appSettings = KvProxy(context).get("APP_SETTINGS", AppSettings.serializer())
        val devicePpi = appSettings?.devicePpi ?: DEFAULT_PPI

        // Determine paper format - try to get from notebook if page belongs to a notebook
        val page = PageRepository(context).getById(pageId)
        val notebookId = page?.notebookId
        val paperFormat = if (notebookId != null) {
            val notebook = BookRepository(context).getById(notebookId)
            notebook?.paperFormat ?: PaperFormat.A4
        } else {
            PaperFormat.A4
        }

        // Calculate scale factor based on paper format and device PPI
        val paperWidth = paperFormat.getWidthInPoints(devicePpi)
        val scaleFactor = paperWidth.toFloat() / SCREEN_WIDTH

        val tempFile = File(context.cacheDir, "exported_page.xml")

        BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(tempFile),
                Charsets.UTF_8
            )
        ).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<xournal creator=\"Notable ${BuildConfig.VERSION_NAME}\" version=\"0.4\">\n")
            writePage(context, pageId, writer, scaleFactor, paperFormat, devicePpi)
            writer.write("</xournal>\n")
        }

        saveAsXopp(context, tempFile, "exported_page")
    }


    /**
     * Writes a single page's XML data to the output stream.
     *
     * This method retrieves the strokes and images for the given page
     * and writes them to the provided BufferedWriter.
     *
     * @param context The application context.
     * @param pageId The ID of the page to process.
     * @param writer The BufferedWriter to write XML data to.
     * @param scaleFactor The scale factor to apply to coordinates.
     * @param paperFormat The paper format being used.
     * @param devicePpi The device PPI setting.
     */
    private fun writePage(context: Context, pageId: String, writer: BufferedWriter,
                          scaleFactor: Float, paperFormat: PaperFormat, devicePpi: Int) {
        val pages = PageRepository(context)
        val (_, strokes) = pages.getWithStrokeById(pageId)
        val (_, images) = pages.getWithImageById(pageId)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

        val root = doc.createElement("page")
        val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50

        // Calculate paper dimensions based on the paper format and device PPI
        val paperWidth = paperFormat.getWidthInPoints(devicePpi)
        val paperHeight = paperFormat.getHeightInPoints(devicePpi)

        // Use actual paper width and height for export
        root.setAttribute("width", paperWidth.toString())

        // For height, either use the paper height or scale the content height if it's larger
        val height = (strokeHeight * scaleFactor).coerceAtLeast(paperHeight.toFloat()).toInt()
        root.setAttribute("height", height.toString())

        doc.appendChild(root)

        val bcgElement = doc.createElement("background")
        bcgElement.setAttribute("type", "solid")
        bcgElement.setAttribute("color", "#ffffffff")
        bcgElement.setAttribute("style", "plain")
        root.appendChild(bcgElement)

        val layer = doc.createElement("layer")
        root.appendChild(layer)

        for (stroke in strokes) {
            val strokeElement = doc.createElement("stroke")
            strokeElement.setAttribute("tool", stroke.pen.toString())
            strokeElement.setAttribute("color", getColorName(Color(stroke.color)))
            val widthValues = mutableListOf(stroke.size * scaleFactor)
            if (stroke.pen == Pen.FOUNTAIN || stroke.pen == Pen.BRUSH || stroke.pen == Pen.PENCIL)
                widthValues += stroke.points.map { it.pressure / pressureFactor }
            val widthString = widthValues.joinToString(" ")

            strokeElement.setAttribute("width", widthString)

            val pointsString =
                stroke.points.joinToString(" ") { "${it.x * scaleFactor} ${it.y * scaleFactor}" }
            strokeElement.textContent = pointsString
            layer.appendChild(strokeElement)
        }

        for (image in images) {
            val imgElement = doc.createElement("image")

            val left = image.x * scaleFactor
            val top = image.y * scaleFactor
            val right = (image.x + image.width) * scaleFactor
            val bottom = (image.y + image.height) * scaleFactor

            imgElement.setAttribute("left", left.toString())
            imgElement.setAttribute("top", top.toString())
            imgElement.setAttribute("right", right.toString())
            imgElement.setAttribute("bottom", bottom.toString())

            image.uri?.let { uri ->
                imgElement.setAttribute("filename", uri)
                imgElement.textContent = convertImageToBase64(image.uri, context)
            }

            layer.appendChild(imgElement)
        }

        val xmlString = convertXmlToString(doc)
        writer.write(xmlString)
    }


    /**
     * Opens a file and converts it to a base64 string.
     */
    private fun convertImageToBase64(uri: String, context: Context): String {
        val inputStream = context.contentResolver.openInputStream(uri.toUri())
        val bytes = inputStream?.readBytes() ?: return ""
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }


    /**
     * Converts an XML Document to a formatted string without the XML declaration.
     *
     * This is used to convert an individual page's XML structure into a string
     * before writing it to the output file. The XML declaration is removed to
     * prevent duplicate headers when merging pages.
     *
     * @param document The XML Document to convert.
     * @return The formatted XML string without the XML declaration.
     */
    private fun convertXmlToString(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") // ❗ Omit XML header
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString().trim() // Remove extra spaces or newlines
    }


    /**
     * Saves a temporary XML file as a compressed `.xopp` file.
     *
     * @param context The application context.
     * @param file The temporary XML file to compress.
     * @param fileName The name of the final `.xopp` file.
     */
    private fun saveAsXopp(context: Context, file: File, fileName: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, "$fileName.xopp")
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/x-xopp")
            put(
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/Notable/"
            )
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            ?: throw IOException("Failed to create Media Store entry")

        resolver.openOutputStream(uri)?.use { outputStream ->
            GzipCompressorOutputStream(BufferedOutputStream(outputStream)).use { gzipOutputStream ->
                file.inputStream().copyTo(gzipOutputStream)
            }
        } ?: throw IOException("Failed to open output stream")

        file.delete()
    }


    /**
     * Imports a `.xopp` file, creating a new book and pages in the database.
     *
     * @param context The application context.
     * @param uri The URI of the `.xopp` file to import.
     */
    fun importBook(context: Context, uri: Uri, parentFolderId: String?) {
        if (Looper.getMainLooper().isCurrentThread)
            Log.e(TAG, "Importing is done on main thread.")
        Log.i(TAG, "got uri $uri, and parentFolderId $parentFolderId")
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        val xmlContent = extractXmlFromXopp(inputStream) ?: return

        val document = parseXml(xmlContent) ?: return
        val bookTitle = getBookTitle(uri)
        val bookRepo = BookRepository(context)
        val pageRepo = PageRepository(context)

        // Get app settings for device PPI
        val appSettings = KvProxy(context).get("APP_SETTINGS", AppSettings.serializer())
        val devicePpi = appSettings?.devicePpi ?: DEFAULT_PPI

        val book = Notebook(
            title = bookTitle,
            parentFolderId = parentFolderId,
            defaultNativeTemplate = "blank",
            // Use Letter format for imported books by default, can be changed later
            paperFormat = PaperFormat.LETTER
        )
        bookRepo.createEmpty(book)

        val pages = document.getElementsByTagName("page")

        for (i in 0 until pages.length) {
            val pageElement = pages.item(i) as Element
            val page = Page(notebookId = book.id, nativeTemplate = "blank")
            pageRepo.create(page)
            parseStrokes(context, pageElement, page, devicePpi)
            bookRepo.addPage(book.id, page.id)

//            parseImages(pageElement, page, pageRepo, context)
        }
        Log.i(TAG, "Successfully imported book '${book.title}' with ${pages.length} pages.")
    }

    /**
     * Extracts XML content from a `.xopp` file.
     */
    private fun extractXmlFromXopp(inputStream: InputStream): String? {
        return try {
            GzipCompressorInputStream(BufferedInputStream(inputStream)).bufferedReader()
                .use { it.readText() }
        } catch (e: IOException) {
            Log.e(TAG, "Error extracting XML from .xopp file: ${e.message}")
            null
        }
    }

    /**
     * Parses an XML string into a DOM Document.
     */
    private fun parseXml(xml: String): Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing XML: ${e.message}")
            null
        }
    }

    /**
     * Extracts strokes from a page element and saves them.
     *
     * @param devicePpi Device PPI for scaling during import
     */
    private fun parseStrokes(context: Context, pageElement: Element, page: Page, devicePpi: Int) {
        val strokeRepo = AppDatabase.getDatabase(context).strokeDao()
        val strokeNodes = pageElement.getElementsByTagName("stroke")
        val strokes = mutableListOf<Stroke>()

        // Get page width to calculate scale factor
        val pageWidth = pageElement.getAttribute("width").toFloatOrNull() ?: 595f
        val scaleFactor = SCREEN_WIDTH / pageWidth

        for (i in 0 until strokeNodes.length) {
            val strokeElement = strokeNodes.item(i) as Element
            val pointsString = strokeElement.textContent.trim()

            if (pointsString.isBlank()) continue // Skip empty strokes

            // Decode width attribute
            val widthString = strokeElement.getAttribute("width").trim()
            val widthValues = widthString.split(" ").mapNotNull { it.toFloatOrNull() }

            val strokeSize = widthValues.firstOrNull()?.times(scaleFactor) ?: 1.0f
            val pressureValues = widthValues.drop(1) // Remaining values are pressure

            val points = pointsString.split(" ").chunked(2).mapIndexedNotNull { index, chunk ->
                try {
                    StrokePoint(
                        x = chunk[0].toFloat() * scaleFactor,
                        y = chunk[1].toFloat() * scaleFactor,
                        pressure = pressureValues.getOrNull(index - 1)?.times(pressureFactor)
                            ?: (maxPressure / 2),
                        size = strokeSize,
                        tiltX = 0,
                        tiltY = 0,
                        timestamp = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing stroke point: ${e.message}")
                    null
                }
            }
            if (points.isEmpty()) continue // Skip strokes without valid points

            val boundingBox = RectF()

            val decodedPoints = points.mapIndexed { index, it ->
                if (index == 0) boundingBox.set(it.x, it.y, it.x, it.y) else boundingBox.union(
                    it.x,
                    it.y
                )
                it
            }

            boundingBox.inset(-strokeSize, -strokeSize)
            val toolName = strokeElement.getAttribute("tool")
            val tool = Pen.fromString(toolName)

            val stroke = Stroke(
                size = strokeSize,
                pen = tool,
                pageId = page.id,
                top = boundingBox.top,
                bottom = boundingBox.bottom,
                left = boundingBox.left,
                right = boundingBox.right,
                points = decodedPoints,
                color = android.graphics.Color.argb(
                    (Color(parseColor(strokeElement.getAttribute("color"))).alpha * 255).toInt(),
                    (Color(parseColor(strokeElement.getAttribute("color"))).red * 255).toInt(),
                    (Color(parseColor(strokeElement.getAttribute("color"))).green * 255).toInt(),
                    (Color(parseColor(strokeElement.getAttribute("color"))).blue * 255).toInt()
                )
            )
            strokes.add(stroke)
        }
        strokeRepo.create(strokes)
    }


    /**
     * Extracts images from a page element and saves them.
     */
    private fun parseImages(
        context: Context,
        pageElement: Element,
        page: Page
    ) {
        TODO("Not yet implemented")
    }

    /**
     * Saves image data to a file and returns the file object.
     */
    private fun saveImageToFile(imageBytes: ByteArray, context: Context): File {
        val file = File(context.filesDir, "image_${UUID.randomUUID()}.png")
        file.outputStream().use { it.write(imageBytes) }
        return file
    }

    /**
     * Extracts the book title from a file URI.
     */
    private fun getBookTitle(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast("/")?.removeSuffix(".xopp")
            ?: "Imported Book"
    }

    /**
     * Parses an Xournal++ color string to a Compose Color.
     */
    private fun parseColor(colorString: String): Int {
        return when (colorString.lowercase()) {
            "black" -> Color.Black.toArgb()
            "blue" -> Color.Blue.toArgb()
            "red" -> Color.Red.toArgb()
            "green" -> Color.Green.toArgb()
            "magenta" -> Color.Magenta.toArgb()
            "yellow" -> Color.Yellow.toArgb()
            // Convert "#RRGGBBAA" → "#AARRGGBB" → Android Color
            else -> {
                if (colorString.startsWith("#") && colorString.length == 9)
                    ("#" + colorString.substring(7, 9) +
                            colorString.substring(1, 7)).toColorInt()
                else {
                    Log.e(TAG, "Unknown color: $colorString")
                    Color.Black.toArgb()
                }
            }
        }
    }

    /**
     * Maps a Compose Color to an Xournal++ color name.
     *
     * @param color The Compose Color object.
     * @return The corresponding color name as a string.
     */
    private fun getColorName(color: Color): String {
        return when (color) {
            Color.Black -> "black"
            Color.Blue -> "blue"
            Color.Red -> "red"
            Color.Green -> "green"
            Color.Magenta -> "magenta"
            Color.Yellow -> "yellow"
            Color.DarkGray, Color.Gray -> "gray"
            Color.Cyan -> "lightblue"
            else -> {
                val argb = color.toArgb()
                // Convert ARGB (Android default) → RGBA
                String.format(
                    "#%02X%02X%02X%02X",
                    (argb shr 16) and 0xFF, // Red
                    (argb shr 8) and 0xFF,  // Green
                    (argb) and 0xFF,        // Blue
                    (argb shr 24) and 0xFF  // Alpha
                )
            }
        }
    }
}