use std::{collections::HashSet, sync::Arc};

use ease_order_key::OrderKey;
use redb::{ReadTransaction, ReadableMultimapTable, ReadableTable, ReadableTableMetadata};

use crate::error::BResult;

use super::{
    core::DatabaseServer,
    music::{AddedMusic, ArgDBAddMusic},
};
use ease_client_schema::{
    BlobId, DbKeyAlloc, MusicId, PlaylistId, PlaylistModel, PlaylistMusicModel, StorageEntryLoc,
    TABLE_MUSIC_PLAYLIST, TABLE_PLAYLIST, TABLE_PLAYLIST_MUSIC,
};

impl DatabaseServer {
    pub fn load_playlist(self: &Arc<Self>, id: PlaylistId) -> BResult<Option<PlaylistModel>> {
        let db = self.db().begin_read()?;
        self.load_playlist_impl(&db, id)
    }

    fn load_playlist_impl(
        self: &Arc<Self>,
        db: &ReadTransaction,
        id: PlaylistId,
    ) -> BResult<Option<PlaylistModel>> {
        let table = db.open_table(TABLE_PLAYLIST)?;
        let p = table.get(id)?.map(|v| v.value());
        Ok(p)
    }

    pub fn load_playlists(self: &Arc<Self>) -> BResult<Vec<PlaylistModel>> {
        let db = self.db().begin_read()?;
        let table = db.open_table(TABLE_PLAYLIST)?;
        let len = table.len()? as usize;

        let mut ret: Vec<PlaylistModel> = Default::default();
        ret.reserve(len);

        let iter = table.iter()?;
        for v in iter {
            let v = v?.1.value();
            ret.push(v);
        }

        ret.sort_by_key(|v| OrderKey::wrap(v.order.clone()));

        Ok(ret)
    }

    pub fn create_playlist(
        self: &Arc<Self>,
        title: String,
        picture: Option<StorageEntryLoc>,
        musics: Vec<ArgDBAddMusic>,
        current_time_ms: i64,
        order: OrderKey,
    ) -> BResult<(PlaylistId, Vec<AddedMusic>)> {
        let db = self.db().begin_write()?;
        let rdb = self.db().begin_read()?;

        let mut ret: Vec<AddedMusic> = Vec::with_capacity(musics.len());

        let playlist_id = {
            let id = {
                let id = self.alloc_id(&db, DbKeyAlloc::Playlist)?;

                PlaylistId::wrap(id)
            };

            let mut playlist = PlaylistModel {
                id,
                title: Default::default(),
                created_time: Default::default(),
                picture: Default::default(),
                order: order.into_raw(),
            };

            playlist.title = title;
            playlist.picture = picture;
            playlist.created_time = current_time_ms;

            let mut table = db.open_table(TABLE_PLAYLIST)?;
            table.insert(id, playlist)?;

            id
        };

        let mut order = OrderKey::default();
        for m in musics {
            let (id, existed) = self.add_music_impl(&db, &rdb, m, order.clone())?;
            let mut table_pm = db.open_multimap_table(TABLE_PLAYLIST_MUSIC)?;
            let mut table_mp = db.open_multimap_table(TABLE_MUSIC_PLAYLIST)?;
            table_pm.insert(
                playlist_id,
                PlaylistMusicModel {
                    music_id: id,
                    order: order.clone().into_raw(),
                },
            )?;
            table_mp.insert(id, playlist_id)?;
            ret.push(AddedMusic { id, existed });
            order = OrderKey::greater(&order);
        }

        db.commit()?;
        Ok((playlist_id, ret))
    }

    pub fn update_playlist(
        self: &Arc<Self>,
        id: PlaylistId,
        title: String,
        picture: Option<StorageEntryLoc>,
    ) -> BResult<PlaylistId> {
        let db = self.db().begin_write()?;
        let rdb = self.db().begin_read()?;

        {
            let playlist = self.load_playlist_impl(&rdb, id)?;

            if let Some(mut playlist) = playlist {
                playlist.title = title;
                playlist.picture = picture;

                let mut table = db.open_table(TABLE_PLAYLIST)?;
                table.insert(id, playlist)?;
            }
        };
        db.commit()?;
        Ok(id)
    }

    pub fn set_playlist_order(self: &Arc<Self>, id: PlaylistId, order: OrderKey) -> BResult<()> {
        let db = self.db().begin_write()?;
        let rdb = self.db().begin_read()?;

        {
            let playlist = self.load_playlist_impl(&rdb, id)?;

            if let Some(mut playlist) = playlist {
                playlist.order = order.into_raw();

                let mut table = db.open_table(TABLE_PLAYLIST)?;
                table.insert(id, playlist)?;
            }
        };
        db.commit()?;
        Ok(())
    }

