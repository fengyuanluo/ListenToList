use crate::backend::{
    DirectHttpPlaybackSource, Entry, PlaybackHttpHeader, ResolvedPlaybackSource, SearchResult,
    SearchScope, StorageBackend, StorageBackendError, StorageBackendResult, StreamFile,
};

use ease_client_tokio::tokio_runtime;
use futures_util::future::BoxFuture;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use reqwest::StatusCode;
use reqwest::Url;
use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::json;

use std::cmp::Ordering;
use std::sync::OnceLock;
use std::time::Duration;

const OPENLIST_API_TIMEOUT: Duration = Duration::from_secs(25);
const OPENLIST_DOWNLOAD_RESPONSE_TIMEOUT: Duration = Duration::from_secs(20);
const OPENLIST_DOWNLOAD_CHUNK_TIMEOUT: Duration = Duration::from_secs(30);
const OPENLIST_MAX_RETRIES: usize = 2;
const OPENLIST_RETRY_BASE_DELAY_MS: u64 = 400;
const OPENLIST_RETRY_MAX_DELAY_MS: u64 = 2000;
const OPENLIST_TCP_KEEPALIVE: Duration = Duration::from_secs(60);
const OPENLIST_POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(90);
const OPENLIST_BROWSER_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
     (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
const OPENLIST_BROWSER_ACCEPT: &str = "application/json, text/plain, */*";
const OPENLIST_BROWSER_ACCEPT_LANGUAGE: &str = "zh-CN,zh;q=0.9,en;q=0.8";
const OPENLIST_BROWSER_SEC_CH_UA: &str =
    "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"";
const OPENLIST_BROWSER_SEC_CH_UA_MOBILE: &str = "?0";
const OPENLIST_BROWSER_SEC_CH_UA_PLATFORM: &str = "\"Linux\"";

pub struct BuildOpenListArg {
    pub addr: String,
    pub username: String,
    pub password: String,
    pub is_anonymous: bool,
    pub connect_timeout: Duration,
}

pub struct OpenList {
    addr: String,
    username: String,
    password: String,
    is_anonymous: bool,
    token: tokio::sync::RwLock<Option<String>>,
    connect_timeout: Duration,
    api_client: OnceLock<reqwest::Client>,
    download_client: OnceLock<reqwest::Client>,
}

#[derive(Deserialize)]
struct ApiResponse<T> {
    code: i64,
    message: String,
    data: Option<T>,
}

#[derive(Deserialize)]
struct LoginData {
    token: String,
}

#[derive(Deserialize)]
struct FsListData {
    content: Vec<FsObject>,
    total: Option<i64>,
}

#[derive(Deserialize)]
struct FsSearchData {
    content: Vec<FsSearchObject>,
    total: Option<i64>,
}

#[derive(Deserialize, Clone)]
struct FsObject {
    name: String,
    size: Option<u64>,
    is_dir: bool,
    #[serde(default)]
    sign: String,
    #[serde(default)]
    r#type: Option<i64>,
    #[serde(default)]
    raw_url: Option<String>,
}

#[derive(Deserialize)]
struct FsSearchObject {
    parent: String,
    name: String,
    size: Option<u64>,
    is_dir: bool,
}

fn normalize_path(path: &str) -> String {
    if path.starts_with('/') {
        path.to_string()
    } else {
        format!("/{path}")
    }
}

fn join_path(dir: &str, name: &str) -> String {
    let dir = dir.trim_end_matches('/');
    if dir.is_empty() {
        format!("/{name}")
    } else if dir == "/" {
        format!("/{name}")
    } else {
        format!("{dir}/{name}")
    }
}

fn is_auth_error<T>(r: &StorageBackendResult<T>) -> bool {
    if let Err(e) = r {
        return e.is_unauthorized();
    }
    false
}

fn should_retry_error(err: &StorageBackendError) -> bool {
    if err.is_unauthorized() {
        return false;
    }
    if err.is_timeout() {
        return true;
    }
    match err {
        StorageBackendError::RequestFail(e) => {
            if e.is_timeout() || e.is_connect() || e.is_request() {
                return true;
            }
            if let Some(status) = e.status() {
                return status.is_server_error()
                    || status == StatusCode::REQUEST_TIMEOUT
                    || status == StatusCode::TOO_MANY_REQUESTS;
            }
            false
        }
        StorageBackendError::ApiError { code, .. } => {
            matches!(*code, 408 | 429) || (500..=599).contains(code)
        }
        _ => false,
    }
}

fn retry_delay(attempt: usize) -> Duration {
    let shift = attempt.saturating_sub(1).min(5) as u32;
    let base = OPENLIST_RETRY_BASE_DELAY_MS.saturating_mul(1_u64 << shift);
    let delay = base.min(OPENLIST_RETRY_MAX_DELAY_MS);
    Duration::from_millis(delay)
}

fn same_origin(base: &Url, url: &Url) -> bool {
    base.scheme() == url.scheme()
        && base.host_str() == url.host_str()
        && base.port_or_known_default() == url.port_or_known_default()
}

async fn sleep_backoff(delay: Duration) -> StorageBackendResult<()> {
    tokio_runtime()
        .spawn(async move {
            tokio::time::sleep(delay).await;
        })
        .await?;
    Ok(())
}

fn build_header_value(value: &str) -> StorageBackendResult<HeaderValue> {
    HeaderValue::from_str(value).map_err(|e| StorageBackendError::UrlParseError(e.to_string()))
}

fn json_like(content_type: &str, text: &str) -> bool {
    content_type.contains("application/json")
        || text.trim_start().starts_with('{')
        || text.trim_start().starts_with('[')
}

fn truncate_error_text(text: &str) -> String {
    let normalized = text.replace('\n', " ").replace('\r', " ");
    normalized.chars().take(160).collect()
}

fn detect_site_block(status: StatusCode, content_type: &str, text: &str) -> Option<String> {
    let lower = text.to_ascii_lowercase();
    let looks_html = content_type.contains("text/html")
        || lower.contains("<html")
        || lower.contains("<!doctype html");
    if !looks_html {
        return None;
    }
    if lower.contains("cloudflare") || lower.contains("just a moment") {
        return Some("cloudflare".to_string());
    }
    if status == StatusCode::FORBIDDEN
        || status == StatusCode::TOO_MANY_REQUESTS
        || status == StatusCode::SERVICE_UNAVAILABLE
    {
        return Some("html-challenge".to_string());
    }
    None
}

impl OpenList {
    pub fn new(arg: BuildOpenListArg) -> Self {
        Self {
            addr: arg.addr,
            username: arg.username,
            password: arg.password,
            is_anonymous: arg.is_anonymous,
            token: Default::default(),
            connect_timeout: arg.connect_timeout,
            api_client: OnceLock::new(),
            download_client: OnceLock::new(),
        }
    }

    fn build_client(
        &self,
        slot: &OnceLock<reqwest::Client>,
        timeout: Option<Duration>,
    ) -> StorageBackendResult<reqwest::Client> {
        if let Some(client) = slot.get() {
            return Ok(client.clone());
        }
        let mut builder = reqwest::Client::builder()
            .connect_timeout(self.connect_timeout)
            .tcp_keepalive(Some(OPENLIST_TCP_KEEPALIVE))
            .pool_idle_timeout(Some(OPENLIST_POOL_IDLE_TIMEOUT))
            .pool_max_idle_per_host(6)
            .http1_only()
            .no_proxy();
        if let Some(timeout) = timeout {
            builder = builder.timeout(timeout);
        }
        let client = builder.build()?;
        let _ = slot.set(client);
        Ok(slot.get().expect("openlist client missing").clone())
    }

    fn api_client(&self) -> StorageBackendResult<reqwest::Client> {
        self.build_client(&self.api_client, Some(OPENLIST_API_TIMEOUT))
    }

    fn download_client(&self) -> StorageBackendResult<reqwest::Client> {
        self.build_client(&self.download_client, None)
    }

    fn build_api_url(&self, api_path: &str) -> StorageBackendResult<Url> {
        let mut url = Url::parse(&self.addr)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;
        let base_path = url.path().trim_end_matches('/');
        let api_path = api_path.trim_start_matches('/');
        let new_path = if base_path.is_empty() || base_path == "/" {
            format!("/{api_path}")
        } else {
            format!("{base_path}/{api_path}")
        };
        url.set_path(&new_path);
        Ok(url)
    }

    fn build_download_url(&self, path: &str, sign: Option<&str>) -> StorageBackendResult<Url> {
        let mut url = Url::parse(&self.addr)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;
        let base_path = url.path().trim_end_matches('/');
        let path = normalize_path(path);
        let path = path.trim_start_matches('/');
        let new_path = if base_path.is_empty() || base_path == "/" {
            format!("/d/{path}")
        } else {
            format!("{base_path}/d/{path}")
        };
        url.set_path(&new_path);
        if let Some(sign) = sign {
            if !sign.is_empty() {
                url.query_pairs_mut().append_pair("sign", sign);
            }
        }
        Ok(url)
    }

    fn build_raw_url(&self, raw_url: &str) -> StorageBackendResult<Url> {
        if let Ok(url) = Url::parse(raw_url) {
            return Ok(url);
        }
        let base = Url::parse(&self.addr)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;
        base.join(raw_url)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))
    }

    fn browser_origin(&self) -> Option<String> {
        let base = Url::parse(&self.addr).ok()?;
        let host = base.host_str()?;
        let mut origin = format!("{}://{}", base.scheme(), host);
        if let Some(port) = base.port() {
            origin.push(':');
            origin.push_str(port.to_string().as_str());
        }
        Some(origin)
    }

    fn browser_referer(&self) -> Option<String> {
        let mut base = Url::parse(&self.addr).ok()?;
        let path = base.path();
        let referer_path = if path.is_empty() || path == "/" {
            "/".to_string()
        } else if path.ends_with('/') {
            path.to_string()
        } else {
            format!("{path}/")
        };
        base.set_path(&referer_path);
        Some(base.to_string())
    }

    fn browser_api_headers(&self) -> StorageBackendResult<HeaderMap> {
        let mut headers = HeaderMap::new();
        headers.insert(
            reqwest::header::USER_AGENT,
            build_header_value(OPENLIST_BROWSER_UA)?,
        );
        headers.insert(
            reqwest::header::ACCEPT,
            build_header_value(OPENLIST_BROWSER_ACCEPT)?,
        );
        headers.insert(
            reqwest::header::CONTENT_TYPE,
            build_header_value("application/json;charset=UTF-8")?,
        );
        headers.insert(
            reqwest::header::ACCEPT_LANGUAGE,
            build_header_value(OPENLIST_BROWSER_ACCEPT_LANGUAGE)?,
        );
        headers.insert(
            HeaderName::from_static("sec-ch-ua"),
            build_header_value(OPENLIST_BROWSER_SEC_CH_UA)?,
        );
        headers.insert(
            HeaderName::from_static("sec-ch-ua-mobile"),
            build_header_value(OPENLIST_BROWSER_SEC_CH_UA_MOBILE)?,
        );
        headers.insert(
            HeaderName::from_static("sec-ch-ua-platform"),
            build_header_value(OPENLIST_BROWSER_SEC_CH_UA_PLATFORM)?,
        );
        if let Some(origin) = self.browser_origin() {
            headers.insert(
                reqwest::header::ORIGIN,
                build_header_value(origin.as_str())?,
            );
        }
        if let Some(referer) = self.browser_referer() {
            headers.insert(
                reqwest::header::REFERER,
                build_header_value(referer.as_str())?,
            );
        }
        Ok(headers)
    }

    fn header_map_to_playback_headers(headers: &HeaderMap) -> Vec<PlaybackHttpHeader> {
        headers
            .iter()
            .filter_map(|(name, value)| {
                Some(PlaybackHttpHeader {
                    name: name.as_str().to_string(),
                    value: value.to_str().ok()?.to_string(),
                })
            })
            .collect()
    }

    async fn post_api<T: DeserializeOwned>(
        &self,
        api_path: &str,
        body: serde_json::Value,
    ) -> StorageBackendResult<T> {
        let url = self.build_api_url(api_path)?;
        let body = serde_json::to_vec(&body)?;
        let base_headers = self.browser_api_headers()?;
        let mut attempt = 0;

        loop {
            let client = self.api_client()?;
            let token = self.token.read().await.clone();
            let body = body.clone();
            let url = url.clone();
            let mut headers = base_headers.clone();
            if let Some(token) = token {
                let mut val = HeaderValue::from_str(token.as_str()).unwrap();
                val.set_sensitive(true);
                headers.insert(reqwest::header::AUTHORIZATION, val);
            }
            let resp = tokio_runtime()
                .spawn(async move { client.post(url).headers(headers).body(body).send().await })
                .await;

            let resp = match resp {
                Ok(Ok(resp)) => resp,
                Ok(Err(e)) => {
                    let err: StorageBackendError = e.into();
                    if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                        attempt += 1;
                        sleep_backoff(retry_delay(attempt)).await?;
                        continue;
                    }
                    return Err(err);
                }
                Err(e) => {
                    return Err(e.into());
                }
            };

            let status = resp.status();
            let content_type = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|value| value.to_str().ok())
                .unwrap_or_default()
                .to_string();
            let text = match resp.text().await {
                Ok(text) => text,
                Err(e) => {
                    let err: StorageBackendError = e.into();
                    if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                        attempt += 1;
                        sleep_backoff(retry_delay(attempt)).await?;
                        continue;
                    }
                    return Err(err);
                }
            };

            if let Some(provider) = detect_site_block(status, content_type.as_str(), text.as_str())
            {
                return Err(StorageBackendError::SiteBlocked {
                    status_code: status.as_u16(),
                    provider,
                });
            }

            if !status.is_success() {
                let err = if json_like(content_type.as_str(), text.as_str()) {
                    match serde_json::from_str::<ApiResponse<serde_json::Value>>(text.as_str()) {
                        Ok(resp) => StorageBackendError::ApiError {
                            code: resp.code,
                            message: resp.message,
                        },
                        Err(_) => StorageBackendError::ApiError {
                            code: status.as_u16() as i64,
                            message: truncate_error_text(text.as_str()),
                        },
                    }
                } else {
                    StorageBackendError::ApiError {
                        code: status.as_u16() as i64,
                        message: truncate_error_text(text.as_str()),
                    }
                };
                if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                    attempt += 1;
                    sleep_backoff(retry_delay(attempt)).await?;
                    continue;
                }
                return Err(err);
            }

            let resp: ApiResponse<T> = serde_json::from_str(&text)?;
            if resp.code != 200 {
                let err = StorageBackendError::ApiError {
                    code: resp.code,
                    message: resp.message,
                };
                if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                    attempt += 1;
                    sleep_backoff(retry_delay(attempt)).await?;
                    continue;
                }
                return Err(err);
            }
            return resp.data.ok_or(StorageBackendError::ApiError {
                code: resp.code,
                message: resp.message,
            });
        }
    }

    async fn login_impl(&self) -> StorageBackendResult<String> {
        let data: LoginData = self
            .post_api(
                "/api/auth/login",
                json!({
                    "username": self.username,
                    "password": self.password,
                }),
            )
            .await?;
        Ok(data.token)
    }

    async fn ensure_token(&self) -> StorageBackendResult<()> {
        if self.is_anonymous {
            return Ok(());
        }
        if self.token.read().await.is_some() {
            return Ok(());
        }
        let token = self.login_impl().await?;
        *self.token.write().await = Some(token);
        Ok(())
    }

    async fn refresh_token(&self) -> StorageBackendResult<()> {
        if self.is_anonymous {
            return Ok(());
        }
        let token = self.login_impl().await?;
        *self.token.write().await = Some(token);
        Ok(())
    }

    async fn list_impl(&self, dir: &str) -> StorageBackendResult<Vec<Entry>> {
        let dir = normalize_path(dir);
        let mut page = 1;
        let mut ret: Vec<Entry> = Default::default();

        loop {
            let data: FsListData = self
                .post_api(
                    "/api/fs/list",
                    json!({
                        "path": dir,
                        "password": "",
                        "page": page,
                        "per_page": 200,
                    }),
                )
                .await?;

            for item in data.content.into_iter() {
                let name_raw = item.name;
                let name = urlencoding::decode(name_raw.as_str())
                    .map(|v| v.to_string())
                    .unwrap_or(name_raw.clone());
                let is_dir = item.is_dir || item.r#type == Some(1);
                let size = if is_dir {
                    None
                } else {
                    item.size.map(|s| s as usize)
                };
                let path = join_path(dir.as_str(), name_raw.as_str());
                ret.push(Entry {
                    name,
                    path,
                    size,
                    is_dir,
                });
            }

            let total = data.total.unwrap_or(ret.len() as i64) as usize;
            if ret.len() >= total {
                break;
            }
            page += 1;
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

    async fn search_impl(
        &self,
        parent: &str,
        keywords: &str,
        scope: SearchScope,
        page: usize,
        per_page: usize,
    ) -> StorageBackendResult<SearchResult> {
        let parent = normalize_path(parent);
        let api_scope = match scope {
            SearchScope::All => 0,
            SearchScope::Directory => 1,
            SearchScope::File => 2,
        };
        let data: FsSearchData = self
            .post_api(
                "/api/fs/search",
                json!({
                    "parent": parent,
                    "keywords": keywords,
                    "scope": api_scope,
                    "page": page,
                    "per_page": per_page,
                    "password": "",
                }),
            )
            .await?;

        let entries = data
            .content
            .into_iter()
            .map(|item| Entry {
                name: item.name.clone(),
                path: join_path(item.parent.as_str(), item.name.as_str()),
                size: if item.is_dir {
                    None
                } else {
                    item.size.map(|value| value as usize)
                },
                is_dir: item.is_dir,
            })
            .collect();

        Ok(SearchResult {
            entries,
            total: data.total.unwrap_or_default().max(0) as usize,
        })
    }

    async fn resolve_direct_http_request_impl(
        &self,
        p: &str,
        byte_offset: u64,
    ) -> StorageBackendResult<(Url, HeaderMap)> {
        let data: FsObject = self
            .post_api(
                "/api/fs/get",
                json!({
                    "path": normalize_path(p),
                    "password": "",
                }),
            )
            .await?;

        let url = if let Some(raw_url) = data.raw_url.as_ref() {
            self.build_raw_url(raw_url)?
        } else {
            self.build_download_url(p, Some(data.sign.as_str()))?
        };

        let mut headers = HeaderMap::new();
        headers.insert(
            reqwest::header::USER_AGENT,
            HeaderValue::from_str("EaseMusicPlayer/1.0").unwrap(),
        );
        if byte_offset > 0 {
            headers.insert(
                reqwest::header::RANGE,
                HeaderValue::from_str(format!("bytes={byte_offset}-").as_str()).unwrap(),
            );
        }

        if let Some(token) = self.token.read().await.clone() {
            if let Ok(base) = Url::parse(&self.addr) {
                if same_origin(&base, &url) {
                    let mut val = HeaderValue::from_str(token.as_str()).unwrap();
                    val.set_sensitive(true);
                    headers.insert(reqwest::header::AUTHORIZATION, val);
                }
            }
        }

        Ok((url, headers))
    }

    async fn resolve_direct_playback_source_impl(
        &self,
        p: &str,
    ) -> StorageBackendResult<DirectHttpPlaybackSource> {
        let (url, headers) = self.resolve_direct_http_request_impl(p, 0).await?;
        Ok(DirectHttpPlaybackSource {
            url: url.to_string(),
            headers: Self::header_map_to_playback_headers(&headers),
            cache_key: Some(normalize_path(p)),
        })
    }

    async fn list_with_retry_impl(&self, dir: String) -> StorageBackendResult<Vec<Entry>> {
        self.ensure_token().await?;
        let r = self.list_impl(dir.as_str()).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token().await?;
        self.list_impl(dir.as_str()).await
    }

    async fn search_with_retry_impl(
        &self,
        parent: String,
        keywords: String,
        scope: SearchScope,
        page: usize,
        per_page: usize,
    ) -> StorageBackendResult<SearchResult> {
        self.ensure_token().await?;
        let r = self
            .search_impl(parent.as_str(), keywords.as_str(), scope, page, per_page)
            .await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token().await?;
        self.search_impl(parent.as_str(), keywords.as_str(), scope, page, per_page)
            .await
    }

    async fn get_impl(&self, p: &str, byte_offset: u64) -> StorageBackendResult<StreamFile> {
        let (url, headers) = self
            .resolve_direct_http_request_impl(p, byte_offset)
            .await?;

        let mut attempt = 0;
        let resp = loop {
            let client = self.download_client()?;
            let url = url.clone();
            let headers = headers.clone();
            let resp = tokio_runtime()
                .spawn(async move {
                    tokio::time::timeout(
                        OPENLIST_DOWNLOAD_RESPONSE_TIMEOUT,
                        client.get(url).headers(headers).send(),
                    )
                    .await
                })
                .await;
            let resp = match resp {
                Ok(Ok(Ok(resp))) => resp,
                Ok(Ok(Err(e))) => {
                    let err: StorageBackendError = e.into();
                    if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                        attempt += 1;
                        sleep_backoff(retry_delay(attempt)).await?;
                        continue;
                    }
                    return Err(err);
                }
                Ok(Err(_)) => {
                    let err = StorageBackendError::Timeout {
                        operation: "openlist download response",
                        timeout_ms: OPENLIST_DOWNLOAD_RESPONSE_TIMEOUT.as_millis() as u64,
                    };
                    if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                        attempt += 1;
                        sleep_backoff(retry_delay(attempt)).await?;
                        continue;
                    }
                    return Err(err);
                }
                Err(e) => {
                    return Err(e.into());
                }
            };
            let resp = match resp.error_for_status() {
                Ok(resp) => resp,
                Err(e) => {
                    let err: StorageBackendError = e.into();
                    if should_retry_error(&err) && attempt < OPENLIST_MAX_RETRIES {
                        attempt += 1;
                        sleep_backoff(retry_delay(attempt)).await?;
                        continue;
                    }
                    return Err(err);
                }
            };
            break resp;
        };
        let byte_offset = if resp.headers().get(reqwest::header::CONTENT_RANGE).is_some() {
            0
        } else {
            byte_offset
        };
        let res = resp.error_for_status().map(|resp| {
            StreamFile::new_with_chunk_timeout(
                resp,
                byte_offset,
                Some(OPENLIST_DOWNLOAD_CHUNK_TIMEOUT),
            )
        })?;
        Ok(res)
    }

    async fn resolve_direct_http_impl(
        &self,
        p: &str,
    ) -> StorageBackendResult<ResolvedPlaybackSource> {
        self.resolve_direct_playback_source_impl(p)
            .await
            .map(ResolvedPlaybackSource::DirectHttp)
    }

    async fn get_with_retry_impl(
        &self,
        p: String,
        byte_offset: u64,
    ) -> StorageBackendResult<StreamFile> {
        self.ensure_token().await?;
        let r = self.get_impl(p.as_str(), byte_offset).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token().await?;
        self.get_impl(p.as_str(), byte_offset).await
    }

    async fn resolve_playback_source_with_retry_impl(
        &self,
        p: String,
    ) -> StorageBackendResult<ResolvedPlaybackSource> {
        self.ensure_token().await?;
        let r = self.resolve_direct_http_impl(p.as_str()).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token().await?;
        self.resolve_direct_http_impl(p.as_str()).await
    }
}

