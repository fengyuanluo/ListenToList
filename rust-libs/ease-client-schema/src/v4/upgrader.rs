use std::sync::Arc;

use ease_order_key::OrderKey;
use redb::{
    MultimapTableDefinition, ReadableMultimapTable, ReadableTable, TableDefinition,
    WriteTransaction,
};

use crate::{v3, v4};

impl From<v3::DbKeyAlloc> for v4::DbKeyAlloc {
    fn from(value: v3::DbKeyAlloc) -> Self {
        match value {
            v3::DbKeyAlloc::Playlist => v4::DbKeyAlloc::Playlist,
            v3::DbKeyAlloc::Music => v4::DbKeyAlloc::Music,
            v3::DbKeyAlloc::Storage => v4::DbKeyAlloc::Storage,
        }
    }
}

impl From<v3::PlaylistModel> for v4::PlaylistModel {
    fn from(value: v3::PlaylistModel) -> Self {
        Self {
            id: value.id,
            title: value.title,
            created_time: value.created_time,
            picture: value.picture,
            order: value.order,
        }
    }
}

impl From<v3::MusicModel> for v4::MusicModel {
    fn from(value: v3::MusicModel) -> Self {
        Self {
            id: value.id,
            loc: value.loc,
            title: value.title,
            duration: value.duration,
            cover: value.cover,
            lyric: value.lyric,
            lyric_default: value.lyric_default,
            order: value.order,
        }
    }
}

impl From<v3::StorageModel> for v4::StorageModel {
    fn from(value: v3::StorageModel) -> Self {
        Self {
            id: value.id,
            addr: value.addr,
            alias: value.alias,
            username: value.username,
            password: value.password,
            is_anonymous: value.is_anonymous,
            typ: value.typ,
        }
    }
}

impl From<v3::PreferenceModel> for v4::PreferenceModel {
    fn from(value: v3::PreferenceModel) -> Self {
        Self {
            playmode: value.playmode,
        }
    }
}

fn convert_table<KF, VF, KT, VT>(
    db: &WriteTransaction,
    d_from: TableDefinition<KF, VF>,
    d_to: TableDefinition<KT, VT>,
) -> anyhow::Result<()>
where
    KF: redb::Key + 'static,
    VF: redb::Value + 'static,
    KT: redb::Key + 'static,
    VT: redb::Value + 'static,
    for<'b> <KT as redb::Value>::SelfType<'b>: From<<KF as redb::Value>::SelfType<'b>>,
    for<'b> <VT as redb::Value>::SelfType<'b>: From<<VF as redb::Value>::SelfType<'b>>,
{
    let ot = db.open_table(d_from)?;
    let mut nt = db.open_table(d_to)?;
    for v in ot.iter()? {
        let v = v?;
        nt.insert(&v.0.value().into(), &v.1.value().into())?;
    }
    Ok(())
}

fn convert_multi_table<KF, VF, KT, VT>(
    db: &WriteTransaction,
    d_from: MultimapTableDefinition<KF, VF>,
    d_to: MultimapTableDefinition<KT, VT>,
) -> anyhow::Result<()>
where
    KF: redb::Key + 'static,
    VF: redb::Key + 'static,
    KT: redb::Key + 'static,
    VT: redb::Key + 'static,
    for<'b> <KT as redb::Value>::SelfType<'b>: From<<KF as redb::Value>::SelfType<'b>>,
    for<'b> <VT as redb::Value>::SelfType<'b>: From<<VF as redb::Value>::SelfType<'b>>,
{
    let ot = db.open_multimap_table(d_from)?;
    let mut nt = db.open_multimap_table(d_to)?;

    for v in ot.iter()? {
        let (k, v) = v?;
        for v in v.into_iter() {
            let v = v?;
            nt.insert(&k.value().into(), &v.value().into())?;
        }
    }
    Ok(())
}

