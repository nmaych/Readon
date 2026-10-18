# Changelog

All notable changes to Readon are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`versionCode` is derived from the version name as `major*10000 + minor*100 +
patch`, so `1.0.1` is `10001`. The tag, the APK metadata and `dist/update.json`
must all agree on that number -- the app compares `versionCode` and nothing else,
and a mismatch shows up as an update prompt that never goes away.

`tools/ci_release.py` derives and asserts this, so it is checked on every release
rather than trusted.

## [Unreleased]

## [1.0.10] - 2026-10-18

### Fixed

- **阅读界面上下方的章节、时间、电量盖住了正文。** 1.0.9 加的两行状态信息是**按 dp 预留**
  空间、**按 sp 绘制**文字的：`HUD_LINE_HEIGHT` 是 `18.dp`，而 `HUD_TEXT_LINE` 是 `18.sp`。
  dp 不随系统字号变化，sp 会——所以把系统字号调到 1.3 倍（国产 ROM 上很常见的"大"档）时，
  两行文字实际有 23 dp 高，却只占着 18 dp 的位置，**分页器按 18 dp 算出的正文区域就压在它们
  下面**。用户看到的就是章节名和时间电量盖住每一页的第一行和最后一行。1.0 倍时也没好到哪去：
  页脚把自己的下边距当成了留给正文的余量，又少算了 18 dp。

  现在只有一个数字：`ReaderHud.band(lineDp)` 由**字体换算后的行高**算出上下两条带子的完整
  高度，页面按它做内缩，状态行按它在**同一条带子里**绘制。行高是 `18.sp.toDp()`——它本身
  已经带着字体缩放——也就是文字真正会占的高度，两边从此不可能对不上。回传给 `lineHeight`
  的也是这个 dp 值经 `toSp()` 换回来的 sp，而不是把 dp 数字再当成 sp 用一次（那会二次放大，
  把刚修好的问题原样做回去）。

  这几条都在 `tools/check-book-logic.cmd` 里钉住：`band` 必须随字号变大（1.3x / 2x 各断言
  一次），预留的行高在任何缩放下都**不小于**文字的行高，顶部与底部的留白都**大于一行**（否则
  正文会贴着状态行），挖孔只加在顶部、间隙与边缘为负时按 0 处理、`toDp()` 量到 0 时不能把带子
  压成零。这类错误在截图评审里看不出来，在代码评审里也看不出来。

- **从其他应用切回 Readon 的阅读界面时，手机的系统 UI 又出现了。** 沉浸式是在进入书籍时
  `hide()` 一次实现的，而 Android 会在窗口**重新获得焦点**时自己把状态栏和导航栏放回来——
  从别的应用、从桌面、从安装器、从权限弹窗回来都是这样；旋转屏幕或改系统深色模式还会重建
  窗口。那个 `DisposableEffect` 的 key 是 `book.id`，以上任何一种都不是换书，于是它**再也不会
  执行**：回到阅读界面时系统栏就停在屏幕上，唯一的补救办法是退出这本书再重新点进去。

  现在除了进入时隐藏，还在 `ON_RESUME` 隐藏一次，并在窗口重新获得焦点时再隐藏一次——这是
  平台唯一能撤销沉浸式的两个时刻。退出阅读界面时仍然恢复系统栏，其它页面照常。

### Changed

- **书架上的书不再那么大。** 之前书架把 `Adapt.SHELF_TILE_MIN`（116 dp）当成**最小**列宽交给
  `Adapt.gridColumns`，而那个函数只能回答"至少 116 dp 时最多能放几列"——也就是永远给出最小的
  合法列数。于是一部 411 dp 的手机只排 3 列、每本 126 dp，360 dp 的手机排 2 列、每本 158 dp：
  书架看起来是三张巨幅海报，而不是一个可以扫视的书架。

  现在书架有自己的一套算术 `Adapt.shelfColumns`：从**目标宽度**（96 dp）出发四舍五入，再用
  78 dp 的地板兜住（更窄会把两行书名截掉），最后限制在 12 列以内。同一部 411 dp 手机变成 4 列、
  每本约 90 dp，320 dp 的窄屏也仍有 3 列。

  平板上的做法是**先按 `Adapt.contentWidthDp` 封住内容列**（与主页列表同一个上限），把剩下的
  宽度变成两侧等宽的边距，再在封顶后的宽度里排书。这样封面在**任何窗口**上都是 90–105 dp：
  一部 1200 dp 的平板不是把封面放大，而是多排几本。修改前的同一部平板是每本 284 dp；超宽窗口
  更是 624 dp。检查里对 320 / 360 / 393 / 411 / 480 / 600 / 840 / 1200 / 1600 / 2560 dp 逐个
  断言「封面大小与窗口无关」，并**同时断言旧算法会给出更少的列**，所以把书架改回 `gridColumns`
  会让检查失败而不是静默复发。

  格子本身也收紧了：封面不再画分类名（96 dp 上那是标题的位置），格式角标缩小，书名一行 12 sp、
  两行封顶，百分比与章节进度合成一行（`23% · 12/340 章`），上次阅读时间在下面一行。删除按钮
  从每个格子里移走——96 dp 的格子上，一个 24 dp 的删除按钮要占掉三分之一，而它恰好是最不该
  误触的那一个；长按封面仍然可以删除。

- **阅读界面本身也收紧了。** 上下滚动模式此前**永久**给工具栏预留 60 dp（顶部）和最多 92 dp
  （底部）的空白，即使工具栏是收起的——而阅读时工具栏绝大多数时候就是收起的，等于平时白白
  让出一截屏幕。现在这份额外留白跟着工具栏一起淡入淡出，收起时正文用满整屏。

  两行状态信息的中文标点也改顺了：页脚左侧从单独的百分比变成 `第 12 / 340 章 · 23%`，与
  工具栏里的章节计数一致，不再只有读者得自己换算的数字。状态行与工具栏现在共用同一个 10 dp
  边距常量（`ReaderHud.EDGE_DP`），不再一个 10 dp 一个 12 dp 差着 2 dp。

## [1.0.9] - 2026-10-18

### Fixed

- **主页在手机上什么都不显示。** 1.0.8 的自适应布局把窗口宽度算错了单位：
  `ProvideWindowSize` 直接把 `constraints.maxWidth`（**像素**）交给了 `WindowSize`，
  而它的每一个使用方都按 **dp** 解释这个值，`Adapt` 的断点更是 600dp / 840dp。

  于是一部 393dp 的手机在 2.75 倍密度下报出约 1080，被判成 `Expanded`，**每一部手机都走了
  平板分支**。而平板分支本身也是坏的（见下一条），所以主页在手机上只剩顶栏、搜索框和一片空白。

  修法是在这一个边界上做换算（`with(density) { constraints.maxWidth.toDp() }`），
  而不是在每个使用方各自除一次：`WindowSize` 的文档写的就是 dp，让它拿到的值符合自己的
  文档，比让五个调用点都记住"这里要除密度"可靠。`tools/check-book-logic.cmd` 新增了一组
  检查，用真实机型的三组像素/密度断言「换算后仍是窄屏」，并**同时断言「直接用像素会被误判
  成宽屏」**——后者是这次 bug 的真实性证明，如果有人以后把换算"简化"掉，检查会失败而不是
  静默复发。