    pub fn remove_playlist(self: &Arc<Self>, playlist_id: PlaylistId) -> BResult<()> {
        let db = self.db().begin_write()?;
        let rdb = self.db().begin_read()?;
        let mut to_remove_blobs: Vec<BlobId> = Default::default();

        {
            let mut table_playlist = db.open_table(TABLE_PLAYLIST)?;
            let mut table_pm = db.open_multimap_table(TABLE_PLAYLIST_MUSIC)?;
            let mut table_mp = db.open_multimap_table(TABLE_MUSIC_PLAYLIST)?;

            table_playlist.remove(playlist_id)?;

            let ids = table_pm.get(playlist_id)?;
            for relation in ids {
                let relation = relation?.value();
                let id = relation.music_id;
                table_mp.remove(id, playlist_id)?;
                self.compact_music_impl(&db, &rdb, &mut table_mp, &mut to_remove_blobs, id)?;
            }
            table_pm.remove_all(playlist_id)?;
        }

        db.commit()?;

        for id in to_remove_blobs {
            self.blob().remove(id)?;
        }

        Ok(())
    }

    pub fn remove_music_from_playlist(
        self: &Arc<Self>,
        playlist_id: PlaylistId,
        music_id: MusicId,
    ) -> BResult<()> {
        let mut to_remove_blobs: Vec<BlobId> = Default::default();

        let db = self.db().begin_write()?;
        let rdb = self.db().begin_read()?;

        {
            let mut table_pm = db.open_multimap_table(TABLE_PLAYLIST_MUSIC)?;
            let mut table_mp = db.open_multimap_table(TABLE_MUSIC_PLAYLIST)?;
            let relations: Vec<_> = table_pm
                .get(playlist_id)?
                .into_iter()
                .filter_map(|value| value.ok().map(|value| value.value()))
                .filter(|relation| relation.music_id == music_id)
                .collect();
            for relation in relations {
                table_pm.remove(playlist_id, relation)?;
            }
            table_mp.remove(music_id, playlist_id)?;

            self.compact_music_impl(&db, &rdb, &mut table_mp, &mut to_remove_blobs, music_id)?;
        }

        db.commit()?;

        for id in to_remove_blobs {
            self.blob().remove(id)?;
        }

        Ok(())
    }

    pub fn add_musics_to_playlist(
        self: &Arc<Self>,
        playlist_id: PlaylistId,
        musics: Vec<ArgDBAddMusic>,
        last_order: OrderKey,
    ) -> BResult<Vec<AddedMusic>> {
        let db = self.db().begin_write()?;
        let rdb = self.db().begin_read()?;

        let mut ret: Vec<AddedMusic> = Vec::with_capacity(musics.len());
        let existing_music_ids: HashSet<_> = db
            .open_multimap_table(TABLE_PLAYLIST_MUSIC)?
            .get(playlist_id)?
            .into_iter()
            .filter_map(|value| value.ok().map(|value| value.value().music_id))
            .collect();
        let mut seen_music_ids = existing_music_ids;

        let mut order = OrderKey::greater(&last_order);
        for m in musics {
            let (id, existed) = self.add_music_impl(&db, &rdb, m, order.clone())?;
            if seen_music_ids.contains(&id) {
                continue;
            }

            let mut table_pm = db.open_multimap_table(TABLE_PLAYLIST_MUSIC)?;
            let mut table_mp = db.open_multimap_table(TABLE_MUSIC_PLAYLIST)?;
            table_pm.insert(
                playlist_id,
                PlaylistMusicModel {
                    music_id: id,
                    order: order.clone().into_raw(),
                },
            )?;
            table_mp.insert(id, playlist_id)?;

            ret.push(AddedMusic { id, existed });
            seen_music_ids.insert(id);
            order = OrderKey::greater(&order);
        }
        db.commit()?;
        Ok(ret)
    }

    pub fn set_playlist_music_order(
        self: &Arc<Self>,
        playlist_id: PlaylistId,
        music_id: MusicId,
        order: OrderKey,
    ) -> BResult<()> {
        let db = self.db().begin_write()?;
        {
            let mut table = db.open_multimap_table(TABLE_PLAYLIST_MUSIC)?;
            let relations: Vec<_> = table
                .get(playlist_id)?
                .into_iter()
                .filter_map(|value| value.ok().map(|value| value.value()))
                .collect();
            for relation in relations {
                if relation.music_id != music_id {
                    continue;
                }
                table.remove(playlist_id, relation.clone())?;
                table.insert(
                    playlist_id,
                    PlaylistMusicModel {
                        music_id,
                        order: order.clone().into_raw(),
                    },
                )?;
                break;
            }
        }
        db.commit()?;
        Ok(())
    }
}
