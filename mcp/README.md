# personal-workspace MCP

把个人工作台（待办 / 项目 / 笔记 / 快捷命令）暴露成本地 MCP 服务，供 Qoder、Claude Desktop、Cursor 等支持 stdio 的 AI 客户端调用。

- 传输：stdio（本机进程，不开放公网端口）
- 认证：Halo 个人访问令牌（`pat_...`），通过 `Authorization: Bearer` 调 `https://<你的站点>/apis/api.workspace.halo.run/v1alpha1/*`
- 范围：读写（不提供删除工具）
- 依赖：Node.js ≥ 20

## 最短路径

```bat
cd /d D:\qt\halo\plugins\personal-workspace\mcp
npm install
npm run build
notepad .token
```

把 `pat_...` 粘进 `.token`（一行，保存），然后按下面任一客户端配置添加 stdio MCP。

## 准备个人令牌

1. 打开 Halo 后台 → 个人中心 → 个人令牌 → 新建。
2. 角色至少包含 `authenticated`（工作台 API 权限已聚合到该角色）；如果界面里没有，选你日常使用、能访问工作台的角色。
3. 复制只显示一次的 `pat_...`，存到本机文件，例如：
   `D:\qt\halo\plugins\personal-workspace\mcp\.token`
4. 该文件已被 `.gitignore` 忽略；不要把令牌写进代码、提交记录或聊天里。

服务支持两种配置方式，推荐文件方式：

- `WORKSPACE_TOKEN_FILE`：指向保存令牌的文件（自动去掉首尾空白）
- `WORKSPACE_TOKEN`：直接把令牌放进 MCP 配置的 `env`

## 客户端配置

公共参数：

- 命令：`D:\devtool\nodejs\node.exe`（用绝对路径最稳；PATH 可用时也可以写 `node`）
- 参数：`D:\qt\halo\plugins\personal-workspace\mcp\dist\index.js`
- 环境变量：
  - `WORKSPACE_BASE_URL`：站点地址，不带末尾斜杠；本地测试可用 `http://127.0.0.1:8093`
  - `WORKSPACE_TOKEN_FILE`：令牌文件绝对路径

JSON 里的 Windows 反斜杠要写成 `\\`。

### Qoder Desktop

在「设置 → MCP / 连接器」里新增一个自定义 **stdio** 服务器（名称 `personal-workspace`），填上面的命令、参数和环境变量。保存后在工具列表里应能看到 `list_todos` 等 14 个工具。

### Claude Desktop

编辑 `%APPDATA%\Claude\claude_desktop_config.json`，合并：

```json
{
  "mcpServers": {
    "personal-workspace": {
      "command": "D:\\devtool\\nodejs\\node.exe",
      "args": ["D:\\qt\\halo\\plugins\\personal-workspace\\mcp\\dist\\index.js"],
      "env": {
        "WORKSPACE_BASE_URL": "https://www.xiahan.asia",
        "WORKSPACE_TOKEN_FILE": "D:\\qt\\halo\\plugins\\personal-workspace\\mcp\\.token"
      }
    }
  }
}
```

完全退出并重启 Claude Desktop。

### Cursor

Settings → Tools & MCP → Add Custom MCP，或写入全局 `%USERPROFILE%\.cursor\mcp.json` / 项目 `.cursor/mcp.json`，内容同上。

### Claude Code（CLI）

```bat
claude mcp add personal-workspace ^
  -e WORKSPACE_BASE_URL=https://www.xiahan.asia ^
  -e WORKSPACE_TOKEN_FILE=D:\qt\halo\plugins\personal-workspace\mcp\.token ^
  -- "D:\devtool\nodejs\node.exe" "D:\qt\halo\plugins\personal-workspace\mcp\dist\index.js"
```

具体参数以 `claude mcp add --help` 为准。

### VS Code / GitHub Copilot

在 `.vscode/mcp.json` 里用 `servers` 键，条目加 `"type": "stdio"`：

```json
{
  "servers": {
    "personal-workspace": {
      "type": "stdio",
      "command": "D:\\devtool\\nodejs\\node.exe",
      "args": ["D:\\qt\\halo\\plugins\\personal-workspace\\mcp\\dist\\index.js"],
      "env": {
        "WORKSPACE_BASE_URL": "https://www.xiahan.asia",
        "WORKSPACE_TOKEN_FILE": "D:\\qt\\halo\\plugins\\personal-workspace\\mcp\\.token"
      }
    }
  }
}
```

