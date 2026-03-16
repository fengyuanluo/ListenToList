use std::sync::Arc;

use ease_client_schema::{MusicId, PlaylistId, StorageEntryLoc};
use ease_order_key::{OrderKey, OrderKeyRef};

use crate::{
    error::{BError, BResult},
    objects::{Playlist, PlaylistAbstract},
    repositories::music::{AddedMusic, ArgDBAddMusic},
    services::{
        get_all_playlist_abstracts, get_playlist, ArgAddMusicsToPlaylist, ArgCreatePlaylist,
        ArgRemoveMusicFromPlaylist, ArgUpdatePlaylist,
    },
    Backend,
};

#[uniffi::export]
pub async fn ct_get_playlist(cx: Arc<Backend>, arg: PlaylistId) -> BResult<Option<Playlist>> {
    let cx = cx.get_context();
    get_playlist(cx, arg)
}

#[uniffi::export]
pub async fn ct_update_playlist(cx: Arc<Backend>, arg: ArgUpdatePlaylist) -> BResult<()> {
    let cx = cx.get_context();
    cx.database_server()
        .update_playlist(arg.id, arg.title, arg.cover)?;

    Ok(())
}

#[uniffi::export]
pub async fn ct_list_playlist(cx: Arc<Backend>) -> BResult<Vec<PlaylistAbstract>> {
    let cx = cx.get_context();
    return get_all_playlist_abstracts(cx);
}

#[derive(uniffi::Record)]
pub struct RetCreatePlaylist {
    pub id: PlaylistId,
    pub music_ids: Vec<AddedMusic>,
}

#[uniffi::export]
pub async fn ct_create_playlist(
    cx: Arc<Backend>,
    arg: ArgCreatePlaylist,
) -> BResult<RetCreatePlaylist> {
    let cx = cx.get_context();
    let current_time_ms = cx.current_time().as_millis() as i64;

    let musics = arg
        .entries
        .clone()
        .into_iter()
        .map(|arg| {
            let entry = arg.entry;
            let name = arg.name;
            ArgDBAddMusic {
                loc: StorageEntryLoc {
                    storage_id: entry.storage_id,
                    path: entry.path,
                },
                title: name,
            }
        })
        .collect();

    let last_order = get_all_playlist_abstracts(cx)?
        .last()
        .map(|v| OrderKey::wrap(v.meta.order.clone()))
        .unwrap_or_default();

    let (playlist_id, music_ids) = cx.database_server().create_playlist(
        arg.title,
        arg.cover.clone(),
        musics,
        current_time_ms,
        OrderKey::greater(&last_order),
    )?;

    Ok(RetCreatePlaylist {
        id: playlist_id,
        music_ids,
    })
}

#[uniffi::export]
pub async fn ct_add_musics_to_playlist(
    cx: Arc<Backend>,
    arg: ArgAddMusicsToPlaylist,
) -> BResult<Vec<AddedMusic>> {
    let cx = cx.get_context();
    let musics = arg
        .entries
        .clone()
        .into_iter()
        .map(|arg| {
            let entry = arg.entry;
            let name = arg.name;
            ArgDBAddMusic {
                loc: StorageEntryLoc {
                    storage_id: entry.storage_id,
                    path: entry.path,
                },
                title: name,
            }
        })
        .collect();

    let Some(playlist) = get_playlist(cx, arg.id)? else {
        return Err(BError::PlaylistNotFound(arg.id));
    };
    let last_order = playlist
        .musics
        .last()
        .map(|v| OrderKey::wrap(v.meta.order.clone()))
        .unwrap_or(OrderKey::default());

    let ret = cx
        .database_server()
        .add_musics_to_playlist(arg.id, musics, last_order)?;

    Ok(ret)
}

#[uniffi::export]
pub async fn ct_remove_music_from_playlist(
    cx: Arc<Backend>,
    arg: ArgRemoveMusicFromPlaylist,
) -> BResult<()> {
    let cx = cx.get_context();
    cx.database_server()
        .remove_music_from_playlist(arg.playlist_id, arg.music_id)?;

    Ok(())
}

