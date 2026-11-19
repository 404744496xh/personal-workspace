# 个人工作台（personal-workspace）

Halo 2.26+ 的前台个人工作台插件：把待办、项目、快速命令与笔记收进一个页面，所有数据按登录用户隔离。入口 `/workspace`，不进入 Console。

后端 Java 21 + WebFlux，前端 Vue 3 + Vite + Tailwind，笔记编辑器使用 Vditor。

当前版本：`0.6.100`

![待办](docs/screenshots/todos.png)

## 功能

### 概览

![概览](docs/screenshots/overview.png)

- 登录后默认进入概览页，点左上角「个人工作台」随时回到首屏
- 日历按月 / 按周切换，按天显示新增、完成与不用处理数量，可翻页、一键回到今天；点某天弹出当天的新增 / 完成 / 不用处理明细，新增里已完成或已忽略的条目标注「完成」/「不用处理」，点条目直接打开对应待办，也可在这天补记待办（创建时间预设为当天 09:00）
- 日历右上角提供「AI 月报 / AI 周报」，按当前查看的月份或周生成报告笔记，详见下方「AI 报告」
- 右侧卡片：未完成（含「今日 +N」）、快逾期（创建超过 7 天仍未完成）、不用处理、项目状态（进行中 / 已验收与各项目当前状态）；点击卡片直接跳到对应筛选
- 日历下方折线图与日历共用月 / 周视图，悬停显示当天的新增 / 完成 / 不用处理数量
- 「进行中」列出最新创建的 8 条未完成待办，勾选即完成；点行进待办页并带入项目筛选

### AI 报告

- 日历右上角「AI 月报 / AI 周报」汇总当前查看的月份或周：待办动态、项目全部属性（状态、模块、标签、链接、说明与关联笔记）以及本周期更新的笔记
- 按报告模板写成结构化 Markdown，保存为笔记并直接打开；之前生成的报告笔记不参与汇总，避免引用上一次报告
- 报告模板在插件设置「AI 与报告」中编辑：用 Markdown 标题定义章节，可写每节要点与写作要求；留空使用内置研发模板（结论、目标与进度、关键交付、风险与资源、下期计划、知识沉淀）
- 不虚构数据、周报 / 月报措辞、表格最多 4 列等约束由插件固定，模板只决定正文结构与写作要求
- 保存模板后下一次生成立即生效，无需重启；需先配置 OpenAI 兼容接口

### 待办

- 默认只显示未完成，但保留全部项目与模块（0 条也显示），方便直接新建或编辑
- 创建、编辑、删除，勾选完成，标记 / 恢复「不用处理」；优先级、项目 / 模块、姓名、创建时间、截止日期都可编辑
- 点击整行打开编辑；多选态下点击改为勾选，选中整块浅蓝底
- 关键词搜索；状态、优先级直接放在工具栏；项目、模块（可输入搜索）、项目状态固定在工具栏下方的筛选行，筛选时同一行出现「清除筛选」；时间（创建 / 完成）由「时间」按钮展开面板；粘贴导入保留一键入口，视图切换、多选与导出 CSV 收在「更多」菜单，工具栏固定一行
- 按创建 / 完成 / 截止时间与优先级排序，可切换升降序，全部在前端即时生效
- 分组按项目与模块展示：整行点击即筛选，标题右侧箭头折叠 / 展开，分组悬停「全部完成」
- 工具栏可切换「分组 / 列表」视图：列表视图不分组，按当前排序平铺，行内显示所属项目 · 模块，点它即按该项目与模块筛选
- 模块底部与项目卡片底部有幽灵输入行，输入回车即创建待办，失焦自动保存，可连续添加
- 多选后工具条收拢为「批量操作」菜单：标记完成 / 标记不用处理 / 移动到… / 删除所选 / 清除选择；旁边的「复制」菜单可选复制完整信息或仅复制标题
- 已验收项目的待办默认隐藏，工具栏眼睛按钮可切换；待办行可跳转或新建关联笔记
- 创建待办时若选了项目，会把项目当时的状态一并记录在该待办上；之后项目状态变化不影响旧待办，把待办移动到别的项目时刷新为新项目当前状态
- 保存筛选视图：把当前关键词、状态、优先级、项目 / 模块、项目状态与时间区间存成命名视图，搜索框内的书签按钮一键恢复，视图保存在当前浏览器

