#!/usr/bin/env node
import fs from 'node:fs'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'
import {
  WorkspaceApi,
  type Note,
  type NotePayload,
  type NoteSummary,
  type Project,
  type ProjectPayload,
  type QuickCommand,
  type Todo,
  type TodoPayload
} from './api.js'

const BASE_URL = (process.env.WORKSPACE_BASE_URL ?? 'https://www.xiahan.asia').replace(/\/+$/, '')

function resolveToken(): string {
  const direct = process.env.WORKSPACE_TOKEN?.trim()
  if (direct) return direct
  const file = process.env.WORKSPACE_TOKEN_FILE?.trim()
  if (file) {
    try {
      const value = fs.readFileSync(file, 'utf8').trim()
      if (value) return value
      console.error(`personal-workspace-mcp: 令牌文件为空：${file}`)
    } catch {
      console.error(`personal-workspace-mcp: 无法读取令牌文件：${file}`)
    }
  }
  console.error(
    'personal-workspace-mcp: 缺少令牌。请设置 WORKSPACE_TOKEN，或用 WORKSPACE_TOKEN_FILE 指向保存 pat_ 令牌的文件。'
  )
  process.exit(1)
}

const TOKEN = resolveToken()

const api = new WorkspaceApi(BASE_URL, TOKEN)
const server = new McpServer({ name: 'personal-workspace', version: '0.1.0' })

const ok = (data: unknown) => ({
  content: [{ type: 'text' as const, text: JSON.stringify(data, null, 2) }]
})