impl StorageBackend for OpenList {
    fn list(&self, dir: String) -> BoxFuture<'_, StorageBackendResult<Vec<Entry>>> {
        Box::pin(self.list_with_retry_impl(dir))
    }

    fn search(
        &self,
        parent: String,
        keywords: String,
        scope: SearchScope,
        page: usize,
        per_page: usize,
    ) -> BoxFuture<'_, StorageBackendResult<SearchResult>> {
        Box::pin(self.search_with_retry_impl(parent, keywords, scope, page, per_page))
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

#[cfg(test)]
mod test {
    use std::{
        convert::Infallible,
        net::SocketAddr,
        sync::{
            atomic::{AtomicUsize, Ordering},
            Arc, Mutex,
        },
        time::Duration,
    };

    use hyper::Body;
    use hyper::{Request, Response, StatusCode};
    use reqwest::header::HeaderValue;
    use tokio::task::JoinHandle;

    use crate::{backend::StorageBackend, ResolvedPlaybackSource, SearchScope};

    use super::{BuildOpenListArg, OpenList, OPENLIST_BROWSER_ACCEPT};

    const TEST_TOKEN: &str = "token-123";

    #[derive(Clone, Copy, Default)]
    struct RetryConfig {
        list_failures: usize,
        search_failures: usize,
        get_failures: usize,
        file_failures: usize,
        raw_url_host: Option<&'static str>,
        search_unavailable: bool,
        site_blocked: bool,
    }

