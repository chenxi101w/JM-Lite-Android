# JMComicReader · Android（jmcomicreader-android）

> 基于 **Kotlin + Jetpack Compose(Material You) + Chaquopy(jmcomic) + Room** 的 Android 原生漫画阅读 / 下载 / 管理工具。
> 功能与 UI 设计**复刻自 Windows 版 `jmcomicreader-windows`**（pywebview + Flask 桌面端），
> 移动优先的响应式界面：底部导航（主页 / 搜索 / 阅读 / 设置）+ 下载悬浮按钮 + 卡片网格。
> **当前版本：v6.0.6**（versionCode 115）——首页已全面升级 Material Design 3（Material You 动态取色）。

---

## ✨ 功能一览（对齐 Windows v2.4.9）

| 功能 | 说明 | 对应 Windows 版 |
|------|------|----------------|
| 🔍 多维度搜索 | 关键词 / 标签 / 作者 / 联合 / JM号，支持排序(最新/浏览/收藏/标题)与时间(今天/本周/本月) | ✅ 搜索 |
| 📚 搜索偏好库 | 搜过的关键词/标签/作者聚合展示，点击一键回填搜索框 | ✅ 搜索偏好库 |
| 🏠 为你推荐（主页） | 基于搜索历史的兴趣标签 + 最近下载网格 | ✅ 主页推荐 |
| 📖 在线阅读 | 章节切换、图片经 jmcomic 解码后本地加载、阅读进度自动记录 | ✅ 在线阅读 |
| 💾 下载与书架 | 整本下载到本地、书架统一管理、分类树（无限层级）、长按指派分类 | ✅ 下载与书架 |
| ⬇ 下载管理 | 悬浮按钮进入，查看下载任务状态/进度 | ✅ 下载管理(FAB) |
| 🚫 黑名单 / 同义词 | 屏蔽作者/作品/标签，标签/作者同义词映射（注入搜索过滤） | ✅ 拉黑 / 同义词 |
| 💾 智能缓存 | 常看漫画自动整本缓存、冷门超量自动淘汰、一键清空自动缓存 | ✅ 缓存清理 |
| 🎨 设置 | 主题(亮/暗/跟随系统)、代理、并发下载数、画质、搜索上限、翻章提示、下载后处理等 | ✅ 设置 |
| 🎨 Material You 主题 | Android 12+ 动态取色 + ink/paper/neon 三套预设 + 完整亮暗（v6.0.0） | — |
| 🏷 标签库 / 实验标签库 | 兴趣库搜索累积；实验库 10 路并发爬取 1~100000 全量标签、续抓/防风控/清空重建（v5.9~v6.0.x） | — |
| 🔀 同义词智能推荐 | 共现计算推荐同义词；从标签库（含实验库）选词归组，搜索按规范词归一 | ✅ 同义词 |
| 🗂 数据可携带 | Room 数据库存于 App 私有目录，卸载即清，可随 App 迁移 | ✅ 数据可携带 |

---

## 🧱 技术栈

