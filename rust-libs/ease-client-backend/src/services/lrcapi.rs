use std::time::Duration;

use crate::objects::Lyrics;
use reqwest::{
    header::{HeaderMap, HeaderName, HeaderValue, AUTHORIZATION},
    Client, StatusCode,
};

use super::lyrics::parse_lrc;

const LRCAPI_USER_AGENT: &str = "ListenToList/0.3.0 (+https://github.com/fengyuanluo/ListenToList)";
const AUTHENTICATION_HEADER: &str = "authentication";
const LYRICS_NOT_FOUND_TEXT: &str = "Lyrics not found.";

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct LrcApiConfig {
    pub enabled: bool,
    pub base_url: String,
    pub auth_key: Option<String>,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct LrcApiQuery {
    pub title: String,
    pub artist: String,
    pub album: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum LrcApiFetchStatus {
    Disabled,
    Loaded,
    #[default]
    Missing,
    Unauthorized,
    InvalidConfig,
    Failed,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct LrcApiFetchResult {
    pub lyrics: Option<Lyrics>,
    pub lyrics_status: LrcApiFetchStatus,
    pub cover: Option<Vec<u8>>,
    pub cover_status: LrcApiFetchStatus,
}

pub(crate) async fn fetch_lrcapi_music_supplement(
    config: &LrcApiConfig,
    query: &LrcApiQuery,
) -> LrcApiFetchResult {
    if !config.enabled {
        return status_only_result(LrcApiFetchStatus::Disabled);
    }

    let Some(base_url) = normalize_base_url(config.base_url.as_str()) else {
        return status_only_result(LrcApiFetchStatus::InvalidConfig);
    };

    let client = match build_client() {
        Ok(client) => client,
        Err(error) => {
            tracing::error!("failed to build lrcapi client: {error}");
            return status_only_result(LrcApiFetchStatus::Failed);
        }
    };

    let auth_key = config
        .auth_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());

    let query = query.clone();
    let base_url = base_url.to_string();
    let auth_key = auth_key.map(str::to_string);
    let task = ease_client_tokio::tokio_runtime().spawn(async move {
        let (lyrics, lyrics_status) = if query.title.trim().is_empty() {
            (None, LrcApiFetchStatus::Missing)
        } else {
            fetch_lyrics(&client, base_url.as_str(), &query, auth_key.as_deref()).await
        };
        let (cover, cover_status) = if is_cover_query_empty(&query) {
            (None, LrcApiFetchStatus::Missing)
        } else {
            fetch_cover(&client, base_url.as_str(), &query, auth_key.as_deref()).await
        };

        LrcApiFetchResult {
            lyrics,
            lyrics_status,
            cover,
            cover_status,
        }
    });

    match task.await {
        Ok(result) => result,
        Err(error) => {
            tracing::error!("lrcapi runtime task failed: {error}");
            status_only_result(LrcApiFetchStatus::Failed)
        }
    }
}

fn status_only_result(status: LrcApiFetchStatus) -> LrcApiFetchResult {
    LrcApiFetchResult {
        lyrics_status: status,
        cover_status: status,
        ..Default::default()
    }
}

fn normalize_base_url(input: &str) -> Option<String> {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return None;
    }
    let url = reqwest::Url::parse(trimmed).ok()?;
    let scheme = url.scheme();
    if scheme != "http" && scheme != "https" {
        return None;
    }
    Some(trimmed.to_string())
}

fn build_client() -> reqwest::Result<Client> {
    reqwest::Client::builder()
        .user_agent(LRCAPI_USER_AGENT)
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::limited(10))
        .build()
}

fn is_cover_query_empty(query: &LrcApiQuery) -> bool {
    query.title.trim().is_empty() && query.artist.trim().is_empty() && query.album.trim().is_empty()
}

fn build_query_pairs(query: &LrcApiQuery) -> Vec<(&'static str, String)> {
    vec![
        ("title", query.title.trim().to_string()),
        ("artist", query.artist.trim().to_string()),
        ("album", query.album.trim().to_string()),
    ]
}

fn build_auth_headers(header_name: HeaderName, auth_key: &str) -> Option<HeaderMap> {
    let value = HeaderValue::from_str(auth_key).ok()?;
    let mut headers = HeaderMap::new();
    headers.insert(header_name, value);
    Some(headers)
}

