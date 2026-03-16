package com.kutedev.easemusicplayer.widgets.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseSearchField
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.components.dropShadow
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteStorageBrowser
import com.kutedev.easemusicplayer.viewmodels.StorageSearchSectionUiState
import com.kutedev.easemusicplayer.viewmodels.StorageSearchVM
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_backend.StorageEntryType

@Composable
private fun AggregateSearchHero(
    query: String,
    scope: StorageSearchScope,
    supportedStorageCount: Int,
    totalHits: Int,
    onQueryChange: (String) -> Unit,
    onScopeChange: (StorageSearchScope) -> Unit,
    onClearQuery: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .dropShadow(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                offsetX = 0.dp,
                offsetY = 10.dp,
                blurRadius = 24.dp,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(id = R.string.storage_search_home_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    id = if (query.isBlank()) {
                        R.string.storage_search_home_desc_idle
                    } else {
                        R.string.storage_search_home_desc_active
                    },
                    supportedStorageCount,
                    totalHits,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        EaseSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(id = R.string.storage_search_placeholder_home),
            onClear = onClearQuery,
        )
        StorageSearchScopeSelector(
            selectedScope = scope,
            onScopeChange = onScopeChange,
        )
    }
}

@Composable
private fun AggregateSearchEmptyCard(
    title: String,
    desc: String,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = desc,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SearchSectionCard(
    section: StorageSearchSectionUiState,
    onResultClick: (StorageSearchEntry) -> Unit,
    onLocate: (StorageSearchEntry) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .dropShadow(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                offsetX = 0.dp,
                offsetY = 6.dp,
                blurRadius = 16.dp,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
            .padding(16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = section.storage.alias.ifBlank { section.storage.addr },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = section.storage.addr,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (section.total > 0) {
                        stringResource(id = R.string.storage_search_total_chip, section.total)
                    } else {
                        stringResource(id = R.string.storage_search_total_chip_idle)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        when {
            section.loading && !section.hasResults -> {
                repeat(2) {
                    StorageSearchLoadingRow()
                }
            }

            section.error != null && !section.hasResults -> {
                StorageSearchErrorCard(
                    errorType = section.error,
                    onRetry = onRetry,
                )
            }

            !section.hasResults -> {
                AggregateSearchEmptyCard(
                    title = stringResource(id = R.string.storage_search_section_empty_title),
                    desc = stringResource(id = R.string.storage_search_section_empty_desc),
                )
            }

            else -> {
                section.entries.forEach { entry ->
                    StorageSearchResultRow(
                        entry = entry,
                        subtitle = entry.parentPath,
                        onClick = { onResultClick(entry) },
                        onLocate = { onLocate(entry) },
                    )
                }
                if (section.error != null) {
                    StorageSearchErrorCard(
                        errorType = section.error,
                        onRetry = onRetry,
                    )
                }
            }
        }

        if (section.loadingMore) {
            StorageSearchLoadingRow()
        }
        if (section.canLoadMore) {
            EaseTextButton(
                text = stringResource(id = R.string.storage_search_load_more),
                type = EaseTextButtonType.PrimaryVariant,
                size = EaseTextButtonSize.Medium,
                onClick = onLoadMore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun StorageSearchContent(
    query: String,
    scope: StorageSearchScope,
    sections: List<StorageSearchSectionUiState>,
    supportedStorageCount: Int,
    totalHits: Int,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onScopeChange: (StorageSearchScope) -> Unit,
    onClearQuery: () -> Unit,
    onResultClick: (StorageSearchEntry) -> Unit,
    onLocate: (StorageSearchEntry) -> Unit,
    onLoadMore: (uniffi.ease_client_schema.StorageId) -> Unit,
    onRetryStorage: (uniffi.ease_client_schema.StorageId) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                ) {
                    EaseIconButton(
                        sizeType = EaseIconButtonSize.Large,
                        buttonType = EaseIconButtonType.Default,
                        painter = painterResource(id = R.drawable.icon_back),
                        onClick = onBack,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(id = R.string.storage_search_page_title),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(id = R.string.storage_search_page_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            item {
                AggregateSearchHero(
                    query = query,
                    scope = scope,
                    supportedStorageCount = supportedStorageCount,
                    totalHits = totalHits,
                    onQueryChange = onQueryChange,
                    onScopeChange = onScopeChange,
                    onClearQuery = onClearQuery,
                )
            }
            if (supportedStorageCount == 0) {
                item {
                    AggregateSearchEmptyCard(
                        title = stringResource(id = R.string.storage_search_no_instance_title),
                        desc = stringResource(id = R.string.storage_search_no_instance_desc),
                    )
                }
            } else if (query.isBlank()) {
                item {
                    AggregateSearchEmptyCard(
                        title = stringResource(id = R.string.storage_search_idle_title),
                        desc = stringResource(id = R.string.storage_search_idle_desc),
                    )
                }
            } else {
                items(sections, key = { section -> section.storage.id.value }) { section ->
                    SearchSectionCard(
                        section = section,
                        onResultClick = onResultClick,
                        onLocate = onLocate,
                        onLoadMore = { onLoadMore(section.storage.id) },
                        onRetry = { onRetryStorage(section.storage.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun StorageSearchPage(
    storageSearchVM: StorageSearchVM = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val query by storageSearchVM.query.collectAsState()
    val scope by storageSearchVM.scope.collectAsState()
    val sections by storageSearchVM.sections.collectAsState()
    val searchableStorages by storageSearchVM.searchableStorages.collectAsState()
    val totalHits by storageSearchVM.totalHits.collectAsState()

    fun openEntry(entry: StorageSearchEntry) {
        when {
            entry.isDir -> navController.navigate(
                RouteStorageBrowser(entry.storageId.value.toString(), entry.path)
            )

            entry.entryTyp() == StorageEntryType.MUSIC -> {
                storageSearchVM.playEntry(entry)
            }

            else -> navController.navigate(
                RouteStorageBrowser(entry.storageId.value.toString(), entry.parentPath)
            )
        }
    }

    BackHandler(onBack = { navController.popBackStack() })

    StorageSearchContent(
        query = query,
        scope = scope,
        sections = sections,
        supportedStorageCount = searchableStorages.size,
        totalHits = totalHits,
        onBack = { navController.popBackStack() },
        onQueryChange = { value -> storageSearchVM.updateQuery(value) },
        onScopeChange = { value -> storageSearchVM.updateScope(value) },
        onClearQuery = { storageSearchVM.clearQuery() },
        onResultClick = { entry -> openEntry(entry) },
        onLocate = { entry ->
            navController.navigate(RouteStorageBrowser(entry.storageId.value.toString(), entry.parentPath))
        },
        onLoadMore = { storageId -> storageSearchVM.loadMore(storageId) },
        onRetryStorage = { storageId -> storageSearchVM.retryStorage(storageId) },
    )
}
