use redb::{MultimapTableDefinition, TableDefinition};

use crate::{BlobId, v2};

use super::super::{
    models::{
        DbKeyAlloc, MusicModel, PlaylistModel, PlaylistMusicModel, PreferenceModel, StorageModel,
    },
    objects::{MusicId, PlaylistId, StorageEntryLoc, StorageId},
};

use super::bin::{BinSerde, BinSerdeTN};

impl BinSerdeTN for DbKeyAlloc {
    const NAME: &'static str = "DbKeyAlloc";
}

impl BinSerdeTN for PlaylistId {
    const NAME: &'static str = "PlaylistId";
}

impl BinSerdeTN for MusicId {
    const NAME: &'static str = "MusicId";
}

impl BinSerdeTN for StorageId {
    const NAME: &'static str = "StorageId";
}

impl BinSerdeTN for BlobId {
    const NAME: &'static str = "BlobId";
}

impl BinSerdeTN for StorageEntryLoc {
    const NAME: &'static str = "StorageEntryLoc";
}

impl BinSerdeTN for MusicModel {
    const NAME: &'static str = "MusicModel";
}

impl BinSerdeTN for PlaylistModel {
    const NAME: &'static str = "PlaylistModel";
}

impl BinSerdeTN for PlaylistMusicModel {
    const NAME: &'static str = "PlaylistMusicModel";
}

impl BinSerdeTN for PreferenceModel {
    const NAME: &'static str = "PreferenceModel";
}

impl BinSerdeTN for StorageModel {
    const NAME: &'static str = "StorageModel";
}

pub const TABLE_ID_ALLOC: TableDefinition<BinSerde<DbKeyAlloc>, i64> =
    TableDefinition::new("v5_alloc");
pub const TABLE_PLAYLIST: TableDefinition<BinSerde<PlaylistId>, BinSerde<PlaylistModel>> =
    TableDefinition::new("v5_playlist");
pub const TABLE_PLAYLIST_MUSIC: MultimapTableDefinition<
    BinSerde<PlaylistId>,
    BinSerde<PlaylistMusicModel>,
> = MultimapTableDefinition::new("v5_playlist_music");
pub const TABLE_MUSIC_PLAYLIST: MultimapTableDefinition<BinSerde<MusicId>, BinSerde<PlaylistId>> =
    MultimapTableDefinition::new("v5_music_playlist");
pub const TABLE_MUSIC: TableDefinition<BinSerde<MusicId>, BinSerde<MusicModel>> =
    TableDefinition::new("v5_music");
pub const TABLE_MUSIC_BY_LOC: TableDefinition<BinSerde<StorageEntryLoc>, BinSerde<MusicId>> =
    TableDefinition::new("v5_music_by_loc");
pub const TABLE_STORAGE: TableDefinition<BinSerde<StorageId>, BinSerde<StorageModel>> =
    TableDefinition::new("v5_storage");
pub const TABLE_STORAGE_MUSIC: MultimapTableDefinition<BinSerde<StorageId>, BinSerde<MusicId>> =
    MultimapTableDefinition::new("v5_storage_music");
pub const TABLE_PREFERENCE: TableDefinition<(), BinSerde<PreferenceModel>> =
    TableDefinition::new("v5_preference");
pub use v2::TABLE_SCHEMA_VERSION;
pub const TABLE_BLOB: TableDefinition<(), BinSerde<BlobId>> = TableDefinition::new("v5_blob");