- **主页的平板网格一个条目都不渲染。** `WideBookList` 在 `items(count) { ... }` 的**内容
  lambda 里**再调用 `item(...)` 去给分类标题加整行跨度。那个 lambda 是在条目被组合时才执行
  的，而 `LazyVerticalGrid` 早在那之前就已经建好了自己的区间表，所以嵌套的 `item` 从未进入
  布局——列表看起来是空的，且没有任何报错。

  现在改成在 `items(count, span = { ... })` 上按索引声明跨度：分类标题 `maxLineSpan`、
  书占一格。这与窄屏列表逐项发出的顺序完全一致，也是 `LazyGrid` 读取跨度的唯一位置。
  这个 bug 被上一条遮住了（手机根本走不到平板分支），两条一起修才能看到主页。

- **在主页对一本已缓存的书点「下载」，会重新把整本书拉一遍。** 在线阅读留下的完整副本就在
  `cacheDir` 里，而且已经按仓库大小校验过（这正是它能被算作缓存命中的条件），但下载路径
  完全没看它：一部 75 MB 的书会在这条"绕开 GitHub"的线路上被第二次完整传输。

  现在 `OnlineLibrary.download` 先问 `OnlineCache.take(book, target)`：有可用副本就**移动**
  到书架目标路径（同属应用私有存储，因此是一次 rename），登记进书架并删掉缓存条目；没有
  才走网络。**在移动的这一刻重新校验 `isUsable`**，而不是相信列表上几分钟前算出的
  `cachedIds`——缓存可能在 `cacheDir` 里被系统回收、被设置页清空，或留下一个大小对不上的
  残留文件。rename 失败时退回复制再删除；两条路都不通就把缓存留在原地并照常下载，宁可慢
  一次，也不能因为一个只读的角落把书弄丢。

  列表上的标记也改成了「已缓存 · 下载即入书架」，因为那个按钮现在做的事变了。

- **更新说明以前是展开的，把「下载更新」按钮挤出屏幕。** 1.0.7 起说明在卡片里内联渲染
  （截断到六个块），完整的放在一个「更新内容」对话框里。两半都不对：内联副本让一个像
  1.0.6 那样几千字的版本把卡片撑开、下载按钮落到屏幕外；对话框又把用户做决定前**该看**的
  文字藏到第二次点击后面。

  现在是一个**固定最大高度、自己滚动**的框（`heightIn(max = 220.dp)` + `verticalScroll`），
  整段说明都在里面，卡片保持卡片的样子，按钮留在屏幕上。底部「上下滑动查看完整更新内容」
  只在内容真的溢出时出现——一段话的版本上显示它就是在撒谎。

### Added

- **阅读界面自己画出被隐藏的状态栏。** 1.0.7 把状态栏和导航栏一起隐藏，让整个窗口就是页面，
  这是对的；代价是时钟、电量和"我在哪一章"一起消失了，要看时间只能从顶部边缘划一下——多数人
  永远发现不了这个手势。

  现在由阅读器自己画两行：

  - **上方一行显示当前章节**。章节名是读者真正需要的信息（工具栏也显示它，但阅读时工具栏是
    收起的），所以它用阅读主题自己的颜色和字体画在正文上方；正文的上边距就是从这一行的高度
    算出来的，第一行字不可能压在它下面。**工具栏弹出时这一行淡出**——工具栏在同一位置用更大的
    字号写着同一个章节名，两份叠在一起看起来像渲染错误。
  - **下方一行，左边是进度，右边是时间、电量**。左边是整本书的百分比，和进度条用的是同一个
    fraction，所以两者不可能互相矛盾；右边正是被隐藏的状态栏原本在说的东西。电量只在**低电
    且没在充电**时用错误色（`ReaderHud.isLowBattery`）——插着充电器的 5% 不是需要报警的事，
    给它上红色只会教用户忽略这个颜色。

  时钟按**对齐到下一分钟**的方式轮询，而不是每 60 秒从任意起点跳一次：从 :37 开始每 60 秒
  一次，会让真实的 :38 之后还显示一分钟的 :37。电量走 `ACTION_BATTERY_CHANGED` 的 sticky
  广播，注册即得到当前值，之后只在变化时更新。两者都在离开阅读器时注销。

  这几个字符串和阈值放在 `reader/ReaderHud.kt` 里（纯 JVM，无 Android/Compose 类型），
  所以 `tools/check-book-logic.cmd` 能断言它们：一位数的分钟（`9:5`）、`EXTRA_LEVEL` 缺失
  时的 `-1%`、被夹到 100% 的 99.6%，都是"截图里一眼能看出、代码评审里看不出来"的错误。

- **更新安装完成后清掉下载的安装包。** 更新包是约 11 MB，放在 `cacheDir/updates`。Android
  **可能**在存储紧张时回收 `cacheDir`，但"可能"不是承诺：在从不缺空间的手机上，这个文件会
  一直留着；而目录只会在**下一次**下载前被清空，所以一个更新过一次就再没更新过的用户会永久
  留着它。

  现在应用启动时（`ReadonApp.onCreate`，IO 线程）调用 `UpdateManager.clearDownloadedPackages()`。
  启动是这段代码唯一能确认安装成功的时刻：安装器替换的是应用自己的代码，旧版本在用户点下
  「安装」之后再也不会运行。文件被安装器占用时删不掉，留给下次启动，不当作错误上报。

### Changed

- `tools/check-book-logic.cmd` 新增编译 `reader/ReaderHud.kt`，并新增两组检查：状态行的
  时钟/电量/百分比格式化与低电量判定，以及窗口宽度的像素→dp 换算（含上面说的"直接用像素会
  被误判"的反向断言）。

## [1.0.8] - 2026-10-22

### Added

- **在线书库里标出「已缓存」的书。** 在线阅读缓存对用户是**看不见**的：存在应用私有缓存目录
  里，系统随时可以清掉。于是同一本书有两种完全不同的打开体验——有的点开几乎立刻出正文，
  有的要等下载——而列表上没有任何区别，用户无从判断，也不会知道「这本书为什么这次这么快」。

  现在列表里已经缓存的书会带一条「已缓存 · 可直接打开」。它和「已在书架」用**不一样的颜色**
  （次要文字色 vs. 主题强调色）和不一样的图标，因为这是两件性质不同的事：书架是用户主动
  保存、重启也在的；缓存只是读过一次留下的副本，系统可能回收，也不占用书架的存储统计。

  **没有用 `OnlineCache.get()` 去判断**，这是这次改动里最容易做错的地方。`get()` 是「要读这本
  书」的接口：它是 `suspend` 的、按单本做 I/O，并且**命中时会刷新文件的修改时间**（LRU 按最后
  阅读时间淘汰）。用它来给列表打标，会让**单纯滚动一遍书库就重排了淘汰顺序**——离线的书被
  留在缓存里，常看的书反倒先被清掉。所以新增 `OnlineCache.cachedIds(books)`：只列一次目录、
  按文件名匹配、比对大小（仍走 `isUsable` 的同一套判断，大小对不上的残留文件不算命中），
  **不写任何东西**，一次回答「哪些书现在就能离线打开」。

  判断依据是**缓存元数据（路径 + 仓库报告的大小）**，不是书名或「读过没有」的标记：上游换过
  文件时旧缓存会被 `get()` 判定为残留并删掉，如果标记来自别处，它就会在文件已经被删之后仍然
  亮着。刷新时机为：书库加载完成后、下载完成后（缓存副本此时移交给书架）、返回自阅读器后，
  以及手动刷新。