    struct SetupServerRes {
        addr: String,
        handle: JoinHandle<()>,
        list_calls: Arc<AtomicUsize>,
        search_calls: Arc<AtomicUsize>,
        file_calls: Arc<AtomicUsize>,
        last_search_body: Arc<Mutex<Option<String>>>,
        last_search_headers: Arc<Mutex<Vec<(String, String)>>>,
    }
    impl SetupServerRes {
        pub fn addr(&self) -> String {
            self.addr.clone()
        }

        pub fn list_calls(&self) -> usize {
            self.list_calls.load(Ordering::SeqCst)
        }

        pub fn search_calls(&self) -> usize {
            self.search_calls.load(Ordering::SeqCst)
        }

        pub fn file_calls(&self) -> usize {
            self.file_calls.load(Ordering::SeqCst)
        }

        pub fn last_search_body(&self) -> Option<String> {
            self.last_search_body.lock().unwrap().clone()
        }

        pub fn last_search_headers(&self) -> Vec<(String, String)> {
            self.last_search_headers.lock().unwrap().clone()
        }
    }
    impl Drop for SetupServerRes {
        fn drop(&mut self) {
            self.handle.abort();
        }
    }

    async fn setup_server() -> SetupServerRes {
        setup_server_with_retry(RetryConfig::default()).await
    }

