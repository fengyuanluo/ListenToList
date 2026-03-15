use ease_client_tokio::tokio_runtime;
use futures_util::future::BoxFuture;

use crate::{
    Entry, ResolvedPlaybackSource, StorageBackend, StorageBackendError, StorageBackendResult,
    StreamFile,
};

pub struct LocalBackend;

static ANDROID_PREFIX_PATH: &str = "/storage/emulated/0";

impl Default for LocalBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl LocalBackend {
    pub fn new() -> Self {
        Self
    }

    fn normalize_input_path(&self, p: &str) -> String {
        if std::env::consts::OS == "windows" {
            p.replace('/', "\\")
        } else if std::env::consts::OS == "android" {
            ANDROID_PREFIX_PATH.to_string() + p
        } else {
            p.to_string()
        }
    }

    async fn resolve_absolute_path_impl(&self, p: String) -> StorageBackendResult<(String, usize)> {
        let p = self.normalize_input_path(&p);
        let (path, total) = {
            let p = p.clone();
            tokio_runtime()
                .spawn(async move {
                    let path = tokio::fs::canonicalize(&p).await?;
                    let meta = tokio::fs::metadata(&path).await?;
                    Ok::<_, StorageBackendError>((path, meta.len() as usize))
                })
                .await??
        };
        let absolute_path = path.to_string_lossy().to_string().replace("\\\\?\\", "");
        Ok((absolute_path, total))
    }

    async fn list_impl(&self, dir: String) -> StorageBackendResult<Vec<Entry>> {
        let dir = self.normalize_input_path(&dir);

        let mut ret = tokio_runtime()
            .spawn(async move {
                let path = tokio::fs::canonicalize(dir).await?;
                let mut dir = tokio::fs::read_dir(path).await?;

                let mut ret: Vec<Entry> = Default::default();
                while let Some(entry) = dir.next_entry().await? {
                    let metadata = entry.metadata().await?;
                    let mut path = entry
                        .path()
                        .to_string_lossy()
                        .to_string()
                        .replace("\\\\?\\", "");
                    if std::env::consts::OS == "android" {
                        if let Some(strip_path) = path.strip_prefix(ANDROID_PREFIX_PATH) {
                            path = strip_path.to_string();
                        }
                    }

                    ret.push(Entry {
                        name: entry.file_name().to_string_lossy().to_string(),
                        path: path.replace('\\', "/"),
                        size: Some(metadata.len() as usize),
                        is_dir: metadata.is_dir(),
                    });
                }

                Ok::<_, StorageBackendError>(ret)
            })
            .await??;

        ret.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(ret)
    }

    async fn get_impl(&self, p: String, byte_offset: u64) -> StorageBackendResult<StreamFile> {
        let (path, total) = self.resolve_absolute_path_impl(p).await?;
        Ok(StreamFile::new_from_file(path, total, byte_offset))
    }
}

impl StorageBackend for LocalBackend {
    fn list(&self, dir: String) -> BoxFuture<'_, StorageBackendResult<Vec<Entry>>> {
        Box::pin(self.list_impl(dir))
    }
    fn get(&self, p: String, byte_offset: u64) -> BoxFuture<'_, StorageBackendResult<StreamFile>> {
        Box::pin(self.get_impl(p, byte_offset))
    }
    fn resolve_playback_source(
        &self,
        p: String,
    ) -> BoxFuture<'_, StorageBackendResult<ResolvedPlaybackSource>> {
        Box::pin(async move {
            let (absolute_path, _) = self.resolve_absolute_path_impl(p).await?;
            Ok(ResolvedPlaybackSource::LocalFile { absolute_path })
        })
    }
}

#[cfg(test)]
mod test {
    use crate::{LocalBackend, ResolvedPlaybackSource, StorageBackend};

    #[tokio::test]
    async fn test_list_dir() {
        let backend = LocalBackend::new();

        let cwd = std::env::current_dir()
            .unwrap()
            .join("test/assets/case_list");
        let cwd = cwd.to_string_lossy().to_string();
        let list = backend.list(cwd).await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "a.txt");
        assert_eq!(list[1].name, "b.log.txt");
    }

    #[tokio::test]
    async fn test_list_dir_use_linux_slash() {
        let backend = LocalBackend::new();

        let cwd = std::env::current_dir()
            .unwrap()
            .join("test/assets/case_list");
        let cwd = cwd.to_string_lossy().to_string();
        let cwd = cwd.replace("\\", "/");
        let list = backend.list(cwd).await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "a.txt");
        assert_eq!(list[1].name, "b.log.txt");
    }

    #[tokio::test]
    async fn test_partial_bytes() {
        let backend = LocalBackend::new();

        let cwd = std::env::current_dir()
            .unwrap()
            .join("test/assets/case_list/b.log.txt");
        let cwd = cwd.to_string_lossy().to_string();
        let file = backend.get(cwd, 3).await.unwrap();
        let bytes = file.bytes().await.unwrap();

        assert_eq!(String::from_utf8_lossy(bytes.as_ref()), "og.txt");
    }

    #[tokio::test]
    async fn test_partial_stream() {
        let backend = LocalBackend::new();

        let cwd = std::env::current_dir()
            .unwrap()
            .join("test/assets/case_list/b.log.txt");
        let cwd = cwd.to_string_lossy().to_string();
        let file = backend.get(cwd, 3).await.unwrap();

        let stream = file.into_rx();
        let chunk = stream.recv().await;
        assert!(chunk.is_ok());
        let chunk = chunk.unwrap().unwrap();
        assert_eq!(String::from_utf8_lossy(chunk.as_ref()), "og.txt");
    }

    #[tokio::test]
    async fn test_resolve_playback_source_returns_absolute_path() {
        let backend = LocalBackend::new();

        let cwd = std::env::current_dir()
            .unwrap()
            .join("test/assets/case_list/a.txt");
        let cwd = cwd.to_string_lossy().to_string().replace("\\", "/");
        let resolved = backend.resolve_playback_source(cwd).await.unwrap();
        match resolved {
            ResolvedPlaybackSource::LocalFile { absolute_path } => {
                assert!(absolute_path.ends_with("a.txt"));
                assert!(absolute_path.contains("case_list"));
            }
            other => panic!("unexpected resolved playback source: {other:?}"),
        }
    }
}