- **主页记住你上次浏览到哪里。** 之前离开主页（切到书架或设置）再回来，搜索框会清空、
  分类筛选会取消、列表回到顶部。这不只是麻烦：书库有几百本，在一堆同名分类里重新找到
  刚才那一本，代价比一次网络请求高得多。

  现在会记住**搜索词、分类筛选、滚动位置**，回到主页时原样恢复。

  三个地方值得说明，因为每一处做错都会变成「看起来能用，实际是坏的」：

  - **记忆不放在 composable 里。** `AppShell` 用 `when (tab)` 换页面，离开主页时整棵
    子树是真的被移除，`remember` 和 `rememberSaveable` 都留不下东西。所以记忆写进
    `Prefs`，同时也就活了进程被杀之后。
  - **记忆带着「它属于哪个书库」一起存。** 换了仓库却把上一个仓库的搜索词恢复回来，
    结果是满屏「没有匹配的书」——用户只会认为书库坏了。所以恢复前先比对
    `owner/repo@branch`，不一致就从头开始。同理，记住的分类如果已经被改名或删除，
    也会丢弃而不是恢复，否则筛选会把所有书都藏起来。
  - **恢复滚动位置必须等书真的在列表里。** `LazyListState` 在没有 item 的时候滚动
    会被夹到 0 且不会重试，所以在书库加载完成前恢复等于没恢复。恢复因此是一次性的、
    在列表就绪后触发。

  写入时机是三个：离开主页（`onDispose`）、滚动停下来后 400ms 防抖、以及搜索/筛选
  变化后。写入走的是**不触发 `revision`** 的静默通道——如果按普通设置项写，
  「保存滚动位置」本身会被主页当成「设置变了」，于是每次离开标签页都重新拉一遍书库，
  一个用来省时间的功能反倒让主页变慢。

- **按屏幕宽度自适应布局。** 之前所有列表都是单列、铺满整个窗口，在平板上书封和下载
  按钮相隔大半个屏幕，阅读器则是**一行七十多个汉字**——远超舒适范围（约 25–40 个）。

  现在按窗口宽度分三档（紧凑 / 中宽 / 宽，断点 600dp 和 840dp）：

  - 主页在中宽以上变成**居中的、有最大宽度的列**，够宽时排成**两三列网格**，
    分类标题横跨整行；
  - 书架封面从「有多少排多少」改成**有上限的列数**（`GridCells.Adaptive` 在 1200dp
    上会排出十一列，那不是更大的书架，是没法读的缩略图墙）；
  - 阅读器**限制正文列宽**（上限 720dp），左右边距由剩下的空间平分。

  阅读器这条是**先做错再改对**的，值得记一笔：最初的写法是「让边距随窗口变宽」。
  它在两端都不成立——360dp 手机上 16% 边距是 57dp，为了修平板反而把手机边距撑大三倍；
  而 2560dp 窗口上任何合理的边距上限都会留下 2000dp 以上的正文，约 117 个汉字。
  所以受约束的必须是**正文列宽**，边距应当由它推导，而不是反过来。手机上边距仍是原来
  的 22dp，一点没动。

  宽度取自**根部的实测约束**，不是 `LocalConfiguration.screenWidthDp`：分屏、自由窗口、
  折叠屏双应用拿到的窗口都比「屏幕」窄得多，按屏幕排版等于把 1200dp 的设计塞进 500dp。

- **主页加了一键清除筛选**（同时清掉搜索词和分类）。之前两者叠加时，只能一个字一个字
  删搜索框——而且很容易忘了分类筛选还开着。

### Fixed

- **在设置里清空在线阅读缓存后，主页的「已缓存」标记仍然亮着。** 清缓存发生在设置页，
  完全不会碰到书库状态；主页只在「书库加载完 / 下载完成 / 从阅读器返回」这三个时机重新
  判断，清缓存不属于任何一个。于是列表继续声称某本书「可直接打开」，而文件已经被删掉了。
  现在清空时通过应用级的 `cacheRevision` 通知，主页据此直接丢弃全部标记。
  用的是 `forgetCached()` 而不是重新扫描：重新扫描在没有加载过书库时会提前返回，
  而那恰好是「先清缓存、再打开主页」这个最常见的情形。

- **重启应用后分类筛选会丢。** 恢复的记忆需要校验「这个分类现在还存在吗」，而校验发生在
  书库加载完成之前时，分类列表还是空的——校验于是把刚恢复的筛选清掉了。现在校验只在
  书库有内容后进行。

- **旋转屏幕跨过断点时会保存错列表的位置。** 主页在窄屏用列表、宽屏用网格，两个滚动
  状态各自独立。离开主页时保存的代码捕获的是首帧的「现在是不是宽屏」，旋转之后就会
  去读那个已经不在屏幕上的列表，保存下 0。改为读当前值。

## [1.0.7] - 2026-10-15

### Fixed

- **阅读界面没有关掉状态栏，上方留白也不是「留白」。** 1.0.6 的留白是按**可见状态栏**的
  高度算的：状态栏还在，正文被它压着，所以留白只是「让开一个一直在那儿的栏」。要的是
  反过来——**阅读时把状态栏关掉**，然后正文上方留出真正的空白。

  现在打开一本书会同时隐藏**状态栏和导航栏**（`systemBars()`），整块屏幕都是页面；退出
  时一并恢复。留白改为**显示挖孔 + 一行正文行高**（`字号 × 行距`），两者相加而不是取较大
  值：留白是要看得见的，取较大值会让第一行紧贴挖孔下沿。

  这里有一个**很容易踩空的地方**，也是这次改动里最关键的一处：Compose 的
  `WindowInsets.statusBars` 报告的是**可见**inset，状态栏一关它就变成 0。所以如果留白
  继续按状态栏算，它会**在所有设备上悄悄塌成最小值**——看起来「代码没动」，实际留白没了。
  因此留白只从**显示挖孔**取硬件遮挡（挖孔是硬件，不随状态栏隐藏而消失），再加上一行正文。

  阅读器自己的工具栏和面板同理：它们原本用 `statusBarsPadding()` /
  `navigationBarsPadding()`，两个 inset 现在都是 0，会贴着屏幕边缘、并且压在挖孔下面。改
  为 `displayCutoutPadding()` + 一个固定最小边距（`readerTopChrome` /
  `readerBottomChrome`）——否则底部按钮会落进系统手势区，和上滑手势抢触摸。

- **正文第一行仍会压在手机的状态栏下面。** 挖孔（display cutout）是硬件遮挡，即使状态栏
  隐藏也依然存在，而且**可以比状态栏更高**（横屏时尤其明显）。留白里含挖孔高度，所以刘海
  / 挖孔屏上第一行不会落到摄像头下面。分页高度仍然同步扣掉这段留白（滚动模式也一样），
  否则排版会认为最后一行放得下、实际却被盖住。

