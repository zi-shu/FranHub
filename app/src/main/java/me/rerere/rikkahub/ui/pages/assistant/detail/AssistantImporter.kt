package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.tavern.TavernCardImport
import me.rerere.rikkahub.data.tavern.TavernImporter
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import me.rerere.ui.toast.ToasterState
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    userName: String = "user",
    onImport: (TavernCardImport) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(userName = userName, onImport = onImport)
    }
}

@Composable
private fun SillyTavernImporter(
    userName: String,
    onImport: (TavernCardImport) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val importer: (Uri) -> Unit = { uri ->
        isLoading = true
        scope.launch {
            try {
                val (jsonString, background) = TavernImporter.readCardPayload(
                    context = context,
                    uri = uri,
                    filesManager = filesManager,
                )
                val result = withContext(Dispatchers.Default) {
                    TavernImporter.readCharacterCard(
                        context = context,
                        jsonText = jsonString,
                        background = background,
                        userName = userName,
                    )
                }
                onImport(result)
            } catch (exception: Exception) {
                exception.printStackTrace()
                toaster.show(
                    message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
                    type = ToastType.Error,
                )
            } finally {
                isLoading = false
            }
        }
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(importer) }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(importer) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(
                text = if (isLoading) {
                    stringResource(R.string.assistant_importer_importing)
                } else {
                    stringResource(R.string.assistant_importer_import_tavern_png)
                }
            )
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(
                text = if (isLoading) {
                    stringResource(R.string.assistant_importer_importing)
                } else {
                    stringResource(R.string.assistant_importer_import_tavern_json)
                }
            )
        }
    }
}
