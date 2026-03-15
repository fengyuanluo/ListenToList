use crate::backend::{
    DirectHttpPlaybackSource, Entry, PlaybackHttpHeader, ResolvedPlaybackSource, StorageBackend,
    StorageBackendResult, StreamFile,
};
use crate::StorageBackendError;

use base64::Engine;
use ease_client_tokio::tokio_runtime;
use futures_util::future::BoxFuture;
use reqwest::header::HeaderValue;
use reqwest::{StatusCode, Url};

use std::cmp::Ordering;

use std::sync::OnceLock;
use std::sync::RwLock;
use std::time::Duration;

const WEBDAV_TCP_KEEPALIVE: Duration = Duration::from_secs(60);
const WEBDAV_POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(90);
const WEBDAV_HTTP1_ONLY_COMPAT: bool = true;
const WEBDAV_DOWNLOAD_RESPONSE_TIMEOUT: Duration = Duration::from_secs(20);
const WEBDAV_DOWNLOAD_CHUNK_TIMEOUT: Duration = Duration::from_secs(30);

pub struct Webdav {
    addr: String,
    username: String,
    password: String,
    _is_anonymous: bool,
    last_www_authenticate: RwLock<Option<String>>,
    connect_timeout: Duration,
    client: OnceLock<reqwest::Client>,
}

pub struct BuildWebdavArg {
    pub addr: String,
    pub username: String,
    pub password: String,
    pub is_anonymous: bool,
    pub connect_timeout: Duration,
}

mod webdav_list_types {
    use serde::Deserialize;

    #[derive(Deserialize, Debug)]
    pub struct Collection {}

    #[derive(Deserialize, Debug)]
    pub struct ResourceType {
        pub collection: Option<Collection>,
    }

    #[derive(Deserialize, Debug)]
    pub struct Prop {
        pub displayname: Option<String>,
        pub resourcetype: ResourceType,
        pub getcontentlength: Option<usize>,
    }

    #[derive(Deserialize, Debug)]
    pub struct PropStat {
        pub prop: Prop,
    }

    #[derive(Deserialize, Debug)]
    pub struct Response {
        pub href: String,
        pub propstat: PropStat,
    }

    #[derive(Deserialize, Debug)]
    pub struct Root {
        pub response: Vec<Response>,
    }
}

fn normalize_path(p: String) -> String {
    if p.starts_with('/') {
        p
    } else {
        "/".to_string() + p.as_str()
    }
}

fn build_authorization_header_value(
    www_authenticate: &str,
    username: &str,
    password: &str,
    uri: &str,
    method: &str,
) -> StorageBackendResult<Option<String>> {
    if www_authenticate.is_empty() {
        return Ok(None);
    }
    let mut pw_client = http_auth::PasswordClient::try_from(www_authenticate)
        .map_err(|e| StorageBackendError::UnsupportedAuthChallenge(e.to_string()))?;
    let ret = pw_client
        .respond(&http_auth::PasswordParams {
            username,
            password,
            uri,
            method,
            body: Some(&[]),
        })
        .map_err(|e| StorageBackendError::UnsupportedAuthChallenge(e.to_string()))?;
    Ok(Some(ret))
}

fn build_basic_authorization_header_value(username: &str, password: &str) -> String {
    let encoded =
        base64::engine::general_purpose::STANDARD.encode(format!("{username}:{password}"));
    format!("Basic {encoded}")
}

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

impl Webdav {
    pub fn new(arg: BuildWebdavArg) -> Self {
        Self {
            addr: arg.addr,
            username: arg.username,
            password: arg.password,
            _is_anonymous: arg.is_anonymous,
            last_www_authenticate: Default::default(),
            connect_timeout: arg.connect_timeout,
            client: OnceLock::new(),
        }
    }

    fn post_handle_response(&self, resp: &reqwest::Response) {
        let headers = resp.headers();
        let www_authenticate = headers.get(reqwest::header::WWW_AUTHENTICATE);
        if let Some(www_authenticate) = www_authenticate {
            let www_authenticate = www_authenticate.to_str();
            if let Ok(www_authenticate) = www_authenticate {
                {
                    let mut writter = self.last_www_authenticate.write().unwrap();
                    *writter = Some(www_authenticate.to_string());
                }
            }
        }
    }