- **自动阅读的滚动模式是「停顿一整段，然后猛地跳一屏」，不是缓慢滑动。**
  旧实现在等待结束后用 700 毫秒滚过 0.92 屏：15 秒的间隔里有 14.3 秒画面**完全静止**，
  然后不到一秒冲过一整屏。那不是自动滚动，那是**带过渡动画的自动翻页**——设置里写着
  「15 秒」，而实际移动只用了 0.7 秒，用户看到的是一次次的突跳。

  现在**滚动本身就是整个间隔**：动画时长等于设定的间隔，用线性缓动（`LinearEasing`），
  文字以恒定速度连续爬行，没有任何一帧是静止的，也没有任何一次跳跃。平均速度没有变
  （仍然是「每个间隔一屏」，这才是间隔的含义），变的是一直在动。线性缓动在这里是**关键**
  而不是修饰：15 秒的动画如果套上 ease-in-out，会以几乎察觉不到的速度起步、再以肉眼可见
  的冲刺收尾——那正是这次要消除的突跳。

### Added

- **更新说明用 Markdown 渲染。** 更新卡片此前把说明的第一行截到 90 个字符，于是用户在
  决定要不要装的时候看到的是 `### Added`，或者一条断在 `**` 中间的列表项——说明是应用
  里**唯一**解释「这次改了什么」的地方，而它恰好是 Markdown（它就是 `CHANGELOG.md` 里
  那一节，由 `tools/ci_release.py` 提取、GitHub release 页面用的也是同一段文字）。

  现在说明会被真正解析并排版：ATX 标题、段落、嵌套列表（缩进会变成层级，子项不再和它
  所解释的那一条并排）、有序列表、引用、围栏代码、分隔线，以及行内的粗体 / 斜体 /
  删除线 / 行内代码 / 链接（可点击）。卡片上只显示前几块，完整的说明放在「更新内容」
  按钮打开的对话框里——1.0.6 那一节有几千字，一张卡片长到那种程度会把下载按钮和整个
  设置页挤下去。

  解析器（`data/Markdown.kt`）**刻意不含 Android 与 Compose 类型**，理由和 `AutoRead`、
  `UpdateDecision` 一样：会「悄悄丢掉一个列表项」的错误在手机上只是让说明看起来短了一
  点，肉眼看不出来，而对着断言就很明显。它被 `tools/check-book-logic.cmd` 编译并用
  **真实的 `dist/update.json` 说明**跑过（60 项检查），其中一项专门断言「源文件里的
  列表项数量 = 解析出来的列表项数量」，另一项断言折行确实被并入了列表项。

  几个刻意的选择：

  - **不认识的构造按原样显示，而不是丢掉。** 未闭合的 `**`、空的 `****`、`#hashtag`
    这样的非标题，都会作为文本出现——把标记吞掉等于删掉用户的内容，显示出来才是诚实的
    失败。
  - **列表项拥有它下面缩进的续行。** 日志是按 80 列折行的，折出来的续行缩进两个空格
    （仅 1.0.6 那一节就有 51 行）。把它们当成普通段落，会让每一条都被**截断在第一行**，
    剩下的半句漂在下面变成没有缩进的独立块——文字一个都没少，全都在屏幕上，所以这正是
    那种「扫一眼看不出来」的错误。检查里因此不是数文字，而是断言**解析出的最长列表项
    必须超过最长源行的两倍**：折行错误下每一项恰好只有一行长，这条断言过不去（实测
    67 字 vs 69 字）。
  - **行内代码优先于其它所有标记**，所以 `` `**` `` 是两个字符而不是粗体的开始；反过来
    `**`Content-Range` 的解析**`（日志里真实存在的写法）里的代码段嵌在粗体里，两者都
    生效。
  - **软换行合并成一个段落**，因为日志是按 80 列折行的；一行一个块会让每个句子中间都
    断开。
  - **摘要行是散文，不是标记的碎片**，并且只在真的被截断时才加省略号。

- **阅读时隐藏状态栏与导航栏。** 阅读器是 edge-to-edge 的，正文一直画到屏幕边缘，但系统
  的两条栏还压在上面——既是干扰，也是正文用不到的屏幕。现在打开一本书就会把**状态栏和
  导航栏一起**收起来，**退出时恢复**（和状态栏/导航栏图标的明暗是同一个契约：离开阅读
  界面不能把导航一起带走）。

  行为是 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 而不是彻底隐藏：需要看时间、电量或导航
  键的时候从边缘划一下就能调出来，它们自己会再滑走。彻底隐藏且没有出路会让不熟悉边缘手势
  的用户连返回手势都用不了——那比「要的时候能叫出来」糟糕得多。

### Changed

- `AutoRead` 新增 `SCROLL_FRACTION`、`scrollDurationMillis` 与
  `scrollSpeedDpPerSecond`，把滚动模式的步长、时长和速度变成可以被桌面检查断言的值。
  检查里直接断言「默认速度 < 100 dp/s」，并断言旧行为（800 dp 视口 0.7 秒滚 0.92 屏）
  会超过 1000 dp/s——否则这个检查会随着时间失去意义。
- `tools/check-book-logic.cmd` 新增编译 `Markdown.kt`，并从 Gradle 缓存里取 Gson
  用于**按应用自己的方式**读回 `dist/update.json`（在检查里手写一遍 JSON 反转义等于
  再实现一次 JSON，而错的会是检查本身）。仓库根通过 `-Dreadon.repoRoot` 显式传入，
  而不是用相对路径——调用者站在哪个目录不该影响检查读到哪个文件。

## [1.0.6] - 2026-10-20

### Added

- **自动阅读。** 阅读界面下方新增一个开关，打开后按设定的间隔自动翻页；上下滚动模式
  则自动滚过一屏（0.92 屏，让下一屏的第一行是刚看过的那行，视觉上不跳）。设置里也能
  打开它并调整间隔（3–60 秒，默认 15 秒）。几个刻意的选择：

  - **间隔是被夹紧的，不是被直接使用的。** 存进去的 0 秒（或负数）会让翻页快到像是
    书在自己往后翻，一小时一页又和功能坏了没有区别，所以读出来的值一律夹到 3–60 秒。
    这样即使以后版本写了别的值、或者被手工改过，也只会得到一个正常的速度，而不是失控。
  - **读到最后一页会停下，不会绕回开头。** 悄悄从第一页重新开始、还把阅读进度一起
    带过去，等于趁用户没看着的时候弄丢他的位置。
  - **点一下正文就暂停。** 手指落在屏幕上，说明人正拿着手机；从他手指底下继续翻页
    不是他要的。
  - 开关是**跨书记住**的（写在设置里），但一本书**第一次**打开时总是暂停的——刚打开就
    自己翻页会吓人一跳，而且用户可能只是打开来确认点什么。

- **阅读界面顶部留出一行空白。** 阅读器是 edge-to-edge 的，顶部工具栏大部分时候又是
  隐藏的（这正是阅读界面该有的样子），于是正文第一行就压在状态栏的时间和电量下面。
  现在正文上方固定留出「状态栏高度 + 一行行高」，并且**不随工具栏显隐变化**——如果跟着
  工具栏走，每次呼出工具栏正文就会跳一行。分页高度同步扣掉了这段留白，否则排版会认为
  最后一行放得下，实际却被状态栏盖住。

