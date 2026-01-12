use crate::backend::{Entry, StorageBackend, StorageBackendError, StorageBackendResult, StreamFile};

use ease_client_tokio::tokio_runtime;
use futures_util::future::BoxFuture;
use reqwest::header::HeaderValue;
use reqwest::StatusCode;
use reqwest::Url;
use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::json;

use std::cmp::Ordering;
use std::sync::OnceLock;
use std::time::Duration;

const OPENLIST_API_TIMEOUT: Duration = Duration::from_secs(25);
const OPENLIST_MAX_RETRIES: usize = 2;
const OPENLIST_RETRY_BASE_DELAY_MS: u64 = 400;
const OPENLIST_RETRY_MAX_DELAY_MS: u64 = 2000;
const OPENLIST_TCP_KEEPALIVE: Duration = Duration::from_secs(60);
const OPENLIST_POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(90);

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

async fn sleep_backoff(delay: Duration) -> StorageBackendResult<()> {
    tokio_runtime()
        .spawn(async move {
            tokio::time::sleep(delay).await;
        })
        .await?;
    Ok(())
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

    async fn post_api<T: DeserializeOwned>(
        &self,
        api_path: &str,
        body: serde_json::Value,
    ) -> StorageBackendResult<T> {
        let url = self.build_api_url(api_path)?;
        let body = serde_json::to_vec(&body)?;
        let mut attempt = 0;

        loop {
            let client = self.api_client()?;
            let token = self.token.read().await.clone();
            let body = body.clone();
            let url = url.clone();
            let resp = tokio_runtime().spawn(async move {
                let mut req = client
                    .post(url)
                    .header(reqwest::header::CONTENT_TYPE, "application/json")
                    .header(reqwest::header::USER_AGENT, "EaseMusicPlayer/1.0")
                    .body(body);
                if let Some(token) = token {
                    let mut val = HeaderValue::from_str(token.as_str()).unwrap();
                    val.set_sensitive(true);
                    req = req.header(reqwest::header::AUTHORIZATION, val);
                }
                req.send().await
            }).await;

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

    async fn list_with_retry_impl(&self, dir: String) -> StorageBackendResult<Vec<Entry>> {
        self.ensure_token().await?;
        let r = self.list_impl(dir.as_str()).await;
        if !is_auth_error(&r) {
            return r;
        }
        self.refresh_token().await?;
        self.list_impl(dir.as_str()).await
    }

    async fn get_impl(&self, p: &str, byte_offset: u64) -> StorageBackendResult<StreamFile> {
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

        let mut headers = reqwest::header::HeaderMap::new();
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
                if base.domain() == url.domain() {
                    let mut val = HeaderValue::from_str(token.as_str()).unwrap();
                    val.set_sensitive(true);
                    headers.insert(reqwest::header::AUTHORIZATION, val);
                }
            }
        }

        let mut attempt = 0;
        let resp = loop {
            let client = self.download_client()?;
            let url = url.clone();
            let headers = headers.clone();
            let resp = tokio_runtime()
                .spawn(async move { client.get(url).headers(headers).send().await })
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
        let res = resp
            .error_for_status()
            .map(|resp| StreamFile::new(resp, byte_offset))?;
        Ok(res)
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
}

impl StorageBackend for OpenList {
    fn list(&self, dir: String) -> BoxFuture<'_, StorageBackendResult<Vec<Entry>>> {
        Box::pin(self.list_with_retry_impl(dir))
    }

    fn get(&self, p: String, byte_offset: u64) -> BoxFuture<'_, StorageBackendResult<StreamFile>> {
        Box::pin(self.get_with_retry_impl(p, byte_offset))
    }
}

#[cfg(test)]
mod test {
    use std::{
        convert::Infallible,
        net::SocketAddr,
        sync::{
            atomic::{AtomicUsize, Ordering},
            Arc,
        },
        time::Duration,
    };

    use hyper::Body;
    use hyper::{Request, Response, StatusCode};
    use reqwest::header::HeaderValue;
    use tokio::task::JoinHandle;

    use crate::backend::StorageBackend;

    use super::{BuildOpenListArg, OpenList};

    const TEST_TOKEN: &str = "token-123";

    #[derive(Clone, Copy, Default)]
    struct RetryConfig {
        list_failures: usize,
        get_failures: usize,
        file_failures: usize,
    }

    struct SetupServerRes {
        addr: String,
        handle: JoinHandle<()>,
        list_calls: Arc<AtomicUsize>,
        file_calls: Arc<AtomicUsize>,
    }
    impl SetupServerRes {
        pub fn addr(&self) -> String {
            self.addr.clone()
        }

        pub fn list_calls(&self) -> usize {
            self.list_calls.load(Ordering::SeqCst)
        }

        pub fn file_calls(&self) -> usize {
            self.file_calls.load(Ordering::SeqCst)
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
        let get_calls = Arc::new(AtomicUsize::new(0));
        let file_calls = Arc::new(AtomicUsize::new(0));
        let list_calls_server = list_calls.clone();
        let get_calls_server = get_calls.clone();
        let file_calls_server = file_calls.clone();
        let make_service = hyper::service::make_service_fn(move |_| {
            let port = port;
            let retry = retry;
            let list_calls = list_calls_server.clone();
            let get_calls = get_calls_server.clone();
            let file_calls = file_calls_server.clone();
            async move {
                let func = move |req: Request<Body>| {
                let list_calls = list_calls.clone();
                let get_calls = get_calls.clone();
                let file_calls = file_calls.clone();
                async move {
                let path = req.uri().path().to_string();
                match (req.method().as_str(), path.as_str()) {
                    ("POST", "/api/auth/login") => {
                        let body =
                            r#"{"code":200,"message":"success","data":{"token":"token-123"}}"#;
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
                    ("POST", "/api/fs/get") => {
                        let attempt = get_calls.fetch_add(1, Ordering::SeqCst);
                        if attempt < retry.get_failures {
                            let mut resp = Response::new(Body::from("temporary failure"));
                            *resp.status_mut() = StatusCode::BAD_GATEWAY;
                            return Ok::<_, Infallible>(resp);
                        }
                        let body = format!(
                            "{{\"code\":200,\"message\":\"success\",\"data\":{{\"name\":\"a.bin\",\"size\":5,\"is_dir\":false,\"sign\":\"sign\",\"type\":4,\"raw_url\":\"http://127.0.0.1:{port}/d/a.bin\"}}}}"
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
                            let content_range =
                                format!("bytes {}-{}/{}", start, full.len() - 1, full.len());
                            (slice.to_vec(), StatusCode::PARTIAL_CONTENT, Some(content_range))
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
            file_calls,
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
}
