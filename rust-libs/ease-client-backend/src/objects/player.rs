#[derive(Debug, Clone, uniffi::Record)]
pub struct PlaybackHttpHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DirectHttpPlaybackSource {
    pub url: String,
    pub headers: Vec<PlaybackHttpHeader>,
    pub cache_key: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LocalFilePlaybackSource {
    pub absolute_path: String,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum PlaybackSourceDescriptor {
    DirectHttp(DirectHttpPlaybackSource),
    LocalFile(LocalFilePlaybackSource),
    StreamFallback,
}