- **在线阅读改为先下载一部分。** 这是这次改动里最大的一处。此前的流程是「整本下载完
  再显示」，而这个书库最大的书有 75 MB，于是在网络本来就不好、需要多线路竞速的环境里，
  用户要盯着「正在下载… 3%」好几分钟才看到第一页。现在：

  - 第一次只取 **2 MB 前缀**（约七十万字，按手机一屏算超过一千页），立刻排版显示；
    剩下的在**应用级作用域**后台继续拉，读完前缀之前就已经补齐。前缀大小是权衡出来的：
    再小就有可能在边界处看到停顿，再大就把这个功能要消除的等待又放回去了。
  - 全部到齐后**原地替换**成完整正文——阅读位置是字符偏移，新正文是旧正文的严格延长，
    所以同一个偏移仍然指向同一句话，对用户是无感的；不会跳回开头。滚动模式的分段也
    用同一个信号重建。
  - 下载是**按区间续传**的：中断后再打开，从已经落盘的部分继续，而不是从头再来。落盘
    仍然用 `.part` 临时文件，中断的下载不会被当成完整的一本书。
  - 三种响应被分别处理：**206** 是正常情况，`Content-Range` 顺带给出文件的真实大小
    （这是仓库列表没报大小时唯一的来源）；**200** 说明这条线路忽略了 `Range`、正在从头
    发整本，此时只收下前缀就断开，并改用一次整本下载收尾，而不是反复重写同一个前缀；
    **416** 是「已经到文件末尾」的诚实回答，不是错误。
  - 前缀末尾如果正好切断一个多字节字符，流式解码会留下一个 U+FFFD，它会显示在用户
    手里那页的最后。只在这种情况下、只去掉**末尾**的那一个：正文中间的替换字符要么是
    源文件真的坏了，要么是 `TextCodec` 有损兜底的明确结果，藏起来就等于藏起「这个文件
    有问题」的信号。
  - EPUB 是例外，仍然整块取回：zip 需要随机访问字节，一个前缀不是一本能读的书。它的
    64 MB 上限没有变。
  - 实测（`tools/check_ranges.py`，对着线上书库跑）：这个书库里唯一能扛住 75 MB 文件的
    两条线路 `gh-proxy.com` 与 `ghproxy.net` **都支持 Range 并返回 206**，且都报出真实
    大小 78,566,542 字节。jsDelivr 系对大文件返回 403，直连在本机不通——这也正是
    「整本下载」时代慢的原因。

### Fixed

- **书架里已经下载好的书，在主页点开不再重新下载一遍。** 主页列表里的条目来自仓库
  列表，它**没有** `localFile` 字段；把它直接交给阅读器，阅读器就会认为这本书在设备上
  不存在，于是走网络——而且因为在线阅读现在会缓存，还会在缓存目录里再写一份完整副本。
  现在打开前会先查书架：已下载的书交给阅读器的是**书架自己的记录**（指向设备上的文件），
  于是既不发请求也不写缓存。顺带地，书架下载完成时会**删掉在线阅读缓存里的同一本书**，
  否则「已下载」就等于「存了两份」，而 75 MB 的书存两份没有任何理由。

### Changed

- `tools/check-book-logic.cmd` 新增两组桌面检查（不需要设备）：**自动阅读的夹紧规则**
  （0 秒 / 负数 / 超大值都必须落到 3–60 秒，任何输入都要产生正的等待时间，标签和实际
  等待必须来自同一个夹紧）与**部分下载的判定**（`Content-Range` 的解析，含 `*` 这种
  「有区间但没报大小」的写法；「大小未知时不敢断言完整」；前缀长度的选择；以及只去掉
  末尾那一个替换字符）。`tools/check_ranges.py` 是新增的：它对着线上书库逐条线路验证
  `Range` 是否真的被遵守——这个功能的前提是能被验证的，而不是假设的。

## [1.0.5] - 2026-10-14

### Fixed

- **「检查更新」显示已是最新版本，但其实不是。** 更新检查原本只信 `dist/update.json`：
  清单一旦说「已是最新」，检查就结束，只有清单**完全读不到**时才会去问 GitHub releases
  API。而清单**过期**恰恰是常见故障而不是罕见故障——它放在默认分支上、由发布 workflow
  写入，所以手工发的 release、或者清单那一步没提交成功，清单就停在上一版。应用于是拿着一份
  **完全健康**的旧清单宣布「已是最新版本」，而 releases 页面上躺着新版本。这两个状态在旧
  代码里无法区分，这正是 bug 的根源。

  现在两个来源**每次都会同时问**（并发发出，取较新的那个答案）。多一个几 KB 的请求，
  换掉一整类谎报。同时：

  - 清单和 releases 的请求都带上 `Cache-Control: no-cache` 与 `Pragma`，并在 URL 上追加
    一个时间戳参数——`raw.githubusercontent.com` 前面的代理会缓存这个文件，而**缓存住的
    清单**是同一类谎报的另一条路径。有些 CDN 只认其中一种写法，所以两者都做。
  - 「发现了新版本但没有 APK 下载地址」不再被吞掉：这种情况以前会走到 `Failed`，然后被
    另一个来源的「已是最新」覆盖掉。现在它会**优先显示**，卡片标题是「发现新版本，但无法在
    这里安装」（不是「检查失败」——检查成功了，而且找到了东西），并给一个「到 GitHub 下载」
    的按钮，而不是让用户重试一遍拿到同样的结果。无法安装不等于已是最新。
  - 「已是最新」时显示的是**两个来源里较新**的那个版本号，而不是碰巧先返回的那个。

### Added

- **返回键处理。** 阅读界面此前完全不接管返回键，于是：打开目录 / 书签 / 搜索 / 阅读设置
  后按返回，会**直接退出应用**并丢掉阅读位置；什么都没打开时按返回，同样直接退出。现在返回键
  按**层**关闭：先关书签列表 → 搜索框 → 目录 → 阅读设置面板（顺序即面板的绘制层级，最后画的
  在最上面，所以返回关掉的就是用户真正看得见的那一层），都关掉之后才离开这本书
  （和工具栏左上角的箭头是同一个动作）。在主页 / 书架 / 设置三个标签页之间，返回键先回到
  「主页」，再按才退出应用——从设置页一下被弹出应用、还要重新找回自己刚才在看哪本书，
  是没有道理的。

- **设置界面可以直接跳转的 GitHub 链接。** 新增「链接」一栏：项目主页、全部版本、问题反馈、
  默认书库。整行都是点击区域（不是只有小图标），地址用链接色显示，点击交给系统浏览器。
  地址是**写死**的，不从「书库来源」或「更新仓库」推导——那两项是用户可以改的设置，把
  「问题反馈」指向用户碰巧在看的那个书库仓库，等于让反馈发到没有人的地方。设备上没有浏览器时
  不会静默失败，而是把地址作为文本提示出来。