### 其他桌面客户端

Cherry Studio、ChatWise、Open WebUI 等：在 MCP 设置里新增 **stdio** 服务器，命令、参数、环境变量与上面一致。多个客户端可以共用同一个 `.token` 文件。

### 网页版 / 手机 AI

ChatGPT 网页版、Claude 网页版、手机 AI 等不能启动你电脑上的进程，**无法直接安装本地 stdio MCP**。需要把同一套工具部署成远程 MCP（客户端只填 URL + 令牌），目前版本未包含。

## 怎么用

配置完成后直接用自然语言对话，AI 会自动选择工具，例如：

- 「列出所有未完成的高优先级待办」
- 「给个人工作台的‘前端’模块加一条待办：首页改版，截止 11 月 25 日」
- 「把‘首页改版’标记完成」「把它改成不用处理」「取消今日聚焦」
- 「把这条待办移到 项目 A 的 模块 B」
- 「搜索和‘培训’有关的笔记」「读一下笔记 xxx 的全文」
- 「建一篇笔记，关联项目 A 的模块 B」
- 「把项目 A 的状态改成测试中」

没有删除工具；删除仍需回工作台页面操作。

## 工具

| 工具 | 作用 |
| --- | --- |
| `list_todos` | 查询待办；`status` 默认 `OPEN`，可按关键词、优先级、项目、模块过滤 |
| `get_todo` | 按 `name` 读取完整待办（含详情与项目状态快照） |
| `create_todo` | 新建待办；支持项目/模块、截止日期、姓名、今日聚焦 |
| `update_todo` | 更新待办；可完成、不用处理、置顶、移动项目/模块 |
| `list_projects` | 列出项目、模块与项目状态 |
| `create_project` | 新建项目（状态默认 `NOT_STARTED`） |
| `update_project` | 改标题、模块、状态、备注、链接、标签 |
| `rename_project_module` | 重命名模块，并同步该项目待办的模块名 |
| `list_notes` | 按关键词搜索笔记摘要 |
| `get_note` | 读取笔记 Markdown 全文 |
| `create_note` | 新建笔记，可关联项目、模块与待办 |
| `update_note` | 更新笔记内容、摘要、标签与关联 |
| `list_commands` | 列出快捷命令 |
| `search` | 全局搜索项目、模块、待办、笔记、命令 |

约定：

- 项目参数可以传 `name`（UUID）或项目标题；标题重名会要求改用 `name`。
- 待办/笔记写入时，若模块不存在会自动加入项目（最多 20 个模块）。
- 未分类内容不能设置模块。
- 待办创建时记录项目当前状态快照（`projectStatus`）；项目状态后续推进不会回改旧待办。
- 没有删除类工具；需要删除请回工作台页面操作。

## 本地冒烟测试

仅用于本地 8093 测试台；会创建并清理临时数据，不要直接对生产跑。

```bat
cd /d D:\qt\halo\plugins\personal-workspace\mcp
set WORKSPACE_BASE_URL=http://127.0.0.1:8093
set WORKSPACE_TOKEN_FILE=D:\qt\halo\plugins\personal-workspace\mcp\.token
npm run smoke
```

覆盖状态快照、自动加模块、笔记读写和搜索。测试脚本不会打印令牌。

## 排错

- **客户端提示启动失败 / 工具列表为空**：确认 `mcp\dist\index.js` 存在（先 `npm run build`），`command` 指向的 `node.exe` 正确，Node ≥ 20。
- **提示「缺少令牌」**：`.token` 不存在、为空，或 `WORKSPACE_TOKEN_FILE` 路径写错；也可以改用 `WORKSPACE_TOKEN`。
- **401 / 403**：令牌已撤销或过期，或角色缺少 `authenticated`（工作台 API 权限）。
- **改了配置没生效**：重启客户端；查看客户端 MCP 日志，正常会看到 `personal-workspace-mcp ready: <base>`（不含令牌）。
- **本地能用、生产不行**：确认 `WORKSPACE_BASE_URL` 改成 `https://www.xiahan.asia`，且令牌属于对应站点的用户。

## 安全边界

- 令牌等同于你的工作台账号权限：只放在本机文件或客户端配置里，泄露后立即在 Halo 后台撤销。
- 没有删除工具；`update_todo` / `update_project` 仍可改变数据，AI 执行前应自行确认内容。
- 服务只主动连接 `WORKSPACE_BASE_URL`，不接受来自网络的入站连接。
