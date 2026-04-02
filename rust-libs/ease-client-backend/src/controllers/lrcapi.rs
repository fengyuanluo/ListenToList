use crate::services::{
    fetch_lrcapi_music_supplement, LrcApiConfig, LrcApiFetchResult, LrcApiQuery,
};

#[uniffi::export]
pub async fn ct_fetch_lrcapi_music_supplement(
    config: LrcApiConfig,
    query: LrcApiQuery,
) -> LrcApiFetchResult {
    fetch_lrcapi_music_supplement(&config, &query).await
}
