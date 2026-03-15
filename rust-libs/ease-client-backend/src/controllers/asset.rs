use std::sync::Arc;

use bytes::Bytes;
use ease_client_schema::{DataSourceKey, MusicId};
use ease_remote_storage::{
    DirectHttpPlaybackSource, PlaybackHttpHeader, ResolvedPlaybackSource, StorageBackendResult,
};

use crate::{
    error::BResult,
    objects::{
        DirectHttpPlaybackSource as BackendDirectHttpPlaybackSource, LocalFilePlaybackSource,
        PlaybackHttpHeader as BackendPlaybackHttpHeader, PlaybackSourceDescriptor,
    },
    services::{get_asset_file, resolve_music_playback_source},
    Backend,
};

#[uniffi::export]
pub async fn ct_get_asset(cx: Arc<Backend>, key: DataSourceKey) -> BResult<Option<Vec<u8>>> {
    let cx = cx.get_context();
    let file = get_asset_file(cx, key, 0).await?;
    let Some(file) = file else {
        return Ok(None);
    };

    let buf = file.bytes().await?;
    Ok(Some(buf.to_vec()))
}

#[derive(uniffi::Object)]
pub struct AssetStream {
    stream: async_channel::Receiver<StorageBackendResult<Bytes>>,
    size: Option<u64>,
}

impl From<PlaybackHttpHeader> for BackendPlaybackHttpHeader {
    fn from(value: PlaybackHttpHeader) -> Self {
        Self {
            name: value.name,
            value: value.value,
        }
    }
}

impl From<DirectHttpPlaybackSource> for BackendDirectHttpPlaybackSource {
    fn from(value: DirectHttpPlaybackSource) -> Self {
        Self {
            url: value.url,
            headers: value.headers.into_iter().map(Into::into).collect(),
            cache_key: value.cache_key,
        }
    }
}

impl From<ResolvedPlaybackSource> for PlaybackSourceDescriptor {
    fn from(value: ResolvedPlaybackSource) -> Self {
        match value {
            ResolvedPlaybackSource::DirectHttp(source) => Self::DirectHttp(source.into()),
            ResolvedPlaybackSource::LocalFile { absolute_path } => {
                Self::LocalFile(LocalFilePlaybackSource { absolute_path })
            }
            ResolvedPlaybackSource::StreamFallback => Self::StreamFallback,
        }
    }
}

#[uniffi::export]
impl AssetStream {
    pub async fn next(&self) -> BResult<Option<Vec<u8>>> {
        if let Ok(result) = self.stream.recv().await {
            let result = result?;
            Ok(Some(result.to_vec()))
        } else {
            Ok(None)
        }
    }

    pub fn size(&self) -> Option<u64> {
        self.size
    }
}

#[uniffi::export]
pub async fn ct_get_asset_stream(
    cx: Arc<Backend>,
    key: DataSourceKey,
    byte_offset: u64,
) -> BResult<Option<Arc<AssetStream>>> {
    let cx = cx.get_context();
    let file = get_asset_file(cx, key, byte_offset).await?;
    let Some(file) = file else {
        return Ok(None);
    };

    let len = file.size();
    let stream = file.into_rx();
    let stream = Arc::new(AssetStream {
        stream,
        size: len.map(|v| v as u64),
    });

    Ok(Some(stream))
}

#[uniffi::export]
pub async fn ct_resolve_music_playback_source(
    cx: Arc<Backend>,
    id: MusicId,
) -> BResult<Option<PlaybackSourceDescriptor>> {
    let cx = cx.get_context();
    let resolved = resolve_music_playback_source(cx, id).await?;
    Ok(resolved.map(Into::into))
}

#[cfg(test)]
mod test {
    use std::sync::Arc;

    use ease_client_schema::StorageType;
    use tempfile::TempDir;

    use crate::{
        controllers::{
            playlist::{ct_create_playlist, ct_get_playlist, ct_list_playlist},
            storage::ct_list_storage,
        },
        create_backend,
        services::{ArgCreatePlaylist, ArgInitializeApp, ToAddMusicEntry},
        StorageEntry,
    };

    use super::{ct_resolve_music_playback_source, PlaybackSourceDescriptor};

