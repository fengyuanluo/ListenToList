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
        list_storage, normalize_storage_default_path_for_type,
    },
    ArgUpsertStorage, Backend,
};

fn normalize_arg_upsert_storage(mut arg: ArgUpsertStorage) -> ArgUpsertStorage {
    if arg.is_anonymous {
        arg.username = Default::default();
        arg.password = Default::default();
    }
    arg.default_path = normalize_storage_default_path_for_type(arg.typ, arg.default_path.as_str());
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
    let backend = build_storage_backend_by_arg(cx, arg.clone())?;
    let res = backend.list(arg.default_path.clone()).await;

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
pub async fn ct_list_storage_entry_children_by_arg(
    cx: Arc<Backend>,
    arg: ArgUpsertStorage,
    path: String,
) -> BResult<ListStorageEntryChildrenResp> {
    let arg = normalize_arg_upsert_storage(arg);
    let storage_id = arg.id.unwrap_or(StorageId::wrap(-1));
    let cx = cx.get_context();
    let backend = build_storage_backend_by_arg(cx, arg)?;
    let res = backend.list(path).await;

    match res {
        Ok(entries) => {
            let entries = entries
                .into_iter()
                .map(|entry| StorageEntry {
                    storage_id,
                    name: entry.name,
                    path: entry.path,
                    size: entry.size.map(|s| s as u64),
                    is_dir: entry.is_dir,
                })
                .collect();
            Ok(ListStorageEntryChildrenResp::Ok(entries))
        }
        Err(e) => {
            tracing::warn!("ct_list_storage_entry_children_by_arg, {e:?}");
            if e.is_unauthorized() {
                Ok(ListStorageEntryChildrenResp::AuthenticationFailed)
            } else if e.is_timeout() {
                Ok(ListStorageEntryChildrenResp::Timeout)
            } else if e.is_search_unavailable() {
                Ok(ListStorageEntryChildrenResp::Unavailable)
            } else if e.is_site_blocked() {
                Ok(ListStorageEntryChildrenResp::BlockedBySite)
            } else {
                Ok(ListStorageEntryChildrenResp::Unknown)
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
            } else if e.is_search_unavailable() {
                Ok(ListStorageEntryChildrenResp::Unavailable)
            } else if e.is_site_blocked() {
                Ok(ListStorageEntryChildrenResp::BlockedBySite)
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

#[cfg(test)]
mod tests {
    use super::normalize_arg_upsert_storage;
    use crate::objects::ArgUpsertStorage;
    use crate::services::{normalize_default_storage_path, storage_type_supports_default_path};
    use ease_client_schema::StorageType;

    fn sample_arg() -> ArgUpsertStorage {
        ArgUpsertStorage {
            id: None,
            addr: "https://example.com".to_string(),
            alias: "demo".to_string(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            typ: StorageType::OpenList,
            default_path: "/".to_string(),
        }
    }

    #[test]
    fn normalize_default_storage_path_handles_blank_and_slashes() {
        assert_eq!("/", normalize_default_storage_path(""));
        assert_eq!("/", normalize_default_storage_path(" / "));
        assert_eq!("/Music", normalize_default_storage_path("Music"));
        assert_eq!("/Music/Sub", normalize_default_storage_path("/Music/Sub/"));
    }

    #[test]
    fn normalize_arg_upsert_storage_clears_unsupported_default_path() {
        let mut arg = sample_arg();
        arg.typ = StorageType::Local;
        arg.default_path = "/Music".to_string();

        let normalized = normalize_arg_upsert_storage(arg);

        assert_eq!("/", normalized.default_path);
    }

    #[test]
    fn openlist_webdav_and_onedrive_support_default_path() {
        assert!(storage_type_supports_default_path(StorageType::OpenList));
        assert!(storage_type_supports_default_path(StorageType::Webdav));
        assert!(storage_type_supports_default_path(StorageType::OneDrive));
        assert!(!storage_type_supports_default_path(StorageType::Local));
    }

    #[test]
    fn normalize_arg_upsert_storage_keeps_supported_default_path() {
        let mut arg = sample_arg();
        arg.typ = StorageType::Webdav;
        arg.default_path = "Music/Sub/".to_string();

        let normalized = normalize_arg_upsert_storage(arg);

        assert_eq!("/Music/Sub", normalized.default_path);
    }

    #[test]
    fn upsert_storage_evicts_cached_backend() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let tempdir = tempfile::tempdir().expect("create tempdir");
            let documents_dir = tempdir.path().join("documents");
            let cache_dir = tempdir.path().join("cache");
            std::fs::create_dir_all(&documents_dir).expect("create documents dir");
            std::fs::create_dir_all(&cache_dir).expect("create cache dir");

            let backend = crate::create_backend(crate::services::ArgInitializeApp {
                app_document_dir: format!("{}/", documents_dir.display()),
                app_cache_dir: format!("{}/", cache_dir.display()),
                storage_path: "/".to_string(),
            });
            backend.init().expect("init backend");

            let arg = sample_arg();
            super::ct_upsert_storage(backend.clone(), arg)
                .await
                .expect("insert storage");
            let storage = crate::services::list_storage(backend.get_context())
                .await
                .expect("list storage")
                .into_iter()
                .find(|storage| storage.alias == "demo")
                .expect("inserted storage");

            let cached = crate::services::get_storage_backend(backend.get_context(), storage.id)
                .expect("load backend");
            assert!(cached.is_some());
            assert!(crate::services::storage_backend_cache_contains(backend.get_context(), storage.id));

            let mut updated = sample_arg();
            updated.id = Some(storage.id);
            updated.password = "new-pass".to_string();
            super::ct_upsert_storage(backend.clone(), updated)
                .await
                .expect("update storage");

            assert!(!crate::services::storage_backend_cache_contains(backend.get_context(), storage.id));
        });
    }
}