    async fn setup_server_with_retry(retry: RetryConfig) -> SetupServerRes {
        let addr: SocketAddr = ([127, 0, 0, 1], 0).into();
        let server = hyper::Server::bind(&addr);
        let port = server.local_addr().port();
        let list_calls = Arc::new(AtomicUsize::new(0));
        let search_calls = Arc::new(AtomicUsize::new(0));
        let get_calls = Arc::new(AtomicUsize::new(0));
        let file_calls = Arc::new(AtomicUsize::new(0));
        let list_calls_server = list_calls.clone();
        let search_calls_server = search_calls.clone();
        let get_calls_server = get_calls.clone();
        let file_calls_server = file_calls.clone();
        let last_search_body = Arc::new(Mutex::new(None));
        let last_search_headers = Arc::new(Mutex::new(Vec::new()));
        let last_search_body_server = last_search_body.clone();
        let last_search_headers_server = last_search_headers.clone();
        let make_service = hyper::service::make_service_fn(move |_| {
            let port = port;
            let retry = retry;
            let list_calls = list_calls_server.clone();
            let search_calls = search_calls_server.clone();
            let get_calls = get_calls_server.clone();
            let file_calls = file_calls_server.clone();
            let last_search_body = last_search_body_server.clone();
            let last_search_headers = last_search_headers_server.clone();
            async move {
                let func = move |req: Request<Body>| {
                    let list_calls = list_calls.clone();
                    let search_calls = search_calls.clone();
                    let get_calls = get_calls.clone();
                    let file_calls = file_calls.clone();
                    let last_search_body = last_search_body.clone();
                    let last_search_headers = last_search_headers.clone();
                    async move {
                        let path = req.uri().path().to_string();
                        match (req.method().as_str(), path.as_str()) {
                            ("POST", "/api/auth/login") => {
                                let body = r#"{"code":200,"message":"success","data":{"token":"token-123"}}"#;
                                Ok::<_, Infallible>(Response::new(Body::from(body)))
                            }
                            ("POST", "/api/fs/list") => {
                                let auth = req.headers().get(reqwest::header::AUTHORIZATION);
                                if auth.map(|v| v.to_str().ok()) != Some(Some(TEST_TOKEN)) {
                                    let mut resp = Response::new(Body::from(
                                        r#"{"code":401,"message":"unauthorized","data":null}"#,
                                    ));
                                    *resp.status_mut() = StatusCode::UNAUTHORIZED;
                                    return Ok::<_, Infallible>(resp);
                                }
                                let attempt = list_calls.fetch_add(1, Ordering::SeqCst);
                                if attempt < retry.list_failures {
                                    let mut resp = Response::new(Body::from("temporary failure"));
                                    *resp.status_mut() = StatusCode::SERVICE_UNAVAILABLE;
                                    return Ok::<_, Infallible>(resp);
                                }
                                let body = r#"{"code":200,"message":"success","data":{"content":[{"name":"a.bin","size":5,"is_dir":false,"sign":"sign","type":4}],"total":1}}"#;
                                Ok::<_, Infallible>(Response::new(Body::from(body)))
                            }
                            ("POST", "/api/fs/search") => {
                                let headers = req
                                    .headers()
                                    .iter()
                                    .map(|(name, value)| {
                                        (
                                            name.as_str().to_string(),
                                            value.to_str().unwrap_or_default().to_string(),
                                        )
                                    })
                                    .collect::<Vec<_>>();
                                *last_search_headers.lock().unwrap() = headers;
                                let body_bytes =
                                    hyper::body::to_bytes(req.into_body()).await.unwrap();
                                *last_search_body.lock().unwrap() =
                                    Some(String::from_utf8_lossy(&body_bytes).to_string());
                                let auth = reqwest::header::HeaderValue::from_static(TEST_TOKEN);
                                let stored_headers = last_search_headers.lock().unwrap().clone();
                                let auth_header = stored_headers
                                    .iter()
                                    .find(|(name, _)| {
                                        name.eq_ignore_ascii_case(
                                            reqwest::header::AUTHORIZATION.as_str(),
                                        )
                                    })
                                    .map(|(_, value)| value.clone());
                                if auth_header.as_deref() != Some(auth.to_str().unwrap()) {
                                    let mut resp = Response::new(Body::from(
                                        r#"{"code":401,"message":"unauthorized","data":null}"#,
                                    ));
                                    *resp.status_mut() = StatusCode::UNAUTHORIZED;
                                    return Ok::<_, Infallible>(resp);
                                }
                                let attempt = search_calls.fetch_add(1, Ordering::SeqCst);
                                if retry.site_blocked {
                                    let mut resp = Response::new(Body::from(
                                        "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Cloudflare challenge</body></html>",
                                    ));
                                    *resp.status_mut() = StatusCode::FORBIDDEN;
                                    resp.headers_mut().insert(
                                        reqwest::header::CONTENT_TYPE,
                                        HeaderValue::from_static("text/html; charset=UTF-8"),
                                    );
                                    return Ok::<_, Infallible>(resp);
                                }
                                if attempt < retry.search_failures {
                                    let mut resp = Response::new(Body::from("temporary failure"));
                                    *resp.status_mut() = StatusCode::BAD_GATEWAY;
                                    return Ok::<_, Infallible>(resp);
                                }
                                if retry.search_unavailable {
                                    let body = r#"{"code":404,"message":"search not available","data":null}"#;
                                    return Ok::<_, Infallible>(Response::new(Body::from(body)));
                                }
                                let body = r#"{"code":200,"message":"success","data":{"content":[{"parent":"/music","name":"target-song.mp3","is_dir":false,"size":12}],"total":1}}"#;
                                Ok::<_, Infallible>(Response::new(Body::from(body)))
                            }
                            ("POST", "/api/fs/get") => {
                                let attempt = get_calls.fetch_add(1, Ordering::SeqCst);
                                if attempt < retry.get_failures {
                                    let mut resp = Response::new(Body::from("temporary failure"));
                                    *resp.status_mut() = StatusCode::BAD_GATEWAY;
                                    return Ok::<_, Infallible>(resp);
                                }
                                let raw_host = retry.raw_url_host.unwrap_or("127.0.0.1");
                                let body = format!(
                            "{{\"code\":200,\"message\":\"success\",\"data\":{{\"name\":\"a.bin\",\"size\":5,\"is_dir\":false,\"sign\":\"sign\",\"type\":4,\"raw_url\":\"http://{raw_host}:{port}/d/a.bin\"}}}}"
                        );
                                Ok::<_, Infallible>(Response::new(Body::from(body)))
                            }
                            ("GET", "/d/a.bin") => {
                                let attempt = file_calls.fetch_add(1, Ordering::SeqCst);
                                if attempt < retry.file_failures {
                                    let mut resp = Response::new(Body::from("temporary failure"));
                                    *resp.status_mut() = StatusCode::SERVICE_UNAVAILABLE;
                                    return Ok::<_, Infallible>(resp);
                                }
                                let full = b"12345";
                                let range = req
                                    .headers()
                                    .get(reqwest::header::RANGE)
                                    .and_then(|v| v.to_str().ok());
                                let (body, status, content_range) = if let Some(range) = range {
                                    let start = range
                                        .strip_prefix("bytes=")
                                        .and_then(|v| v.trim_end_matches('-').parse::<usize>().ok())
                                        .unwrap_or(0);
                                    let slice = &full[start..];
                                    let content_range = format!(
                                        "bytes {}-{}/{}",
                                        start,
                                        full.len() - 1,
                                        full.len()
                                    );
                                    (
                                        slice.to_vec(),
                                        StatusCode::PARTIAL_CONTENT,
                                        Some(content_range),
                                    )
                                } else {
                                    (full.to_vec(), StatusCode::OK, None)
                                };
                                let body_len = body.len();
                                let mut resp = Response::new(Body::from(body));
                                *resp.status_mut() = status;
                                resp.headers_mut().insert(
                                    reqwest::header::CONTENT_LENGTH,
                                    HeaderValue::from_str(body_len.to_string().as_str()).unwrap(),
                                );
                                if let Some(content_range) = content_range {
                                    resp.headers_mut().insert(
                                        reqwest::header::CONTENT_RANGE,
                                        HeaderValue::from_str(content_range.as_str()).unwrap(),
                                    );
                                }
                                Ok::<_, Infallible>(resp)
                            }
                            _ => {
                                let mut resp = Response::new(Body::from("not found"));
                                *resp.status_mut() = StatusCode::NOT_FOUND;
                                Ok::<_, Infallible>(resp)
                            }
                        }
                    }
                };
                Ok::<_, Infallible>(hyper::service::service_fn(func))
            }
        });

        let server = server.serve(make_service);

        let handle = tokio::spawn(async move {
            server.await.unwrap();
        });
        tokio::time::sleep(Duration::from_millis(200)).await;

        SetupServerRes {
            addr: format!("http://127.0.0.1:{port}"),
            handle,
            list_calls,
            search_calls,
            file_calls,
            last_search_body,
            last_search_headers,
        }
    }