#[derive(uniffi::Record)]
pub struct ArgReorderPlaylist {
    id: PlaylistId,
    a: Option<PlaylistId>,
    b: Option<PlaylistId>,
}

#[uniffi::export]
pub fn cts_reorder_playlist(cx: Arc<Backend>, arg: ArgReorderPlaylist) -> BResult<()> {
    let cx = cx.get_context();
    if arg.a == arg.b {
        return Ok(());
    }

    let playlists = get_all_playlist_abstracts(cx)?;

    let from = playlists
        .iter()
        .find(|v| v.meta.id == arg.id)
        .ok_or(BError::PlaylistNotFound(arg.id))?;
    let a = match arg.a {
        Some(id) => Some(
            playlists
                .iter()
                .find(|v| v.meta.id == id)
                .ok_or(BError::PlaylistNotFound(id))?,
        ),
        None => None,
    };
    let b = match arg.b {
        Some(id) => Some(
            playlists
                .iter()
                .find(|v| v.meta.id == id)
                .ok_or(BError::PlaylistNotFound(id))?,
        ),
        None => None,
    };

    if a.is_none() && b.is_none() {
        tracing::warn!("reorder but both playlists are null");
        return Ok(());
    }

    let a_order = a.map(|v| OrderKeyRef::wrap(&v.meta.order));
    let b_order = b.map(|v| OrderKeyRef::wrap(&v.meta.order));
    let order = {
        match (a_order, b_order) {
            (Some(a), Some(b)) => OrderKey::between(a, b)?,
            (Some(a), None) => OrderKey::greater(a),
            (None, Some(b)) => OrderKey::less_or_fallback(b),
            (None, None) => unreachable!(),
        }
    };

    cx.database_server()
        .set_playlist_order(from.meta.id, order)?;
    Ok(())
}

#[uniffi::export]
pub async fn ct_remove_playlist(cx: Arc<Backend>, arg: PlaylistId) -> BResult<()> {
    let cx = cx.get_context();
    cx.database_server().remove_playlist(arg)?;

    Ok(())
}

#[derive(uniffi::Record)]
pub struct ArgReorderMusic {
    playlist_id: PlaylistId,
    id: MusicId,
    a: Option<MusicId>,
    b: Option<MusicId>,
}

