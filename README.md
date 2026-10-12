# Readon

一个简洁的 Android 阅读器。书籍来自 GitHub 仓库，在线阅读或下载到本地，界面遵循「主页 / 书架 / 设置」三栏结构。

当前版本 **1.0.1**（versionCode 10001）。

## 功能

### 主页 —— 在线书库
- 从 GitHub 仓库递归列出所有可读文件，按文件夹自动分类（如 `默认/xxx.txt`）
- 支持格式：`txt`、`md`（及 `markdown` 别名）、`html`、`epub`；`pdf` 提示下载后用系统应用打开
- **在线阅读**：点书即读，无需下载，正文直接分页排版
- **下载**：点右侧下载图标保存到设备，带百分比进度，出现在书架
- 搜索书名 / 分类；顶栏显示当前仓库与分支；可配置访问令牌以提升 API 限额

### 书架
- 本地已下载书籍的封面网格，封面按书名哈希生成渐变，同一本书永远同一张封面
- 阅读进度百分比直接画在封面上
- **从本地导入**：右上角「+」一次可选多个文件，导入 `txt` / `md` / `html` / `epub`
- 排序：最近阅读 / 最近添加 / 书名 / 分类；长按删除

### 阅读器
- 正文用 Compose 原生排版，所见即所量：分页与渲染同一套测量
- 左 1/3 屏上一页，右 1/3 屏下一页，中间点按呼出/隐藏工具栏
- 自动识别章节（`第X章`、`Chapter N`、`楔子/序章/尾声`、Markdown 标题），侧滑目录直达
- 全书全文搜索，逐条跳转
- 阅读主题：纸白 / 米黄 / 护眼 / 夜间 / 纯黑；字号 12–30sp；行距 1.2–2.4；衬线开关
- 阅读位置以字符偏移保存，改字号后仍停在同一句话
- TXT 自动检测编码（UTF-8 / GBK / UTF-16，含无 BOM 情况），中文书不再乱码
- 翻页按需分页，整书不预排版，百万字也秒开

### 设置
- 阅读偏好（主题、字号、行距、字体、屏幕常亮）
- 应用主题：跟随系统 / 浅色 / 深色
- 书库来源：GitHub 用户名 / 仓库 / 分支 / 访问令牌，全部可改
- **网络线路**：显示当前实际生效的线路，可手动指定，可点「检测线路」逐条测试
- 存储：查看占用、清空本地书籍
- 更新：从 GitHub 检查新版本，下载并校验 SHA-256 后安装
- 关于：版本信息

## 网络线路

`raw.githubusercontent.com` 在部分网络下被完全阻断（连接超时后 TLS 重置）。因此所有请求都同时向多条线路发起，取最先成功的，并记住可用线路。

| 线路 | 小文件 | 大文件（>20 MB） | 列表接口 |
|------|--------|------------------|----------|
| GitHub 直连 | 超时 | 超时 | 可用 |
| cdn.jsdelivr.net | 可用 | 403 | 403 |
| fastly / gcore.jsdelivr.net | 可用 | 403 | 403 |
| ghproxy.net | 可用 | 可用 | 403 |
| gh-proxy.com | 可用 | 可用 | 可用 |
| ghfast.top / gitmirror / Statically | 超时 | 超时 | — |

实测数据来自本仓库（53 本书，33 本超过 8 MB，最大 75 MB）。jsDelivr 限制单文件 20 MB、整包 50 MB，所以它无法覆盖这个书库；`ghproxy.net` 与 `gh-proxy.com` 才能。

因此：
- **列表**与**取文件**用两套独立的线路表——`api.github.com` 与 `raw.githubusercontent.com` 是两个不同的服务，前者没被阻断时后者可能已被阻断。
- 选线路先用 **2 KB 的 Range 请求探测**，再流式传输，避免把几十兆的文件在九条线路上各试一遍。
- 在线阅读上限 64 MB；超过的书提示先下载（下载直接流式写盘，不进内存）。

## 书库格式

任意公开 GitHub 仓库，文件夹即分类：

```
仓库根/
├── 默认/
│   ├── 书名.txt
│   └── 另一本书.epub
├── 科幻/
│   └── 三体.txt
└── ...
```

默认书库：`nmaych/ReadonBooklibrary`，分支 `main`。全部可在设置中修改。

## 构建

需要 JDK 17、Android SDK 34、Gradle 8.7。依赖已全部落在本地 Gradle 缓存中，支持离线构建：

```
build.cmd                # assembleDebug
build.cmd assembleRelease
```

发布版签名密钥位于 `keystore/readon-release.jks`，凭据在 `app-project/keystore.properties`（不入库）。

发布新版时改 `app/build.gradle.kts` 里的 `appVersionCode` / `appVersionName`，构建后运行：

```
python tools/stamp_manifest.py
```

它会用实际产出的 APK 重算 SHA-256 并写回 `dist/update.json`，避免手改哈希导致应用判定更新包损坏。

## 更新机制

1. 应用内「设置 → 更新」读取 `dist/update.json`（走多线路）
2. `versionCode` 高于当前版本即提示；比对始终用 versionCode，不用版本名字符串
3. 下载到应用私有缓存，逐块回传进度；有 `sha256` 则校验，不匹配直接删除
4. 调系统 PackageInstaller 安装；首次需要用户在系统里授予「安装未知应用」权限

## 图标

`art/original-icon.png` 是设计原图，不重绘。`tools/make_icon.py` 只做派生：各密度 PNG、圆形版本，以及自适应图标前景（背景取原图自身的深蓝色，因此任何启动器裁切形状下都不会露出白边）。

## 目录

```
readon/
├── app-project/          # Gradle 工程（app 模块）
├── art/                  # 设计原图与预览
├── dist/                 # 发布产物与 update.json
├── keystore/             # 发布签名密钥
└── tools/                # 图标生成、密钥生成、线路检测、链路验证、清单盖章
```
