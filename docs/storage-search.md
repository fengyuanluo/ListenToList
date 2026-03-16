# AList / OpenList 官方搜索接入说明

## 功能范围

本仓库现已支持两类基于 **官方 API** 的搜索能力：

1. **目录内搜索**
   - 入口：`StorageBrowserPage`
   - 范围：当前浏览目录 `currentPath`
   - 协议：`POST /api/fs/search`

2. **首页聚合搜索**
   - 入口：左下角首页（播放列表页）顶部长条搜索框 + 独立 `StorageSearchPage`
   - 范围：所有已配置的 `StorageType.OPEN_LIST` 实例根目录 `/`
   - 协议：对每个实例并发调用 `POST /api/fs/search`

## 官方 API 契约

当前实现只依赖官方 `AList v3 / OpenList` 搜索接口，不做网页逆向：

- 接口：`POST /api/fs/search`
- 请求字段：
  - `parent`
  - `keywords`
  - `scope`
  - `page`
  - `per_page`
  - `password`
- `scope` 语义：
  - `0`：全部
  - `1`：目录
  - `2`：文件

Rust 侧已将其映射到：

- `ease-remote-storage::SearchScope`
- `ease-client-backend::StorageSearchScope`
- `ctSearchStorageEntries(...)`

## 兼容性与错误处理

### 搜索能力

并不是所有实例都保证支持搜索：

- 若实例未启用官方搜索索引，会返回 `search not available`
- Android 端会将其显示为“实例未开启搜索”而不是笼统失败

### 站点风控 / Cloudflare

一些站点对脚本请求较敏感，即使官方 API 本身可用，也可能直接返回 HTML challenge 页。

为兼容实站，OpenList Rust 客户端的 API 请求已补充浏览器兼容请求头，包括：

- `User-Agent`
- `Accept`
- `Accept-Language`
- `Origin`
- `Referer`
- `sec-ch-ua*`

若仍返回 HTML challenge，则会映射为 `BlockedBySite`，Android 端显示专门错误态。

## Android 行为约定

### 目录内搜索

- 搜索框只在 `StorageType.OPEN_LIST` 设备页出现
- 搜索中不破坏原目录浏览状态；清空搜索后返回目录列表
- 点击结果：
  - 目录：进入目录
  - 音频：直接播放
  - 其他文件：定位到所在目录
- 支持继续加载下一页搜索结果

### 首页聚合搜索

- 只搜索 `StorageType.OPEN_LIST` 实例
- 首页入口为一个极简长条搜索框，不显示额外标题、说明或状态文案
- 搜索框放在左下角首页（播放列表页）顶部，而不是中间设备页
- 结果按实例分组
- 每个实例单独处理：
  - 有结果正常展示
  - 没结果显示空态
  - 失败显示实例级错误卡片
- 点击结果：
  - 目录：直接打开对应目录页
  - 音频：直接播放
  - 其他文件：打开所在目录

## 本地验证命令

### Rust

```bash
cd rust-libs
cargo test -p ease-remote-storage openlist -- --nocapture
cargo test -p ease-client-backend --lib -- --nocapture
```

### FFI / Android

```bash
bun run build:jni
cd android
./gradlew testDebugUnitTest --warning-mode all
./gradlew :app:assembleDebug --warning-mode all
./gradlew connectedDebugAndroidTest
```

## 实站验证：www.asmrgay.com

`2026-03-16` 本地只读验证结果：

- `GET https://www.asmrgay.com/api/public/settings`
  - 返回 `200`
  - 关键字段：
    - `version = v3.38.0`
    - `search_index = database_non_full_text`
- `POST https://www.asmrgay.com/api/fs/list`
  - 返回 `200`
- `POST https://www.asmrgay.com/api/fs/search`
  - 返回 `200`
  - 可正常返回搜索结果

说明：该站点当前是 **AList v3**，且官方搜索索引处于启用状态。