- **在线阅读缓存。** 在线阅读过的书现在会在设备上留一份，再次打开**不再重新下载**。
  这与书架数据**完全分开**：书架是用户主动保留的书（带阅读进度、书签），缓存只是为了让第二次
  阅读变快，随时可以清掉。因此它：

  - 存在 `cacheDir` 而不是 `filesDir`，有自己的目录、自己的占用统计、自己的清理按钮
    （设置 → 存储 → 「清理在线阅读缓存」）。清空书架不会碰它，清空它也不会碰书架。
  - 上限 512 MB，按**最后阅读时间**（LRU）淘汰，最近读的那本留到最后。单个超过上限的条目
    也会被删掉——否则它每次都被淘汰又重新写入，顺带把别的条目全挤出去。
  - 只缓存**原始文件字节**，不缓存解码后的正文和章节切分：后者大好几倍，而且从字节重算很便宜
    （贵的是网络），缓存它们还会让缓存必须在解析逻辑变化时失效。
  - 命中要求**大小与仓库报告的一致**，所以上游换了文件不会被旧的缓存顶掉；空文件和截断文件
    永远不算命中，不会给用户显示一本空书。
  - 写盘用 `.part` 临时文件再改名，中断的下载不会被误当成完整的一本书。EPUB 走同一套缓存
    （它必须整块取回，走 `fetchBytes`）。

### Changed

- **阅读界面的书签和目录按钮移到底部，章节名现在看得见了。** 顶栏原本挤了四个图标
  （书签、搜索、目录、阅读设置），在手机上留给标题的宽度大约只剩一半，章节名会被截成
  「第一章…」——而每章前两个字都一样，这种截断等于没有信息。现在书签和目录移到**下方**
  工具栏，紧挨着「第 12 / 340 章」这个指示器和进度条：那里本来就在说「你在哪」，按钮放在
  那里才看得出它作用于当前位置。顶栏只剩返回、搜索、阅读设置三个动作，标题因此有空间放大到
  16sp 并允许折到第二行再截断。
- 底部工具栏分两行：上一行是位置（章数 + **章节名** + 百分比），下一行是操作
  （上一章 / 目录 / 加书签 / 书签列表 / 下一章）。章节名在底部也显示一遍，因为顶栏在阅读时
  通常是被隐藏的。
- `BookLoader` 接受一个可选的 `OnlineCache`；不传时行为和以前完全一样（每次都重新下载）。

### Tooling

- `tools/check-book-logic.cmd` 新增编译 `OnlineCachePolicy.kt`（不含 Android 与 Compose
  类型）与 `UpdateDecision.kt`，并新增两组检查：
  - **在线阅读缓存**（15 项）：大小不符 / 截断 / 空文件不算命中、仓库未报告大小时接受非空
    文件、未超上限不淘汰、按最后使用时间而非目录顺序淘汰、正好等于上限不淘汰、单个超限条目
    也被淘汰、上限为 0 视为不限。
  - **更新检查的两个来源如何合并**（20 项）：把这次修的 bug 钉住——「过期清单说已是最新 +
    releases 有新版本」必须提示更新，**两个来源的顺序反过来结果相同**；「发现了新版本但无法
    安装」必须优先于「已是最新」；两个来源都有更新时取较新的；只有一边能连上时用另一边的
    答案；以及 `versionCode` 算术（`1.10.0` > `1.9.0`，而字符串序相反）。
- 合并规则从 `UpdateManager` 里拆到 `UpdateDecision`（不含 Android 类型），理由和 `PageCursor`
  从 `Paginator` 里拆出来一样：这是能**独立于 I/O 出错**的规则——「两份互相矛盾的答案该信
  哪一个」正是这次 bug 的全部内容，而它在设备上只会表现为一句错误的「已是最新版本」。
- 缓存策略（`OnlineCachePolicy`）与文件操作（`OnlineCache`）同样分开。
- 写测试时发现并修掉一个潜在陷阱：`versionCodeOf("v1.0.5")` 原本会静默返回 `500` 而不是
  `10005`（开头的 `v` 让第一个分量解析失败，剩下的数字被当成 minor/patch）。当前唯一的调用方
  已经去掉了 `v`，所以这个 bug 还没生效——但算错两个数量级的版本号会以最糟的方式失败：它看起来
  像个合理的答案。现在 `v` 前缀被容忍，并且有测试盯着。

## [1.0.4] - 2026-10-14

### Fixed

- **阅读界面出现空白页。** 章末的连续空行会被分页成一页一页的空白页，用户必须一页页点过去才能
  进入下一章。根因是分页器只问「这一段放得下吗」，而空行**放得下**——`"\n\n"` 就是两行空行，
  很短。`TextPaginator.pageEnd` 找到能放下的最大范围后，会调用 `adjustBreak` 向前跳过连续换行，
  以免把空行留在下一页顶部；在章末这一跳会吃掉整段空行并返回 `start + 1`，于是**每个换行符各自
  成为一页**，每页都是空屏。现在每一页都从第一个非空白字符开始（`TextFlow.skipBlank`），
  章末的空行不属于任何一页，正文之后直接是下一章。

  这个 bug 用真实测量模型（行高按可视行数算、换行符算一行）在 `tools/check-book-logic.cmd`
  里被钉住：同一段章末带 30 个空行的章节，旧逻辑产生空白页、新逻辑一页空白都没有，而且测试会
  断言「旧逻辑确实会产生空白页」，否则说明这个测试已经不再覆盖它。

- **进入曾经读过的章节会跳到旧位置。** 阅读位置原本每次布局章节时都从 `book.charOffset` 读取，
  而那是**打开这本书时**的位置、阅读过程中永不改变。因此从目录跳到任何一本以前读过的章节，都会
  落在那本书上次读到的地方，而不是该章开头。现在待恢复的位置是 `ReaderState.restoreOffset`，
  只在首次布局时消费一次，任何显式导航（目录、搜索、书签、上一/下一章）都会清掉它。

- **上下滚动模式下打开书会丢失阅读位置。** 进度记录器与位置恢复同时启动，而
  `snapshotFlow` 会把**当前**索引作为第一个值发出——恢复尚未滚动时那就是书首。若直接持久化，
  等于每次打开书都把进度重置为 0。现在进度记录器以「恢复已完成」为键，恢复之后才启动，因此它
  的第一次上报是恢复后的位置；这同时让**新书**（没有可恢复的位置）无需用户滚动就记录进度。

- **深色阅读页面下状态栏图标看不见。** 窗口是 edge-to-edge 的，阅读器自己画背景，但状态栏与导航栏
  图标的明暗来自**应用**主题（由 `enableEdgeToEdge` 设定）。阅读主题是另一条独立的轴，所以在浅色
  应用主题下用「夜间」阅读时，深色页面配上深色图标；1.0.4 新增的自定义页面颜色让这个组合从两个
  方向都容易碰到。现在阅读器按页面明暗设置 `isAppearanceLightStatusBars` /
  `isAppearanceLightNavigationBars`，并在退出时恢复原值，而不是根据主题模式去猜。

### Added

- **可选择字体。** 阅读设置里可选宋体 / 黑体 / 等宽 / 楷体（`ReaderFont`）。用的是系统自带字体族
  而不是下载字体：要联网取到字体才能显示一页的阅读器，比一个只提供已有四种字体的阅读器更糟。
  旧的「衬线字体」开关会被读取以决定默认值，升级用户不会被动换字体。