const blankToNull = (value: string | null | undefined): string | null => {
  if (value == null) return null
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

const statusEnum = ['ALL', 'OPEN', 'DONE', 'DISMISSED'] as const
const priorityEnum = ['HIGH', 'MEDIUM', 'LOW'] as const
const projectStatusEnum = ['NOT_STARTED', 'DEV', 'TEST', 'RELEASED', 'ACCEPTED'] as const

async function resolveProject(ref: string | null | undefined): Promise<Project | null> {
  if (ref == null || ref.trim() === '') return null
  const key = ref.trim()
  const projects = await api.listProjects()
  const byName = projects.find((project) => project.name === key)
  if (byName) return byName
  const byTitle = projects.filter((project) => project.title === key)
  if (byTitle.length === 1) return byTitle[0]
  if (byTitle.length > 1) {
    throw new Error(`项目「${key}」重名，请改用项目 name。`)
  }
  const needle = key.toLowerCase()
  const similar = projects
    .filter((project) => project.title.toLowerCase().includes(needle))
    .slice(0, 8)
    .map((project) => project.title)
  throw new Error(
    `找不到项目「${key}」${similar.length ? `，相近项目：${similar.join('、')}` : ''}。`
  )
}

async function ensureModule(project: Project | null, module: string | null): Promise<void> {
  if (!project || !module || project.modules.includes(module)) return
  if (project.modules.length >= 20) {
    throw new Error(`项目「${project.title}」的模块已达 20 个上限，无法自动加入「${module}」。`)
  }
  const updated = await api.updateProject(project.name, {
    title: project.title,
    modules: [...project.modules, module],
    status: project.status,
    note: project.note,
    links: project.links,
    tags: project.tags
  })
  project.modules = updated.modules
}

function projectTitle(projects: Project[], name: string | null): string | null {
  if (!name) return null
  return projects.find((project) => project.name === name)?.title ?? null
}

function compactTodo(todo: Todo, projects: Project[]) {
  return {
    name: todo.name,
    title: todo.title,
    priority: todo.priority,
    completed: todo.completed,
    dismissed: todo.dismissed,
    pinned: todo.pinned,
    project: todo.project,
    projectTitle: projectTitle(projects, todo.project),
    projectStatus: todo.projectStatus,
    module: todo.module,
    reporter: todo.reporter,
    dueDate: todo.dueDate,
    createdAt: todo.createdAt,
    completedAt: todo.completedAt
  }
}

function todoPayload(todo: Todo): TodoPayload {
  return {
    title: todo.title,
    detail: todo.detail,
    priority: todo.priority,
    project: todo.project,
    module: todo.module,
    reporter: todo.reporter,
    dueDate: todo.dueDate,
    completed: todo.completed,
    dismissed: todo.dismissed,
    pinned: todo.pinned,
    createdAt: todo.createdAt
  }
}

function compactNote(note: NoteSummary) {
  return {
    name: note.name,
    title: note.title,
    summary: note.summary,
    excerpt: note.excerpt,
    tags: note.tags,
    project: note.project,
    module: note.module,
    todos: note.todos,
    updatedAt: note.updatedAt
  }
}

function compactCommand(command: QuickCommand) {
  return {
    name: command.name,
    title: command.title,
    command: command.command,
    note: command.note,
    category: command.category,
    tags: command.tags
  }
}

async function collectTodos(
  params: { status?: string; priority?: string; keyword?: string },
  shouldStop: (items: Todo[]) => boolean
): Promise<Todo[]> {
  const collected: Todo[] = []
  let page = 1
  for (;;) {
    const result = await api.listTodos({
      q: params.keyword,
      status: params.status ?? 'ALL',
      priority: params.priority,
      page,
      size: 500
    })
    collected.push(...result.items)
    if (result.items.length === 0 || collected.length >= result.total || collected.length >= 5000) {
      break
    }
    if (shouldStop(collected)) break
    page += 1
  }
  return collected
}

server.registerTool(
  'list_todos',
  {
    title: '查询待办',
    description:
      '查询个人工作台待办。status 默认 OPEN（未完成）；project 可传项目 name 或标题，' +
      '「未关联」表示未分类；module 传「未分组」表示无模块；返回 name 供 update_todo 使用。',
    inputSchema: {
      status: z.enum(statusEnum).optional().default('OPEN').describe('完成状态筛选'),
      priority: z.enum(priorityEnum).optional().describe('优先级筛选'),
      keyword: z.string().max(100).optional().describe('按标题或详情搜索'),
      project: z.string().max(200).optional().describe('项目 name 或标题；「未关联」= 未分类'),
      module: z.string().max(32).optional().describe('模块名；「未分组」= 无模块'),
      limit: z.number().int().min(1).max(200).optional().default(30).describe('最多返回几条')
    },
    annotations: { readOnlyHint: true }
  },
  async ({ status, priority, keyword, project, module, limit }) => {
    let projectFilter: string | null | undefined
    if (project === '未关联') projectFilter = null
    else if (project) projectFilter = (await resolveProject(project))?.name ?? ''
    const moduleFilter = module === '未分组' ? null : module
    const filterByProject = project !== undefined
    const filterByModule = module !== undefined
    const todos = await collectTodos(
      { status, priority, keyword },
      (items) => items.length >= 1200
    )
    const projects = await api.listProjects()
    const matched = todos.filter((todo) => {
      if (filterByProject && (todo.project ?? null) !== (projectFilter ?? null)) return false
      if (filterByModule && (todo.module ?? null) !== (moduleFilter ?? null)) return false
      return true
    })
    return ok({
      count: Math.min(matched.length, limit ?? 30),
      totalMatched: matched.length,
      skipped: Math.max(0, matched.length - (limit ?? 30)),
      items: matched.slice(0, limit ?? 30).map((todo) => compactTodo(todo, projects))
    })
  }
)

server.registerTool(
  'get_todo',
  {
    title: '读取待办',
    description: '按 name（UUID）读取一条待办的完整字段，包括详情、时间与项目状态快照。',
    inputSchema: {
      name: z.string().min(1).describe('待办 name')
    },
    annotations: { readOnlyHint: true }
  },
  async ({ name }) => {
    const todo = await api.getTodo(name)
    const projects = await api.listProjects()
    return ok({
      ...todo,
      projectTitle: projectTitle(projects, todo.project)
    })
  }
)

server.registerTool(
  'create_todo',
  {
    title: '新建待办',
    description:
      '新建待办。project 传项目 name 或标题，不传即未分类；module 不存在时会自动加入项目。' +
      '待办会记录项目当前状态快照；priority 默认 MEDIUM。',
    inputSchema: {
      title: z.string().min(1).max(200),
      detail: z.string().max(5000).optional(),
      priority: z.enum(priorityEnum).optional().default('MEDIUM'),
      project: z.string().max(200).optional().describe('项目 name 或标题'),
      module: z.string().max(32).optional(),
      reporter: z.string().max(64).optional().describe('提出人姓名'),
      dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe('截止日期 YYYY-MM-DD'),
      completed: z.boolean().optional(),
      dismissed: z.boolean().optional().describe('标记为不用处理'),
      pinned: z.boolean().optional().describe('加入今日聚焦'),
      createdAt: z.string().max(40).optional().describe('创建时间，默认服务器当前时间')
    }
  },
  async (input) => {
    const project = await resolveProject(input.project)
    if (!project && blankToNull(input.module)) {
      throw new Error('未分类待办不能设置模块，请先指定 project。')
    }
    const module = project ? blankToNull(input.module) : null
    await ensureModule(project, module)
    const created = await api.createTodo({
      title: input.title.trim(),
      detail: blankToNull(input.detail),
      priority: input.priority,
      project: project?.name ?? null,
      module,
      reporter: blankToNull(input.reporter),
      dueDate: blankToNull(input.dueDate),
      completed: input.completed ?? false,
      dismissed: input.dismissed ?? false,
      pinned: input.pinned ?? false,
      createdAt: blankToNull(input.createdAt)
    })
    return ok({ created: compactTodo(created, await api.listProjects()) })
  }
)

server.registerTool(
  'update_todo',
  {
    title: '更新待办',
    description:
      '更新待办，只传要改的字段。project 传 null 移到未分类；module 传 null 清除模块；' +
      'completed / dismissed / pinned 分别控制完成、不用处理、今日聚焦；改项目或模块会自动加入缺失模块。',
    inputSchema: {
      name: z.string().min(1).describe('待办 name'),
      title: z.string().min(1).max(200).optional(),
      detail: z.string().max(5000).nullable().optional(),
      priority: z.enum(priorityEnum).optional(),
      project: z.string().max(200).nullable().optional(),
      module: z.string().max(32).nullable().optional(),
      reporter: z.string().max(64).nullable().optional(),
      dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullable().optional(),
      completed: z.boolean().optional(),
      dismissed: z.boolean().optional(),
      pinned: z.boolean().optional(),
      createdAt: z.string().max(40).nullable().optional()
    }
  },
  async (input) => {
    const current = await api.getTodo(input.name)
    const projectChanged = input.project !== undefined
    const nextProject = projectChanged
      ? await resolveProject(input.project)
      : input.module !== undefined && current.project
        ? await resolveProject(current.project)
        : undefined

    let nextModule: string | null
    if (input.module !== undefined) {
      nextModule = blankToNull(input.module)
    } else if (projectChanged) {
      nextModule = nextProject && current.module && nextProject.modules.includes(current.module)
        ? current.module
        : null
    } else {
      nextModule = current.module
    }
    const effectiveProject = projectChanged ? nextProject?.name ?? null : current.project
    if (nextModule && !effectiveProject) {
      throw new Error('未分类待办不能设置模块，请先指定 project。')
    }
    await ensureModule(nextProject ?? null, nextModule)

    const next: Todo = {
      ...current,
      title: input.title?.trim() ?? current.title,
      detail: input.detail !== undefined ? blankToNull(input.detail) : current.detail,
      priority: input.priority ?? current.priority,
      project: projectChanged ? nextProject?.name ?? null : current.project,
      module: nextModule,
      reporter: input.reporter !== undefined ? blankToNull(input.reporter) : current.reporter,
      dueDate: input.dueDate !== undefined ? blankToNull(input.dueDate) : current.dueDate,
      completed: input.completed ?? current.completed,
      dismissed: input.dismissed ?? current.dismissed,
      pinned: input.pinned ?? current.pinned,
      createdAt: input.createdAt !== undefined ? blankToNull(input.createdAt) : current.createdAt
    }
    const updated = await api.updateTodo(input.name, todoPayload(next))
    return ok({ updated: compactTodo(updated, await api.listProjects()) })
  }
)

server.registerTool(
  'list_projects',
  {
    title: '查询项目',
    description:
      '列出项目及其模块、项目状态（NOT_STARTED/DEV/TEST/RELEASED/ACCEPTED）、备注与链接。' +
      '可用 keyword 过滤标题或模块，用 status 过滤状态。',
    inputSchema: {
      keyword: z.string().max(100).optional(),
      status: z.enum(projectStatusEnum).optional(),
      limit: z.number().int().min(1).max(200).optional().default(100)
    },
    annotations: { readOnlyHint: true }
  },
  async ({ keyword, status, limit }) => {
    const projects = await api.listProjects()
    const needle = keyword?.trim().toLowerCase()
    const matched = projects.filter((project) => {
      if (status && project.status !== status) return false
      if (!needle) return true
      return project.title.toLowerCase().includes(needle)
        || project.modules.some((module) => module.toLowerCase().includes(needle))
    })
    return ok({
      count: Math.min(matched.length, limit ?? 100),
      total: matched.length,
      items: matched.slice(0, limit ?? 100)
    })
  }
)

server.registerTool(
  'create_project',
  {
    title: '新建项目',
    description: '新建项目。status 默认 NOT_STARTED；modules 最多 20 个。',
    inputSchema: {
      title: z.string().min(1).max(200),
      modules: z.array(z.string().min(1).max(32)).max(20).optional(),
      status: z.enum(projectStatusEnum).optional().default('NOT_STARTED'),
      note: z.string().max(2000).optional(),
      links: z.array(z.string().min(1).max(2000)).max(20).optional(),
      tags: z.array(z.string().min(1).max(32)).max(10).optional()
    }
  },
  async (input) => {
    const created = await api.createProject({
      title: input.title.trim(),
      modules: input.modules ?? [],
      status: input.status,
      note: blankToNull(input.note),
      links: input.links ?? [],
      tags: input.tags ?? []
    })
    return ok({ created })
  }
)

server.registerTool(
  'update_project',
  {
    title: '更新项目',
    description:
      '更新项目，project 传 name 或标题，只传要改的字段。改 status 会推进项目阶段；' +
      'modules 是完整的新模块列表（不是增量）。已验收项目会导致其待办在列表默认隐藏。',
    inputSchema: {
      project: z.string().min(1).max(200).describe('项目 name 或标题'),
      title: z.string().min(1).max(200).optional(),
      modules: z.array(z.string().min(1).max(32)).max(20).optional(),
      status: z.enum(projectStatusEnum).optional(),
      note: z.string().max(2000).nullable().optional(),
      links: z.array(z.string().min(1).max(2000)).max(20).optional(),
      tags: z.array(z.string().min(1).max(32)).max(10).optional()
    }
  },
  async (input) => {
    const current = await resolveProject(input.project)
    if (!current) throw new Error('请提供项目 name 或标题。')
    const updated = await api.updateProject(current.name, {
      title: input.title?.trim() ?? current.title,
      modules: input.modules ?? current.modules,
      status: input.status ?? current.status,
      note: input.note !== undefined ? blankToNull(input.note) : current.note,
      links: input.links ?? current.links,
      tags: input.tags ?? current.tags
    })
    return ok({ updated })
  }
)

server.registerTool(
  'rename_project_module',
  {
    title: '重命名项目模块',
    description: '重命名项目下的模块，并同步该项目待办里的模块名。',
    inputSchema: {
      project: z.string().min(1).max(200).describe('项目 name 或标题'),
      from: z.string().min(1).max(32),
      to: z.string().min(1).max(32)
    }
  },
  async ({ project, from, to }) => {
    const current = await resolveProject(project)
    if (!current) throw new Error('请提供项目 name 或标题。')
    return ok({ updated: await api.renameModule(current.name, from.trim(), to.trim()) })
  }
)

server.registerTool(
  'list_notes',
  {
    title: '查询笔记',
    description: '按关键词搜索笔记（服务端搜索标题与正文），返回摘要；project/module 可再过滤。',
    inputSchema: {
      keyword: z.string().max(200).optional(),
      project: z.string().max(200).optional().describe('项目 name 或标题'),
      module: z.string().max(32).optional(),
      limit: z.number().int().min(1).max(100).optional().default(20)
    },
    annotations: { readOnlyHint: true }
  },
  async ({ keyword, project, module, limit }) => {
    const notes = await api.listNotes(keyword)
    const projectName = project ? (await resolveProject(project))?.name ?? '' : undefined
    const matched = notes.filter((note) => {
      if (projectName !== undefined && (note.project ?? '') !== projectName) return false
      if (module !== undefined && (note.module ?? '') !== module) return false
      return true
    })
    return ok({
      count: Math.min(matched.length, limit ?? 20),
      total: matched.length,
      items: matched.slice(0, limit ?? 20).map(compactNote)
    })
  }
)

server.registerTool(
  'get_note',
  {
    title: '读取笔记',
    description: '按 name 读取笔记全文（Markdown）与属性。',
    inputSchema: {
      name: z.string().min(1).describe('笔记 name')
    },
    annotations: { readOnlyHint: true }
  },
  async ({ name }) => ok(await api.getNote(name))
)

server.registerTool(
  'create_note',
  {
    title: '新建笔记',
    description:
      '新建笔记，content 为 Markdown。project 传项目 name 或标题；module 不存在时会自动加入项目。' +
      'todos 传关联待办的 name 列表。',
    inputSchema: {
      title: z.string().min(1).max(200),
      content: z.string().max(200000).optional(),
      summary: z.string().max(1000).optional(),
      tags: z.array(z.string().min(1).max(32)).max(10).optional(),
      project: z.string().max(200).optional(),
      module: z.string().max(32).optional(),
      todos: z.array(z.string().min(1).max(64)).max(50).optional()
    }
  },
  async (input) => {
    const project = await resolveProject(input.project)
    if (!project && blankToNull(input.module)) {
      throw new Error('未分类笔记不能设置模块，请先指定 project。')
    }
    const module = project ? blankToNull(input.module) : null
    await ensureModule(project, module)
    const created = await api.createNote({
      title: input.title.trim(),
      content: blankToNull(input.content),
      summary: blankToNull(input.summary),
      tags: input.tags ?? [],
      project: project?.name ?? null,
      module,
      todos: input.todos ?? []
    })
    return ok({
      created: {
        name: created.name,
        title: created.title,
        summary: created.summary,
        project: created.project,
        module: created.module,
        todos: created.todos,
        updatedAt: created.updatedAt
      }
    })
  }
)

server.registerTool(
  'update_note',
  {
    title: '更新笔记',
    description:
      '更新笔记，只传要改的字段。project 传 null 清除项目；module 传 null 清除模块；' +
      'todos 是完整的关联待办 name 列表。',
    inputSchema: {
      name: z.string().min(1).describe('笔记 name'),
      title: z.string().min(1).max(200).optional(),
      content: z.string().max(200000).nullable().optional(),
      summary: z.string().max(1000).nullable().optional(),
      tags: z.array(z.string().min(1).max(32)).max(10).optional(),
      project: z.string().max(200).nullable().optional(),
      module: z.string().max(32).nullable().optional(),
      todos: z.array(z.string().min(1).max(64)).max(50).optional()
    }
  },
  async (input) => {
    const current = await api.getNote(input.name)
    const projectChanged = input.project !== undefined
    const nextProject = projectChanged
      ? await resolveProject(input.project)
      : input.module !== undefined && current.project
        ? await resolveProject(current.project)
        : undefined
    let nextModule: string | null
    if (input.module !== undefined) {
      nextModule = blankToNull(input.module)
    } else if (projectChanged) {
      nextModule = nextProject && current.module && nextProject.modules.includes(current.module)
        ? current.module
        : null
    } else {
      nextModule = current.module
    }
    const effectiveProject = projectChanged ? nextProject?.name ?? null : current.project
    if (nextModule && !effectiveProject) {
      throw new Error('未分类笔记不能设置模块，请先指定 project。')
    }
    await ensureModule(nextProject ?? null, nextModule)
    const payload: NotePayload = {
      title: input.title?.trim() ?? current.title,
      content: input.content !== undefined ? blankToNull(input.content) : current.content,
      summary: input.summary !== undefined ? blankToNull(input.summary) : current.summary,
      tags: input.tags ?? current.tags,
      project: projectChanged ? nextProject?.name ?? null : current.project,
      module: nextModule,
      todos: input.todos ?? current.todos
    }
    const updated = await api.updateNote(input.name, payload)
    return ok({
      updated: {
        name: updated.name,
        title: updated.title,
        summary: updated.summary,
        project: updated.project,
        module: updated.module,
        todos: updated.todos,
        updatedAt: updated.updatedAt
      }
    })
  }
)

server.registerTool(
  'list_commands',
  {
    title: '查询快捷命令',
    description: '列出个人工作台的快捷命令，可按标题、命令内容、备注、分类或标签过滤。',
    inputSchema: {
      keyword: z.string().max(100).optional(),
      limit: z.number().int().min(1).max(200).optional().default(50)
    },
    annotations: { readOnlyHint: true }
  },
  async ({ keyword, limit }) => {
    const commands = await api.listCommands()
    const needle = keyword?.trim().toLowerCase()
    const matched = needle
      ? commands.filter((command) => [
          command.title,
          command.command,
          command.note ?? '',
          command.category ?? '',
          command.tags.join(' ')
        ].join(' ').toLowerCase().includes(needle))
      : commands
    return ok({
      count: Math.min(matched.length, limit ?? 50),
      total: matched.length,
      items: matched.slice(0, limit ?? 50).map(compactCommand)
    })
  }
)

server.registerTool(
  'search',
  {
    title: '全局搜索',
    description: '在工作台内全局搜索项目、模块、待办、笔记和命令，返回命中的类型与 name。',
    inputSchema: {
      q: z.string().min(1).max(100),
      limit: z.number().int().min(1).max(100).optional().default(30)
    },
    annotations: { readOnlyHint: true }
  },
  async ({ q, limit }) => {
    const result = await api.search(q.trim())
    return ok({
      q: result.q,
      count: Math.min(result.hits.length, limit ?? 30),
      hits: result.hits.slice(0, limit ?? 30)
    })
  }
)

const transport = new StdioServerTransport()
await server.connect(transport)
console.error(`personal-workspace-mcp ready: ${BASE_URL}`)