- **语言**：Kotlin 2.0.21
- **UI**：Jetpack Compose（BOM 2024.09.00）+ Material 3
- **架构**：MVVM（ViewModel + StateFlow）+ Repository 单一数据源
- **本地存储**：Room 2.7.0（books / chapters / downloads / read_progress / system_config / blocks / aliases / categories / comic_categories / search_history）
- **漫画源**：Chaquopy 15.0.1 内嵌 Python 3.11 + [jmcomic](https://github.com/hect0x7/JMComic-Crawler-Python) 2.7.3（纯 Python `requests` 客户端，绕开 curl-cffi/cffi 编译坑）
- **图片**：Coil 加载经 Python 解码落盘的本地文件
- **构建**：AGP 8.7.0 · Gradle 8.11.1（腾讯云镜像）· minSdk 24 · targetSdk 34
- **镜像**：Gradle 分发走腾讯云；PyPI 走清华源（见 `app/build.gradle.kts`）

---

## 📂 项目结构

```
jmcomicreader-android/
├─ app/
│  ├─ build.gradle.kts          # AGP/Kotlin/Compose/Chaquopy 配置（含 jmcomic pip 安装）
│  ├─ src/main/
│  │  ├─ AndroidManifest.xml
│  │  ├─ python/jm_bridge.py    # Chaquopy 桥接：search/detail/online_chapter/download_album
│  │  ├─ java/com/example/jmcomicreader/
│  │  │  ├─ JmComicApplication.kt     # 启动 Chaquopy + Room + Repository
│  │  │  ├─ MainActivity.kt           # 入口，应用持久化主题
│  │  │  ├─ data/
│  │  │  │  ├─ local/   (Entities / AppDatabase / DAO)
│  │  │  │  ├─ mapper/  (JSON ⇄ Book/Chapter)
│  │  │  │  ├─ python/  (PythonBridge.kt)
│  │  │  │  └─ repository/ (JmRepository.kt)
│  │  │  └─ ui/
│  │  │     ├─ MainScaffold / Navigation / JmViewModelFactory / JmUiState
│  │  │     ├─ components/ComicCard.kt   # 卡片网格（MD3 大圆角 + 已读蒙层）
│  │  │     ├─ home/ search/ library/ reader/ detail/ settings/ filter/ downloads/
│  │  │     └─ theme/Theme.kt            # MD3 动态取色 + ink/paper/neon 预设
│  └─ res/ (图标 / 字符串 / 主题)
├─ docs/ (架构报告 / 需求文档 / 功能函数清单 / 真机验证清单 / 已知坑 / Python桥接 / 升级日志)
├─ gradle/ (wrapper)
├─ build.gradle.kts / settings.gradle.kts / gradle.properties
└─ README.md
```

---

## 🚀 构建与运行

### 前置条件
- **Android Studio 2024.2**（或兼容 AGP 8.7 的版本）
- **JDK 17**
- 构建机需安装 **Python 3.11.x**（Chaquopy 用它在构建期跑 pip 并编译 `.pyc`）。
  本工程 `app/build.gradle.kts` 已写死：`buildPython = C:/Users/Administrator/AppData/Local/Programs/Python/Python311/python.exe`
  ——若你的 Python 路径不同，请改为本机 3.11 绝对路径。
- 首次构建需联网：Chaquopy 会从清华源 pip 安装 `jmcomic==2.7.3` 及其依赖（已用 `--no-deps` 跳过 curl-cffi/cffi，改用纯 Python 客户端 + curl_cffi 桩模块，规避 Android 无 cffi wheel 的编译坑）。
- 真机调试（推荐，省 AVD 资源）。

### 步骤
1. Android Studio → `Open` → 选择 `jmcomicreader-android/` 目录。
2. 等待 Gradle Sync（会自动下载 Gradle 8.9 发行包、AGP、依赖，以及 Chaquopy 打包 Python 运行时）。
3. 连接真机，点击 ▶ Run `app`（或 `Build → Make Project`）。
4. 首次启动会初始化 Chaquopy + Room，进入主页即可搜索漫画。

> 沙箱环境无法编译 Android；本工程已在结构与配置层面校验一致，请在 Android Studio 中构建，如有报错回传日志继续迭代。

---

## 🆚 与 Windows 版的主要差异（已知待迭代）

- **主页推荐**：Windows 版按搜索历史实时向 JM 源站拉取推荐漫画；本 Android 版当前以「搜索历史标签 + 最近下载网格」呈现（离线即可用），实时推荐可后续接入 `jm_bridge` 的推荐搜索。
- **搜索偏好库的收藏/回收站**：Windows 支持把常用搜索「收藏」并带回收站恢复；本版目前提供「最近搜索一键回填」，收藏/回收站为后续增强项。
- **缓存清理 / 数据重置**：Windows 有 TempCache 清理与 `UserData` 重置；本版已提供「清空自动缓存」（设置 → 缓存，只清自动缓存不动手动下载），下载文件的整体清理为后续增强项。
- 其余核心链路（搜索/在线阅读/下载/书架/分类/黑名单/同义词/设置/代理）已对齐。

---

## ⚠️ 免责声明

本项目仅用于**个人本地漫画阅读与管理**，不提供任何漫画内容的存储与分发。使用过程中请遵守所在地区法律法规及相关网站的使用条款。