    fn setup_backend() -> (TempDir, Arc<crate::Backend>) {
        let tempdir = tempfile::tempdir().expect("create tempdir");
        let documents_dir = tempdir.path().join("documents");
        let cache_dir = tempdir.path().join("cache");
        std::fs::create_dir_all(&documents_dir).expect("create documents dir");
        std::fs::create_dir_all(&cache_dir).expect("create cache dir");

        let backend = create_backend(ArgInitializeApp {
            app_document_dir: format!("{}/", documents_dir.display()),
            app_cache_dir: format!("{}/", cache_dir.display()),
            storage_path: "/".to_string(),
        });
        backend.init().expect("init backend");
        (tempdir, backend)
    }

    #[test]
    fn test_resolve_music_playback_source_returns_local_file_descriptor() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (tempdir, backend) = setup_backend();
            let media_dir = tempdir.path().join("media");
            std::fs::create_dir_all(&media_dir).expect("create media dir");
            let file_path = media_dir.join("smoke.wav");
            std::fs::write(&file_path, b"RIFF").expect("write smoke media");
            let canonical_path =
                std::fs::canonicalize(&file_path).expect("canonicalize smoke media");

            let local_storage = ct_list_storage(backend.clone())
                .await
                .expect("list storages")
                .into_iter()
                .find(|storage| storage.typ == StorageType::Local)
                .expect("local storage");

            let _created = ct_create_playlist(
                backend.clone(),
                ArgCreatePlaylist {
                    title: "debug-smoke-playlist".to_string(),
                    cover: None,
                    entries: vec![ToAddMusicEntry {
                        entry: StorageEntry {
                            storage_id: local_storage.id,
                            name: "smoke.wav".to_string(),
                            path: file_path.to_string_lossy().to_string(),
                            size: Some(4),
                            is_dir: false,
                        },
                        name: "smoke".to_string(),
                    }],
                },
            )
            .await
            .expect("create playlist");

            let playlist_id = ct_list_playlist(backend.clone())
                .await
                .expect("list playlists")
                .into_iter()
                .find(|playlist| playlist.meta.title == "debug-smoke-playlist")
                .expect("created playlist")
                .meta
                .id;
            let music_id = ct_get_playlist(backend.clone(), playlist_id)
                .await
                .expect("load playlist")
                .expect("playlist")
                .musics
                .first()
                .expect("playlist music")
                .meta
                .id;
            let resolved = ct_resolve_music_playback_source(backend.clone(), music_id)
                .await
                .expect("resolve playback source")
                .expect("descriptor");

            match resolved {
                PlaybackSourceDescriptor::LocalFile(source) => {
                    assert_eq!(
                        source.absolute_path,
                        canonical_path
                            .to_string_lossy()
                            .to_string()
                            .replace("\\\\?\\", "")
                    );
                }
                other => panic!("unexpected descriptor: {other:?}"),
            }
        })
    }

    #[test]
    fn test_resolve_music_playback_source_returns_none_when_file_is_missing() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (tempdir, backend) = setup_backend();
            let missing_path = tempdir.path().join("missing").join("ghost.wav");

            let local_storage = ct_list_storage(backend.clone())
                .await
                .expect("list storages")
                .into_iter()
                .find(|storage| storage.typ == StorageType::Local)
                .expect("local storage");

            let _created = ct_create_playlist(
                backend.clone(),
                ArgCreatePlaylist {
                    title: "debug-smoke-missing".to_string(),
                    cover: None,
                    entries: vec![ToAddMusicEntry {
                        entry: StorageEntry {
                            storage_id: local_storage.id,
                            name: "ghost.wav".to_string(),
                            path: missing_path.to_string_lossy().to_string(),
                            size: None,
                            is_dir: false,
                        },
                        name: "ghost".to_string(),
                    }],
                },
            )
            .await
            .expect("create playlist");

            let playlist_id = ct_list_playlist(backend.clone())
                .await
                .expect("list playlists")
                .into_iter()
                .find(|playlist| playlist.meta.title == "debug-smoke-missing")
                .expect("created playlist")
                .meta
                .id;
            let music_id = ct_get_playlist(backend.clone(), playlist_id)
                .await
                .expect("load playlist")
                .expect("playlist")
                .musics
                .first()
                .expect("playlist music")
                .meta
                .id;
            let resolved = ct_resolve_music_playback_source(backend, music_id)
                .await
                .expect("resolve missing playback source");
            assert!(resolved.is_none());
        })
    }
}
