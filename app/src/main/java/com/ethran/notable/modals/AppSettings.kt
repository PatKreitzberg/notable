package com.ethran.notable.modals

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.BuildConfig
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.SnackState
import com.ethran.notable.components.SelectMenu
import com.ethran.notable.db.KvProxy
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.isNext
import com.ethran.notable.utils.noRippleClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.concurrent.thread

// it is workaround for now
var NeoTools: Boolean = false

// Paper Size constants (in inches)
@Serializable
enum class PaperFormat(val displayName: String, val widthInches: Float, val heightInches: Float) {
    A4("A4", 8.27f, 11.69f),
    LETTER("Letter", 8.5f, 11.0f),
    LEGAL("Legal", 8.5f, 14.0f);

    // Calculate width in points for the given PPI
    fun getWidthInPoints(ppi: Int): Int = (widthInches * ppi).toInt()

    // Calculate height in points for the given PPI
    fun getHeightInPoints(ppi: Int): Int = (heightInches * ppi).toInt()
}

// Default PPI is 72
const val DEFAULT_PPI = 72

// For backward compatibility
val A4_WIDTH = PaperFormat.A4.getWidthInPoints(DEFAULT_PPI)
val A4_HEIGHT = PaperFormat.A4.getHeightInPoints(DEFAULT_PPI)

@Serializable
data class AppSettings(
    val version: Int,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),
    val debugMode: Boolean = false,
    val neoTools: Boolean = false,
    val paperFormat: PaperFormat = PaperFormat.A4,
    val devicePpi: Int = DEFAULT_PPI,

    val doubleTapAction: GestureAction? = defaultDoubleTapAction,
    val twoFingerTapAction: GestureAction? = defaultTwoFingerTapAction,
    val swipeLeftAction: GestureAction? = defaultSwipeLeftAction,
    val swipeRightAction: GestureAction? = defaultSwipeRightAction,
    val twoFingerSwipeLeftAction: GestureAction? = defaultTwoFingerSwipeLeftAction,
    val twoFingerSwipeRightAction: GestureAction? = defaultTwoFingerSwipeRightAction,
    val holdAction: GestureAction? = defaultHoldAction,

    ) {
    companion object {
        val defaultDoubleTapAction get() = GestureAction.Undo
        val defaultTwoFingerTapAction get() = GestureAction.ChangeTool
        val defaultSwipeLeftAction get() = GestureAction.NextPage
        val defaultSwipeRightAction get() = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction get() = GestureAction.ToggleZen
        val defaultTwoFingerSwipeRightAction get() = GestureAction.ToggleZen
        val defaultHoldAction get() = GestureAction.Select
    }

    enum class GestureAction {
        Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }
}

