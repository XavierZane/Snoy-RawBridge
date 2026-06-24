package com.rawbridge.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rawbridge.backend.TransferBackend
import com.rawbridge.backend.config.UsbModePreference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun RawBridgeApp(
    transferBackend: TransferBackend,
    uiPreferencesRepository: RawBridgeUiPreferencesRepository,
    viewModel: RawBridgeViewModel = viewModel(
        factory = RawBridgeViewModel.factory(transferBackend, uiPreferencesRepository),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val showSecondaryTopBar = uiState.currentScreen == RawBridgeScreen.Setup &&
        uiState.setupDestination == SetupDestination.About

    LaunchedSnackBar(uiState.snackbarMessage, snackbarHostState, viewModel::consumeSnackbar)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (showSecondaryTopBar) {
                RawBridgeTopBar(
                    title = uiState.topBarSupportingText,
                    onBack = viewModel::closeSetupAbout,
                )
            }
        },
        bottomBar = {
            RawBridgeBottomBar(
                currentScreen = uiState.currentScreen,
                onScreenSelected = viewModel::selectScreen,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.statusBars,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(innerPadding),
        ) {
            Crossfade(
                targetState = uiState.currentScreen,
                label = "rawbridge-screen",
            ) { screen ->
                when (screen) {
                    RawBridgeScreen.Home -> HomeScreen(
                        uiState = uiState,
                        onRunTransfer = viewModel::runImportSelected,
                        onStopTransfer = viewModel::stopReceiver,
                        onCaptureFilterSelected = viewModel::selectCaptureFilter,
                        onFocusedCaptureChanged = viewModel::focusCapture,
                        onCaptureSelectionToggled = viewModel::toggleCaptureSelection,
                        onSelectVisibleCaptures = viewModel::selectAllVisibleCaptures,
                        onClearSelection = viewModel::clearSelectedCaptures,
                        onToggleMultiSelect = viewModel::toggleMultiSelectMode,
                    )

                    RawBridgeScreen.Setup -> SetupScreen(
                        uiState = uiState,
                        onUsbModePreferenceChanged = viewModel::setUsbModePreference,
                        onThemeModeChanged = viewModel::setThemeMode,
                        onDirectoryChanged = viewModel::updateDirectory,
                        onAutoDateFolderChanged = viewModel::setAutoDateFolder,
                        onSplitStorageChanged = viewModel::setSplitStorage,
                        onClearPreviewCacheOnDisconnectChanged = viewModel::setClearPreviewCacheOnDisconnect,
                        onSave = viewModel::saveConfiguration,
                        onTestConnection = viewModel::runConnectionCheck,
                        onRefreshCatalog = viewModel::refreshCatalog,
                        onOpenAbout = viewModel::openSetupAbout,
                        onBackHome = { viewModel.selectScreen(RawBridgeScreen.Home) },
                    )

                    RawBridgeScreen.History -> HistoryScreen(
                        uiState = uiState,
                        onFilterSelected = viewModel::selectHistoryFilter,
                        onRecordExpanded = viewModel::toggleRecordExpanded,
                        onClearHistory = viewModel::clearHistory,
                    )
                }
            }

            ConnectionNoticeHost(
                notice = uiState.connectionNotice,
                onConsumed = viewModel::consumeConnectionNotice,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun LaunchedSnackBar(
    message: String?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onConsumed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawBridgeTopBar(
    title: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "\u8fd4\u56de",
                )
            }
        },
    )
}

@Composable
private fun ConnectionNoticeHost(
    notice: ConnectionNotice?,
    onConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notice == null) {
        return
    }

    LaunchedEffect(notice.id) {
        delay(2500)
        onConsumed()
    }

    val containerColor = when (notice.kind) {
        ConnectionNoticeKind.Connected -> Color(0xFF2E7D32)
        ConnectionNoticeKind.Disconnected -> Color(0xFFC62828)
    }
    val icon = when (notice.kind) {
        ConnectionNoticeKind.Connected -> Icons.Outlined.CheckCircleOutline
        ConnectionNoticeKind.Disconnected -> Icons.Outlined.ErrorOutline
    }

    Surface(
        modifier = modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = Color.White,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
            )
            Text(
                text = notice.message,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RawBridgeBottomBar(
    currentScreen: RawBridgeScreen,
    onScreenSelected: (RawBridgeScreen) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.height(78.dp),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        RawBridgeScreen.entries.forEach { screen ->
            NavigationBarItem(
                selected = currentScreen == screen,
                onClick = { onScreenSelected(screen) },
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = {
                    Text(
                        text = screen.label,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    uiState: RawBridgeUiState,
    onRunTransfer: () -> Unit,
    onStopTransfer: () -> Unit,
    onCaptureFilterSelected: (CapturePickerFilter) -> Unit,
    onFocusedCaptureChanged: (String) -> Unit,
    onCaptureSelectionToggled: (String) -> Unit,
    onSelectVisibleCaptures: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleMultiSelect: () -> Unit,
) {
    val transferLocked = uiState.activeTransfer != null

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.filteredCaptureLibrary.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp),
            ) {
                GalleryToolbar(
                    uiState = uiState,
                    transferLocked = transferLocked,
                    onCaptureFilterSelected = onCaptureFilterSelected,
                    onSelectVisibleCaptures = onSelectVisibleCaptures,
                    onToggleMultiSelect = onToggleMultiSelect,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 154.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeGalleryEmptyState(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 154.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GalleryToolbar(
                        uiState = uiState,
                        transferLocked = transferLocked,
                        onCaptureFilterSelected = onCaptureFilterSelected,
                        onSelectVisibleCaptures = onSelectVisibleCaptures,
                        onToggleMultiSelect = onToggleMultiSelect,
                    )
                }

                items(uiState.filteredCaptureLibrary, key = { it.id }) { capture ->
                    GalleryCaptureCard(
                        capture = capture,
                        selected = capture.id in uiState.selectedCaptureIds,
                        focused = uiState.focusedCapture?.id == capture.id && !uiState.isMultiSelectMode,
                        transferLocked = transferLocked,
                        onClick = {
                            if (uiState.isMultiSelectMode) {
                                onCaptureSelectionToggled(capture.id)
                            } else {
                                onFocusedCaptureChanged(capture.id)
                            }
                        },
                    )
                }
            }
        }

        HomeTransferDock(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            uiState = uiState,
            onClearSelection = onClearSelection,
            onRunTransfer = onRunTransfer,
            onStopTransfer = onStopTransfer,
        )
    }
}

@Composable
private fun GalleryToolbar(
    uiState: RawBridgeUiState,
    transferLocked: Boolean,
    onCaptureFilterSelected: (CapturePickerFilter) -> Unit,
    onSelectVisibleCaptures: () -> Unit,
    onToggleMultiSelect: () -> Unit,
) {
    val toolbarFilters = buildList {
        add(CapturePickerFilter.All)
        add(CapturePickerFilter.Raw)
        add(CapturePickerFilter.Jpeg)
        if (uiState.shouldShowSelectedFilter) {
            add(CapturePickerFilter.Selected)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(toolbarFilters, key = { it.name }) { filter ->
                    FilterChip(
                        modifier = Modifier.height(34.dp),
                        selected = uiState.captureFilter == filter,
                        onClick = { onCaptureFilterSelected(filter) },
                        label = {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        enabled = !transferLocked,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.06f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.isMultiSelectMode) {
                            IconButton(
                                onClick = onSelectVisibleCaptures,
                                enabled = !transferLocked && uiState.filteredCaptureLibrary.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = if (uiState.allVisibleCapturesSelected) {
                                        Icons.Outlined.CheckBox
                                    } else {
                                        Icons.Outlined.DoneAll
                                    },
                                    contentDescription = if (uiState.allVisibleCapturesSelected) {
                                        "\u53d6\u6d88\u5f53\u524d\u5168\u9009"
                                    } else {
                                        "\u5168\u9009\u5f53\u524d"
                                    },
                                )
                            }
                            IconButton(
                                onClick = onToggleMultiSelect,
                                enabled = !transferLocked,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Done,
                                    contentDescription = "\u5b8c\u6210\u591a\u9009",
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onToggleMultiSelect,
                                enabled = !transferLocked,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = "\u8fdb\u5165\u591a\u9009",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeGalleryEmptyState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "\u6682\u65e0\u76f8\u673a\u56fe\u7247",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "\u8fde\u63a5\u76f8\u673a\u540e\uff0c\u8fd9\u91cc\u4f1a\u663e\u793a RAW / JPEG \u7f29\u7565\u56fe\u3002",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GalleryCaptureCard(
    capture: CapturePreviewItem,
    selected: Boolean,
    focused: Boolean,
    transferLocked: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        focused -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !transferLocked, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.88f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(capture.previewBrush()),
            ) {
                CapturePreviewImage(capture = capture)

                MiniPill(
                    modifier = Modifier.padding(6.dp),
                    text = capture.fileType.label,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )

                capture.badgeText(selected)?.let { badgeText ->
                    MiniPill(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        text = badgeText,
                        containerColor = capture.badgeContainerColor(selected),
                        contentColor = capture.badgeContentColor(selected),
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = capture.shotTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = capture.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${capture.fileType.label} / ${capture.sizeLabel} / ${capture.capturedAt.format(timeFormatter)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeTransferDock(
    modifier: Modifier = Modifier,
    uiState: RawBridgeUiState,
    onClearSelection: () -> Unit,
    onRunTransfer: () -> Unit,
    onStopTransfer: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
        ),
    ) {
        if (uiState.activeTransfer != null) {
            ActiveTransferDock(
                transfer = uiState.activeTransfer,
                onStopTransfer = onStopTransfer,
            )
        } else {
            SelectionTransferDock(
                uiState = uiState,
                onClearSelection = onClearSelection,
                onRunTransfer = onRunTransfer,
            )
        }
    }
}

@Composable
private fun SelectionTransferDock(
    uiState: RawBridgeUiState,
    onClearSelection: () -> Unit,
    onRunTransfer: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (uiState.hasSelectedCaptures) {
                            "\u5df2\u9009 ${uiState.selectedCaptureCount} \u5f20"
                        } else {
                            "\u672a\u9009\u62e9\u56fe\u7247"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (uiState.hasSelectedCaptures) {
                        TextButton(
                            onClick = onClearSelection,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("\u6e05\u7a7a")
                        }
                    }
                }
                Text(
                    text = if (uiState.hasSelectedCaptures) {
                        "\u9884\u8ba1\u5bfc\u5165 ${uiState.selectedPayloadLabel}"
                    } else if (uiState.isConfigured) {
                        "\u9009\u62e9\u56fe\u7247\u540e\u53ef\u5f00\u59cb\u5bfc\u5165"
                    } else {
                        "\u8bf7\u5148\u5b8c\u6210\u6709\u7ebf\u914d\u7f6e"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniPill(
                text = if (uiState.isConfigured) "\u5df2\u914d\u7f6e" else "\u5f85\u914d\u7f6e",
                containerColor = if (uiState.isConfigured) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (uiState.isConfigured) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }

        Button(
            onClick = onRunTransfer,
            enabled = uiState.hasSelectedCaptures,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 11.dp),
        ) {
            Text("\u5bfc\u5165\u6240\u9009")
        }
    }
}

@Composable
private fun ActiveTransferDock(
    transfer: ActiveTransfer,
    onStopTransfer: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "\u6b63\u5728\u5bfc\u5165 ${transfer.receivedCount}/${transfer.targetCount}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = transfer.currentFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MiniPill(
                text = "${transfer.successCount} \u6210\u529f / ${transfer.failedCount} \u5931\u8d25",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        LinearProgressIndicator(
            progress = { transfer.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
        )

        Text(
            text = "${(transfer.progress * 100f).toInt()}% / \u603b\u5927\u5c0f ${transfer.totalSizeLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onStopTransfer,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            contentPadding = PaddingValues(vertical = 11.dp),
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("\u505c\u6b62\u5bfc\u5165")
        }
    }
}

@Composable
private fun HomeStatusCard(
    uiState: RawBridgeUiState,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = when {
                            uiState.activeTransfer != null -> "\u5bfc\u5165\u8fdb\u884c\u4e2d"
                            uiState.serviceState == ReceiverServiceState.Ready -> "\u5df2\u8fde\u63a5\u76f8\u673a"
                            uiState.isConfigured -> "\u7b49\u5f85\u8fde\u63a5\u76f8\u673a"
                            else -> "\u5c1a\u672a\u5b8c\u6210\u914d\u7f6e"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            uiState.activeTransfer != null -> "\u5f53\u524d\u6b63\u5728\u4ece\u76f8\u673a\u5bfc\u5165\u539f\u56fe\uff0c\u53ef\u5728\u5e95\u90e8\u67e5\u770b\u8fdb\u5ea6\u3002"
                            uiState.serviceState == ReceiverServiceState.Ready -> "\u5df2\u8fde\u63a5\uff0c\u53ef\u6d4f\u89c8\u76f8\u673a\u4e2d\u7684 RAW / JPEG \u7f29\u7565\u56fe\u5e76\u6309\u9700\u5bfc\u5165\u3002"
                            uiState.isConfigured -> "\u914d\u7f6e\u5df2\u4fdd\u5b58\uff0c\u8fde\u63a5\u76f8\u673a\u540e\u4f1a\u81ea\u52a8\u8fdb\u5165\u53ef\u6d4f\u89c8\u72b6\u6001\u3002"
                            else -> "\u524d\u5f80 USB \u914d\u7f6e\u9875\u5b8c\u6210\u9996\u6b21\u8fde\u63a5\u8bbe\u7f6e\u3002"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ServiceStateChip(serviceState = uiState.serviceState)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "\u8bbe\u5907",
                    value = uiState.config.connectedDeviceName ?: "\u672a\u68c0\u6d4b\u5230",
                    icon = Icons.Outlined.Lan,
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "\u5df2\u9009",
                    value = uiState.selectedCaptureCount.toString(),
                    icon = Icons.Outlined.PhotoLibrary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    uiState.activeTransfer != null -> {
                        Button(onClick = onStopReceiver, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u505c\u6b62")
                        }
                        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.History, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u8bb0\u5f55")
                        }
                    }

                    !uiState.isConfigured -> {
                        Button(onClick = onOpenSetup, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u53bb\u914d\u7f6e")
                        }
                        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.History, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u8bb0\u5f55")
                        }
                    }

                    uiState.serviceState == ReceiverServiceState.Ready -> {
                        Button(onClick = onStopReceiver, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u505c\u6b62")
                        }
                        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u914d\u7f6e")
                        }
                    }

                    else -> {
                        Button(onClick = onStartReceiver, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u5f00\u59cb")
                        }
                        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("\u914d\u7f6e")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureWorkspaceCard(
    uiState: RawBridgeUiState,
    onCaptureFilterSelected: (CapturePickerFilter) -> Unit,
    onFocusedCaptureChanged: (String) -> Unit,
    onCaptureSelectionToggled: (String) -> Unit,
    onSelectVisibleCaptures: () -> Unit,
    onClearSelection: () -> Unit,
    onRunTransfer: () -> Unit,
) {
    val focusedCapture = uiState.focusedCapture
    val transferLocked = uiState.activeTransfer != null

    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "\u76f8\u673a\u56fe\u5e93",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "RAW \u4e0e JPEG \u4ee5\u7f29\u7565\u56fe\u5f62\u5f0f\u6d4f\u89c8\uff0c\u539f\u6587\u4ef6\u4ec5\u5728\u5bfc\u5165\u65f6\u62c9\u53d6\u3002",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MiniPill(
                    text = "${uiState.selectedCaptureCount} \u5f20",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(CapturePickerFilter.entries) { filter ->
                    FilterChip(
                        selected = uiState.captureFilter == filter,
                        onClick = { onCaptureFilterSelected(filter) },
                        label = { Text(filter.label) },
                        enabled = !transferLocked,
                    )
                }
            }

            if (uiState.filteredCaptureLibrary.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "\u5f53\u524d\u7b5b\u9009\u4e0b\u65e0\u56fe\u7247",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "\u53ef\u5207\u6362\u7b5b\u9009\u6761\u4ef6\uff0c\u6216\u5728\u914d\u7f6e\u9875\u91cd\u65b0\u626b\u63cf\u76f8\u673a\u3002",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.filteredCaptureLibrary, key = { it.id }) { capture ->
                        CaptureStripCard(
                            capture = capture,
                            focused = focusedCapture?.id == capture.id,
                            selected = capture.id in uiState.selectedCaptureIds,
                            enabled = !transferLocked,
                            onClick = { onFocusedCaptureChanged(capture.id) },
                        )
                    }
                }

                focusedCapture?.let { capture ->
                    FocusedCapturePanel(
                        capture = capture,
                        selected = capture.id in uiState.selectedCaptureIds,
                        transferLocked = transferLocked,
                        onToggleSelection = { onCaptureSelectionToggled(capture.id) },
                    )
                }
            }

            HorizontalDivider()

            SelectionSummaryBlock(
                uiState = uiState,
                transferLocked = transferLocked,
                onRunTransfer = onRunTransfer,
                onSelectVisibleCaptures = onSelectVisibleCaptures,
                onClearSelection = onClearSelection,
            )
        }
    }
}

@Composable
private fun CaptureStripCard(
    capture: CapturePreviewItem,
    focused: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    }

    Surface(
        modifier = Modifier
            .width(156.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(capture.previewBrush()),
            ) {
                CapturePreviewImage(capture = capture)

                MiniPill(
                    modifier = Modifier.padding(10.dp),
                    text = capture.fileType.label,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )

                capture.badgeText(selected)?.let { badgeText ->
                    MiniPill(
                        modifier = Modifier.align(Alignment.TopEnd),
                        text = badgeText,
                        containerColor = capture.badgeContainerColor(selected),
                        contentColor = capture.badgeContentColor(selected),
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = capture.shotTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = capture.previewModeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = capture.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${capture.sizeLabel} ? ${capture.capturedAt.format(timeFormatter)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FocusedCapturePanel(
    capture: CapturePreviewItem,
    selected: Boolean,
    transferLocked: Boolean,
    onToggleSelection: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(224.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(capture.previewBrush()),
            ) {
                CapturePreviewImage(capture = capture)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MiniPill(
                        text = capture.fileType.label,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    capture.badgeText(selected)?.let { badgeText ->
                        MiniPill(
                            text = badgeText,
                            containerColor = capture.badgeContainerColor(selected),
                            contentColor = capture.badgeContentColor(selected),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = capture.shotTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = capture.shotNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = capture.fileName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${capture.fileType.label} ? ${capture.sizeLabel} ? ${capture.capturedAt.format(fullFormatter)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    MiniPill(
                        text = capture.previewModeLabel,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                item {
                    MiniPill(
                        text = capture.lensLabel,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                item {
                    MiniPill(
                        text = "\u5b58\u50a8 ${capture.storageHint}",
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = onToggleSelection,
                enabled = !transferLocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (selected) "\u53d6\u6d88\u9009\u4e2d" else "\u9009\u4e2d\u5f53\u524d")
            }
        }
    }
}

@Composable
private fun SelectionSummaryBlock(
    uiState: RawBridgeUiState,
    transferLocked: Boolean,
    onRunTransfer: () -> Unit,
    onSelectVisibleCaptures: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "\u5df2\u9009\u6982\u89c8",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "\u5df2\u9009\u6570",
                value = uiState.selectedCaptureCount.toString(),
                icon = Icons.Outlined.CheckCircleOutline,
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "\u9884\u4f30\u5927\u5c0f",
                value = uiState.selectedPayloadLabel,
                icon = Icons.Outlined.PhotoLibrary,
            )
        }

        if (uiState.selectedCaptures.isEmpty()) {
            Text(
                text = "\u9009\u4e2d\u9700\u8981\u5bfc\u5165\u7684 RAW \u6216 JPEG\uff0c\u4e0b\u65b9\u4f1a\u6309\u5b9e\u9645\u9009\u4e2d\u5217\u51fa\u3002",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            uiState.selectedCaptures.take(3).forEach { capture ->
                ValueRow(
                    label = capture.fileName,
                    value = "${capture.fileType.label} / ${capture.sizeLabel} / ${capture.previewModeLabel}",
                    icon = Icons.Outlined.PhotoLibrary,
                )
            }
            if (uiState.selectedCaptureCount > 3) {
                Text(
                    text = "\u53e6\u6709 ${uiState.selectedCaptureCount - 3} \u5f20\u56fe\u7247\u5df2\u9009\u4e2d",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onRunTransfer,
                enabled = !transferLocked && uiState.selectedCaptureCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (uiState.isConfigured) "\u5f00\u59cb\u5bfc\u5165" else "\u5148\u53bb\u914d\u7f6e")
            }
            OutlinedButton(
                onClick = onClearSelection,
                enabled = !transferLocked && uiState.selectedCaptureCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text("\u6e05\u7a7a\u5df2\u9009")
            }
        }

        OutlinedButton(
            onClick = onSelectVisibleCaptures,
            enabled = !transferLocked && uiState.filteredCaptureLibrary.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("\u5168\u9009\u5f53\u524d")
        }

        Text(
            text = when {
                transferLocked -> "\u5bfc\u5165\u8fdb\u884c\u4e2d\uff0c\u6682\u65f6\u65e0\u6cd5\u66f4\u6539\u9009\u62e9\u3002"
                !uiState.isConfigured -> "\u8bf7\u5148\u5b8c\u6210 USB \u914d\u7f6e\uff0c\u7136\u540e\u518d\u5f00\u59cb\u5bfc\u5165\u3002"
                uiState.serviceState == ReceiverServiceState.Stopped -> "\u8bf7\u5148\u8fde\u63a5\u76f8\u673a\u5e76\u8fdb\u5165\u53ef\u6d4f\u89c8\u72b6\u6001\u3002"
                else -> "\u53ef\u76f4\u63a5\u4ece\u5df2\u9009\u5217\u8868\u53d1\u8d77\u539f\u56fe\u5bfc\u5165\u3002"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavePolicyCard(config: ConnectionConfig) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "\u4fdd\u5b58\u7b56\u7565",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ValueRow(label = "\u5b58\u50a8\u76ee\u5f55", value = config.saveDirectory, icon = Icons.Outlined.FolderOpen)
            ValueRow(
                label = "\u65e5\u671f\u5f52\u6863",
                value = if (config.autoCreateDateFolder) "\u5f00\u542f" else "\u5173\u95ed",
                icon = Icons.Outlined.Tune,
            )
            ValueRow(
                label = "RAW / JPEG \u5206\u5f00",
                value = if (config.splitRawAndJpeg) "\u5206\u76ee\u5f55" else "\u540c\u76ee\u5f55",
                icon = Icons.Outlined.PhotoLibrary,
            )
        }
    }
}

@Composable
private fun RecentSessionCard(
    session: TransferSessionSummary,
    onOpenHistory: () -> Unit,
) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "\u6700\u8fd1\u4f1a\u8bdd",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "\u5b8c\u6210\u4e8e ${session.finishedAt.format(timeFormatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenHistory) {
                    Text("\u8bb0\u5f55")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "\u6210\u529f",
                    value = session.successCount.toString(),
                    icon = Icons.Outlined.CheckCircleOutline,
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "\u5931\u8d25",
                    value = session.failedCount.toString(),
                    icon = Icons.Outlined.ErrorOutline,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "\u603b\u5927\u5c0f",
                    value = session.totalSizeLabel,
                    icon = Icons.Outlined.PhotoLibrary,
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "\u76ee\u6807\u76ee\u5f55",
                    value = session.destinationLabel,
                    icon = Icons.Outlined.FolderOpen,
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    uiState: RawBridgeUiState,
    onUsbModePreferenceChanged: (UsbModePreference) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onDirectoryChanged: (String) -> Unit,
    onAutoDateFolderChanged: (Boolean) -> Unit,
    onSplitStorageChanged: (Boolean) -> Unit,
    onClearPreviewCacheOnDisconnectChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenAbout: () -> Unit,
    onBackHome: () -> Unit,
) {
    val context = LocalContext.current
    val currentUsbMode = uiState.config.usbModeLabel ?: uiState.config.usbModePreference.name
    val connectedDevice = uiState.config.connectedDeviceName ?: "\u672a\u68c0\u6d4b\u5230\u8bbe\u5907"

    if (uiState.setupDestination == SetupDestination.About) {
        SetupAboutScreen(
            versionLabel = appVersionLabel(context),
            deviceModel = deviceModelLabel(),
            githubUrl = RawBridgeGithubUrl,
            onOpenGithub = { openExternalLink(context, RawBridgeGithubUrl) },
        )
        return
    }

    ScrollScreen {
        Text(
            text = "\u8fde\u63a5\u8bbe\u7f6e",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "\u5f53\u524d\u72b6\u6001",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ServiceStateChip(serviceState = uiState.serviceState)
                }
                ValueRow(
                    label = "\u8bbe\u5907",
                    value = connectedDevice,
                    icon = Icons.Outlined.Lan,
                )
                ValueRow(
                    label = "USB \u6a21\u5f0f",
                    value = currentUsbMode,
                    icon = Icons.Outlined.CheckCircleOutline,
                )
                ValueRow(
                    label = "\u5bfc\u5165\u76ee\u5f55",
                    value = uiState.config.destinationSummary,
                    icon = Icons.Outlined.FolderOpen,
                )
            }
        }

        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = "\u8fde\u63a5\u4e0e\u5b58\u50a8",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UsbModePreference.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.config.usbModePreference == mode,
                            onClick = { onUsbModePreferenceChanged(mode) },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = mode.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.config.saveDirectory,
                    onValueChange = onDirectoryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("\u4fdd\u5b58\u76ee\u5f55") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                ToggleRow(
                    title = "\u6309\u65e5\u671f\u5efa\u76ee\u5f55",
                    checked = uiState.config.autoCreateDateFolder,
                    onCheckedChange = onAutoDateFolderChanged,
                )
                HorizontalDivider()
                ToggleRow(
                    title = "RAW / JPEG \u5206\u5f00\u4fdd\u5b58",
                    checked = uiState.config.splitRawAndJpeg,
                    onCheckedChange = onSplitStorageChanged,
                )
                HorizontalDivider()
                ToggleRow(
                    title = "\u65ad\u5f00\u540e\u6e05\u7406\u7f29\u7565\u56fe\u7f13\u5b58",
                    checked = uiState.config.clearPreviewCacheOnDisconnect,
                    onCheckedChange = onClearPreviewCacheOnDisconnectChanged,
                )
            }
        }

        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = "显示主题",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.themeMode == mode,
                            onClick = { onThemeModeChanged(mode) },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    text = "更多",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                SettingsActionRow(
                    title = "关于",
                    icon = Icons.Outlined.Info,
                    onClick = onOpenAbout,
                )
                HorizontalDivider()
                SettingsActionRow(
                    title = "使用文档",
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    onClick = { openExternalLink(context, RawBridgeDocsUrl) },
                    trailingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                )
                HorizontalDivider()
                SettingsActionRow(
                    title = "分享",
                    icon = Icons.Outlined.Share,
                    onClick = { sharePlainText(context, RawBridgeShareText) },
                )
            }
        }

        ConnectionCheckBanner(state = uiState.connectionCheckState)

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Text("\u4fdd\u5b58")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = onTestConnection,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 11.dp),
            ) {
                Text("\u68c0\u6d4b\u8fde\u63a5")
            }
            OutlinedButton(
                onClick = onRefreshCatalog,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 11.dp),
            ) {
                Text("\u91cd\u65b0\u626b\u63cf")
            }
        }

        OutlinedButton(
            onClick = onBackHome,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 13.dp),
        ) {
            Text("\u8fd4\u56de\u4e3b\u9875")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SetupAboutScreen(
    versionLabel: String,
    deviceModel: String,
    githubUrl: String,
    onOpenGithub: () -> Unit,
) {
    val context = LocalContext.current
    val appIcon = remember(context) { loadApplicationIcon(context) }

    ScrollScreen {
        SurfaceCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(132.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = "RAWBridge icon",
                                modifier = Modifier.size(94.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.PhotoLibrary,
                                contentDescription = "RAWBridge icon",
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Text(
                    text = "Sony RAWBridge",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = versionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                AboutInfoRow(
                    label = "版本号",
                    value = versionLabel,
                    icon = Icons.Outlined.Settings,
                )
                HorizontalDivider()
                AboutInfoRow(
                    label = "手机型号",
                    value = deviceModel,
                    icon = Icons.Outlined.Lan,
                )
                HorizontalDivider()
                AboutLinkRow(
                    title = "GitHub",
                    supportingText = githubUrl,
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = onOpenGithub,
                    trailingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                )
            }
        }

    }
}

@Composable
private fun HistoryScreen(
    uiState: RawBridgeUiState,
    onFilterSelected: (HistoryFilter) -> Unit,
    onRecordExpanded: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val filteredRecords = uiState.filteredHistory

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 820.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 18.dp + 78.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader(title = "\u5bfc\u5165\u8bb0\u5f55")
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HistoryFilter.entries) { filter ->
                        FilterChip(
                            selected = uiState.historyFilter == filter,
                            onClick = { onFilterSelected(filter) },
                            label = {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        label = "\u8bb0\u5f55\u603b\u6570",
                        value = filteredRecords.size.toString(),
                        icon = Icons.Outlined.History,
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        label = "\u5931\u8d25\u6570",
                        value = filteredRecords.count { it.status == TransferResultStatus.Failed }.toString(),
                        icon = Icons.Outlined.ErrorOutline,
                    )
                }
            }

            if (filteredRecords.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "\u6682\u65e0\u5bfc\u5165\u8bb0\u5f55",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                items(filteredRecords, key = { it.id }) { record ->
                    HistoryRecordCard(
                        record = record,
                        expanded = uiState.expandedRecordId == record.id,
                        onClick = { onRecordExpanded(record.id) },
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = onClearHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("\u6e05\u7a7a\u8bb0\u5f55")
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: TransferHistoryRecord,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    SurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .animateContentSize(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = record.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${record.fileType.label} / ${record.sizeLabel} / ${record.transferredAt.format(fullFormatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = record.savedPath,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ResultChip(status = record.status)
            }

            if (expanded) {
                HorizontalDivider()
                ValueRow(label = "\u4fdd\u5b58\u8def\u5f84", value = record.savedPath, icon = Icons.Outlined.FolderOpen)
                record.message?.let {
                    ValueRow(label = "\u63d0\u793a", value = it, icon = Icons.Outlined.ErrorOutline)
                }
            }
        }
    }
}

@Composable
private fun ActiveTransferContent(transfer: ActiveTransfer) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = transfer.currentFileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(transfer.progress * 100f).toInt()}% / ${transfer.receivedCount}/${transfer.targetCount} \u5f20",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "\u8fdb\u5ea6",
                value = "${transfer.receivedCount}/${transfer.targetCount}",
                icon = Icons.Outlined.Sync,
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "\u6210\u529f",
                value = transfer.successCount.toString(),
                icon = Icons.Outlined.CheckCircleOutline,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "\u5931\u8d25",
                value = transfer.failedCount.toString(),
                icon = Icons.Outlined.ErrorOutline,
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "\u603b\u5927\u5c0f",
                value = transfer.totalSizeLabel,
                icon = Icons.Outlined.PhotoLibrary,
            )
        }

        Text(
            text = "\u961f\u5217\u660e\u7ec6",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        transfer.batchItems.forEach { item ->
            QueueRow(item = item)
        }
    }
}

@Composable
private fun ConnectionCheckBanner(state: ConnectionCheckState) {
    when (state) {
        ConnectionCheckState.Idle -> Unit
        ConnectionCheckState.Checking -> SurfaceCard {
            ValueRow(label = "\u68c0\u6d4b", value = "\u6b63\u5728\u6267\u884c USB \u8fde\u63a5\u68c0\u67e5...", icon = Icons.Outlined.Sync)
        }
        is ConnectionCheckState.Result -> {
            val container = if (state.success) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val content = if (state.success) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = container,
                contentColor = content,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (state.success) Icons.Outlined.CheckCircleOutline else Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollScreen(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 18.dp + 78.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: ImageVector,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServiceStateChip(serviceState: ReceiverServiceState) {
    Surface(
        shape = CircleShape,
        color = serviceState.containerColor(),
        contentColor = serviceState.contentColor(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(serviceState.contentColor()),
            )
            Text(
                text = serviceState.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ResultChip(status: TransferResultStatus) {
    val container = if (status == TransferResultStatus.Success) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (status == TransferResultStatus.Success) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supportingText: String? = null,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingIcon?.let { trailing ->
            Icon(
                imageVector = trailing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AboutRowIcon(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supportingText: String? = null,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AboutRowIcon(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!supportingText.isNullOrBlank()) {
                SelectionContainer {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        trailingIcon?.let { trailing ->
            Icon(
                imageVector = trailing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun AboutRowIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CapturePreviewImage(capture: CapturePreviewItem) {
    val model = capture.thumbnailUri ?: capture.contentUri
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = capture.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun StepRow(
    step: String,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun QueueRow(item: TransferBatchItem) {
    val markerColor = when (item.status) {
        TransferItemStatus.Idle -> MaterialTheme.colorScheme.outline
        TransferItemStatus.Pending -> MaterialTheme.colorScheme.outline
        TransferItemStatus.Receiving -> MaterialTheme.colorScheme.primary
        TransferItemStatus.Success -> MaterialTheme.colorScheme.primary
        TransferItemStatus.Failed -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(markerColor),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.fileType.label} / ${item.sizeLabel} / ${item.status.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


private fun currentArchiveDate(): String = LocalDateTime.now().toLocalDate().toString()

private const val RawBridgeGithubUrl = "https://github.com/XavierZane/Snoy-RawBridge"
private const val RawBridgeDocsUrl =
    "https://github.com/XavierZane/Snoy-RawBridge/blob/main/%E4%BD%BF%E7%94%A8%E6%96%87%E6%A1%A3.md"
private const val RawBridgeShareText =
    "Sony RAWBridge：一款面向索尼相机的 Android RAW / JPEG 按需导入工具。作者 GitHub：https://github.com/XavierZane/Snoy-RawBridge"

private fun deviceModelLabel(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    return when {
        manufacturer.isBlank() -> model.ifBlank { "未知设备" }
        model.isBlank() -> manufacturer
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}

private fun appVersionLabel(context: Context): String {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName ?: "0.0.0"
    val versionCode = packageInfo.longVersionCode
    return "v$versionName ($versionCode)"
}

private fun loadApplicationIcon(context: Context): ImageBitmap? {
    return runCatching {
        context.packageManager
            .getApplicationIcon(context.packageName)
            .toImageBitmap()
    }.getOrNull()
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val safeWidth = intrinsicWidth.coerceAtLeast(1)
    val safeHeight = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

private fun openExternalLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun sharePlainText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(intent, "分享 Sony RAWBridge").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}


private fun List<CapturePreviewItem>.filteredBy(
    filter: CapturePickerFilter,
    selectedIds: Set<String>,
): List<CapturePreviewItem> = when (filter) {
    CapturePickerFilter.All -> this
    CapturePickerFilter.Raw -> filter { it.fileType == CaptureFileType.Raw }
    CapturePickerFilter.Jpeg -> filter { it.fileType == CaptureFileType.Jpeg }
    CapturePickerFilter.Selected -> filter { it.id in selectedIds }
}

private fun List<CapturePreviewItem>.markTransferState(
    captureId: String,
    status: TransferItemStatus,
): List<CapturePreviewItem> {
    return map { capture ->
        if (capture.id == captureId) {
            capture.copy(transferState = status)
        } else {
            capture
        }
    }
}

private fun formatMegabytes(value: Double): String {
    return when {
        value == 0.0 -> "0"
        value < 100 -> String.format("%.1f", value)
        else -> value.toInt().toString()
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val fullFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

data class RawBridgeUiState(
    val currentScreen: RawBridgeScreen = RawBridgeScreen.Home,
    val isConfigured: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val setupDestination: SetupDestination = SetupDestination.Main,
    val isMultiSelectMode: Boolean = false,
    val config: ConnectionConfig = ConnectionConfig(),
    val serviceState: ReceiverServiceState = ReceiverServiceState.Stopped,
    val connectionCheckState: ConnectionCheckState = ConnectionCheckState.Idle,
    val captureLibrary: List<CapturePreviewItem> = emptyList(),
    val captureFilter: CapturePickerFilter = CapturePickerFilter.All,
    val selectedCaptureIds: Set<String> = emptySet(),
    val focusedCaptureId: String? = null,
    val activeTransfer: ActiveTransfer? = null,
    val recentSession: TransferSessionSummary? = null,
    val historyRecords: List<TransferHistoryRecord> = emptyList(),
    val historyFilter: HistoryFilter = HistoryFilter.All,
    val expandedRecordId: String? = null,
    val connectionNotice: ConnectionNotice? = null,
    val snackbarMessage: String? = null,
) {
    val filteredCaptureLibrary: List<CapturePreviewItem>
        get() = captureLibrary.filteredBy(captureFilter, selectedCaptureIds)

    val focusedCapture: CapturePreviewItem?
        get() = captureLibrary.firstOrNull { it.id == focusedCaptureId }
            ?: filteredCaptureLibrary.firstOrNull()
            ?: captureLibrary.firstOrNull()

    val selectedCaptures: List<CapturePreviewItem>
        get() = captureLibrary.filter { it.id in selectedCaptureIds }

    val hasSelectedCaptures: Boolean
        get() = selectedCaptures.isNotEmpty()

    val selectedCaptureCount: Int
        get() = selectedCaptures.size

    val shouldShowSelectedFilter: Boolean
        get() = isMultiSelectMode

    val allVisibleCapturesSelected: Boolean
        get() = filteredCaptureLibrary.isNotEmpty() &&
            filteredCaptureLibrary.all { it.id in selectedCaptureIds }

    val selectedPayloadLabel: String
        get() = "${formatMegabytes(selectedCaptures.sumOf { it.sizeInMegabytes })} MB"

    val filteredHistory: List<TransferHistoryRecord>
        get() = when (historyFilter) {
            HistoryFilter.All -> historyRecords
            HistoryFilter.Success -> historyRecords.filter { it.status == TransferResultStatus.Success }
            HistoryFilter.Failed -> historyRecords.filter { it.status == TransferResultStatus.Failed }
            HistoryFilter.Raw -> historyRecords.filter { it.fileType == CaptureFileType.Raw }
            HistoryFilter.Jpeg -> historyRecords.filter { it.fileType == CaptureFileType.Jpeg }
        }

    val topBarSupportingText: String
        get() = when {
            currentScreen == RawBridgeScreen.Setup && setupDestination == SetupDestination.About -> "\u5173\u4e8e"
            else -> ""
        }
}

data class ConnectionNotice(
    val id: Long,
    val kind: ConnectionNoticeKind,
    val message: String,
)

enum class ConnectionNoticeKind {
    Connected,
    Disconnected,
}

data class ConnectionConfig(
    val connectedDeviceName: String? = null,
    val usbModeLabel: String? = null,
    val usbModePreference: UsbModePreference = UsbModePreference.MTP,
    val saveDirectory: String = "Pictures/RAWBridge",
    val autoCreateDateFolder: Boolean = true,
    val splitRawAndJpeg: Boolean = true,
    val clearPreviewCacheOnDisconnect: Boolean = true,
)

private val ConnectionConfig.destinationSummary: String
    get() = if (autoCreateDateFolder) {
        "$saveDirectory/${currentArchiveDate()}"
    } else {
        saveDirectory
    }

data class CapturePreviewItem(
    val id: String,
    val fileName: String,
    val fileType: CaptureFileType,
    val sizeLabel: String,
    val sizeInMegabytes: Double,
    val shotTitle: String,
    val shotNote: String,
    val lensLabel: String,
    val capturedAt: LocalDateTime,
    val previewMood: CapturePreviewMood,
    val contentUri: String? = null,
    val thumbnailUri: String? = null,
    val transferState: TransferItemStatus = TransferItemStatus.Idle,
    val shouldFail: Boolean = false,
) {
    val previewModeLabel: String
        get() = if (fileType == CaptureFileType.Raw) "RAW \u5185\u5d4c\u9884\u89c8" else "JPEG \u7f29\u7565\u56fe"

    val storageHint: String
        get() = if (fileType == CaptureFileType.Raw) "RAW" else "JPEG"

    fun badgeText(selected: Boolean): String? {
        return when {
            transferState == TransferItemStatus.Receiving -> "\u5bfc\u5165\u4e2d"
            transferState == TransferItemStatus.Pending -> "\u6392\u961f"
            transferState == TransferItemStatus.Success -> "\u5b8c\u6210"
            transferState == TransferItemStatus.Failed -> "\u5931\u8d25"
            selected -> "\u5df2\u9009"
            else -> null
        }
    }
}

data class TransferSessionSummary(
    val finishedAt: LocalDateTime,
    val successCount: Int,
    val failedCount: Int,
    val totalSizeLabel: String,
    val destinationLabel: String,
)

data class ActiveTransfer(
    val currentFileName: String,
    val progress: Float,
    val receivedCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val totalSizeLabel: String,
    val targetCount: Int,
    val batchItems: List<TransferBatchItem>,
)

data class TransferBatchItem(
    val captureId: String,
    val fileName: String,
    val fileType: CaptureFileType,
    val sizeLabel: String,
    val status: TransferItemStatus,
)

data class TransferHistoryRecord(
    val id: String,
    val fileName: String,
    val fileType: CaptureFileType,
    val sizeLabel: String,
    val status: TransferResultStatus,
    val savedPath: String,
    val transferredAt: LocalDateTime,
    val message: String? = null,
)

enum class RawBridgeScreen(
    val label: String,
    val supportingText: String,
    val icon: ImageVector,
) {
    Home("\u4e3b\u9875", "\u56fe\u5e93", Icons.Outlined.Home),
    Setup("\u914d\u7f6e", "USB \u8fde\u63a5", Icons.Outlined.Settings),
    History("\u8bb0\u5f55", "\u5bfc\u5165\u5386\u53f2", Icons.Outlined.History),
}

enum class SetupDestination {
    Main,
    About,
}

enum class ReceiverServiceState(val label: String) {
    Stopped("\u672a\u8fde\u63a5"),
    Ready("\u5df2\u5c31\u7eea"),
    Receiving("\u5bfc\u5165\u4e2d"),
}

enum class CapturePickerFilter(val label: String) {
    All("\u5168\u90e8"),
    Raw("RAW"),
    Jpeg("JPEG"),
    Selected("\u5df2\u9009"),
}

enum class TransferItemStatus(val label: String) {
    Idle("\u5f85\u547d"),
    Pending("\u6392\u961f"),
    Receiving("\u4f20\u8f93\u4e2d"),
    Success("\u5df2\u5b8c\u6210"),
    Failed("\u5931\u8d25"),
}

enum class TransferResultStatus(val label: String) {
    Success("\u6210\u529f"),
    Failed("\u5931\u8d25"),
}

enum class CaptureFileType(val label: String) {
    Jpeg("JPEG"),
    Raw("RAW"),
}

enum class HistoryFilter(val label: String) {
    All("\u5168\u90e8"),
    Success("\u6210\u529f"),
    Failed("\u5931\u8d25"),
    Raw("RAW"),
    Jpeg("JPEG"),
}

enum class CapturePreviewMood {
    WarmGlass,
    CoolSky,
    DeepSlate,
    SunsetRail,
    CityNight,
    FreshLeaf,
}

sealed interface ConnectionCheckState {
    val label: String

    data object Idle : ConnectionCheckState {
        override val label: String = "\u5f85\u68c0\u6d4b"
    }

    data object Checking : ConnectionCheckState {
        override val label: String = "\u68c0\u6d4b\u4e2d"
    }

    data class Result(
        val success: Boolean,
        val message: String,
    ) : ConnectionCheckState {
        override val label: String = if (success) "\u6210\u529f" else "\u5931\u8d25"
    }
}

@Composable
private fun ReceiverServiceState.containerColor(): Color {
    return when (this) {
        ReceiverServiceState.Stopped -> MaterialTheme.colorScheme.surfaceVariant
        ReceiverServiceState.Ready -> MaterialTheme.colorScheme.primaryContainer
        ReceiverServiceState.Receiving -> MaterialTheme.colorScheme.secondaryContainer
    }
}

@Composable
private fun ReceiverServiceState.contentColor(): Color {
    return when (this) {
        ReceiverServiceState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        ReceiverServiceState.Ready -> MaterialTheme.colorScheme.onPrimaryContainer
        ReceiverServiceState.Receiving -> MaterialTheme.colorScheme.onSecondaryContainer
    }
}

@Composable
private fun CapturePreviewItem.previewBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return when (previewMood) {
        CapturePreviewMood.WarmGlass -> Brush.linearGradient(
            listOf(
                colors.tertiary.copy(alpha = 0.78f),
                colors.primary.copy(alpha = 0.48f),
                colors.secondaryContainer.copy(alpha = 0.82f),
            ),
        )

        CapturePreviewMood.CoolSky -> Brush.linearGradient(
            listOf(
                colors.primary.copy(alpha = 0.88f),
                colors.secondary.copy(alpha = 0.58f),
                colors.surfaceTint.copy(alpha = 0.26f),
            ),
        )

        CapturePreviewMood.DeepSlate -> Brush.linearGradient(
            listOf(
                colors.secondaryContainer.copy(alpha = 0.92f),
                colors.primary.copy(alpha = 0.52f),
                colors.surfaceTint.copy(alpha = 0.18f),
            ),
        )

        CapturePreviewMood.SunsetRail -> Brush.linearGradient(
            listOf(
                colors.tertiaryContainer.copy(alpha = 0.96f),
                colors.primary.copy(alpha = 0.40f),
                colors.secondary.copy(alpha = 0.34f),
            ),
        )

        CapturePreviewMood.CityNight -> Brush.linearGradient(
            listOf(
                colors.primary.copy(alpha = 0.94f),
                colors.tertiary.copy(alpha = 0.52f),
                colors.secondary.copy(alpha = 0.36f),
            ),
        )

        CapturePreviewMood.FreshLeaf -> Brush.linearGradient(
            listOf(
                colors.secondaryContainer.copy(alpha = 0.98f),
                colors.tertiaryContainer.copy(alpha = 0.92f),
                colors.primary.copy(alpha = 0.24f),
            ),
        )
    }
}

@Composable
private fun CapturePreviewItem.badgeContainerColor(selected: Boolean): Color {
    return when {
        transferState == TransferItemStatus.Success -> MaterialTheme.colorScheme.primaryContainer
        transferState == TransferItemStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        transferState == TransferItemStatus.Pending || transferState == TransferItemStatus.Receiving -> MaterialTheme.colorScheme.surface
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
}

@Composable
private fun CapturePreviewItem.badgeContentColor(selected: Boolean): Color {
    return when {
        transferState == TransferItemStatus.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        transferState == TransferItemStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        transferState == TransferItemStatus.Pending || transferState == TransferItemStatus.Receiving -> MaterialTheme.colorScheme.onSurface
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
}