async fn get_with_auth_fallback(
    client: &Client,
    url: &str,
    query_pairs: &[(&'static str, String)],
    auth_key: Option<&str>,
) -> Result<reqwest::Response, reqwest::Error> {
    let Some(auth_key) = auth_key else {
        return client.get(url).query(query_pairs).send().await;
    };

    let first_headers = build_auth_headers(AUTHORIZATION, auth_key);
    let first = client
        .get(url)
        .headers(first_headers.unwrap_or_default())
        .query(query_pairs)
        .send()
        .await?;
    if first.status() != StatusCode::UNAUTHORIZED && first.status() != StatusCode::FORBIDDEN {
        return Ok(first);
    }

    let fallback_headers =
        build_auth_headers(HeaderName::from_static(AUTHENTICATION_HEADER), auth_key);
    client
        .get(url)
        .headers(fallback_headers.unwrap_or_default())
        .query(query_pairs)
        .send()
        .await
}

async fn fetch_lyrics(
    client: &Client,
    base_url: &str,
    query: &LrcApiQuery,
    auth_key: Option<&str>,
) -> (Option<Lyrics>, LrcApiFetchStatus) {
    let url = format!("{base_url}/lyrics");
    let query_pairs = build_query_pairs(query);
    let response = match get_with_auth_fallback(client, url.as_str(), &query_pairs, auth_key).await
    {
        Ok(response) => response,
        Err(error) => {
            tracing::error!("lrcapi lyrics request failed: {error}");
            return (None, LrcApiFetchStatus::Failed);
        }
    };

    match response.status() {
        StatusCode::OK => {
            let body = match response.text().await {
                Ok(body) => body,
                Err(error) => {
                    tracing::error!("lrcapi lyrics body read failed: {error}");
                    return (None, LrcApiFetchStatus::Failed);
                }
            };
            let body = body.trim();
            if body.is_empty() || body.eq_ignore_ascii_case(LYRICS_NOT_FOUND_TEXT) {
                return (None, LrcApiFetchStatus::Missing);
            }
            match parse_lrc(body.to_string()) {
                Ok(lyrics) => (Some(lyrics), LrcApiFetchStatus::Loaded),
                Err(error) => {
                    tracing::error!("lrcapi lyrics parse failed: {error}");
                    (None, LrcApiFetchStatus::Failed)
                }
            }
        }
        StatusCode::NOT_FOUND => (None, LrcApiFetchStatus::Missing),
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => (None, LrcApiFetchStatus::Unauthorized),
        status => {
            tracing::error!("lrcapi lyrics unexpected status: {status}");
            (None, LrcApiFetchStatus::Failed)
        }
    }
}

async fn fetch_cover(
    client: &Client,
    base_url: &str,
    query: &LrcApiQuery,
    auth_key: Option<&str>,
) -> (Option<Vec<u8>>, LrcApiFetchStatus) {
    let url = format!("{base_url}/cover");
    let query_pairs = build_query_pairs(query);
    let response = match get_with_auth_fallback(client, url.as_str(), &query_pairs, auth_key).await
    {
        Ok(response) => response,
        Err(error) => {
            tracing::error!("lrcapi cover request failed: {error}");
            return (None, LrcApiFetchStatus::Failed);
        }
    };

    match response.status() {
        StatusCode::OK => {
            let bytes = match response.bytes().await {
                Ok(bytes) => bytes,
                Err(error) => {
                    tracing::error!("lrcapi cover body read failed: {error}");
                    return (None, LrcApiFetchStatus::Failed);
                }
            };
            if bytes.is_empty() {
                return (None, LrcApiFetchStatus::Missing);
            }
            (Some(bytes.to_vec()), LrcApiFetchStatus::Loaded)
        }
        StatusCode::NOT_FOUND => (None, LrcApiFetchStatus::Missing),
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => (None, LrcApiFetchStatus::Unauthorized),
        status => {
            tracing::error!("lrcapi cover unexpected status: {status}");
            (None, LrcApiFetchStatus::Failed)
        }
    }
}

#[cfg(test)]
mod tests {
    use axum::{
        extract::Query,
        http::{HeaderMap, StatusCode},
        response::{IntoResponse, Redirect},
        routing::get,
        Router,
    };
    use futures::executor::block_on;
    use std::{collections::HashMap, net::TcpListener};
    use tokio::sync::oneshot;

    use super::{
        fetch_lrcapi_music_supplement, LrcApiConfig, LrcApiFetchStatus, LrcApiQuery,
        AUTHENTICATION_HEADER,
    };

    async fn spawn_test_server() -> (String, oneshot::Sender<()>) {
        async fn lyrics(
            headers: HeaderMap,
            Query(query): Query<HashMap<String, String>>,
        ) -> impl IntoResponse {
            let title = query.get("title").cloned().unwrap_or_default();
            if title == "found" {
                return (
                    StatusCode::OK,
                    "[ar:Artist]\n[al:Album]\n[ti:found]\n[00:01.00]Hello",
                )
                    .into_response();
            }
            if title == "needs-authentication" {
                if headers.get(AUTHENTICATION_HEADER).is_some() {
                    return (StatusCode::OK, "[00:01.00]Authorized").into_response();
                }
                return (StatusCode::UNAUTHORIZED, "need auth").into_response();
            }
            (StatusCode::NOT_FOUND, "Lyrics not found.").into_response()
        }

        async fn cover(Query(query): Query<HashMap<String, String>>) -> impl IntoResponse {
            let title = query.get("title").cloned().unwrap_or_default();
            if title == "cover-hit" {
                return Redirect::temporary("/cover-bytes").into_response();
            }
            StatusCode::NOT_FOUND.into_response()
        }

        async fn cover_bytes() -> impl IntoResponse {
            ([("content-type", "image/png")], vec![1_u8, 2, 3, 4]).into_response()
        }

        let app = Router::new()
            .route("/lyrics", get(lyrics))
            .route("/cover", get(cover))
            .route("/cover-bytes", get(cover_bytes));

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test server");
        let addr = listener.local_addr().expect("listener addr");
        listener
            .set_nonblocking(true)
            .expect("set listener non-blocking");
        let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();
        let server = axum::Server::from_tcp(listener)
            .expect("build server")
            .serve(app.into_make_service())
            .with_graceful_shutdown(async move {
                let _ = shutdown_rx.await;
            });
        tokio::spawn(server);
        (format!("http://{}", addr), shutdown_tx)
    }

    #[test]
    fn fetches_and_parses_lyrics_from_legacy_endpoint() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (base_url, shutdown_tx) = spawn_test_server().await;
            let result = fetch_lrcapi_music_supplement(
                &LrcApiConfig {
                    enabled: true,
                    base_url,
                    auth_key: None,
                },
                &LrcApiQuery {
                    title: "found".to_string(),
                    artist: "Artist".to_string(),
                    album: "Album".to_string(),
                },
            )
            .await;

            assert_eq!(result.lyrics_status, LrcApiFetchStatus::Loaded);
            assert_eq!(result.cover_status, LrcApiFetchStatus::Missing);
            let lyrics = result.lyrics.expect("lyrics should load");
            assert_eq!(lyrics.metdata.title, "found");
            assert_eq!(lyrics.lines.len(), 1);
            let _ = shutdown_tx.send(());
        });
    }

    #[test]
    fn returns_missing_when_lyrics_not_found() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (base_url, shutdown_tx) = spawn_test_server().await;
            let result = fetch_lrcapi_music_supplement(
                &LrcApiConfig {
                    enabled: true,
                    base_url,
                    auth_key: None,
                },
                &LrcApiQuery {
                    title: "missing".to_string(),
                    artist: "".to_string(),
                    album: "".to_string(),
                },
            )
            .await;

            assert_eq!(result.lyrics_status, LrcApiFetchStatus::Missing);
            assert!(result.lyrics.is_none());
            let _ = shutdown_tx.send(());
        });
    }

    #[test]
    fn retries_with_authentication_header_after_unauthorized() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (base_url, shutdown_tx) = spawn_test_server().await;
            let result = fetch_lrcapi_music_supplement(
                &LrcApiConfig {
                    enabled: true,
                    base_url,
                    auth_key: Some("secret".to_string()),
                },
                &LrcApiQuery {
                    title: "needs-authentication".to_string(),
                    artist: "".to_string(),
                    album: "".to_string(),
                },
            )
            .await;

            assert_eq!(result.lyrics_status, LrcApiFetchStatus::Loaded);
            assert_eq!(
                result.lyrics.expect("lyrics").lines[0].text,
                "Authorized".to_string()
            );
            let _ = shutdown_tx.send(());
        });
    }

    #[test]
    fn follows_cover_redirect_and_returns_bytes() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (base_url, shutdown_tx) = spawn_test_server().await;
            let result = fetch_lrcapi_music_supplement(
                &LrcApiConfig {
                    enabled: true,
                    base_url,
                    auth_key: None,
                },
                &LrcApiQuery {
                    title: "cover-hit".to_string(),
                    artist: "Artist".to_string(),
                    album: "Album".to_string(),
                },
            )
            .await;

            assert_eq!(result.cover_status, LrcApiFetchStatus::Loaded);
            assert_eq!(result.cover.expect("cover"), vec![1_u8, 2, 3, 4]);
            let _ = shutdown_tx.send(());
        });
    }

    #[test]
    fn fetches_without_tokio_reactor_context() {
        let (base_url, shutdown_tx) =
            ease_client_tokio::tokio_runtime().block_on(spawn_test_server());
        let result = block_on(fetch_lrcapi_music_supplement(
            &LrcApiConfig {
                enabled: true,
                base_url,
                auth_key: None,
            },
            &LrcApiQuery {
                title: "found".to_string(),
                artist: "Artist".to_string(),
                album: "Album".to_string(),
            },
        ));

        assert_eq!(result.lyrics_status, LrcApiFetchStatus::Loaded);
        assert_eq!(result.lyrics.expect("lyrics").lines[0].text, "Hello");
        let _ = shutdown_tx.send(());
    }
}
