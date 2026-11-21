package me.rerere.rikkahub.ui.pages.tavern

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.api.TavernListing
import me.rerere.rikkahub.data.api.TavernMarketDataSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.tavern.TavernImporter
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

private val JSON_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")
private const val MARKET_RESULT_LIMIT = 60

@Composable
fun TavernPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val filesManager: FilesManager = koinInject()
    val httpClient: OkHttpClient = koinInject()
    val market = remember(context, httpClient) {
        TavernMarketDataSource(context.applicationContext, httpClient)
    }
    var importing by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var listings by remember { mutableStateOf<List<TavernListing>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(false) }
    var marketError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun failureToast(exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.tavern_page_import_failed),
            type = ToastType.Error,
        )
    }

    fun saveImportedCard(result: me.rerere.rikkahub.data.tavern.TavernCardImport) {
        scope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants + result.assistant,
                    lorebooks = result.lorebook
                        ?.let { lorebook -> settings.lorebooks + lorebook }
                        ?: settings.lorebooks,
                )
            }
            toaster.show(
                message = context.getString(
                    R.string.tavern_page_imported_assistant,
                    result.assistant.name,
                ),
                type = ToastType.Success,
            )
        }
    }

    fun importCard(uri: Uri) {
        importing = true
        scope.launch {
            try {
                val (jsonText, background) = TavernImporter.readCardPayload(
                    context = context,
                    uri = uri,
                    filesManager = filesManager,
                )
                val result = withContext(Dispatchers.Default) {
                    TavernImporter.readCharacterCard(
                        context = context,
                        jsonText = jsonText,
                        background = background,
                        userName = settingsStore.settingsFlow.value.displaySetting.userNickname
                            .ifBlank { "user" },
                    )
                }
                saveImportedCard(result)
            } catch (exception: Exception) {
                failureToast(exception)
            } finally {
                importing = false
            }
        }
    }

    fun importLorebook(uri: Uri) {
        importing = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { reader -> reader?.readText() }
                } ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                val fallbackName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.tavern_page_imported_lorebook_fallback_name)
                val lorebook = withContext(Dispatchers.Default) {
                    TavernImporter.readLorebook(
                        context = context,
                        jsonText = text,
                        fallbackName = fallbackName,
                    )
                }
                settingsStore.update { settings ->
                    settings.copy(lorebooks = settings.lorebooks + lorebook)
                }
                toaster.show(
                    message = context.getString(R.string.tavern_page_imported_lorebook, lorebook.name),
                    type = ToastType.Success,
                )
            } catch (exception: Exception) {
                failureToast(exception)
            } finally {
                importing = false
            }
        }
    }

    fun importFromMarket(listing: TavernListing) {
        if (downloadingId != null) return
        downloadingId = listing.id
        scope.launch {
            try {
                val bytes = market.downloadCard(listing)
                val (jsonText, background) = TavernImporter.readCardBytes(
                    context = context,
                    bytes = bytes,
                    fileName = listing.id.substringAfterLast('/') + ".png",
                    filesManager = filesManager,
                )
                val result = withContext(Dispatchers.Default) {
                    TavernImporter.readCharacterCard(
                        context = context,
                        jsonText = jsonText,
                        background = background,
                        userName = settingsStore.settingsFlow.value.displaySetting.userNickname
                            .ifBlank { "user" },
                    )
                }
                saveImportedCard(result)
            } catch (exception: Exception) {
                failureToast(exception)
            } finally {
                downloadingId = null
            }
        }
    }

    fun loadMarket(forceRefresh: Boolean) {
        if (marketLoading) return
        marketLoading = true
        marketError = null
        scope.launch {
            try {
                listings = market.loadIndex(forceRefresh)
            } catch (exception: Exception) {
                exception.printStackTrace()
                marketError = exception.message
                    ?: context.getString(R.string.tavern_market_failed_generic)
            } finally {
                marketLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadMarket(false)
    }

    val filteredListings = remember(listings, search) {
        listings.asSequence()
            .filter { it.matches(search) }
            .take(MARKET_RESULT_LIMIT)
            .toList()
    }

    val pngPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importCard(it) } }

    val cardJsonPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importCard(it) } }

    val lorebookPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importLorebook(it) } }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tavern_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.tavern_page_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.tavern_page_section_card)) },
                ) {
                    item(
                        onClick = if (importing) null else ({ pngPicker.launch(arrayOf("image/png")) }),
                        leadingContent = {
                            CardGroupIcon(
                                HugeIcons.File02,
                                containerColor = containerColor,
                                contentColor = contentColor,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.assistant_importer_import_tavern_png)) },
                        supportingContent = { Text(stringResource(R.string.tavern_page_card_desc)) },
                    )
                    item(
                        onClick = if (importing) null else ({ cardJsonPicker.launch(JSON_MIME_TYPES) }),
                        leadingContent = {
                            CardGroupIcon(
                                HugeIcons.File02,
                                containerColor = containerColor,
                                contentColor = contentColor,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.assistant_importer_import_tavern_json)) },
                        supportingContent = { Text(stringResource(R.string.tavern_page_card_desc)) },
                    )
                }
            }

            item {
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.tavern_page_section_lorebook)) },
                ) {
                    item(
                        onClick = if (importing) null else ({ lorebookPicker.launch(JSON_MIME_TYPES) }),
                        leadingContent = {
                            CardGroupIcon(
                                HugeIcons.Book03,
                                containerColor = containerColor,
                                contentColor = contentColor,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.tavern_page_import_lorebook)) },
                        supportingContent = { Text(stringResource(R.string.tavern_page_lorebook_desc)) },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tavern_page_section_market),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { loadMarket(true) },
                        enabled = !marketLoading,
                    ) {
                        Text(stringResource(R.string.tavern_market_refresh))
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.tavern_market_search)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                )
            }

            if (marketLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            marketError?.let { message ->
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tavern_market_failed, message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { loadMarket(true) }) {
                            Text(stringResource(R.string.tavern_market_retry))
                        }
                    }
                }
            }

            if (!marketLoading && marketError == null && filteredListings.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tavern_market_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(filteredListings, key = { it.id }) { listing ->
                TavernMarketItem(
                    listing = listing,
                    downloading = downloadingId == listing.id,
                    enabled = downloadingId == null,
                    onImport = { importFromMarket(listing) },
                )
            }
        }
    }
}

@Composable
private fun TavernMarketItem(
    listing: TavernListing,
    downloading: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = listing.emoji,
                style = MaterialTheme.typography.headlineSmall,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = listing.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (listing.description.isNotBlank()) {
                    Text(
                        text = listing.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (listing.tags.isNotEmpty()) {
                    Text(
                        text = listing.tags.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = onImport,
                enabled = enabled,
            ) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.tavern_market_import))
                }
            }
        }
    }
}
