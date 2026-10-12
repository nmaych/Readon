# 发布与 GitHub 配置

这份文档说明**你需要手动做的一次性设置**，以及每次发版的流程。

---

## 一、必须配置的仓库 Secrets

自动发布的 workflow 需要四个 secret。**没有它们，打标签触发的发布会直接失败**（这是故意的：签名密钥不一致的 APK 装到用户手机上无法覆盖升级，比不发版更糟）。

到 `https://github.com/nmaych/Readon/settings/secrets/actions` 添加：

| Secret 名称 | 值 | 说明 |
|---|---|---|
| `READON_KEYSTORE_BASE64` | 见下方生成方法 | 签名密钥文件的 base64 |
| `READON_KEYSTORE_PASSWORD` | `readon2026` | 密钥库密码 |
| `READON_KEY_ALIAS` | `readon` | 密钥别名 |
| `READON_KEY_PASSWORD` | `readon2026` | 密钥密码 |

### 生成 `READON_KEYSTORE_BASE64`

密钥文件在 `keystore/readon-release.jks`（已被 `.gitignore` 排除，**不会**进仓库，你需要保留好它）。

Windows PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("E:\deepseekworkspace\readon\keystore\readon-release.jks")) | Set-Clipboard
```

macOS / Linux：

```bash
base64 -w0 keystore/readon-release.jks | pbcopy   # macOS 用 pbcopy，Linux 用 xclip -sel clip
```

把结果整段粘进 `READON_KEYSTORE_BASE64`。

> **密钥就是应用的身份。** 丢了它，已安装的用户无法升级到新版本，只能卸载重装；泄露了它，任何人都能签发一个能覆盖安装的假 APK。请离线备份，不要提交到任何仓库。

### 可选：提升 GitHub API 限额

应用内「设置 → 书库来源」可以填访问令牌（Personal Access Token），把接口限额从 60 次/小时提到 5000 次。这只是应用内的设置，不需要配成仓库 secret。

---

## 二、仓库设置建议

到 `settings` 页面：

- **General → Default branch**：确认是 `main`。workflow 会把 `dist/update.json` 提交到默认分支，应用也从那里读取清单。
- **General → Features → Discussions**：建议开启。`ISSUE_TEMPLATE/config.yml` 里指向了它。
- **Actions → General → Workflow permissions**：选择 **Read and write permissions**。release workflow 需要写权限来创建 release 并提交清单。
- **Actions → General → Allow GitHub Actions to create and approve pull requests**：Dependabot 需要，建议开启。
- **Pages**：不需要。

---

## 三、首次推送

当前仓库已经配好了 remote，但还没有任何提交。

```powershell
cd E:\deepseekworkspace\readon
git add -A
git status          # 确认 keystore/ 和 *.jks 不在列表里
git commit -m "feat: Readon 1.0.1"
git branch -M main  # 当前是 master，workflow 假定 main
git push -u origin main
```

推送后 CI 会自动跑一次编译检查。

---

## 四、发布新版本

流程是**标签驱动**的：打标签 → 自动构建、签名、发 release、更新清单。

### 1. 改版本号并更新 CHANGELOG

`app-project/app/build.gradle.kts`：

```kotlin
val appVersionCode: Int = (System.getenv("READON_VERSION_CODE") ?: "10002").toIntOrNull()
val appVersionName: String = System.getenv("READON_VERSION_NAME") ?: "1.0.2"
```

`versionCode` 的算法是 `major*10000 + minor*100 + patch`：

| 版本 | versionCode |
|---|---|
| 1.0.0 | 10000 |
| 1.0.1 | 10001 |
| 1.0.2 | 10002 |
| 1.1.0 | 10100 |
| 2.0.0 | 20000 |

> 注意别算错。`1.0.1` 是 **10001**，不是 10100（10100 是 1.1.0）。应用只比对 `versionCode`，算错会导致更新提示永远消不掉，或者干脆不提示。

在 `CHANGELOG.md` 顶部加一节：

```markdown
## [1.0.2] - 2026-10-20

### Fixed

- 修复……
```

这一节会**自动**变成 GitHub release 说明和应用内的更新说明，不需要另外写。

### 2. 本地验证（可选但建议）

```powershell
build.cmd assembleRelease
python tools\check_repo_config.py
```

`check_repo_config.py` 会检查版本号三处是否一致、CHANGELOG 是否有对应章节、密钥是否被正确忽略、workflow 的 YAML 是否合法。

### 3. 打标签

```powershell
git add -A
git commit -m "chore(release): 1.0.2"
git push
git tag v1.0.2
git push origin v1.0.2
```

### 4. 之后自动发生的事

`.github/workflows/release.yml` 会：

1. 从标签解析版本号，算出 `versionCode`（标签格式必须是 `vX.Y.Z`，否则报错退出）
2. 用 secrets 里的密钥签名构建 release APK
3. `apksigner verify` 校验签名 —— 未签名或调试签名的 APK 会直接让流程失败
4. 计算 SHA-256
5. 跑 `tools/ci_release.py` 更新 `dist/update.json`（版本号、下载地址、真实哈希、从 CHANGELOG 提取的说明）
6. 把清单提交回默认分支
7. 创建 GitHub Release 并上传 APK

用户手机上的 Readon 下次检查更新时就会看到新版本，下载后校验 SHA-256 再安装。

### 重新发布

如果某次发布因为基础设施原因失败，可以在 Actions 页面手动触发 **Release** workflow，填已有的标签名（例如 `v1.0.2`）。它会覆盖已存在的 release 资产，不会重复创建。

---

## 五、配置检查清单

推送前跑一遍：

```powershell
python tools\check_repo_config.py
```

它会验证：

- 所有 workflow YAML 能解析、有 job、有触发器
- `dist/update.json` 是合法 JSON，`versionCode` 与 `versionName` 一致，`apkUrl` 的标签与版本一致，`sha256` 是 64 位小写十六进制
- `build.gradle.kts` 的默认版本号与清单一致
- `CHANGELOG.md` 有当前版本的章节
- `.gitignore` 排除了 `keystore.properties`、`*.jks`、`local.properties`
- `.gitignore` 里 `*.jar` 规则有 `!gradle/wrapper/gradle-wrapper.jar` 例外（少了它，克隆下来无法构建）
- Gradle wrapper 三个文件齐全

---

## 六、文件说明

```
.github/
├── workflows/
│   ├── ci.yml              # 每次 push/PR 编译检查，不签名不发布
│   └── release.yml         # 打 v* 标签时构建、签名、发布、更新清单
├── ISSUE_TEMPLATE/
│   ├── bug_report.yml      # 带「检测线路」结果字段，多数打不开的问题出在这里
│   ├── feature_request.yml
│   └── config.yml
├── pull_request_template.md
└── dependabot.yml          # 每周检查依赖更新

.gitignore                  # 排除签名材料、构建产物、local.properties
.gitattributes              # 统一换行符；gradlew 必须 LF，否则 Linux runner 跑不起来
LICENSE                     # MIT
CHANGELOG.md                # 发布说明的唯一来源
tools/
├── ci_release.py           # 版本号算术 + 清单盖章 + 从 CHANGELOG 提取说明
├── check_repo_config.py    # 上面那个检查清单
├── make_icon.py            # 从设计原图派生各密度图标
├── check_mirrors.py        # 探测 GitHub 线路可用性
└── verify_read_path.py     # 端到端验证读取链路
```

`dist/update.json` **要**提交（应用从默认分支读它）；`dist/*.apk` **不**提交（作为 release 资产发布，11 MB 的二进制进 git 历史纯属负担）。