### 导入

![粘贴导入](docs/screenshots/import.png)

- 从 Excel 直接粘贴，支持 1–5 列：待办；项目 + 待办 / 模块 + 待办；项目 + 模块 + 待办；再追加创建时间、姓名
- 带表头时按列名自动对应，列顺序随意；自动识别制表符、竖线、连续空格、逗号等分隔符
- 列数或分隔符有歧义时拒绝导入并说明原因，不会猜
- 可整批指定「导入到项目」；导入只匹配已有项目，不会新建，对不上的归入未分类
- 导入前显示逐行预览与问题提示，一次最多 500 行

AI 识别导入（需在插件设置中配置 OpenAI 兼容接口）：

- 由模型按语义判断项目 / 模块 / 待办 / 创建时间 / 姓名列，列顺序打乱、表头缺失也能识别
- 功能名与问题描述合并成一句能独立看懂的标题，补充信息才写入详情，可适度总结提炼、分点排版
- 按业务分区归并已有模块，同义 / 简称 / 全半角 / 通用后缀差异会尽量对上现有名称
- 内容较多时按约 40 行自动分批，单批失败自动重试一次，最后合并成一页可编辑预览
- API Key 只保存在服务端，文本由服务端发往所配置的接口

### 项目

![项目](docs/screenshots/projects.png)

- 卡片展示状态、待办统计、模块、备注与关联链接；状态分为未开始 / 开发中 / 测试中 / 已上线 / 已验收
- 新建 / 编辑 / 删除；模块可增删改名，改名时服务端原子迁移该项目下引用旧名的待办
- 卡片悬停可查看关联笔记或待办
- 支持关键词搜索，并按状态筛选项目

### 快速命令

![命令](docs/screenshots/commands.png)

- 按分类筛选与搜索，命令块一键复制，支持增删改

### 笔记

![笔记](docs/screenshots/notes.png)

