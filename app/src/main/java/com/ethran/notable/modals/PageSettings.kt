package com.ethran.notable.modals

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.PageView
import com.ethran.notable.components.SelectMenu
import kotlinx.coroutines.launch

@Composable
fun PageSettingsModal(pageView: PageView, onClose: () -> Unit) {
    var pageTemplate by remember { mutableStateOf(pageView.pageFromDb!!.nativeTemplate) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Get the notebook if this page is part of one
    val notebookId = pageView.pageFromDb?.notebookId
    val notebook = if (notebookId != null) {
        AppRepository(context).bookRepository.getById(notebookId)
    } else null

    // State for pagination toggle
    var usePagination by remember {
        mutableStateOf(notebook?.usePagination ?: false)
    }

    Dialog(
        onDismissRequest = { onClose() }
    ) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {
                Text(text = "Page settings")
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {
                Row {
                    Text(text = "Background Template")
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
                            val updatedPage = pageView.pageFromDb!!.copy(nativeTemplate = it)
                            pageView.updatePageSettings(updatedPage)
                            scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                            pageTemplate = pageView.pageFromDb!!.nativeTemplate
                        },
                        value = pageTemplate
                    )
                }
                Spacer(Modifier.height(20.dp))

                // Only show pagination toggle if the page is part of a notebook
                if (notebookId != null && notebook != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Use Pagination (Letter 11x8.5)")
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = usePagination,
                            onCheckedChange = { isChecked ->
                                usePagination = isChecked
                                // Update the notebook setting
                                val updatedNotebook = notebook.copy(usePagination = isChecked)
                                AppRepository(context).bookRepository.update(updatedNotebook)

                                // Update the page view's pagination setting
                                pageView.updatePagination(isChecked)

                                // Refresh the display
                                scope.launch {
                                    DrawCanvas.refreshUi.emit(Unit)
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "Pagination breaks content into letter-sized pages for printing",
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 5.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}