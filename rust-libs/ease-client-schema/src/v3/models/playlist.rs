use serde::{Deserialize, Serialize};

use super::super::objects::{PlaylistId, StorageEntryLoc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaylistModel {
    pub id: PlaylistId,
    pub title: String,
    pub created_time: i64,
    pub picture: Option<StorageEntryLoc>,
    pub order: Vec<u32>,
}
