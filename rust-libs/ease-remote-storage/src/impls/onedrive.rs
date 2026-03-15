use std::{cmp::Ordering, sync::OnceLock, time::Duration};

use ease_client_tokio::tokio_runtime;
use futures_util::future::BoxFuture;
use reqwest::header::HeaderValue;
use reqwest::StatusCode;

use crate::{
    env::EASEM_ONEDRIVE_ID, DirectHttpPlaybackSource, Entry, PlaybackHttpHeader,
    ResolvedPlaybackSource, StorageBackend, StorageBackendError, StorageBackendResult, StreamFile,
};

pub struct BuildOneDriveArg {
    pub code: String,
}

struct Auth {
    access_token: String,
    refresh_token: String,
}

pub struct OneDriveBackend {
    refresh_token: String,
    auth: tokio::sync::RwLock<Option<Auth>>,
}

mod onedrive_types {
    use serde::Deserialize;
    use serde_with::{serde_as, DefaultOnError};

    #[derive(Deserialize, Debug)]
    pub struct RedeemCodeResp {
        pub access_token: String,
        pub refresh_token: String,
    }

    #[serde_as]
    #[derive(Debug, Deserialize)]
    pub struct ListItemResponse {
        #[serde_as(deserialize_as = "Vec<DefaultOnError>")]
        pub value: Vec<Option<ListItem>>,
        #[serde(rename = "@odata.nextLink")]
        pub next_link: Option<String>,
    }

    #[derive(Debug, Deserialize)]
    pub struct ListItem {
        pub name: String,
        #[serde(flatten)]
        pub kind: ListItemKind,
    }

    #[derive(Debug, Deserialize)]
    #[serde(untagged)]
    pub enum ListItemKind {
        File {
            size: u64,
            #[serde(rename = "file")]
            _file: ListFileMetadata,
        },
        Folder {
            #[serde(rename = "folder")]
            _folder: ListFolderMetadata,
        },
    }

    #[derive(Debug, Deserialize)]
    pub struct ListFolderMetadata {
        #[serde(rename = "childCount")]
        pub _child_count: u64,
    }

    #[derive(Debug, Deserialize)]
    pub struct ListFileMetadata {
        #[serde(rename = "mimeType")]
        pub _mime_type: String,
    }
}

const ONEDRIVE_ROOT_API: &str = "https://graph.microsoft.com/v1.0/me/drive";
const ONEDRIVE_API_BASE: &str = "https://login.microsoftonline.com/common/oauth2/v2.0";
const ONEDRIVE_REDIRECT_URI: &str = "easem://oauth2redirect/";
const ONEDRIVE_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const ONEDRIVE_TCP_KEEPALIVE: Duration = Duration::from_secs(60);
const ONEDRIVE_POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(90);
const ONEDRIVE_HTTP1_ONLY_COMPAT: bool = true;

static ONEDRIVE_SHARED_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

fn is_auth_error<T>(r: &StorageBackendResult<T>) -> bool {
    if let Err(e) = r {
        if let StorageBackendError::RequestFail(e) = e {
            if let Some(StatusCode::UNAUTHORIZED) = e.status() {
                return true;
            }
        }
    }
    false
}

fn build_client() -> StorageBackendResult<reqwest::Client> {
    if let Some(client) = ONEDRIVE_SHARED_CLIENT.get() {
        return Ok(client.clone());
    }
    let mut builder = reqwest::Client::builder()
        .connect_timeout(ONEDRIVE_CONNECT_TIMEOUT)
        .tcp_keepalive(Some(ONEDRIVE_TCP_KEEPALIVE))
        .pool_idle_timeout(Some(ONEDRIVE_POOL_IDLE_TIMEOUT))
        .pool_max_idle_per_host(6)
        .no_proxy();
    if ONEDRIVE_HTTP1_ONLY_COMPAT {
        // Keep HTTP/1.1 as a compatibility fallback until Graph/redirect chains are revalidated for H2.
        builder = builder.http1_only();
    }
    let client = builder.build()?;
    let _ = ONEDRIVE_SHARED_CLIENT.set(client);
    Ok(ONEDRIVE_SHARED_CLIENT
        .get()
        .expect("onedrive client missing")
        .clone())
}