    fn build_base_header_map(
        &self,
        method: reqwest::Method,
        uri: &reqwest::Url,
    ) -> StorageBackendResult<reqwest::header::HeaderMap> {
        let mut header_map = reqwest::header::HeaderMap::new();
        header_map.append(
            reqwest::header::CONTENT_TYPE,
            HeaderValue::from_bytes(b"application/xml").unwrap(),
        );
        header_map.append(
            reqwest::header::ACCEPT,
            HeaderValue::from_bytes(b"application/xml").unwrap(),
        );
        {
            let www_authenticate = self.last_www_authenticate.read().unwrap().clone();
            if www_authenticate.is_some() {
                let www_authenticate = www_authenticate.unwrap();
                let auth = build_authorization_header_value(
                    &www_authenticate,
                    &self.username,
                    &self.password,
                    uri.as_str(),
                    method.as_str(),
                )?;
                if let Some(auth) = auth {
                    let mut val = HeaderValue::from_str(auth.as_str()).map_err(|e| {
                        StorageBackendError::UnsupportedAuthChallenge(e.to_string())
                    })?;
                    val.set_sensitive(true);
                    header_map.append(reqwest::header::AUTHORIZATION, val);
                }
            }
        }
        Ok(header_map)
    }

    fn get_url<const IS_DIR: bool>(&self, p: &str) -> StorageBackendResult<Url> {
        let mut url = reqwest::Url::parse(&self.addr)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;
        let base = url.path();
        let mut p = base.trim_end_matches('/').to_string() + "/" + p.trim_start_matches('/');
        if IS_DIR {
            if !p.ends_with('/') {
                p += "/";
            }
        }
        url.set_path(&p);
        Ok(url)
    }

    fn get_href(&self, dir: &str) -> StorageBackendResult<String> {
        let url = reqwest::Url::parse(&self.addr)
            .map_err(|e| StorageBackendError::UrlParseError(e.to_string()))?;
        let base = normalize_path(url.path().to_string());
        Ok(normalize_path(dir.trim_start_matches(base.as_str()).into()))
    }

    async fn list_core(&self, dir: &str) -> StorageBackendResult<reqwest::Response> {
        let url = self.get_url::<true>(dir)?;

        let method = reqwest::Method::from_bytes(b"PROPFIND").unwrap();
        let resp = {
            let client = self.build_client()?;
            let headers = self.build_base_header_map(method.clone(), &url)?;

            tokio_runtime()
                .spawn(async move {
                    client
                        .request(method.clone(), url.clone())
                        .headers(headers)
                        .header("Depth", 1)
                        .body(
                            r#"<?xml version="1.0" ?>
                <D:propfind xmlns:D="DAV:">
                <D:allprop/>
                </D:propfind>"#,
                        )
                        .send()
                        .await
                })
                .await??
        };
        self.post_handle_response(&resp);

        Ok(resp)
    }

