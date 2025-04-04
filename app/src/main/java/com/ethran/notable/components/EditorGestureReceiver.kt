package com.ethran.notable.components

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.TAG
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.EditorControlTower
import com.ethran.notable.classes.GestureState
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.SnackState
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.UndoRedoType
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.hypot


private const val HOLD_THRESHOLD_MS = 300
private const val ONE_FINGER_TOUCH_TAP_TIME = 100L
private const val TAP_MOVEMENT_TOLERANCE = 15f
private const val SWIPE_THRESHOLD = 200f
private const val DOUBLE_TAP_TIMEOUT_MS = 170L
private const val DOUBLE_TAP_MIN_MS = 20L
private const val TWO_FINGER_TOUCH_TAP_MAX_TIME = 200L
private const val TWO_FINGER_TOUCH_TAP_MIN_TIME = 20L
private const val TWO_FINGER_TAP_MOVEMENT_TOLERANCE = 20f
// Threshold to detect pinch gestures
private const val PINCH_GESTURE_THRESHOLD = 0.05f


@Composable
@ExperimentalComposeUiApi
fun EditorGestureReceiver(
    goToNextPage: () -> Unit,
    goToPreviousPage: () -> Unit,
    controlTower: EditorControlTower,
    state: EditorState
) {

    val coroutineScope = rememberCoroutineScope()
    val appSettings by AppRepository(LocalContext.current)
        .kvProxy
        .observeKv("APP_SETTINGS", AppSettings.serializer(), AppSettings(version = 1))
        .observeAsState()

    var crossPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rectangleBounds by remember { mutableStateOf<Rect?>(null) }
    var isSelection by remember { mutableStateOf(false) }

    // Flag to track if we're currently in a pinch-to-zoom gesture
    var isPinchZooming by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    try {
                        if (!coroutineScope.isActive) return@awaitEachGesture

                        val gestureState = GestureState()
                        if (!state.isDrawing && !isSelection) {
                            state.isDrawing = true
                        }
                        isSelection = false

                        // Reset pinch zooming flag at start of new gesture
                        isPinchZooming = false

                        // Detect initial touch
                        val down = awaitFirstDown()

                        // Ignore non-touch input
                        if (down.type != PointerType.Touch) {
                            Log.i(TAG, "Ignoring non-touch input")
                            return@awaitEachGesture
                        }
                        gestureState.initialTimestamp = System.currentTimeMillis()
                        gestureState.insertPosition(down)

                        // Track active pointers
                        val activePointers = mutableListOf<PointerInputChange>()
                        activePointers.add(down)

                        // Variables for pinch detection
                        var previousDistance = 0f
                        var initialDistance = 0f

                        do {
                            // wait for next events
                            val event = withTimeoutOrNull(1000L) { awaitPointerEvent() }

                            if (event != null) {
                                val fingerChange = event.changes.filter { it.type == PointerType.Touch }

                                // is already consumed return
                                if (fingerChange.find { it.isConsumed } != null) {
                                    Log.i(TAG, "Canceling gesture - already consumed")
                                    crossPosition = null
                                    rectangleBounds = null
                                    return@awaitEachGesture
                                }

                                // Update active pointers list
                                activePointers.clear()
                                fingerChange.forEach { change ->
                                    if (change.pressed) {
                                        activePointers.add(change)
                                    }
                                }

                                // Check for pinch gesture when we have exactly 2 pointers
                                if (activePointers.size == 2) {
                                    // Calculate distance between two fingers
                                    val currentDistance = calculateDistance(
                                        activePointers[0].position,
                                        activePointers[1].position
                                    )

                                    // Initialize distances if this is the start of a potential pinch
                                    if (previousDistance == 0f) {
                                        previousDistance = currentDistance
                                        initialDistance = currentDistance
                                    }

                                    // Calculate the zoom ratio between current and previous distance
                                    val zoomRatio = currentDistance / previousDistance

                                    // Check if the change is significant enough to be a pinch
                                    if (abs(zoomRatio - 1f) > PINCH_GESTURE_THRESHOLD) {
                                        // This is a pinch gesture!
                                        isPinchZooming = true

                                        // Calculate new zoom scale
                                        val newZoomScale = (state.zoomScale * zoomRatio)
                                            .coerceIn(state.minZoom, state.maxZoom)

                                        // Only update if there's a meaningful change
                                        if (abs(newZoomScale - state.zoomScale) > 0.01f) {
                                            state.zoomScale = newZoomScale
                                            state.normalizeZoom()

                                            // Refresh the UI to reflect zoom changes
                                            coroutineScope.launch {
                                                DrawCanvas.refreshUi.emit(Unit)
                                            }
                                        }

                                        // Consume the changes to prevent other gesture handling
                                        fingerChange.forEach { it.consume() }

                                        // Update previous distance for next calculation
                                        previousDistance = currentDistance
                                    }
                                } else if (isPinchZooming && activePointers.size < 2) {
                                    // End of pinch gesture
                                    isPinchZooming = false

                                    // Show a brief notification of the zoom level
                                    coroutineScope.launch {
                                        SnackState.globalSnackFlow.emit(
                                            SnackConf(
                                                text = "Zoom: ${(state.zoomScale * 100).toInt()}%",
                                                duration = 1000
                                            )
                                        )
                                    }
                                }

                                // If not in a pinch gesture, handle changes normally
                                if (!isPinchZooming) {
                                    fingerChange.forEach { change ->
                                        change.consume()
                                        gestureState.insertPosition(change)
                                    }
                                }

                                if (fingerChange.any { !it.pressed }) {
                                    gestureState.lastTimestamp = System.currentTimeMillis()
                                    break
                                }
                            }

                            // events are only send on change, so we need to check for holding in place separately
                            gestureState.lastTimestamp = System.currentTimeMillis()

                            // Skip selection handling if we're in a pinch gesture
                            if (!isPinchZooming) {
                                if (isSelection) {
                                    crossPosition = gestureState.getLastPositionIO()
                                    rectangleBounds = gestureState.calculateRectangleBounds()
                                } else if (gestureState.getElapsedTime() >= HOLD_THRESHOLD_MS
                                    && gestureState.getInputCount() == 1) {
                                    if (gestureState.calculateTotalDelta() < TAP_MOVEMENT_TOLERANCE) {
                                        isSelection = true
                                        crossPosition = gestureState.getLastPositionIO()
                                        rectangleBounds = gestureState.calculateRectangleBounds()
                                        coroutineScope.launch {
                                            state.isDrawing = false
                                            SnackState.globalSnackFlow.emit(
                                                SnackConf(
                                                    text = "Selection mode!",
                                                    duration = 1500,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        } while (true)

                        // Skip further gesture processing if this was a pinch gesture
                        if (isPinchZooming) {
                            return@awaitEachGesture
                        }

                        if (isSelection) {
                            resolveGesture(
                                settings = appSettings,
                                default = AppSettings.defaultHoldAction,
                                override = AppSettings::holdAction,
                                state = state,
                                scope = coroutineScope,
                                previousPage = goToPreviousPage,
                                nextPage = goToNextPage,
                                rectangle = rectangleBounds!!
                            )
                            crossPosition = null
                            rectangleBounds = null
                            return@awaitEachGesture
                        }

                        // Calculate the total delta (movement distance) for all pointers
                        val totalDelta = gestureState.calculateTotalDelta()
                        val gestureDuration = gestureState.getElapsedTime()
                        Log.v(
                            TAG,
                            "Leaving gesture. totalDelta: ${totalDelta}, gestureDuration: $gestureDuration "
                        )

                        if (gestureState.getInputCount() == 1) {
                            if (totalDelta < TAP_MOVEMENT_TOLERANCE && gestureDuration < ONE_FINGER_TOUCH_TAP_TIME) {
                                if (withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
                                        val secondDown = awaitFirstDown()
                                        val deltaTime =
                                            System.currentTimeMillis() - gestureState.lastTimestamp
                                        Log.v(
                                            TAG,
                                            "Second down detected: ${secondDown.type}, position: ${secondDown.position}, deltaTime: $deltaTime"
                                        )
                                        if (deltaTime < DOUBLE_TAP_MIN_MS) {
                                            coroutineScope.launch {
                                                SnackState.globalSnackFlow.emit(
                                                    SnackConf(
                                                        text = "Too quick for double click! delta: $totalDelta, time between: $deltaTime",
                                                        duration = 3000,
                                                    )
                                                )
                                            }
                                            return@withTimeoutOrNull null
                                        } else {
                                            Log.v(TAG, "double click!")
                                        }
                                        if (secondDown.type != PointerType.Touch) {
                                            Log.i(
                                                TAG,
                                                "Ignoring non-touch input during double-tap detection"
                                            )
                                            return@withTimeoutOrNull null
                                        }

                                        // Add double-tap to reset zoom
                                        if (state.zoomScale != 1.0f) {
                                            state.resetZoom()
                                            coroutineScope.launch {
                                                DrawCanvas.refreshUi.emit(Unit)
                                                SnackState.globalSnackFlow.emit(
                                                    SnackConf(
                                                        text = "Zoom reset to 100%",
                                                        duration = 1000,
                                                    )
                                                )
                                            }
                                            return@withTimeoutOrNull Unit
                                        }

                                        resolveGesture(
                                            settings = appSettings,
                                            default = AppSettings.defaultDoubleTapAction,
                                            override = AppSettings::doubleTapAction,
                                            state = state,
                                            scope = coroutineScope,
                                            previousPage = goToPreviousPage,
                                            nextPage = goToNextPage,
                                        )
                                    } != null) return@awaitEachGesture
                            }
                        } else if (gestureState.getInputCount() == 2) {
                            Log.v(TAG, "Two finger tap")
                            if (totalDelta < TWO_FINGER_TAP_MOVEMENT_TOLERANCE &&
                                gestureDuration < TWO_FINGER_TOUCH_TAP_MAX_TIME &&
                                gestureDuration > TWO_FINGER_TOUCH_TAP_MIN_TIME
                            ) {
                                resolveGesture(
                                    settings = appSettings,
                                    default = AppSettings.defaultTwoFingerTapAction,
                                    override = AppSettings::twoFingerTapAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                )
                            }
                        }

                        val horizontalDrag = gestureState.getHorizontalDrag()
                        val verticalDrag = gestureState
                            .getVerticalDrag()
                            .toInt()

                        Log.v(TAG, "horizontalDrag $horizontalDrag, verticalDrag $verticalDrag")
                        when {
                            horizontalDrag < -SWIPE_THRESHOLD -> {
                                resolveGesture(
                                    settings = appSettings,
                                    default = if (gestureState.getInputCount() == 1) AppSettings.defaultSwipeLeftAction else AppSettings.defaultTwoFingerSwipeLeftAction,
                                    override = if (gestureState.getInputCount() == 1) AppSettings::swipeLeftAction else AppSettings::twoFingerSwipeLeftAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                )
                            }

                            horizontalDrag > SWIPE_THRESHOLD -> {
                                resolveGesture(
                                    settings = appSettings,
                                    default = if (gestureState.getInputCount() == 1) AppSettings.defaultSwipeRightAction else AppSettings.defaultTwoFingerSwipeRightAction,
                                    override = if (gestureState.getInputCount() == 1) AppSettings::swipeRightAction else AppSettings::twoFingerSwipeRightAction,
                                    state = state,
                                    scope = coroutineScope,
                                    previousPage = goToPreviousPage,
                                    nextPage = goToNextPage,
                                )
                            }

                        }

                        if (abs(verticalDrag) > SWIPE_THRESHOLD && gestureState.getInputCount() == 1) {
                            controlTower.onSingleFingerVerticalSwipe(
                                gestureState.getFirstPositionF()!!,
                                verticalDrag
                            )
                        }
                    } catch (e: CancellationException) {
                        Log.e(TAG, "Gesture coroutine canceled", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error in gesture handling", e)
                    }
                }
            }

            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val density = LocalDensity.current
        // Draw cross where finger is touching
        DrawCross(crossPosition, density)
        // Draw the rectangle while dragging
        DrawRectangle(rectangleBounds, density)
    }
}

/**
 * Calculate distance between two points
 */
private fun calculateDistance(point1: Offset, point2: Offset): Float {
    return hypot(point1.x - point2.x, point1.y - point2.y)
}

@Composable
private fun DrawRectangle(rectangleBounds: Rect?, density: Density) {
    rectangleBounds?.let { bounds ->
        // Draw the rectangle
        Box(
            Modifier
                .offset { IntOffset(bounds.left, bounds.top) }
                .size(
                    width = with(density) { (bounds.right - bounds.left).toDp() },
                    height = with(density) { (bounds.bottom - bounds.top).toDp() }
                )
                // Is there rendering speed difference between colors?
                .background(Color(0x55000000))
                .border(1.dp, Color.Black)
        )
    }

}

@Composable
private fun DrawCross(crossPosition: IntOffset?, density: Density) {

    // Draw cross where finger is touching
    crossPosition?.let { pos ->
        val crossSizePx = with(density) { 100.dp.toPx() }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        pos.x - (crossSizePx / 2).toInt(),
                        pos.y
                    )
                } // Horizontal bar centered
                .size(width = 100.dp, height = 2.dp)
                .background(Color.Black)
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        pos.x,
                        pos.y - (crossSizePx / 2).toInt()
                    )
                } // Vertical bar centered
                .size(width = 2.dp, height = 100.dp)
                .background(Color.Black)
        )
    }
}