- 页内 Markdown 编辑器（Vditor IR 模式），标题与正文直接铺开，停顿自动保存，`Ctrl/Cmd + S` 立即保存并返回
- 新建空白笔记时可从快捷模板开始；笔记页「模板」按钮可新建、编辑、删除模板，保存到当前账号，新笔记模板按钮同步更新
- 右侧标题大纲默认收起为贴边轨道，悬停展开，可固定成悬浮卡片
- Mermaid 代码块（`` ```mermaid ``）直接渲染为 SVG，样式与前台文章一致，切换深浅色时按主题重绘
- 支持表格、行内 HTML 预览；粘贴或拖入图片自动上传为站点附件并插入 Markdown，SVG（含内联标记 / data URI / 文件）自动栅格化为 PNG；进入笔记页即预加载 Mermaid 脚本，打开含图表的笔记时直接渲染
- 可关联项目、模块与多个待办；「属性」弹窗编辑关联，不打断正文
- 点击卡片直接打开编辑；卡片上的项目 / 模块 / 待办标签可跳到项目页、该模块待办或具体待办（多个待办时先选一个）
- 搜索与筛选收在一行：项目、项目状态（未开始 / 开发中 / 测试中 / 已上线 / 已验收）、模块、待办支持输入搜索匹配，可组合过滤

![笔记编辑器](docs/screenshots/note-editor.png)

### 搜索与备份

- 左侧栏底部全局搜索跨待办、项目、命令、笔记，点击结果跳到对应页面并带入关键词
- 备份导出待办、项目、命令与笔记为 JSON（笔记模板单独保存，不参与备份）；导入支持合并与覆盖（覆盖前二次确认），保留完成时间、不用处理状态、项目状态与笔记关联

### 外观与操作

- 苹果 HIG 视觉：毛玻璃卡片、18px 圆角、胶囊按钮、最小 44px 点击区
- 深色 / 浅色主题默认跟随系统，可手动切换并记住偏好
- 桌面端侧栏可折叠，偏好写入本地存储；移动端单列布局
- 工具区可整块折叠（搜索、筛选、排序、操作按钮）；快捷键在插件设置中分别配置：折叠全部（默认 `z+d+v`）、新建待办（默认 `n+t`，直接打开新建表单）、全局搜索（默认 `s+s`），均按顺序输入且焦点不在输入框或编辑器时生效
- 各页数据内存缓存，切换页面先显示上次内容再静默刷新
- 待办（含多选、项目 / 模块标题）、首屏进行中与日历明细、项目、笔记、命令均支持右键快捷菜单；项目 / 模块标题右键可直接新建待办并自动带入对应项目与模块；编辑器和输入框保留浏览器原生右键

![深色模式](docs/screenshots/todos-dark.png)

## 安装

构建需要 JDK 21、Node 20。前端由 Gradle 自动构建并打包进 jar。

```bash
./gradlew build
# 产物：build/libs/personal-workspace-0.6.100.jar
```

打开 Halo Console →「插件 → 安装」，上传 jar 并启用，然后访问 `/workspace`。

本地起一个 Halo 调试：

```bash
./gradlew haloServer   # 默认 8090
```

Windows 下如果工作目录被解析成盘符路径导致 R2DBC 报错，用显式路径启动：

```bash
java -jar halo.jar --halo.work-dir=D:/path/to/work
```

仅调试前端：

```bash
cd ui
pnpm install
pnpm run dev      # 热更新，接口仍指向 Halo 站点
pnpm run typecheck
pnpm run build    # 先复制 Vditor 静态资源，再构建 ui/build/dist
```

## 配置

插件设置页（Console → 插件 → 个人工作台 → 设置）包含：

- **基础设置**：整块折叠快捷键，默认 `z+d+v`
- **AI 与报告**：接口地址（Base URL，如 `https://api.deepseek.com/v1`，无需带 `/chat/completions`）、模型名、API Key；任意 OpenAI 兼容接口均可，留空则关闭 AI 识别，规则导入照常使用
- **报告模板**（同在「AI 与报告」页）：周报 / 月报的结构模板，用 Markdown 标题定义章节，可写每节要点与写作要求；留空使用内置研发模板，保存后下一次生成立即生效

## 安全模型

优先级：安全 > 功能 > UI。

- **认证**：`/apis/api.workspace.halo.run/**` 由 `WorkspaceApiAuthFilter` 先加载会话，未登录直接返回 401 JSON，不重定向；只有页面 `/workspace` 会 302 到登录页
- **所有者隔离**：唯一数据访问层 `WorkspaceDataAccess`，所有查询附加 `spec.owner = 当前用户`；请求体里的 `owner` 一律丢弃
- **权限**：业务读写全部走自定义 `CustomEndpoint`；角色模板 `personal-workspace-authenticated` 只聚合到已登录角色，不授予 automatic CRD 接口权限
- **CSRF**：沿用 Halo 原生机制，`/apis` 路径由 Halo 的 CsrfConfigurer 豁免，依赖 `SameSite` 会话 Cookie
- **校验**：DTO 显式校验；请求体上限 待办 / 项目 / 命令 64KB、笔记 256KB、图片 8MB、备份 8MB；笔记正文 ≤200000 字符
- **破坏性操作**：所有 DELETE 必须带 `confirm=true`，备份覆盖导入必须传 `replace=true` 并二次确认
- **内容安全**：笔记图片只接受 `image/*`；项目链接只放行 http/https 与站内相对路径
- **已知边界**：Halo 超级管理员天然拥有全部 CRD 权限，这是平台设计；普通登录用户不具备

## API

