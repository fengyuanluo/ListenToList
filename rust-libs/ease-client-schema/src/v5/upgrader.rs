use std::sync::Arc;

use redb::{
    MultimapTableDefinition, ReadableMultimapTable, ReadableTable, TableDefinition,
    WriteTransaction,
};

use crate::{v4, v5};

impl From<v4::DbKeyAlloc> for v5::DbKeyAlloc {
    fn from(value: v4::DbKeyAlloc) -> Self {
        match value {
            v4::DbKeyAlloc::Playlist => v5::DbKeyAlloc::Playlist,
            v4::DbKeyAlloc::Music => v5::DbKeyAlloc::Music,
            v4::DbKeyAlloc::Storage => v5::DbKeyAlloc::Storage,
        }
    }
}

impl From<v4::PlaylistModel> for v5::PlaylistModel {
    fn from(value: v4::PlaylistModel) -> Self {
        Self {
            id: value.id,
            title: value.title,
            created_time: value.created_time,
            picture: value.picture,
            order: value.order,
        }
    }
}

impl From<v4::MusicModel> for v5::MusicModel {
    fn from(value: v4::MusicModel) -> Self {
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

impl From<v4::StorageModel> for v5::StorageModel {
    fn from(value: v4::StorageModel) -> Self {
        Self {
            id: value.id,
            addr: value.addr,
            alias: value.alias,
            username: value.username,
            password: value.password,
            is_anonymous: value.is_anonymous,
            typ: value.typ,
            default_path: "/".to_string(),
        }
    }
}

impl From<v4::PreferenceModel> for v5::PreferenceModel {
    fn from(value: v4::PreferenceModel) -> Self {
        Self {
            playmode: value.playmode,
        }
    }
}

impl From<v4::PlaylistMusicModel> for v5::PlaylistMusicModel {
    fn from(value: v4::PlaylistMusicModel) -> Self {
        Self {
            music_id: value.music_id,
            order: value.order,
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

pub fn upgrade_v4_to_v5(database: &Arc<redb::Database>) -> anyhow::Result<()> {
    let db = database.begin_write()?;
    {
        let ref db = db;
        convert_table(db, v4::TABLE_ID_ALLOC, v5::TABLE_ID_ALLOC)?;
        convert_table(db, v4::TABLE_PLAYLIST, v5::TABLE_PLAYLIST)?;
        convert_multi_table(db, v4::TABLE_PLAYLIST_MUSIC, v5::TABLE_PLAYLIST_MUSIC)?;
        convert_multi_table(db, v4::TABLE_MUSIC_PLAYLIST, v5::TABLE_MUSIC_PLAYLIST)?;
        convert_table(db, v4::TABLE_MUSIC, v5::TABLE_MUSIC)?;
        convert_table(db, v4::TABLE_MUSIC_BY_LOC, v5::TABLE_MUSIC_BY_LOC)?;
        convert_table(db, v4::TABLE_STORAGE, v5::TABLE_STORAGE)?;
        convert_multi_table(db, v4::TABLE_STORAGE_MUSIC, v5::TABLE_STORAGE_MUSIC)?;
        convert_table(db, v4::TABLE_PREFERENCE, v5::TABLE_PREFERENCE)?;
        convert_table(db, v4::TABLE_BLOB, v5::TABLE_BLOB)?;
        tracing::info!("v4 -> v5: finish storage default-path migration");
    }
    {
        db.delete_table(v4::TABLE_ID_ALLOC)?;
        db.delete_table(v4::TABLE_PLAYLIST)?;
        db.delete_multimap_table(v4::TABLE_PLAYLIST_MUSIC)?;
        db.delete_multimap_table(v4::TABLE_MUSIC_PLAYLIST)?;
        db.delete_table(v4::TABLE_MUSIC)?;
        db.delete_table(v4::TABLE_MUSIC_BY_LOC)?;
        db.delete_table(v4::TABLE_STORAGE)?;
        db.delete_multimap_table(v4::TABLE_STORAGE_MUSIC)?;
        db.delete_table(v4::TABLE_PREFERENCE)?;
        db.delete_table(v4::TABLE_BLOB)?;
        tracing::info!("v4 -> v5: finish deleting old tables");
    }
    {
        let mut t = db.open_table(v5::TABLE_SCHEMA_VERSION)?;
        t.insert((), 5)?;
    }
    db.commit()?;
    tracing::info!("v4 -> v5: finish all");
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use tempfile::tempdir;

    use crate::{v4, v5};

    #[test]
    fn upgrade_v4_to_v5_backfills_storage_default_path() {
        let dir = tempdir().unwrap();
        let db_path = dir.path().join("data.redb");
        let db = Arc::new(redb::Database::create(db_path).unwrap());

        {
            let txn = db.begin_write().unwrap();
            txn.open_table(v4::TABLE_ID_ALLOC).unwrap();
            txn.open_table(v4::TABLE_PLAYLIST).unwrap();
            txn.open_multimap_table(v4::TABLE_PLAYLIST_MUSIC).unwrap();
            txn.open_multimap_table(v4::TABLE_MUSIC_PLAYLIST).unwrap();
            txn.open_table(v4::TABLE_MUSIC).unwrap();
            txn.open_table(v4::TABLE_MUSIC_BY_LOC).unwrap();
            txn.open_table(v4::TABLE_STORAGE).unwrap();
            txn.open_multimap_table(v4::TABLE_STORAGE_MUSIC).unwrap();
            txn.open_table(v4::TABLE_PREFERENCE).unwrap();
            txn.open_table(v4::TABLE_SCHEMA_VERSION).unwrap();
            txn.open_table(v4::TABLE_BLOB).unwrap();

            txn.open_table(v4::TABLE_STORAGE)
                .unwrap()
                .insert(
                    &v4::StorageId::wrap(7),
                    &v4::StorageModel {
                        id: v4::StorageId::wrap(7),
                        addr: "https://openlist.example".to_string(),
                        alias: "Demo".to_string(),
                        username: "user".to_string(),
                        password: "pass".to_string(),
                        is_anonymous: false,
                        typ: v4::StorageType::OpenList,
                    },
                )
                .unwrap();
            txn.open_table(v4::TABLE_SCHEMA_VERSION)
                .unwrap()
                .insert((), 4)
                .unwrap();
            txn.commit().unwrap();
        }

        super::upgrade_v4_to_v5(&db).unwrap();

        let txn = db.begin_read().unwrap();
        let storage = txn
            .open_table(v5::TABLE_STORAGE)
            .unwrap()
            .get(v5::StorageId::wrap(7))
            .unwrap()
            .unwrap()
            .value();
        assert_eq!("/", storage.default_path);

        let schema_version = txn
            .open_table(v5::TABLE_SCHEMA_VERSION)
            .unwrap()
            .get(())
            .unwrap()
            .unwrap()
            .value();
        assert_eq!(5, schema_version);
    }
}