private fun resolveGesture(
    settings: AppSettings?,
    default: AppSettings.GestureAction,
    override: AppSettings.() -> AppSettings.GestureAction?,
    state: EditorState,
    scope: CoroutineScope,
    previousPage: () -> Unit,
    nextPage: () -> Unit,
    rectangle: Rect = Rect()
) {
    when (if (settings != null) override(settings) else default) {
        null -> Log.i(TAG, "No Action")
        AppSettings.GestureAction.PreviousPage -> previousPage()
        AppSettings.GestureAction.NextPage -> nextPage()

        AppSettings.GestureAction.ChangeTool ->
            state.mode = if (state.mode == Mode.Draw) Mode.Erase else Mode.Draw

        AppSettings.GestureAction.ToggleZen ->
            state.isToolbarOpen = !state.isToolbarOpen

        AppSettings.GestureAction.Undo -> {
            Log.i(TAG, "Undo")
            scope.launch {
                History.moveHistory(UndoRedoType.Undo)
//                moved to history operation - avoids unnecessary refresh, and ensures that it will be done after drawing.
//                DrawCanvas.refreshUi.emit(Unit)
            }
        }

        AppSettings.GestureAction.Redo -> {
            Log.i(TAG, "Redo")
            scope.launch {
                History.moveHistory(UndoRedoType.Redo)
//                DrawCanvas.refreshUi.emit(Unit)
            }
        }

        AppSettings.GestureAction.Select -> {
            Log.i(TAG, "select")
            scope.launch {
                DrawCanvas.rectangleToSelect.emit(rectangle)
            }
        }
    }
}