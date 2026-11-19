# personal-workspace MCP

把个人工作台（待办 / 项目 / 笔记 / 快捷命令）暴露成本地 MCP 服务，供 Qoder、Claude Desktop、Cursor 等支持 stdio 的 AI 客户端调用。

- 传输：stdio（本机进程，不开放公网端口）
- 认证：Halo 个人访问令牌（`pat_...`），通过 `Authorization: Bearer` 调 `https://<你的站点>/apis/api.workspace.halo.run/v1alpha1/*`
- 范围：读写（不提供删除工具）
- 依赖：Node.js ≥ 20

## 构建

```bat
cd /d D:\qt\halo\plugins\personal-workspace\mcp
npm install
npm run build
```

构建产物是 `dist/index.js`。

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

### Qoder / Claude Desktop（stdio）

在 MCP 设置里新增一个 stdio 服务器，或写入客户端配置：

```json
{
  "mcpServers": {
    "personal-workspace": {
      "command": "D:\\devtool\\nodejs\\node.exe",
      "args": [
        "D:\\qt\\halo\\plugins\\personal-workspace\\mcp\\dist\\index.js"
      ],
      "env": {
        "WORKSPACE_BASE_URL": "https://www.xiahan.asia",
        "WORKSPACE_TOKEN_FILE": "D:\\qt\\halo\\plugins\\personal-workspace\\mcp\\.token"
      }
    }
  }
}
```

说明：

- `command` 用 `node.exe` 的绝对路径最稳；如果客户端能读到 PATH，也可以写 `node`。
- `WORKSPACE_BASE_URL` 不带末尾斜杠；本地测试可改成 `http://127.0.0.1:8093`。
- Claude Desktop 的配置文件通常在 `%APPDATA%\Claude\claude_desktop_config.json`。
- 修改配置后重启客户端；服务器启动日志会输出 `personal-workspace-mcp ready: <base>`（不含令牌）。

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

```bat
cd /d D:\qt\halo\plugins\personal-workspace\mcp
set WORKSPACE_BASE_URL=http://127.0.0.1:8093
set WORKSPACE_TOKEN_FILE=D:\qt\halo\plugins\personal-workspace\mcp\.token
npm run smoke
```

会创建并清理一组临时项目 / 待办 / 笔记，覆盖状态快照、自动加模块、笔记读写和搜索。测试脚本不会打印令牌。

## 安全边界

- 令牌等同于你的工作台账号权限：只放在本机文件或客户端配置里，泄露后立即在 Halo 后台撤销。
- 没有删除工具；`update_todo` / `update_project` 仍可改变数据，AI 执行前应自行确认内容。
- 服务只主动连接 `WORKSPACE_BASE_URL`，不接受来自网络的入站连接。