    #[tokio::test]
    async fn test_list_with_login() {
        let server = setup_server().await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });
        let list = backend.list("/".to_string()).await.unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].path, "/a.bin");
        assert!(!list[0].is_dir);
    }

    #[tokio::test]
    async fn test_get_with_range() {
        let server = setup_server().await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });
        let file = backend.get("/a.bin".to_string(), 2).await.unwrap();
        assert_eq!(file.size(), Some(3));

        let stream = file.into_rx();
        let chunk = stream.recv().await.unwrap().unwrap();
        assert_eq!(chunk.as_ref(), b"345");
    }

    #[tokio::test]
    async fn test_list_retry_on_server_error() {
        let server = setup_server_with_retry(RetryConfig {
            list_failures: 1,
            ..RetryConfig::default()
        })
        .await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });
        let list = backend.list("/".to_string()).await.unwrap();
        assert_eq!(list.len(), 1);
        assert!(server.list_calls() >= 2);
    }

    #[tokio::test]
    async fn test_get_retry_on_server_error() {
        let server = setup_server_with_retry(RetryConfig {
            file_failures: 1,
            ..RetryConfig::default()
        })
        .await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });
        let file = backend.get("/a.bin".to_string(), 0).await.unwrap();
        let bytes = file.bytes().await.unwrap();
        assert_eq!(bytes.as_ref(), b"12345");
        assert!(server.file_calls() >= 2);
    }

    #[tokio::test]
    async fn test_resolve_playback_source_returns_direct_http() {
        let server = setup_server().await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });

        let resolved = backend
            .resolve_playback_source("/a.bin".to_string())
            .await
            .unwrap();
        match resolved {
            ResolvedPlaybackSource::DirectHttp(source) => {
                assert_eq!(source.url, format!("{}/d/a.bin", server.addr()));
                assert_eq!(source.cache_key.as_deref(), Some("/a.bin"));
                assert!(source.headers.iter().any(|header| {
                    header
                        .name
                        .eq_ignore_ascii_case(reqwest::header::USER_AGENT.as_str())
                        && header.value == "EaseMusicPlayer/1.0"
                }));
                assert!(source.headers.iter().any(|header| {
                    header
                        .name
                        .eq_ignore_ascii_case(reqwest::header::AUTHORIZATION.as_str())
                        && header.value == TEST_TOKEN
                }));
            }
            other => panic!("unexpected resolved playback source: {other:?}"),
        }
    }

    #[tokio::test]
    async fn test_resolve_playback_source_does_not_forward_auth_to_cross_origin_raw_url() {
        let server = setup_server_with_retry(RetryConfig {
            raw_url_host: Some("localhost"),
            ..RetryConfig::default()
        })
        .await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });

        let resolved = backend
            .resolve_playback_source("/a.bin".to_string())
            .await
            .unwrap();
        match resolved {
            ResolvedPlaybackSource::DirectHttp(source) => {
                assert_eq!(
                    source.url,
                    format!(
                        "http://localhost:{}/d/a.bin",
                        server.addr().split(':').next_back().unwrap()
                    )
                );
                assert!(!source.headers.iter().any(|header| {
                    header
                        .name
                        .eq_ignore_ascii_case(reqwest::header::AUTHORIZATION.as_str())
                }));
            }
            other => panic!("unexpected resolved playback source: {other:?}"),
        }
    }

    #[tokio::test]
    async fn test_search_returns_entries_and_total() {
        let server = setup_server().await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });

        let result = backend
            .search(
                "/music".to_string(),
                "target".to_string(),
                SearchScope::All,
                1,
                20,
            )
            .await
            .unwrap();
        assert_eq!(result.total, 1);
        assert_eq!(result.entries.len(), 1);
        assert_eq!(result.entries[0].path, "/music/target-song.mp3");
        assert_eq!(result.entries[0].name, "target-song.mp3");
        assert_eq!(result.entries[0].size, Some(12));
        assert!(!result.entries[0].is_dir);
        assert_eq!(server.search_calls(), 1);
    }

    #[tokio::test]
    async fn test_search_sends_browser_compatible_headers_and_official_payload() {
        let server = setup_server().await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });

        backend
            .search(
                "/music".to_string(),
                "asmr".to_string(),
                SearchScope::File,
                2,
                15,
            )
            .await
            .unwrap();

        let headers = server.last_search_headers();
        assert!(headers.iter().any(|(name, value)| {
            name.eq_ignore_ascii_case(reqwest::header::USER_AGENT.as_str())
                && value.contains("Chrome/134")
        }));
        assert!(headers.iter().any(|(name, value)| {
            name.eq_ignore_ascii_case(reqwest::header::ACCEPT.as_str())
                && value == OPENLIST_BROWSER_ACCEPT
        }));
        assert!(headers.iter().any(|(name, value)| {
            name.eq_ignore_ascii_case(reqwest::header::ORIGIN.as_str()) && value == &server.addr()
        }));
        assert!(headers.iter().any(|(name, value)| {
            name.eq_ignore_ascii_case(reqwest::header::REFERER.as_str())
                && value == &format!("{}/", server.addr())
        }));
        let body = server.last_search_body().expect("search body");
        assert!(body.contains("\"parent\":\"/music\""));
        assert!(body.contains("\"keywords\":\"asmr\""));
        assert!(body.contains("\"scope\":2"));
        assert!(body.contains("\"page\":2"));
        assert!(body.contains("\"per_page\":15"));
        assert!(body.contains("\"password\":\"\""));
    }

    #[tokio::test]
    async fn test_search_maps_unavailable() {
        let server = setup_server_with_retry(RetryConfig {
            search_unavailable: true,
            ..RetryConfig::default()
        })
        .await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });

        let err = backend
            .search("/".to_string(), "asmr".to_string(), SearchScope::All, 1, 10)
            .await
            .unwrap_err();
        assert!(err.is_search_unavailable());
    }

    #[tokio::test]
    async fn test_search_maps_html_site_block_to_site_blocked() {
        let server = setup_server_with_retry(RetryConfig {
            site_blocked: true,
            ..RetryConfig::default()
        })
        .await;

        let backend = OpenList::new(BuildOpenListArg {
            addr: server.addr(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });

        let err = backend
            .search("/".to_string(), "asmr".to_string(), SearchScope::All, 1, 10)
            .await
            .unwrap_err();
        assert!(err.is_site_blocked());
    }
}