- **可选择文字颜色与页面颜色。** 五套主题之外，文字和页面颜色可以各自独立选择（各 10–12 个
  色板）。两者都能覆盖主题，「跟随主题」恢复。次级文字（页脚、章节副标题、搜索摘要）会按用户选的
  这对颜色**重新推导**，而不是沿用主题里那个只对主题自身页面调过的灰色：在
  `ReaderInk.readableSecondary` 里从「向页面色混 45%」往回走，直到满足对比度下限，所以弱化效果
  保留、可读性有保证。120 组配色组合在 `tools/check-book-logic.cmd` 里被逐一断言对比度达标。

  如果文字与页面颜色本身太接近（黑底黑字），正文是用户的明确选择、不去改动，但会给出提示而不是
  画出一页看不见的字。

- **上下滑动的阅读方式。** 除左右翻页外，新增连续滚动的阅读模式（`ReadingMode`），可在阅读界面
  的快速设置里或「设置 → 阅读」里切换。正文按空行切成段落块（`TextFlow.blocks`）交给
  `LazyColumn`，因此百万字长篇只测量和绘制可见的块。块的地址同样是字符偏移，所以阅读进度、进度
  条、目录跳转和书签跳转在两种模式下完全一致，切换模式也不会丢失位置。

  切块是对全书的一遍扫描（桌面 JVM 上 800 万字符约 160ms，低端机上对 64 MB 上限的书会明显更
  慢），所以它跑在 `Dispatchers.Default` 上而不是组合期间同步执行——否则打开一本书的那一帧会整
  个卡住。切块期间显示一个进度环，工具栏与页脚仍在，读者不会看到一片空白。这个耗时被
  `tools/check-book-logic.cmd` 用 800 万字符的样本钉住（要求 1 秒内完成）。

- **书签。** 阅读界面底部可一键在当前位置加书签，顶栏的书签列表可查看、跳转、重命名、删除。
  书签按**字符偏移**保存而不是页码：改字号会整本重排，页码会指到无关的地方，偏移仍停在同一句话。
  未命名的书签显示该位置的正文摘要，所以列表不会是一排一模一样的「未命名书签」。同一位置再次添加
  是重命名而不是产生重复项。书签存在独立的 `bookmarks.json`（与 `library.json` 分开：书架每次出现
  和每次翻页都要读整份书籍索引，把书签折进去会让这次读取无上限地变大），写入沿用临时文件改名，
  因此崩溃最多丢掉最新一个书签而不会毁掉整个文件。删除书籍或清空本地书籍时一并清除。

- **阅读界面内的快速设置面板。** 字号、行距、字体、主题、文字/页面颜色、阅读方式都可以不离开正文
  直接调整。这些正是读者**在阅读时**才会想改的东西（这页太亮、这个字体不好扫、我更想滚动），把
  它们放到另一个标签页去改，代价是丢失阅读位置。

### Changed

- `PageCursor` 增加了 `pageStart` 参数用于规范化每页的起点，默认是恒等函数，因此分页算术仍可
  单独测试；`Paginator` 传入跳空白字符的实现。`textOf` 另外会裁掉页尾空白，不再把一串空行交给
  文本排版。
- 阅读界面的配色统一由 `ReaderSkin` 解析（主题 + 用户覆盖），因此自定义页面颜色会作用到目录、
  搜索框和工具栏，而不只是正文——否则自定义颜色看起来会像 bug。
- `ReaderTheme` 每个主题各自带一个 `accent`：品牌青绿在纯黑页面上清晰，在护眼绿页面上却是一团
  模糊，所以每个主题写明对自己页面有效的那个颜色。
- 顺带清掉了全部已废弃 API 的构建警告：`Icons.Filled.ArrowBack` / `List` / `MenuBook` / `Sort`
  换成 `Icons.AutoMirrored.*` 版本，`Divider` 换成 `HorizontalDivider`。构建警告从 16 条降到 0。

### Tooling

- `tools/check-book-logic.cmd` 现在同时编译 `TextFlow.kt`、`ReaderInk.kt` 与 `Bookmarks.kt`
  （三者都不含 Android 与 Compose 类型），新增空白页（20 项）、滚动分段（20 项）、阅读配色
  （35 项）与书签（20 项）四组检查共 95 项。

- 配色检查会**回读** `Color.kt` 里的色板列表，断言它与检查里用的列表逐项一致。否则往选择器里
  加一个颜色而不检查它的可读性是可能的——这个「元检查」注入一个颜色后确实会失败。

## [1.0.3] - 2026-10-13

### Fixed

- **打开任何一本书都闪退（显示「正在排版…」后应用关闭）。** `Paginator` produced
  its first page by asking an **empty** list for its last entry -- `pages.last()`
  on a list nothing had been added to yet -- so the very first page of every book
  threw `NoSuchElementException`. The call sits inside `remember { ... }` in the
  reader's composition, so the exception was not caught by the loader's
  `try`/`catch` (which wraps the *loading*, not the layout) and it took the
  process down: the user saw "正在排版…" and then the app closed, for every book
  in the library regardless of size or format. The page list now derives its next
  start offset from `lastOrNull()?.end ?: 0`, so the first page begins at 0
  instead of dereferencing an empty list, and every page is coerced to advance by
  at least one character so a pathological measurer cannot spin.

  The bookkeeping is extracted into `PageCursor`, a class with no Android or
  Compose types, so `tools/check-book-logic.cmd` compiles that exact file and
  pins the regression on the desktop -- a crash on the first page of every book
  is not something to catch by opening a book on a phone.

### Added

- **书架显示阅读章节数与上次阅读时间。** Each shelf card now shows "12/340 章" and a
  relative "上次阅读：今天 14:30" (昨天 / N 天前, then a date past a week). The total
  is recorded by the reader when it builds the outline -- the shelf cannot count
  chapters itself without decoding and scanning the whole book, which is the
  expensive work the reader has already done. Books that have never been opened,
  and records written before this field existed, show nothing rather than a
  fabricated "0/0" or a date of 1970.

  The date arithmetic is in `ShelfLabels`, also Android-free and checked on the
  desktop: "昨天" is a *calendar* day, not 24 hours, so 23:50 and 00:10 twenty
  minutes later are correctly one day apart; a DST transition does not turn a day
  into zero; and a future timestamp (a restored backup, a clock change) reads as
  "今天" instead of a negative day count.

### Changed

- `Paginator` now delegates page bookkeeping to `PageCursor`; the public surface
  the reader uses (`pageCount`, `page`, `pageOf`, `textOf`, `completeAll`) is
  unchanged.

## [1.0.2] - 2026-10-12

### Fixed

- **在线阅读一直显示「正在排版」。** Loading a book buffered the whole file into a
  `ByteArray` and then decoded it, so a 75 MB novel briefly needed two full copies
  plus the decoded `String` -- and every mirror was given a 45-second timeout to
  answer. A blocked route therefore stalled the reader for minutes, and an
  `OutOfMemoryError` during the decode was never caught at all (the reader caught
  `Exception`, and `OutOfMemoryError` is an `Error`), which left the screen on
  "正在排版…" forever. Text books now stream to a cache file and are decoded from
  disk; requests are cancellable and carry whole-call timeouts; the loader reports
  each stage ("正在连接线路…", "正在下载… 42%", "正在排版…"); failures are caught as
  `Throwable`; and the loading screen offers a way back.