    async fn list_impl(&self, dir: &str) -> StorageBackendResult<Vec<Entry>> {
        let resp = self.list_core(dir).await?.error_for_status()?;
        let text: String = resp.text().await?;
        let obj: webdav_list_types::Root = quick_xml::de::from_str(&text).map_err(|e| {
            tracing::error!("webdav list resp: {text}");
            e
        })?;

        let mut ret: Vec<Entry> = Default::default();
        for item in obj.response {
            let path = item.href;
            let mut name = item.propstat.prop.displayname.unwrap_or(Default::default());
            let is_dir = item.propstat.prop.resourcetype.collection.is_some();
            let size = item.propstat.prop.getcontentlength;
            let mut path = self.get_href(path.as_str())?;

            if path == "/" {
                continue;
            }
            if path.ends_with("/") {
                path.pop();
            }
            if path == dir || (dir.ends_with('/') && dir[0..dir.len() - 1] == path) {
                continue;
            }
            if name.is_empty() {
                let splited: Vec<&str> = path.split("/").collect();
                if !splited.is_empty() {
                    name = splited.last().unwrap().to_string();
                }
            }
            name = urlencoding::decode(name.as_str())
                .map(|v| v.to_string())
                .unwrap_or(name);

            ret.push(Entry {
                name,
                path,
                size,
                is_dir,
            });
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
        let r = self.list_impl(dir.as_str()).await;
        if !is_auth_error(&r) {
            return r;
        }
        return self.list_impl(dir.as_str()).await;
    }

    async fn get_impl(&self, p: &str, byte_offset: u64) -> StorageBackendResult<StreamFile> {
        let url = self.get_url::<false>(p)?;

        let mut headers = self.build_base_header_map(reqwest::Method::GET, &url)?;
        headers.insert(
            reqwest::header::RANGE,
            HeaderValue::from_str(format!("bytes={byte_offset}-").as_str()).unwrap(),
        );

        let resp = {
            let client = self.build_client()?;
            tokio_runtime()
                .spawn(async move {
                    tokio::time::timeout(
                        WEBDAV_DOWNLOAD_RESPONSE_TIMEOUT,
                        client.get(url.clone()).headers(headers).send(),
                    )
                    .await
                })
                .await?
        };
        let resp = match resp {
            Ok(resp) => resp?,
            Err(_) => {
                return Err(StorageBackendError::Timeout {
                    operation: "webdav download response",
                    timeout_ms: WEBDAV_DOWNLOAD_RESPONSE_TIMEOUT.as_millis() as u64,
                });
            }
        };
        let byte_offset = if resp.headers().get(reqwest::header::CONTENT_RANGE).is_some() {
            0
        } else {
            byte_offset
        };
        self.post_handle_response(&resp);

        let res = resp.error_for_status().map(|resp| {
            StreamFile::new_with_chunk_timeout(
                resp,
                byte_offset,
                Some(WEBDAV_DOWNLOAD_CHUNK_TIMEOUT),
            )
        })?;
        Ok(res)
    }

    async fn get_with_retry_impl(
        &self,
        p: String,
        byte_offset: u64,
    ) -> StorageBackendResult<StreamFile> {
        let r = self.get_impl(p.as_str(), byte_offset).await;
        if !is_auth_error(&r) {
            return r;
        }
        return self.get_impl(p.as_str(), byte_offset).await;
    }

    fn build_client(&self) -> StorageBackendResult<reqwest::Client> {
        if let Some(client) = self.client.get() {
            return Ok(client.clone());
        }
        let mut builder = reqwest::Client::builder()
            .connect_timeout(self.connect_timeout)
            .tcp_keepalive(Some(WEBDAV_TCP_KEEPALIVE))
            .pool_idle_timeout(Some(WEBDAV_POOL_IDLE_TIMEOUT))
            .pool_max_idle_per_host(6)
            .no_proxy();
        if WEBDAV_HTTP1_ONLY_COMPAT {
            // Keep HTTP/1.1 for compatibility with older WebDAV servers and proxies.
            builder = builder.http1_only();
        }
        let client = builder.build()?;
        let _ = self.client.set(client);
        Ok(self.client.get().expect("webdav client missing").clone())
    }

    fn build_direct_playback_source(
        &self,
        p: &str,
    ) -> StorageBackendResult<Option<DirectHttpPlaybackSource>> {
        let url = self.get_url::<false>(p)?;
        if self._is_anonymous {
            return Ok(Some(DirectHttpPlaybackSource {
                url: url.to_string(),
                headers: vec![],
                cache_key: Some(p.to_string()),
            }));
        }

        let challenge = self.last_www_authenticate.read().unwrap().clone();
        let Some(challenge) = challenge else {
            return Ok(None);
        };
        if !challenge.to_ascii_lowercase().starts_with("basic") {
            return Ok(None);
        }

        Ok(Some(DirectHttpPlaybackSource {
            url: url.to_string(),
            headers: vec![PlaybackHttpHeader {
                name: reqwest::header::AUTHORIZATION.as_str().to_string(),
                value: build_basic_authorization_header_value(&self.username, &self.password),
            }],
            cache_key: Some(p.to_string()),
        }))
    }
}

impl StorageBackend for Webdav {
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
        Box::pin(async move {
            Ok(self
                .build_direct_playback_source(p.as_str())?
                .map(ResolvedPlaybackSource::DirectHttp)
                .unwrap_or(ResolvedPlaybackSource::StreamFallback))
        })
    }
}