@Composable
fun AppSettingsModal(onClose: () -> Unit) {
    val context = LocalContext.current
    val kv = KvProxy(context)

    var isLatestVersion by remember { mutableStateOf(true) }
    LaunchedEffect(key1 = Unit, block = { thread { isLatestVersion = isLatestVersion(context) } })

    val settings by
    kv.observeKv("APP_SETTINGS", AppSettings.serializer(), AppSettings(version = 1))
        .observeAsState()

    if (settings == null) return

    Dialog(
        onDismissRequest = { onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(40.dp)
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(Modifier.padding(20.dp, 10.dp)) {
                Text(text = "App setting - v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}")
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(Modifier.padding(20.dp, 10.dp)) {
                Row {
                    Text(text = "Default Page Background Template")
                    Spacer(Modifier.width(10.dp))
                    SelectMenu(
                        options = listOf(
                            "blank" to "Blank page",
                            "dotted" to "Dot grid",
                            "lined" to "Lines",
                            "squared" to "Small squares grid",
                            "hexed" to "Hexagon grid",
                        ),
                        onChange = {
                            kv.setKv(
                                "APP_SETTINGS",
                                settings!!.copy(defaultNativeTemplate = it),
                                AppSettings.serializer()
                            )
                        },
                        value = settings?.defaultNativeTemplate ?: "blank"
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Paper format selection
                Row {
                    Text(text = "Paper Format")
                    Spacer(Modifier.width(10.dp))
                    SelectMenu(
                        options = PaperFormat.values().map { it to it.displayName },
                        onChange = {
                            kv.setKv(
                                "APP_SETTINGS",
                                settings!!.copy(paperFormat = it),
                                AppSettings.serializer()
                            )
                        },
                        value = settings?.paperFormat ?: PaperFormat.A4
                    )
                }
                Spacer(Modifier.height(10.dp))

                // Device PPI input
                Row {
                    Text(text = "Device PPI")
                    Spacer(Modifier.width(10.dp))
                    val ppiOptions = listOf(72, 96, 150, 300, 600).map { it to "$it PPI" }
                    SelectMenu(
                        options = ppiOptions,
                        onChange = {
                            kv.setKv(
                                "APP_SETTINGS",
                                settings!!.copy(devicePpi = it),
                                AppSettings.serializer()
                            )
                        },
                        value = settings?.devicePpi ?: DEFAULT_PPI
                    )
                }
                Spacer(Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Debug Mode (show changed area)")
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = settings?.debugMode ?: false,
                        onCheckedChange = { isChecked ->
                            kv.setKv(
                                "APP_SETTINGS",
                                settings!!.copy(debugMode = isChecked),
                                AppSettings.serializer()
                            )
                        }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Use Onyx NeoTools (may cause crashes)")
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = settings?.neoTools ?: false,
                        onCheckedChange = { isChecked ->
                            kv.setKv(
                                "APP_SETTINGS",
                                settings!!.copy(neoTools = isChecked),
                                AppSettings.serializer()
                            )
                            // it is workaround for now
                            NeoTools = isChecked
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Double Tap Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(doubleTapAction = it) },
                    default = AppSettings.defaultDoubleTapAction,
                    override = { doubleTapAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Two Finger Tap Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(twoFingerTapAction = it) },
                    default = AppSettings.defaultTwoFingerTapAction,
                    override = { twoFingerTapAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Swipe Left Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(swipeLeftAction = it) },
                    default = AppSettings.defaultSwipeLeftAction,
                    override = { swipeLeftAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Swipe Right Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(swipeRightAction = it) },
                    default = AppSettings.defaultSwipeRightAction,
                    override = { swipeRightAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Two Finger Swipe Left Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(twoFingerSwipeLeftAction = it) },
                    default = AppSettings.defaultTwoFingerSwipeLeftAction,
                    override = { twoFingerSwipeLeftAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Two Finger Swipe Right Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(twoFingerSwipeRightAction = it) },
                    default = AppSettings.defaultTwoFingerSwipeRightAction,
                    override = { twoFingerSwipeRightAction }
                )
                Spacer(Modifier.height(10.dp))

                if (!isLatestVersion) {
                    Text(
                        text = "It seems a new version of Notable is available on github.",
                        fontStyle = FontStyle.Italic
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "See release in browser",
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.noRippleClickable {
                            val urlIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/ethran/notable/releases")
                            )
                            context.startActivity(urlIntent)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        text = "Check for newer version",
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.noRippleClickable {
                            thread {
                                isLatestVersion = isLatestVersion(context, true)
                                if (isLatestVersion)
                                    CoroutineScope(Dispatchers.Default).launch {
                                        SnackState.globalSnackFlow.emit(
                                            SnackConf(
                                                text = "You are on latest version.",
                                                duration = 1000,
                                            )
                                        )
                                    }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GestureSelectorRow(
    title: String,
    kv: KvProxy,
    settings: AppSettings?,
    update: AppSettings.(AppSettings.GestureAction?) -> AppSettings,
    default: AppSettings.GestureAction,
    override: AppSettings.() -> AppSettings.GestureAction?,
) {
    Row {
        Text(text = title)
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                null to "None",
                AppSettings.GestureAction.Undo to "Undo",
                AppSettings.GestureAction.Redo to "Redo",
                AppSettings.GestureAction.PreviousPage to "Previous Page",
                AppSettings.GestureAction.NextPage to "Next Page",
                AppSettings.GestureAction.ChangeTool to "Toggle Pen / Eraser",
                AppSettings.GestureAction.ToggleZen to "Toggle Zen Mode",
            ),
            value = if (settings != null) settings.override() else default,
            onChange = {
                if (settings != null) {
                    kv.setKv(
                        "APP_SETTINGS",
                        settings.update(it),
                        AppSettings.serializer(),
                    )
                }
            },
        )
    }
}