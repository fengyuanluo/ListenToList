use std::{io::ErrorKind, time::Duration};

use bytes::Bytes;
use ease_client_tokio::tokio_runtime;
use futures_util::future::BoxFuture;
use reqwest::StatusCode;
use tokio::io::{AsyncReadExt, AsyncSeekExt};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub path: String,
    pub size: Option<usize>,
    pub is_dir: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchScope {
    All,
    Directory,
    File,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResult {
    pub entries: Vec<Entry>,
    pub total: usize,
}

enum StreamFileInner {
    Response(reqwest::Response),
    Total(bytes::Bytes),
    FilePath(String),
}

pub struct StreamFile {
    inner: StreamFileInner,
    total: Option<usize>,
    content_type: Option<String>,
    name: String,
    byte_offset: u64,
    chunk_timeout: Option<Duration>,
}

#[derive(thiserror::Error, Debug)]
pub enum StorageBackendError {
    #[error(transparent)]
    RequestFail(#[from] reqwest::Error),
    #[error("{operation} timed out after {timeout_ms}ms")]
    Timeout {
        operation: &'static str,
        timeout_ms: u64,
    },
    #[error("Parse XML Fail")]
    ParseXMLFail,
    #[error(transparent)]
    TokioIO(#[from] tokio::io::Error),
    #[error(transparent)]
    TokioJoinError(#[from] tokio::task::JoinError),
    #[error("Url Parse Error")]
    UrlParseError(String),
    #[error("Unsupported auth challenge: {0}")]
    UnsupportedAuthChallenge(String),
    #[error("Serde Json Error: {0}")]
    SerdeJsonError(#[from] serde_json::Error),
    #[error("QuickXML De Error: {0}")]
    QuickXMLDeError(#[from] quick_xml::DeError),
    #[error("Api Error: {code} {message}")]
    ApiError { code: i64, message: String },
    #[error("Search unavailable")]
    SearchUnavailable,
    #[error("Site blocked request with HTTP {status_code} ({provider})")]
    SiteBlocked { status_code: u16, provider: String },
}

#[derive(thiserror::Error, Debug)]
enum SendChunkError {
    #[error(transparent)]
    Backend(#[from] StorageBackendError),
    #[error("mpsc send error: {0}")]
    MpscSendError(#[from] async_channel::SendError<StorageBackendResult<Bytes>>),
}

pub type StorageBackendResult<T> = std::result::Result<T, StorageBackendError>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaybackHttpHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectHttpPlaybackSource {
    pub url: String,
    pub headers: Vec<PlaybackHttpHeader>,
    pub cache_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolvedPlaybackSource {
    DirectHttp(DirectHttpPlaybackSource),
    LocalFile { absolute_path: String },
    StreamFallback,
}

impl StorageBackendError {
    pub fn is_timeout(&self) -> bool {
        match self {
            StorageBackendError::RequestFail(e) => e.is_timeout(),
            StorageBackendError::Timeout { .. } => true,
            _ => false,
        }
    }

    pub fn is_unauthorized(&self) -> bool {
        if let StorageBackendError::RequestFail(e) = self {
            return e.status() == Some(StatusCode::UNAUTHORIZED);
        }
        if let StorageBackendError::ApiError { code, .. } = self {
            return *code == 401 || *code == 403;
        }
        false
    }

    pub fn is_not_found(&self) -> bool {
        match self {
            StorageBackendError::RequestFail(e) => e.status() == Some(StatusCode::NOT_FOUND),
            StorageBackendError::TokioIO(e) => e.kind() == ErrorKind::NotFound,
            StorageBackendError::ApiError { code, .. } => *code == 404,
            _ => false,
        }
    }

    pub fn is_search_unavailable(&self) -> bool {
        matches!(self, StorageBackendError::SearchUnavailable)
            || matches!(
                self,
                StorageBackendError::ApiError { code, message }
                    if *code == 404 && message.eq_ignore_ascii_case("search not available")
            )
    }

    pub fn is_site_blocked(&self) -> bool {
        matches!(self, StorageBackendError::SiteBlocked { .. })
    }
}

pub trait StorageBackend {
    fn list(&self, dir: String) -> BoxFuture<'_, StorageBackendResult<Vec<Entry>>>;
    fn get(&self, p: String, byte_offset: u64) -> BoxFuture<'_, StorageBackendResult<StreamFile>>;
    fn search(
        &self,
        _parent: String,
        _keywords: String,
        _scope: SearchScope,
        _page: usize,
        _per_page: usize,
    ) -> BoxFuture<'_, StorageBackendResult<SearchResult>> {
        Box::pin(async { Err(StorageBackendError::SearchUnavailable) })
    }
    fn resolve_playback_source(
        &self,
        _p: String,
    ) -> BoxFuture<'_, StorageBackendResult<ResolvedPlaybackSource>> {
        Box::pin(async { Ok(ResolvedPlaybackSource::StreamFallback) })
    }
}

#[cfg(test)]
mod test {
    use std::{
        convert::Infallible,
        fs,
        future::Future,
        net::SocketAddr,
        pin::Pin,
        sync::Arc,
        task::{Context, Poll, Wake, Waker},
        thread,
        time::Duration,
    };

    use bytes::Bytes;
    use hyper::{Body, Response, StatusCode};

    use super::{StorageBackendError, StreamFile};

    struct ThreadWaker(thread::Thread);

    impl Wake for ThreadWaker {
        fn wake(self: Arc<Self>) {
            self.0.unpark();
        }

        fn wake_by_ref(self: &Arc<Self>) {
            self.0.unpark();
        }
    }

    fn block_on_without_tokio<F: Future>(future: F) -> F::Output {
        let waker: Waker = Waker::from(Arc::new(ThreadWaker(thread::current())));
        let mut context = Context::from_waker(&waker);
        let mut future = Box::pin(future);

        loop {
            match Pin::as_mut(&mut future).poll(&mut context) {
                Poll::Ready(value) => return value,
                Poll::Pending => thread::park(),
            }
        }
    }

    #[tokio::test]
    async fn stream_file_chunk_timeout_surfaces_timeout_error() {
        let addr: SocketAddr = ([127, 0, 0, 1], 0).into();
        let make_service = hyper::service::make_service_fn(|_| async move {
            Ok::<_, Infallible>(hyper::service::service_fn(|_| async move {
                let (mut tx, body) = Body::channel();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(120)).await;
                    let _ = tx.send_data(Bytes::from_static(b"delayed")).await;
                });
                let mut resp = Response::new(body);
                *resp.status_mut() = StatusCode::OK;
                Ok::<_, Infallible>(resp)
            }))
        });
        let server = hyper::Server::bind(&addr).serve(make_service);
        let port = server.local_addr().port();
        let handle = tokio::spawn(async move {
            server.await.unwrap();
        });

        let response = reqwest::get(format!("http://127.0.0.1:{port}/stream"))
            .await
            .expect("request")
            .error_for_status()
            .expect("status");
        let file = StreamFile::new_with_chunk_timeout(response, 0, Some(Duration::from_millis(40)));
        let rx = file.into_rx();
        let item = rx.recv().await.expect("stream result");
        match item {
            Err(StorageBackendError::Timeout { operation, .. }) => {
                assert_eq!(operation, "stream chunk read");
            }
            other => panic!("expected timeout error, got {other:?}"),
        }

        handle.abort();
    }

    #[tokio::test]
    async fn stream_file_without_content_length_still_streams() {
        let addr: SocketAddr = ([127, 0, 0, 1], 0).into();
        let make_service = hyper::service::make_service_fn(|_| async move {
            Ok::<_, Infallible>(hyper::service::service_fn(|_| async move {
                let (mut tx, body) = Body::channel();
                tokio::spawn(async move {
                    let _ = tx.send_data(Bytes::from_static(b"hello")).await;
                });
                let mut resp = Response::new(body);
                *resp.status_mut() = StatusCode::OK;
                resp.headers_mut().remove(reqwest::header::CONTENT_LENGTH);
                Ok::<_, Infallible>(resp)
            }))
        });
        let server = hyper::Server::bind(&addr).serve(make_service);
        let port = server.local_addr().port();
        let handle = tokio::spawn(async move {
            server.await.unwrap();
        });

        let response = reqwest::get(format!("http://127.0.0.1:{port}/stream"))
            .await
            .expect("request")
            .error_for_status()
            .expect("status");
        let file = StreamFile::new_with_chunk_timeout(response, 0, Some(Duration::from_millis(40)));
        let bytes = file.bytes().await.expect("bytes");
        assert_eq!(bytes.as_ref(), b"hello");

        handle.abort();
    }

    #[test]
    fn stream_file_bytes_reads_local_file_outside_tokio_context() {
        let dir = std::env::temp_dir().join(format!(
            "listen-to-list-stream-file-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("time")
                .as_nanos()
        ));
        fs::create_dir_all(&dir).expect("create temp dir");
        let file_path = dir.join("lyric.lrc");
        fs::write(&file_path, b"[00:01.00]hello\n").expect("write file");

        let file = StreamFile::new_from_file(
            file_path.to_string_lossy().to_string(),
            fs::metadata(&file_path).expect("metadata").len() as usize,
            0,
        );

        let bytes = block_on_without_tokio(file.bytes()).expect("read bytes");
        assert_eq!(bytes.as_ref(), b"[00:01.00]hello\n");

        let _ = fs::remove_file(&file_path);
        let _ = fs::remove_dir(&dir);
    }
}

impl StreamFile {
    pub fn new(resp: reqwest::Response, byte_offset: u64) -> Self {
        Self::new_with_chunk_timeout(resp, byte_offset, None)
    }

    pub fn new_with_chunk_timeout(
        resp: reqwest::Response,
        byte_offset: u64,
        chunk_timeout: Option<Duration>,
    ) -> Self {
        let url = resp.url().to_string();
        let name = url.split('/').next_back().unwrap();
        let header_map = resp.headers();
        let content_length = header_map
            .get(reqwest::header::CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<usize>().ok());
        let content_type = header_map
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.to_string());
        Self {
            inner: StreamFileInner::Response(resp),
            total: content_length,
            content_type,
            name: name.to_string(),
            byte_offset,
            chunk_timeout,
        }
    }
    pub fn new_from_bytes(buf: &[u8], name: &str, byte_offset: u64) -> Self {
        let total: usize = buf.len();
        let buf = bytes::Bytes::copy_from_slice(buf);
        let byte_offset = byte_offset.min(total as u64);
        Self {
            inner: StreamFileInner::Total(buf),
            total: Some(total),
            content_type: None,
            name: name.to_string(),
            byte_offset,
            chunk_timeout: None,
        }
    }
    pub fn new_from_file(path: String, total: usize, byte_offset: u64) -> Self {
        let byte_offset = byte_offset.min(total as u64);
        let name = std::path::Path::new(&path)
            .file_name()
            .map(|v| v.to_string_lossy().to_string())
            .unwrap_or_else(|| "Unknown".to_string());
        Self {
            inner: StreamFileInner::FilePath(path),
            total: Some(total),
            content_type: None,
            name,
            byte_offset,
            chunk_timeout: None,
        }
    }
    pub fn size(&self) -> Option<usize> {
        self.total.map(|total| total - self.byte_offset as usize)
    }
    pub fn content_type(&self) -> Option<&str> {
        self.content_type.as_deref()
    }
    pub fn name(&self) -> &str {
        self.name.as_str()
    }

    pub fn into_rx(self) -> async_channel::Receiver<StorageBackendResult<Bytes>> {
        let (tx, rx) = async_channel::bounded::<StorageBackendResult<Bytes>>(10);

        let _ = tokio_runtime().spawn(async move {
            let f = || async {
                match self.inner {
                    StreamFileInner::Response(mut response) => {
                        let mut remaining = self.byte_offset as usize;

                        loop {
                            let next_chunk = if let Some(timeout) = self.chunk_timeout {
                                match tokio::time::timeout(timeout, response.chunk()).await {
                                    Ok(result) => result.map_err(StorageBackendError::from)?,
                                    Err(_) => {
                                        return Err(StorageBackendError::Timeout {
                                            operation: "stream chunk read",
                                            timeout_ms: timeout.as_millis() as u64,
                                        }
                                        .into());
                                    }
                                }
                            } else {
                                response.chunk().await.map_err(StorageBackendError::from)?
                            };

                            let Some(chunk) = next_chunk else {
                                break;
                            };
                            if chunk.len() <= remaining {
                                remaining -= chunk.len();
                            } else if remaining > 0 {
                                let chunk = Bytes::copy_from_slice(&chunk[remaining..]);
                                remaining = 0;
                                tx.send(Ok(chunk)).await?;
                            } else {
                                tx.send(Ok(chunk)).await?;
                            }
                        }
                    }
                    StreamFileInner::Total(buf) => {
                        let offset = self.byte_offset as usize;
                        if offset == 0 {
                            tx.send(Ok(buf)).await?;
                        } else {
                            let buf = Bytes::copy_from_slice(&buf[offset..]);
                            tx.send(Ok(buf)).await?;
                        }
                    }
                    StreamFileInner::FilePath(path) => {
                        let mut file = match tokio::fs::File::open(&path).await {
                            Ok(file) => file,
                            Err(e) => {
                                let _ = tx.send(Err(StorageBackendError::TokioIO(e))).await;
                                return Ok(());
                            }
                        };
                        if let Err(e) = file.seek(std::io::SeekFrom::Start(self.byte_offset)).await
                        {
                            let _ = tx.send(Err(StorageBackendError::TokioIO(e))).await;
                            return Ok(());
                        }
                        let mut buf = vec![0u8; 64 * 1024];
                        loop {
                            let read = match file.read(&mut buf).await {
                                Ok(0) => break,
                                Ok(read) => read,
                                Err(e) => {
                                    let _ = tx.send(Err(StorageBackendError::TokioIO(e))).await;
                                    return Ok(());
                                }
                            };
                            tx.send(Ok(Bytes::copy_from_slice(&buf[..read]))).await?;
                        }
                    }
                }

                Ok(())
            };

            let res: Result<(), SendChunkError> = f().await;
            if let Err(e) = res {
                if let SendChunkError::Backend(e) = e {
                    let _ = tx.send(Err(e)).await;
                }
            }
            let _ = tx.close();
        });

        rx
    }

    pub async fn bytes(self) -> StorageBackendResult<Bytes> {
        let buf = match self.inner {
            StreamFileInner::Response(response) => response.bytes().await?,
            StreamFileInner::Total(buf) => buf,
            StreamFileInner::FilePath(path) => {
                let buf = tokio_runtime()
                    .spawn(async move { tokio::fs::read(path).await })
                    .await??;
                Bytes::from(buf)
            }
        };

        let offset = (self.byte_offset as usize).min(buf.len());
        if offset == 0 {
            Ok(buf)
        } else {
            let buf = Bytes::copy_from_slice(&buf[offset..]);
            Ok(buf)
        }
    }
}
