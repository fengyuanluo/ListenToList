use std::time::Duration;

use ease_client_schema::{DataSourceKey, MusicId, MusicModel, PlaylistId, StorageEntryLoc};

use crate::{
    ctx::BackendContext,
    error::BResult,
    objects::{LyricLoadState, Music, MusicAbstract, MusicLyric, MusicMeta},
    StorageEntry,
};

use super::{
    lyrics::{decode_lyric_text, parse_lrc},
    storage::load_storage_entry_data,
};

#[derive(Debug, uniffi::Record)]
pub struct ArgUpdatePlaylist {
    pub id: PlaylistId,
    pub title: String,
    pub cover: Option<StorageEntryLoc>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ToAddMusicEntry {
    pub entry: StorageEntry,
    pub name: String,
}

#[derive(Debug, uniffi::Record)]
pub struct ArgCreatePlaylist {
    pub title: String,
    pub cover: Option<StorageEntryLoc>,
    pub entries: Vec<ToAddMusicEntry>,
}

#[derive(Debug, uniffi::Record)]
pub struct ArgAddMusicsToPlaylist {
    pub id: PlaylistId,
    pub entries: Vec<ToAddMusicEntry>,
}

#[derive(Debug, uniffi::Record)]
pub struct ArgEnsureMusics {
    pub entries: Vec<ToAddMusicEntry>,
}

#[derive(Debug, uniffi::Record)]
pub struct ArgRemoveMusicFromPlaylist {
    pub playlist_id: PlaylistId,
    pub music_id: MusicId,
}

#[derive(Debug, uniffi::Record)]
pub struct ArgUpdateMusicLyric {
    pub id: MusicId,
    pub lyric_loc: Option<StorageEntryLoc>,
}

async fn load_lyric(
    cx: &BackendContext,
    loc: Option<StorageEntryLoc>,
    is_fallback: bool,
) -> Option<MusicLyric> {
    let loc = match loc {
        Some(loc) => loc,
        None => {
            return None;
        }
    };
    let data = load_storage_entry_data(cx, &loc).await;
    if let Err(e) = &data {
        tracing::error!("fail to load entry {:?}: {}", loc, e);
        return Some(MusicLyric {
            loc,
            data: Default::default(),
            loaded_state: if is_fallback {
                LyricLoadState::Missing
            } else {
                LyricLoadState::Failed
            },
        });
    }
    let data = data.unwrap();
    if data.is_none() {
        return Some(MusicLyric {
            loc,
            data: Default::default(),
            loaded_state: if is_fallback {
                LyricLoadState::Missing
            } else {
                LyricLoadState::Failed
            },
        });
    }
    let data = data.unwrap();
    let decoded = decode_lyric_text(data.bytes.as_slice(), data.content_type.as_deref());
    tracing::info!(
        lyric_path = %loc.path,
        lyric_file = %data.name,
        content_type = ?data.content_type,
        encoding = decoded.encoding_name,
        decode_source = decoded.source.as_str(),
        had_errors = decoded.had_errors,
        "decoded lyric text"
    );
    let lyric = parse_lrc(decoded.text);
    if lyric.is_err() {
        let e = lyric.unwrap_err();
        tracing::error!("fail to parse lyric: {}", e);
        return Some(MusicLyric {
            loc,
            data: Default::default(),
            loaded_state: LyricLoadState::Failed,
        });
    }
    let lyric = lyric.unwrap();

    Some(MusicLyric {
        loc,
        data: lyric,
        loaded_state: LyricLoadState::Loaded,
    })
}

pub(crate) fn build_music_meta(model: MusicModel) -> MusicMeta {
    MusicMeta {
        id: model.id,
        title: model.title,
        duration: model.duration,
        order: model.order,
    }
}

pub(crate) fn build_music_abstract(_cx: &BackendContext, model: MusicModel) -> MusicAbstract {
    let cover = if model.cover.is_some() {
        Some(DataSourceKey::Cover { id: model.id })
    } else {
        Default::default()
    };

    MusicAbstract {
        cover,
        meta: build_music_meta(model),
    }
}

#[allow(dead_code)]
// 预留给外部调用，当前未在后端内部使用。
pub fn get_music_storage_entry_loc(
    cx: &BackendContext,
    id: MusicId,
) -> BResult<Option<StorageEntryLoc>> {
    let m = cx.database_server().load_music(id)?;
    if m.is_none() {
        return Ok(None);
    }
    let m = m.unwrap();
    let m = m.loc;
    Ok(Some(m))
}

pub fn get_music_cover_bytes(cx: &BackendContext, id: MusicId) -> BResult<Vec<u8>> {
    let m = cx.database_server().load_music(id)?.unwrap();
    if let Some(id) = m.cover {
        cx.database_server().blob().read(id)
    } else {
        Ok(Default::default())
    }
}

#[derive(uniffi::Record)]
pub struct ArgUpdateMusicDuration {
    pub id: MusicId,
    pub duration: Duration,
}
pub(crate) fn update_music_duration(
    cx: &BackendContext,
    arg: ArgUpdateMusicDuration,
) -> BResult<()> {
    cx.database_server()
        .update_music_total_duration(arg.id, arg.duration)?;
    Ok(())
}

#[derive(uniffi::Record)]
pub struct ArgUpdateMusicCover {
    pub id: MusicId,
    pub cover: Vec<u8>,
}
pub(crate) fn update_music_cover(cx: &BackendContext, arg: ArgUpdateMusicCover) -> BResult<()> {
    cx.database_server()
        .update_music_cover(arg.id, arg.cover.clone())?;
    Ok(())
}

pub(crate) async fn get_music(cx: &BackendContext, id: MusicId) -> BResult<Option<Music>> {
    let model = cx.database_server().load_music(id)?;
    if model.is_none() {
        return Ok(None);
    }

    let model = model.unwrap();
    let meta = build_music_meta(model.clone());
    let loc = model.loc;
    let mut lyric_loc = model.lyric;
    let using_fallback = lyric_loc.is_none() && model.lyric_default;
    if using_fallback {
        lyric_loc = Some(StorageEntryLoc {
            path: {
                let mut path = loc.path.clone();
                let new_extension = ".lrc";
                if let Some(pos) = path.rfind('.') {
                    path.truncate(pos);
                }
                path.push_str(new_extension);
                path
            },
            storage_id: loc.storage_id,
        });
    }

    let lyric: Option<MusicLyric> = load_lyric(cx, lyric_loc, using_fallback).await;
    let cover = if model.cover.is_none() {
        Default::default()
    } else {
        Some(DataSourceKey::Cover { id: model.id })
    };

    let music: Music = Music {
        meta,
        loc,
        cover,
        lyric,
    };
    Ok(Some(music))
}

pub(crate) fn get_music_abstract(
    cx: &BackendContext,
    id: MusicId,
) -> BResult<Option<MusicAbstract>> {
    let model = cx.database_server().load_music(id)?;
    if model.is_none() {
        return Ok(None);
    }

    let model = model.unwrap();
    let meta = build_music_meta(model.clone());
    let cover = if model.cover.is_none() {
        Default::default()
    } else {
        Some(DataSourceKey::Cover { id: model.id })
    };

    let abstract_music = MusicAbstract { cover, meta };
    Ok(Some(abstract_music))
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use ease_client_schema::StorageType;
    use encoding_rs::GBK;
    use tempfile::TempDir;

    use crate::{
        create_backend,
        objects::LyricLoadState,
        repositories::music::ArgDBAddMusic,
        services::{list_storage, ArgInitializeApp},
    };

    use super::get_music;

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
    fn loads_gbk_fallback_lyric_file() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (tempdir, backend) = setup_backend();
            let media_dir = tempdir.path().join("media");
            std::fs::create_dir_all(&media_dir).expect("create media dir");

            let music_path = media_dir.join("gbk-song.mp3");
            let lyric_path = media_dir.join("gbk-song.lrc");
            std::fs::write(&music_path, b"ID3").expect("write fake music");

            let (encoded, _, had_errors) = GBK.encode("[00:01.00]后来\n");
            assert!(!had_errors, "gbk sample should encode cleanly");
            std::fs::write(&lyric_path, encoded.as_ref()).expect("write gbk lyric");

            let local_storage = list_storage(backend.get_context())
                .await
                .expect("list storages")
                .into_iter()
                .find(|storage| storage.typ == StorageType::Local)
                .expect("local storage");

            let created = backend
                .get_context()
                .database_server()
                .upsert_musics(vec![ArgDBAddMusic {
                    loc: ease_client_schema::StorageEntryLoc {
                        storage_id: local_storage.id,
                        path: music_path.to_string_lossy().to_string(),
                    },
                    title: "gbk-song".to_string(),
                }])
                .expect("create music");

            let music = get_music(backend.get_context(), created[0].id)
                .await
                .expect("load music")
                .expect("music");
            let lyric = music.lyric.expect("fallback lyric should load");
            assert_eq!(lyric.loaded_state, LyricLoadState::Loaded);
            assert_eq!(lyric.data.lines.len(), 1);
            assert_eq!(lyric.data.lines[0].text, "后来");
        });
    }

    #[test]
    fn loads_utf16le_fallback_lyric_file_without_bom() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (tempdir, backend) = setup_backend();
            let media_dir = tempdir.path().join("media");
            std::fs::create_dir_all(&media_dir).expect("create media dir");

            let music_path = media_dir.join("utf16-song.mp3");
            let lyric_path = media_dir.join("utf16-song.lrc");
            std::fs::write(&music_path, b"ID3").expect("write fake music");
            std::fs::write(&lyric_path, utf16le_bytes_without_bom("[00:01.00]后来\n"))
                .expect("write utf16 lyric");

            let local_storage = list_storage(backend.get_context())
                .await
                .expect("list storages")
                .into_iter()
                .find(|storage| storage.typ == StorageType::Local)
                .expect("local storage");

            let created = backend
                .get_context()
                .database_server()
                .upsert_musics(vec![ArgDBAddMusic {
                    loc: ease_client_schema::StorageEntryLoc {
                        storage_id: local_storage.id,
                        path: music_path.to_string_lossy().to_string(),
                    },
                    title: "utf16-song".to_string(),
                }])
                .expect("create music");

            let music = get_music(backend.get_context(), created[0].id)
                .await
                .expect("load music")
                .expect("music");
            let lyric = music.lyric.expect("fallback lyric should load");
            assert_eq!(lyric.loaded_state, LyricLoadState::Loaded);
            assert_eq!(lyric.data.lines.len(), 1);
            assert_eq!(lyric.data.lines[0].text, "后来");
        });
    }

    fn utf16le_bytes_without_bom(text: &str) -> Vec<u8> {
        let mut out = Vec::new();
        for unit in text.encode_utf16() {
            out.extend_from_slice(unit.to_le_bytes().as_slice());
        }
        out
    }
}
