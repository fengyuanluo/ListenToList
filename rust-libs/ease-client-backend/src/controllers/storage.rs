use std::sync::Arc;

use ease_client_schema::{StorageEntryLoc, StorageId};
use ease_remote_storage::{OneDriveBackend, SearchScope};

use crate::{
    error::BResult,
    objects::{
        ArgSearchStorageEntries, ListStorageEntryChildrenResp, SearchStorageEntriesResp, Storage,
        StorageConnectionTestResult, StorageEntry, StorageSearchEntry, StorageSearchPage,
        StorageSearchScope,
    },
    onedrive_oauth_url,
    services::{
        build_storage_backend_by_arg, evict_storage_backend_cache, get_storage_backend,
        list_storage,
    },
    ArgUpsertStorage, Backend,
};

fn normalize_arg_upsert_storage(mut arg: ArgUpsertStorage) -> ArgUpsertStorage {
    if arg.is_anonymous {
        arg.username = Default::default();
        arg.password = Default::default();
    }
    arg
}

#[uniffi::export]
pub async fn ct_list_storage(cx: Arc<Backend>) -> BResult<Vec<Storage>> {
    let cx = cx.get_context();
    let storages = list_storage(cx).await?;

    Ok(storages)
}

#[uniffi::export]
pub async fn ct_upsert_storage(cx: Arc<Backend>, arg: ArgUpsertStorage) -> BResult<()> {
    let arg = normalize_arg_upsert_storage(arg);

    let cx = cx.get_context();
    let id = cx.database_server().upsert_storage(arg)?;
    evict_storage_backend_cache(cx, id);

    Ok(())
}

#[uniffi::export]
pub async fn ct_get_refresh_token(_cx: Arc<Backend>, code: String) -> BResult<String> {
    let refresh_token = OneDriveBackend::request_refresh_token(code).await?;
    Ok(refresh_token)
}

#[uniffi::export]
pub async fn ct_remove_storage(cx: Arc<Backend>, id: StorageId) -> BResult<()> {
    let cx = cx.get_context();
    cx.database_server().remove_storage(id)?;
    evict_storage_backend_cache(cx, id);

    Ok(())
}

#[uniffi::export]
pub async fn ct_test_storage(
    cx: Arc<Backend>,
    arg: ArgUpsertStorage,
) -> BResult<StorageConnectionTestResult> {
    let arg = normalize_arg_upsert_storage(arg);
    let cx = cx.get_context();
    let backend = build_storage_backend_by_arg(cx, arg)?;
    let res = backend.list("/".to_string()).await;

    match res {
        Ok(_) => Ok(StorageConnectionTestResult::Success),
        Err(e) => {
            tracing::warn!("ct_test_storage, {e:?}");
            if e.is_unauthorized() {
                Ok(StorageConnectionTestResult::Unauthorized)
            } else if e.is_timeout() {
                Ok(StorageConnectionTestResult::Timeout)
            } else {
                Ok(StorageConnectionTestResult::OtherError)
            }
        }
    }
}

#[uniffi::export]
pub async fn ct_list_storage_entry_children(
    cx: Arc<Backend>,
    arg: StorageEntryLoc,
) -> BResult<ListStorageEntryChildrenResp> {
    let cx = cx.get_context();
    let backend = get_storage_backend(cx, arg.storage_id)?;
    if backend.is_none() {
        return Ok(ListStorageEntryChildrenResp::Unknown);
    }
    let backend = backend.unwrap();

    let p = arg.path;
    let res = backend.list(p).await;

    match res {
        Ok(entries) => {
            let entries = entries
                .into_iter()
                .map(|entry| StorageEntry {
                    storage_id: arg.storage_id,
                    name: entry.name,
                    path: entry.path,
                    size: entry.size.map(|s| s as u64),
                    is_dir: entry.is_dir,
                })
                .collect();
            Ok(ListStorageEntryChildrenResp::Ok(entries))
        }
        Err(e) => {
            tracing::warn!("ct_list_storage_entry_children, {e:?}");
            if e.is_unauthorized() {
                Ok(ListStorageEntryChildrenResp::AuthenticationFailed)
            } else if e.is_timeout() {
                Ok(ListStorageEntryChildrenResp::Timeout)
            } else {
                Ok(ListStorageEntryChildrenResp::Unknown)
            }
        }
    }
}

fn map_storage_search_scope(scope: StorageSearchScope) -> SearchScope {
    match scope {
        StorageSearchScope::All => SearchScope::All,
        StorageSearchScope::Directory => SearchScope::Directory,
        StorageSearchScope::File => SearchScope::File,
    }
}

fn parent_storage_path(path: &str) -> String {
    if path.trim().is_empty() || path == "/" {
        return "/".to_string();
    }
    let trimmed = path.trim_end_matches('/');
    let idx = trimmed.rfind('/').unwrap_or(0);
    if idx == 0 {
        "/".to_string()
    } else {
        trimmed[..idx].to_string()
    }
}

#[uniffi::export]
pub async fn ct_search_storage_entries(
    cx: Arc<Backend>,
    arg: ArgSearchStorageEntries,
) -> BResult<SearchStorageEntriesResp> {
    let cx = cx.get_context();
    let backend = get_storage_backend(cx, arg.storage_id)?;
    let Some(backend) = backend else {
        return Ok(SearchStorageEntriesResp::Unknown);
    };

    let result = backend
        .search(
            arg.parent.clone(),
            arg.keywords.clone(),
            map_storage_search_scope(arg.scope),
            arg.page.max(1) as usize,
            arg.per_page.max(1) as usize,
        )
        .await;

    match result {
        Ok(page) => Ok(SearchStorageEntriesResp::Ok(StorageSearchPage {
            entries: page
                .entries
                .into_iter()
                .map(|entry| StorageSearchEntry {
                    parent_path: parent_storage_path(entry.path.as_str()),
                    storage_id: arg.storage_id,
                    name: entry.name,
                    path: entry.path,
                    size: entry.size.map(|value| value as u64),
                    is_dir: entry.is_dir,
                })
                .collect(),
            total: page.total as u64,
            page: arg.page.max(1),
            per_page: arg.per_page.max(1),
        })),
        Err(e) => {
            tracing::warn!("ct_search_storage_entries, {e:?}");
            if e.is_search_unavailable() {
                Ok(SearchStorageEntriesResp::Unavailable)
            } else if e.is_site_blocked() {
                Ok(SearchStorageEntriesResp::BlockedBySite)
            } else if e.is_unauthorized() {
                Ok(SearchStorageEntriesResp::AuthenticationFailed)
            } else if e.is_timeout() {
                Ok(SearchStorageEntriesResp::Timeout)
            } else {
                Ok(SearchStorageEntriesResp::Unknown)
            }
        }
    }
}

#[uniffi::export]
pub fn ct_onedrive_oauth_url() -> String {
    onedrive_oauth_url()
}