- **导入本地 txt 后闪退。** The import read the picked document into memory and
  handed the array to the store, which wrote it to disk -- two full copies of the
  file. The bytes now stream straight from the content provider to a scratch file
  and are renamed into place, so a 64 MB book costs a 64 KB buffer. A failed copy
  also leaves the shelf untouched instead of recording a truncated book.

- **导入书库时间过长。** Reading a book scanned every line by allocating a `String`
  for each one -- half a million allocations for a long novel -- on the path that
  shows "正在排版…". Only lines short enough to be a heading are materialised now.
  Library listing is also no longer repeated needlessly (see below).

- **每次切换到主页都重新获取书库。** The listing was loaded by a `LaunchedEffect`
  inside the home screen, which Compose disposes on every tab switch, so returning
  to 主页 re-ran the whole multi-host listing race. The listing, its error state and
  its downloads now live in an application-scoped holder and are reused until the
  repository, branch, token or pinned route actually changes.

- **无法从书库下载。** Downloads ran on the home screen's coroutine scope, which
  is cancelled the moment the user navigates away -- so starting a download and
  switching to 书架 to watch it arrive killed the transfer. Downloads now run at
  application scope and survive navigation; a truncated transfer is detected and
  reported instead of producing a book that ends mid-sentence; a failed transfer
  cleans up its `.part` file; and the row shows the stage and percentage, or an
  indeterminate ring when the route sends no `Content-Length` (which the proxies
  often do not).

- **检查更新一直显示「连接 GitHub 中」。** The check ran in a `LaunchedEffect`
  scoped to the settings screen, so leaving the tab cancelled it -- and because the
  `lastUpdateCheck` timestamp was only written after it returned, every visit
  restarted it from scratch. It now runs at application scope, only one check can
  be in flight, a failure no longer records the timestamp (so a transient network
  error does not silence retries for six hours), and every route attempt carries a
  15-second ceiling so "no answer" resolves into a message instead of spinning.

- Route probes are cancelled when a winner is found. They were blocking `execute()`
  calls that kept their sockets until their own timeouts expired, so opening a book
  could leave a dozen dead connections draining for a minute.

- The remembered route is probed alone first, so a working network costs one 2 KB
  request per book instead of racing all nine hosts every time.

- 「检测线路」probes its routes concurrently rather than one after another, which on
  a blocked network took nine timeouts and read as a hang.

- The settings screen's 刷新 button only showed a toast; it now actually re-reads
  the library.

- The shelf shows a book's reading progress after the reader is closed over it,
  and a download that finishes while the shelf is open appears immediately.

- Concurrent writes to the library index are serialised. The reader persists a
  position on every page turn while a download or import can finish at the same
  moment, and the unsynchronised read-modify-write could drop a newly imported book.

### Changed

- `BookStore.importLocal` takes an already-copied `File` instead of a `ByteArray`;
  `GithubClient.fetchText` is now `fetchBytes`, used only for EPUB, which genuinely
  needs random access.

### Tooling

- `tools/ci_release.py` writes `dist/update.json` with `newline="\n"`. Python's
  text mode turns every `\n` into `\r\n` on Windows, and `tools/check_repo_config.py`
  rejects CRLF in that file -- so running a release locally produced a manifest that
  failed the repository's own check. The script now also re-reads what it wrote and
  refuses to report success if a CRLF got in.

- `tools/verify_release.py` (new) independently re-derives the release invariants
  from the files on disk -- digest against the staged APK, versionCode arithmetic,
  `apkUrl` filename, and release notes against the CHANGELOG section -- without
  importing the release tooling. Wired into `check.cmd`, which now also propagates
  a failing exit code correctly: `%ERRORLEVEL%` inside a parenthesised block expands
  at parse time, so the previous structure could report failure and still exit 0.

## [1.0.1] - 2026-10-11

### Fixed

- **在线阅读和下载全部失败。** Every request went to
  `raw.githubusercontent.com`, which is fully blocked on some networks (connect
  timeout, then a TLS reset). Listing, reading and downloading all shared that one
  hostname, so the app was unusable end to end.

  Requests now go to several routes at once and take the first success: GitHub
  direct, three jsDelivr nodes, `ghproxy.net`, `gh-proxy.com`, `ghfast.top`,
  `gitmirror` and Statically. A blocked route merely loses the race instead of
  failing the request. Measured against this project's library, `ghproxy.net` and
  `gh-proxy.com` served every file and jsDelivr served only files under 20 MB.

  A route is chosen with a 2 KB `Range` request before any real transfer, so
  picking a route costs about one page of a book rather than retrying a
  multi-megabyte file on nine hosts. The winner is remembered.

- **大文件无法在线阅读。** 33 of the library's 53 books are over 8 MB and the
  largest is 75 MB, while the read cap was 8 MB -- most of the shelf could not be
  opened. The cap is now 64 MB, and books past it are told to download first.

- **下载会耗尽内存。** Downloads streamed through memory, so a 75 MB book had to
  be buffered whole. They now stream straight to disk through a `.part` file that
  is renamed only on success, so an interrupted transfer cannot masquerade as a
  complete book.

- **更新检查误报「已是最新」。** `dist/update.json` lives on
  `raw.githubusercontent.com` too, so the check failed silently on the same
  networks. It now uses the same multi-route fetch.

### Added

- **从本地导入。** The shelf's `+` action imports `txt`, `md`, `html` and `epub`
  files, several at a time. Files are copied into app storage rather than
  referenced by content URI, so a later revocation of the grant cannot make a
  book unreadable. Re-importing the same name updates the record instead of
  duplicating it, and imported books never attempt a network fetch.

- **网络线路设置。** Settings now shows which route is actually in use, allows
  pinning one, and offers a "检测线路" action that tests each route and reports
  which are reachable.

- Download progress is shown as a percentage.

### Changed

- The launcher icon is derived from the supplied artwork instead of being redrawn.
  The adaptive icon's background is sampled from the artwork's own navy field, so
  no launcher mask shape exposes a pale corner.

## [1.0.0] - 2026-10-11

### Added

- 主页: recursive listing of a GitHub library repository, grouped by folder, with
  online reading and one-tap download.
- 书架: generated gradient covers, reading progress, sorting, search and delete.
- 阅读器: paginated text rendering measured by the same engine that draws it,
  chapter detection and a table of contents, in-book search, five reading themes,
  adjustable font size and line spacing, and reading position stored as a
  character offset so it survives a repagination.
- 设置: reading preferences, app theme, library source, storage management and a
  GitHub update check that verifies the APK's SHA-256 before installing.

[Unreleased]: https://github.com/nmaych/Readon/compare/v1.0.10...HEAD
[1.0.10]: https://github.com/nmaych/Readon/compare/v1.0.9...v1.0.10
[1.0.9]: https://github.com/nmaych/Readon/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/nmaych/Readon/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/nmaych/Readon/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/nmaych/Readon/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/nmaych/Readon/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/nmaych/Readon/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/nmaych/Readon/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/nmaych/Readon/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/nmaych/Readon/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/nmaych/Readon/releases/tag/v1.0.0