Base：`/apis/api.workspace.halo.run/v1alpha1`，所有响应带 `Cache-Control: no-store`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/todos` | 列表，参数 `q`、`status=all\|open\|done\|dismissed`、`priority`、`page`、`size`（默认 20，上限 500） |
| GET | `/todos/{name}` | 详情，非本人数据 404 |
| POST | `/todos` | 创建 |
| PUT | `/todos/{name}` | 更新 |
| DELETE | `/todos/{name}?confirm=true` | 删除，缺少 `confirm` 返回 400 |
| GET | `/projects` | 项目列表，按创建时间升序 |
| POST | `/projects` | 创建项目 |
| PUT | `/projects/{name}` | 更新项目；未传的状态 / 备注 / 链接保持原值 |
| PUT | `/projects/{name}/modules/rename` | 重命名模块，body `{from,to}`，原子迁移引用旧名的待办 |
| DELETE | `/projects/{name}?confirm=true` | 删除项目；项目下仍有待办返回 409 |
| GET | `/commands` | 命令列表，按创建时间倒序 |
| POST | `/commands` | 创建命令 |
| PUT | `/commands/{name}` | 更新命令 |
| DELETE | `/commands/{name}?confirm=true` | 删除命令 |
| GET | `/notes` | 笔记列表，含摘要、关联与更新时间 |
| POST | `/notes` | 创建笔记 |
| PUT | `/notes/{name}` | 更新笔记，服务端刷新 `updatedAt` |
| DELETE | `/notes/{name}?confirm=true` | 删除笔记 |
| POST | `/images` | 上传笔记图片（multipart `file`），返回 `{url}` |
| GET | `/search?q=` | 跨待办 / 项目 / 命令 / 笔记搜索，每类最多 8 条 |
| GET | `/backup` | 导出当前用户全部数据 |
| POST | `/backup?replace=true\|false` | 导入备份 |

AI 识别走页面接口：`POST /workspace/api/ai-import`，body `{text}`（≤20000 字符），会话鉴权并校验 CSRF；未配置返回 503，模型报错 502，超时 504。

笔记模板走页面接口：`GET/PUT /workspace/api/note-templates`，会话鉴权并校验 CSRF。`GET` 返回 `{templates:[...] | null}`，`null` 表示尚未自定义（前端使用内置模板）；`PUT` body `{templates:[{name,content}]}`，最多 20 个模板，名称 ≤50 字、单个正文 ≤8000 字。

请求体字段：

- 待办：`title`（必填 ≤200）、`detail`（≤5000）、`priority`（必填 `HIGH/MEDIUM/LOW`）、`project`、`module`（≤32）、`dueDate`、`completed`、`dismissed`、`createdAt`、`reporter`；`completedAt` / `dismissedAt` / `projectStatus`（项目状态快照）由服务端维护
- 项目：`title`、`modules`（≤20）、`status`（`NOT_STARTED/DEV/TEST/RELEASED/ACCEPTED`）、`note`、`links`（≤20）、`tags`（≤10）
- 命令：`title`、`command`（≤5000）、`note`、`category`、`tags`
- 笔记：`title`、`content`（≤200000）、`project`、`module`、`todos`（≤50）、`tags`

错误统一为 `{"error":{"code":"...","message":"..."}}`：400 参数错误、401 未登录、404 不存在或非本人、409 冲突、413 请求体过大、500 服务异常。

## 数据模型

4 个 `workspace.halo.run/v1alpha1` CRD，`spec.owner` 存 Halo 用户名：

| Kind | 用途 | 索引 |
| --- | --- | --- |
| WorkspaceTodo | 待办（项目、模块、项目状态快照、完成 / 不用处理时间） | owner、completed、dismissed、priority、dueDate、title、detail、tags |
| WorkspaceProject | 项目（模块、状态、备注、链接） | owner |
| WorkspaceQuickCmd | 快速命令（命令、分类） | owner |
| WorkspaceNote | 笔记（Markdown 正文、关联、更新时间） | owner |

索引在 `PersonalWorkspacePlugin.start()` 注册、`stop()` 注销。笔记图片作为站点附件存储，正文只记 URL。

## 后续扩展

命令变量、拖拽排序与跨设备同步筛选视图属于可选增强，不影响当前功能闭环。