#[cfg(test)]
mod test {
    use std::{convert::Infallible, net::SocketAddr, time::Duration};

    use dav_server::{fakels::FakeLs, localfs::LocalFs, DavHandler};
    use tokio::task::JoinHandle;

    use crate::backend::StorageBackend;
    use crate::{ResolvedPlaybackSource, StorageBackendError};

    use super::{BuildWebdavArg, Webdav};

    struct SetupServerRes {
        addr: String,
        handle: JoinHandle<()>,
    }
    impl SetupServerRes {
        pub fn addr(&self) -> String {
            self.addr.clone()
        }
    }
    impl Drop for SetupServerRes {
        fn drop(&mut self) {
            self.handle.abort();
        }
    }

    async fn setup_server(p: &str) -> SetupServerRes {
        let dav_server = DavHandler::builder()
            .filesystem(LocalFs::new(p, false, false, false))
            .locksystem(FakeLs::new())
            .autoindex(true)
            .build_handler();

        let addr: SocketAddr = ([127, 0, 0, 1], 0).into();
        let make_service = hyper::service::make_service_fn(move |_| {
            let dav_server = dav_server.clone();
            async move {
                let func = move |req| {
                    let dav_server = dav_server.clone();
                    async move { Ok::<_, Infallible>(dav_server.handle(req).await) }
                };
                Ok::<_, Infallible>(hyper::service::service_fn(func))
            }
        });

        let server = hyper::Server::bind(&addr).serve(make_service);
        let port = server.local_addr().port();

        let handle = tokio::spawn(async move {
            server.await.unwrap();
        });
        tokio::time::sleep(Duration::from_millis(200)).await;

        SetupServerRes {
            addr: format!("http://127.0.0.1:{port}"),
            handle,
        }
    }