pub fn upgrade_v3_to_v4(database: &Arc<redb::Database>) -> anyhow::Result<()> {
    let db = database.begin_write()?;
    {
        let ref db = db;
        convert_table(db, v3::TABLE_ID_ALLOC, v4::TABLE_ID_ALLOC)?;
        convert_table(db, v3::TABLE_PLAYLIST, v4::TABLE_PLAYLIST)?;
        convert_table(db, v3::TABLE_MUSIC, v4::TABLE_MUSIC)?;
        convert_table(db, v3::TABLE_MUSIC_BY_LOC, v4::TABLE_MUSIC_BY_LOC)?;
        convert_table(db, v3::TABLE_STORAGE, v4::TABLE_STORAGE)?;
        convert_multi_table(db, v3::TABLE_STORAGE_MUSIC, v4::TABLE_STORAGE_MUSIC)?;
        convert_table(db, v3::TABLE_PREFERENCE, v4::TABLE_PREFERENCE)?;
        convert_table(db, v3::TABLE_BLOB, v4::TABLE_BLOB)?;
        convert_multi_table(db, v3::TABLE_MUSIC_PLAYLIST, v4::TABLE_MUSIC_PLAYLIST)?;

        let table_music = db.open_table(v3::TABLE_MUSIC)?;
        let table_playlist = db.open_table(v3::TABLE_PLAYLIST)?;
        let table_playlist_music = db.open_multimap_table(v3::TABLE_PLAYLIST_MUSIC)?;
        let mut next_playlist_music = db.open_multimap_table(v4::TABLE_PLAYLIST_MUSIC)?;

        for playlist_row in table_playlist.iter()? {
            let (playlist_id, _) = playlist_row?;
            let playlist_id = playlist_id.value();
            let mut memberships = Vec::new();
            let rows = table_playlist_music.get(playlist_id)?;
            for row in rows {
                let music_id = row?.value();
                let music = table_music
                    .get(music_id)?
                    .map(|value| value.value())
                    .ok_or_else(|| {
                        anyhow::anyhow!("missing music {:?} during v3->v4 upgrade", music_id)
                    })?;
                memberships.push((music_id, OrderKey::wrap(music.order.clone())));
            }
            memberships.sort_by(|lhs, rhs| lhs.1.cmp(&rhs.1));
            let mut order = OrderKey::default();
            for (music_id, _) in memberships {
                next_playlist_music.insert(
                    &playlist_id,
                    &v4::PlaylistMusicModel {
                        music_id,
                        order: order.clone().into_raw(),
                    },
                )?;
                order = OrderKey::greater(&order);
            }
        }
        tracing::info!("v3 -> v4: finish membership-order migration");
    }
    {
        db.delete_table(v3::TABLE_ID_ALLOC)?;
        db.delete_table(v3::TABLE_PLAYLIST)?;
        db.delete_multimap_table(v3::TABLE_PLAYLIST_MUSIC)?;
        db.delete_multimap_table(v3::TABLE_MUSIC_PLAYLIST)?;
        db.delete_table(v3::TABLE_MUSIC)?;
        db.delete_table(v3::TABLE_MUSIC_BY_LOC)?;
        db.delete_table(v3::TABLE_STORAGE)?;
        db.delete_multimap_table(v3::TABLE_STORAGE_MUSIC)?;
        db.delete_table(v3::TABLE_PREFERENCE)?;
        db.delete_table(v3::TABLE_BLOB)?;
        tracing::info!("v3 -> v4: finish to delete old tables");
    }
    {
        let mut t = db.open_table(v4::TABLE_SCHEMA_VERSION)?;
        t.insert((), 4)?;
    }
    db.commit()?;
    tracing::info!("v3 -> v4: finish all");
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use tempfile::tempdir;

    use crate::{v3, v4};

    #[test]
    fn upgrade_v3_to_v4_migrates_playlist_order_to_membership_table() {
        let dir = tempdir().unwrap();
        let db_path = dir.path().join("data.redb");
        let db = Arc::new(redb::Database::create(db_path).unwrap());

        {
            let txn = db.begin_write().unwrap();
            txn.open_table(v3::TABLE_ID_ALLOC).unwrap();
            txn.open_table(v3::TABLE_PLAYLIST).unwrap();
            txn.open_multimap_table(v3::TABLE_PLAYLIST_MUSIC).unwrap();
            txn.open_multimap_table(v3::TABLE_MUSIC_PLAYLIST).unwrap();
            txn.open_table(v3::TABLE_MUSIC).unwrap();
            txn.open_table(v3::TABLE_MUSIC_BY_LOC).unwrap();
            txn.open_table(v3::TABLE_STORAGE).unwrap();
            txn.open_multimap_table(v3::TABLE_STORAGE_MUSIC).unwrap();
            txn.open_table(v3::TABLE_PREFERENCE).unwrap();
            txn.open_table(v3::TABLE_SCHEMA_VERSION).unwrap();
            txn.open_table(v3::TABLE_BLOB).unwrap();

            txn.open_table(v3::TABLE_PLAYLIST)
                .unwrap()
                .insert(
                    &v3::PlaylistId::wrap(1),
                    &v3::PlaylistModel {
                        id: v3::PlaylistId::wrap(1),
                        title: "playlist".to_string(),
                        created_time: 1,
                        picture: None,
                        order: vec![1],
                    },
                )
                .unwrap();
            {
                let mut music_table = txn.open_table(v3::TABLE_MUSIC).unwrap();
                music_table
                    .insert(
                        &v3::MusicId::wrap(10),
                        &v3::MusicModel {
                            id: v3::MusicId::wrap(10),
                            loc: v3::StorageEntryLoc {
                                storage_id: v3::StorageId::wrap(1),
                                path: "/a.mp3".to_string(),
                            },
                            title: "a".to_string(),
                            duration: None,
                            cover: None,
                            lyric: None,
                            lyric_default: true,
                            order: vec![2],
                        },
                    )
                    .unwrap();
                music_table
                    .insert(
                        &v3::MusicId::wrap(11),
                        &v3::MusicModel {
                            id: v3::MusicId::wrap(11),
                            loc: v3::StorageEntryLoc {
                                storage_id: v3::StorageId::wrap(1),
                                path: "/b.mp3".to_string(),
                            },
                            title: "b".to_string(),
                            duration: None,
                            cover: None,
                            lyric: None,
                            lyric_default: true,
                            order: vec![1],
                        },
                    )
                    .unwrap();
            }
            {
                let mut playlist_music = txn.open_multimap_table(v3::TABLE_PLAYLIST_MUSIC).unwrap();
                playlist_music
                    .insert(&v3::PlaylistId::wrap(1), &v3::MusicId::wrap(10))
                    .unwrap();
                playlist_music
                    .insert(&v3::PlaylistId::wrap(1), &v3::MusicId::wrap(11))
                    .unwrap();
            }
            {
                let mut music_playlist = txn.open_multimap_table(v3::TABLE_MUSIC_PLAYLIST).unwrap();
                music_playlist
                    .insert(&v3::MusicId::wrap(10), &v3::PlaylistId::wrap(1))
                    .unwrap();
                music_playlist
                    .insert(&v3::MusicId::wrap(11), &v3::PlaylistId::wrap(1))
                    .unwrap();
            }
            txn.open_table(v3::TABLE_SCHEMA_VERSION)
                .unwrap()
                .insert((), 3)
                .unwrap();
            txn.commit().unwrap();
        }

        super::upgrade_v3_to_v4(&db).unwrap();

        let txn = db.begin_read().unwrap();
        let playlist_music = txn.open_multimap_table(v4::TABLE_PLAYLIST_MUSIC).unwrap();
        let mut values = Vec::new();
        for value in playlist_music.get(crate::PlaylistId::wrap(1)).unwrap() {
            values.push(value.unwrap().value());
        }
        values.sort_by(|lhs, rhs| lhs.order.cmp(&rhs.order));

        assert_eq!(2, values.len());
        assert_eq!(crate::MusicId::wrap(11), values[0].music_id);
        assert_eq!(crate::MusicId::wrap(10), values[1].music_id);

        let schema_version = txn
            .open_table(v4::TABLE_SCHEMA_VERSION)
            .unwrap()
            .get(())
            .unwrap()
            .unwrap()
            .value();
        assert_eq!(4, schema_version);
    }
}