#[uniffi::export]
pub fn cts_reorder_music_in_playlist(cx: Arc<Backend>, arg: ArgReorderMusic) -> BResult<()> {
    let cx = cx.get_context();
    if arg.a == arg.b {
        return Ok(());
    }
    let Some(playlist) = get_playlist(cx, arg.playlist_id)? else {
        return Err(BError::PlaylistNotFound(arg.playlist_id));
    };

    let from = playlist
        .musics
        .iter()
        .find(|v| v.meta.id == arg.id)
        .ok_or(BError::MusicNotFound(arg.id))?;
    let a = match arg.a {
        Some(id) => Some(
            playlist
                .musics
                .iter()
                .find(|v| v.meta.id == id)
                .ok_or(BError::MusicNotFound(id))?,
        ),
        None => None,
    };
    let b = match arg.b {
        Some(id) => Some(
            playlist
                .musics
                .iter()
                .find(|v| v.meta.id == id)
                .ok_or(BError::MusicNotFound(id))?,
        ),
        None => None,
    };

    if a.is_none() && b.is_none() {
        tracing::warn!("reorder but both musics are null");
        return Ok(());
    }

    let a_order = a.map(|v| OrderKeyRef::wrap(&v.meta.order));
    let b_order = b.map(|v| OrderKeyRef::wrap(&v.meta.order));
    let order = {
        match (a_order, b_order) {
            (Some(a), Some(b)) => OrderKey::between(a, b)?,
            (Some(a), None) => OrderKey::greater(a),
            (None, Some(b)) => OrderKey::less_or_fallback(b),
            (None, None) => unreachable!(),
        }
    };

    cx.database_server()
        .set_playlist_music_order(arg.playlist_id, from.meta.id, order)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use ease_client_schema::StorageType;
    use tempfile::TempDir;

    use crate::{
        controllers::storage::ct_list_storage,
        create_backend,
        services::{ArgCreatePlaylist, ArgInitializeApp, ToAddMusicEntry},
        StorageEntry,
    };

    use super::{
        ct_create_playlist, ct_get_playlist, ct_list_playlist, cts_reorder_music_in_playlist,
        ArgReorderMusic,
    };

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
    fn reorder_music_only_changes_membership_order_inside_target_playlist() {
        ease_client_tokio::tokio_runtime().block_on(async {
            let (_tempdir, backend) = setup_backend();
            let local_storage = ct_list_storage(backend.clone())
                .await
                .expect("list storages")
                .into_iter()
                .find(|storage| storage.typ == StorageType::Local)
                .expect("local storage");

            let make_entry = |path: &str, name: &str| ToAddMusicEntry {
                entry: StorageEntry {
                    storage_id: local_storage.id,
                    name: name.to_string(),
                    path: path.to_string(),
                    size: None,
                    is_dir: false,
                },
                name: name.to_string(),
            };

            let playlist_a = ct_create_playlist(
                backend.clone(),
                ArgCreatePlaylist {
                    title: "playlist-a".to_string(),
                    cover: None,
                    entries: vec![
                        make_entry("/shared/a.mp3", "a"),
                        make_entry("/shared/b.mp3", "b"),
                    ],
                },
            )
            .await
            .expect("create playlist a")
            .id;

            let playlist_b = ct_create_playlist(
                backend.clone(),
                ArgCreatePlaylist {
                    title: "playlist-b".to_string(),
                    cover: None,
                    entries: vec![
                        make_entry("/shared/a.mp3", "a"),
                        make_entry("/shared/b.mp3", "b"),
                    ],
                },
            )
            .await
            .expect("create playlist b")
            .id;

            let before_a = ct_get_playlist(backend.clone(), playlist_a)
                .await
                .expect("load playlist a")
                .expect("playlist a exists");
            let before_b = ct_get_playlist(backend.clone(), playlist_b)
                .await
                .expect("load playlist b")
                .expect("playlist b exists");

            let shared_a_id = before_a.musics[0].meta.id;
            let shared_b_id = before_a.musics[1].meta.id;
            assert_eq!(shared_a_id, before_b.musics[0].meta.id);
            assert_eq!(shared_b_id, before_b.musics[1].meta.id);

            cts_reorder_music_in_playlist(
                backend.clone(),
                ArgReorderMusic {
                    playlist_id: playlist_a,
                    id: shared_b_id,
                    a: None,
                    b: Some(shared_a_id),
                },
            )
            .expect("reorder playlist a membership");

            let after_a = ct_get_playlist(backend.clone(), playlist_a)
                .await
                .expect("reload playlist a")
                .expect("playlist a exists after reorder");
            let after_b = ct_get_playlist(backend.clone(), playlist_b)
                .await
                .expect("reload playlist b")
                .expect("playlist b exists after reorder");

            assert_eq!(
                vec![shared_b_id, shared_a_id],
                after_a
                    .musics
                    .iter()
                    .map(|music| music.meta.id)
                    .collect::<Vec<_>>()
            );
            assert_eq!(
                vec![shared_a_id, shared_b_id],
                after_b
                    .musics
                    .iter()
                    .map(|music| music.meta.id)
                    .collect::<Vec<_>>()
            );

            let playlist_titles = ct_list_playlist(backend)
                .await
                .expect("list playlists after reorder")
                .into_iter()
                .map(|playlist| playlist.meta.title)
                .collect::<Vec<_>>();
            assert!(playlist_titles.iter().any(|title| title == "playlist-a"));
            assert!(playlist_titles.iter().any(|title| title == "playlist-b"));
        })
    }
}