    #[tokio::test]
    async fn test_list() {
        let server = setup_server("test/assets/case_list").await;

        let backend = Webdav::new(BuildWebdavArg {
            addr: server.addr(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });
        let list = backend.list("/".to_string()).await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].path, "/a.txt");
        assert_eq!(list[1].path, "/b.log.txt");
    }

    #[tokio::test]
    async fn test_file_content_1() {
        let server = setup_server("test/assets/case_content").await;

        let backend = Webdav::new(BuildWebdavArg {
            addr: server.addr(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });
        let mut list = backend.list("/".to_string()).await.unwrap();
        assert_eq!(list.len(), 1);

        let item = list.pop().unwrap();
        assert_eq!(item.path, "/a.bin");
        assert_eq!(item.size, Some(3));

        let file = backend.get(item.path, 0).await.unwrap();
        assert_eq!(file.size(), Some(3));

        let stream = file.into_rx();
        let chunk = stream.recv().await;
        assert!(chunk.is_ok());
        let chunk = chunk.unwrap().unwrap();
        assert_eq!(chunk.as_ref(), [49, 50, 51]);
    }

    #[tokio::test]
    async fn test_file_content_2() {
        let server = setup_server("test/assets/case_content_2").await;

        let backend = Webdav::new(BuildWebdavArg {
            addr: server.addr(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });
        let list = backend.list("/".to_string()).await.unwrap();
        assert_eq!(list.len(), 2);
        let item = &list[0];
        assert_eq!(item.path, "/b-folder");
        assert_eq!(item.size, None);
        let item = &list[1];
        assert_eq!(item.path, "/a.bin");
        assert_eq!(item.size, Some(3));

        let list = backend.list("/b-folder".to_string()).await.unwrap();
        assert_eq!(list.len(), 1);
        let item = &list[0];
        assert_eq!(item.path, "/b-folder/b.bin");
        assert_eq!(item.size, Some(3));

        let file = backend.get(item.path.to_string(), 0).await.unwrap();
        assert_eq!(file.size(), Some(3));

        let stream = file.into_rx();
        let chunk = stream.recv().await;
        assert!(chunk.is_ok());
        let chunk = chunk.unwrap().unwrap();
        assert_eq!(chunk.as_ref(), [49, 50, 51]);
    }

    #[tokio::test]
    async fn test_file_content_1_partial_stream() {
        let server = setup_server("test/assets/case_content").await;

        let backend = Webdav::new(BuildWebdavArg {
            addr: server.addr(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });
        let file = backend.get("/a.bin".to_string(), 2).await.unwrap();
        assert_eq!(file.size(), Some(1));

        let stream = file.into_rx();
        let chunk = stream.recv().await;
        assert!(chunk.is_ok());
        let chunk = chunk.unwrap().unwrap();
        assert_eq!(chunk.as_ref(), [51]);
    }

    #[tokio::test]
    async fn test_file_content_1_partial_bytes() {
        let server = setup_server("test/assets/case_content").await;

        let backend = Webdav::new(BuildWebdavArg {
            addr: server.addr(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });
        let file = backend.get("/a.bin".to_string(), 2).await.unwrap();
        assert_eq!(file.size(), Some(1));

        let chunk = file.bytes().await.unwrap();
        assert_eq!(chunk.as_ref(), [51]);
    }

    #[tokio::test]
    async fn test_resolve_playback_source_returns_direct_http_for_anonymous_webdav() {
        let server = setup_server("test/assets/case_content").await;

        let backend = Webdav::new(BuildWebdavArg {
            addr: server.addr(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });
        let resolved = backend
            .resolve_playback_source("/a.bin".to_string())
            .await
            .unwrap();
        match resolved {
            ResolvedPlaybackSource::DirectHttp(source) => {
                assert_eq!(source.url, format!("{}/a.bin", server.addr()));
                assert!(source.headers.is_empty());
                assert_eq!(source.cache_key.as_deref(), Some("/a.bin"));
            }
            other => panic!("unexpected resolved playback source: {other:?}"),
        }
    }

    #[test]
    fn test_resolve_playback_source_returns_basic_auth_header_when_challenge_is_basic() {
        let backend = Webdav::new(BuildWebdavArg {
            addr: "http://127.0.0.1:8080".to_string(),
            username: "user".to_string(),
            password: "pass".to_string(),
            is_anonymous: false,
            connect_timeout: Duration::from_secs(10),
        });
        *backend.last_www_authenticate.write().unwrap() = Some("Basic realm=\"dav\"".to_string());

        let resolved = ease_client_tokio::tokio_runtime()
            .block_on(backend.resolve_playback_source("/a.bin".to_string()))
            .unwrap();
        match resolved {
            ResolvedPlaybackSource::DirectHttp(source) => {
                assert!(source.headers.iter().any(|header| {
                    header
                        .name
                        .eq_ignore_ascii_case(reqwest::header::AUTHORIZATION.as_str())
                        && header.value.starts_with("Basic ")
                }));
            }
            other => panic!("unexpected resolved playback source: {other:?}"),
        }
    }

    #[test]
    fn test_build_authorization_header_value_returns_error_for_invalid_challenge() {
        let err =
            super::build_authorization_header_value("Digest", "user", "pass", "/a.bin", "GET")
                .expect_err("invalid challenge should fail");
        assert!(matches!(
            err,
            StorageBackendError::UnsupportedAuthChallenge(_)
        ));
    }

    #[test]
    fn test_build_client_is_cached() {
        let backend = Webdav::new(BuildWebdavArg {
            addr: "http://127.0.0.1:8080".to_string(),
            username: Default::default(),
            password: Default::default(),
            is_anonymous: true,
            connect_timeout: Duration::from_secs(10),
        });

        assert!(backend.client.get().is_none());
        let _ = backend.build_client().unwrap();
        let first = backend
            .client
            .get()
            .map(|client| client as *const _)
            .unwrap();
        let _ = backend.build_client().unwrap();
        let second = backend
            .client
            .get()
            .map(|client| client as *const _)
            .unwrap();
        assert_eq!(first, second);
    }
}
