use std::time::Duration;

use ease_remote_storage::{BuildOpenListArg, OpenList, StorageBackend};

#[tokio::test]
async fn test_openlist_guest_list_and_get() {
    let backend = OpenList::new(BuildOpenListArg {
        addr: "https://www.asmrgay.com".to_string(),
        username: "".to_string(),
        password: "".to_string(),
        is_anonymous: true,
        connect_timeout: Duration::from_secs(15),
    });

    let list = backend.list("/".to_string()).await.unwrap();
    assert!(!list.is_empty());

    let updates_dir = list
        .iter()
        .find(|entry| entry.name == "更新日志")
        .expect("更新日志目录不存在");

    let updates = backend.list(updates_dir.path.clone()).await.unwrap();
    let file = updates
        .iter()
        .find(|entry| !entry.is_dir)
        .expect("更新日志目录下没有文件");

    let file = backend.get(file.path.clone(), 0).await.unwrap();
    let bytes = file.bytes().await.unwrap();
    assert!(!bytes.is_empty());
}