async fn refresh_token_by_code_impl(code: String) -> StorageBackendResult<Auth> {
    let client_id = EASEM_ONEDRIVE_ID;
    let body =
        format!("client_id={client_id}&redirect_uri={ONEDRIVE_REDIRECT_URI}&code={code}&grant_type=authorization_code");

    let resp = tokio_runtime()
        .spawn(async move {
            let ret = build_client()?
                .request(reqwest::Method::POST, format!("{ONEDRIVE_API_BASE}/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body)
                .send()
                .await?;
            Ok::<_, StorageBackendError>(ret)
        })
        .await??;
    let resp_text = resp.text().await?;
    let value = serde_json::from_str::<onedrive_types::RedeemCodeResp>(&resp_text)?;
    Ok(Auth {
        access_token: value.access_token,
        refresh_token: value.refresh_token,
    })
}

impl OneDriveBackend {
    pub fn new(arg: BuildOneDriveArg) -> Self {
        Self {
            refresh_token: arg.code,
            auth: Default::default(),
        }
    }

    async fn build_base_header_map(&self) -> reqwest::header::HeaderMap {
        let mut header_map = reqwest::header::HeaderMap::new();
        {
            let r = self.auth.read().await;
            if let Some(auth) = r.as_ref() {
                header_map.append(
                    reqwest::header::AUTHORIZATION,
                    HeaderValue::from_str(format!("bearer {}", auth.access_token).as_str())
                        .unwrap(),
                );
            }
        }
        header_map
    }

    async fn try_ensure_refresh_token_by_refresh_token(&self) -> StorageBackendResult<()> {
        let mut w = self.auth.write().await;
        if w.is_none() {
            self.refresh_token_by_refresh_token_impl(&mut w).await?;
        }
        Ok(())
    }

    async fn refresh_token_by_refresh_token_impl(
        &self,
        w: &mut Option<Auth>,
    ) -> StorageBackendResult<()> {
        let client_id = EASEM_ONEDRIVE_ID;
        let refresh_token = self.refresh_token.clone();
        let body =
            format!("client_id={client_id}&redirect_uri={ONEDRIVE_REDIRECT_URI}&refresh_token={refresh_token}&grant_type=refresh_token");

        let resp = {
            let client = self.build_client()?;

            tokio_runtime()
                .spawn(async move {
                    client
                        .request(reqwest::Method::POST, format!("{ONEDRIVE_API_BASE}/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body(body)
                        .send()
                        .await
                })
                .await??
        };
        let resp_text = resp.text().await?;
        let value = serde_json::from_str::<onedrive_types::RedeemCodeResp>(&resp_text)?;

        *w = Some(Auth {
            access_token: value.access_token,
            refresh_token: value.refresh_token,
        });
        Ok(())
    }

    async fn refresh_token_by_refresh_token(&self) -> StorageBackendResult<()> {
        let mut w = self.auth.write().await;
        self.refresh_token_by_refresh_token_impl(&mut w).await?;
        Ok(())
    }

    async fn list_core_by_url(&self, url: &str) -> StorageBackendResult<reqwest::Response> {
        let url = reqwest::Url::parse(url)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;
        let base_headers = self.build_base_header_map().await;

        let resp = {
            let client = self.build_client()?;

            tokio_runtime()
                .spawn(async move {
                    client
                        .request(reqwest::Method::GET, url.clone())
                        .headers(base_headers)
                        .send()
                        .await
                })
                .await??
        };

        Ok(resp)
    }

    fn compute_list_url(&self, dir: &str) -> String {
        let subdir = if dir == "/" {
            "/root/children".to_string()
        } else {
            ("/root:".to_string() + dir + ":/children").to_string()
        };
        let _url = ONEDRIVE_ROOT_API.to_string() + subdir.as_str();
        _url
    }

    async fn list_impl(&self, dir: &str) -> StorageBackendResult<Vec<Entry>> {
        let mut url = self.compute_list_url(dir);

        let mut ret: Vec<Entry> = Default::default();
        loop {
            let resp = self.list_core_by_url(&url).await?.error_for_status()?;
            let text: String = resp.text().await?;
            let obj: onedrive_types::ListItemResponse =
                serde_json::from_str(&text).map_err(|e| {
                    tracing::warn!("onedrive list resp: {text}");
                    e
                })?;

            for item in obj.value.into_iter().flatten() {
                let name = item.name;
                let path = dir.to_string() + "/" + name.as_str();
                match item.kind {
                    onedrive_types::ListItemKind::File { size, .. } => {
                        ret.push(Entry {
                            name,
                            path,
                            size: Some(size as usize),
                            is_dir: false,
                        });
                    }
                    onedrive_types::ListItemKind::Folder { .. } => {
                        ret.push(Entry {
                            name,
                            path,
                            size: None,
                            is_dir: true,
                        });
                    }
                }
            }
            tracing::info!("load {} items", ret.len());

            if let Some(next_link) = obj.next_link {
                url = next_link;
            } else {
                break;
            }
        }

        ret.sort_by(|lhs, rhs| {
            if lhs.is_dir ^ rhs.is_dir {
                if lhs.is_dir {
                    return Ordering::Less;
                } else {
                    return Ordering::Greater;
                }
            }
            if lhs.path < rhs.path {
                Ordering::Less
            } else {
                Ordering::Greater
            }
        });

        Ok(ret)
    }

    async fn list_with_retry_impl(&self, dir: String) -> StorageBackendResult<Vec<Entry>> {
        self.try_ensure_refresh_token_by_refresh_token().await?;
        let r = self.list_impl(dir.as_str()).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token_by_refresh_token().await?;
        return self.list_impl(dir.as_str()).await;
    }

    async fn get_impl(&self, p: &str, byte_offset: u64) -> StorageBackendResult<StreamFile> {
        let _url = ONEDRIVE_ROOT_API.to_string() + "/root:" + p + ":/content";
        let url = reqwest::Url::parse(_url.as_str())
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;

        let mut headers = self.build_base_header_map().await;
        headers.insert(
            reqwest::header::RANGE,
            HeaderValue::from_str(format!("bytes={byte_offset}-").as_str()).unwrap(),
        );

        let resp = {
            let client = self.build_client()?;

            tokio_runtime()
                .spawn(async move { client.get(url.clone()).headers(headers).send().await })
                .await??
        };
        let byte_offset = if resp.headers().get(reqwest::header::CONTENT_RANGE).is_some() {
            0
        } else {
            byte_offset
        };
        let res = resp
            .error_for_status()
            .map(|resp| StreamFile::new(resp, byte_offset))?;
        Ok(res)
    }

    async fn resolve_playback_source_impl(
        &self,
        p: &str,
    ) -> StorageBackendResult<ResolvedPlaybackSource> {
        let url = format!("{ONEDRIVE_ROOT_API}/root:{p}:/content");
        let base_headers = self.build_base_header_map().await;
        let headers = base_headers
            .iter()
            .filter_map(|(name, value)| {
                let name = name.to_string();
                let value = value.to_str().ok()?.to_string();
                Some(PlaybackHttpHeader { name, value })
            })
            .collect::<Vec<_>>();

        Ok(ResolvedPlaybackSource::DirectHttp(
            DirectHttpPlaybackSource {
                url,
                headers,
                cache_key: Some(p.to_string()),
            },
        ))
    }

    async fn get_with_retry_impl(
        &self,
        p: String,
        byte_offset: u64,
    ) -> StorageBackendResult<StreamFile> {
        self.try_ensure_refresh_token_by_refresh_token().await?;
        let r = self.get_impl(p.as_str(), byte_offset).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token_by_refresh_token().await?;
        return self.get_impl(p.as_str(), byte_offset).await;
    }

    async fn resolve_playback_source_with_retry_impl(
        &self,
        p: String,
    ) -> StorageBackendResult<ResolvedPlaybackSource> {
        self.try_ensure_refresh_token_by_refresh_token().await?;
        let r = self.resolve_playback_source_impl(p.as_str()).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token_by_refresh_token().await?;
        self.resolve_playback_source_impl(p.as_str()).await
    }

    fn build_client(&self) -> StorageBackendResult<reqwest::Client> {
        build_client()
    }
}

impl StorageBackend for OneDriveBackend {
    fn list(&self, dir: String) -> BoxFuture<'_, StorageBackendResult<Vec<Entry>>> {
        Box::pin(self.list_with_retry_impl(dir))
    }

    fn get(&self, p: String, byte_offset: u64) -> BoxFuture<'_, StorageBackendResult<StreamFile>> {
        Box::pin(self.get_with_retry_impl(p, byte_offset))
    }

    fn resolve_playback_source(
        &self,
        p: String,
    ) -> BoxFuture<'_, StorageBackendResult<ResolvedPlaybackSource>> {
        Box::pin(self.resolve_playback_source_with_retry_impl(p))
    }
}

impl OneDriveBackend {
    pub async fn request_refresh_token(code: String) -> StorageBackendResult<String> {
        let authed = refresh_token_by_code_impl(code).await?;
        Ok(authed.refresh_token)
    }
}

#[cfg(test)]
mod test {
    use super::{build_client, Auth, OneDriveBackend, ONEDRIVE_SHARED_CLIENT};
    use crate::{ResolvedPlaybackSource, StorageBackend};

    #[test]
    fn test_build_client_is_cached() {
        assert!(build_client().is_ok());
        assert!(ONEDRIVE_SHARED_CLIENT.get().is_some());
        assert!(build_client().is_ok());
        assert!(ONEDRIVE_SHARED_CLIENT.get().is_some());
    }

    #[tokio::test]
    async fn test_resolve_playback_source_returns_direct_http() {
        let backend = OneDriveBackend::new(super::BuildOneDriveArg {
            code: "refresh-token".to_string(),
        });
        {
            let mut auth = backend.auth.write().await;
            *auth = Some(Auth {
                access_token: "access-token".to_string(),
                refresh_token: "refresh-token".to_string(),
            });
        }

        let resolved = backend
            .resolve_playback_source("/music/test.wav".to_string())
            .await
            .unwrap();
        match resolved {
            ResolvedPlaybackSource::DirectHttp(source) => {
                assert_eq!(
                    source.url,
                    "https://graph.microsoft.com/v1.0/me/drive/root:/music/test.wav:/content"
                );
                assert_eq!(source.cache_key.as_deref(), Some("/music/test.wav"));
                assert!(source.headers.iter().any(|header| {
                    header
                        .name
                        .eq_ignore_ascii_case(reqwest::header::AUTHORIZATION.as_str())
                        && header.value == "bearer access-token"
                }));
            }
            other => panic!("unexpected resolved playback source: {other:?}"),
        }
    }
}
