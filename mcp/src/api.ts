export interface Todo {
  name: string
  title: string
  detail: string | null
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  project: string | null
  module: string | null
  projectStatus: string | null
  reporter: string | null
  dueDate: string | null
  completed: boolean
  completedAt: string | null
  dismissed: boolean
  dismissedAt: string | null
  pinned: boolean
  createdAt: string | null
}

export interface TodoList {
  items: Todo[]
  total: number
  page: number
  size: number
}

export interface Project {
  name: string
  title: string
  modules: string[]
  status: 'NOT_STARTED' | 'DEV' | 'TEST' | 'RELEASED' | 'ACCEPTED'
  note: string | null
  links: string[]
  tags: string[]
}

export interface NoteSummary {
  name: string
  title: string
  excerpt: string
  summary: string | null
  tags: string[]
  project: string | null
  module: string | null
  todos: string[]
  updatedAt: string
}

export interface Note {
  name: string
  title: string
  content: string | null
  summary: string | null
  tags: string[]
  project: string | null
  module: string | null
  todos: string[]
  updatedAt: string
}

export interface QuickCommand {
  name: string
  title: string
  command: string
  note: string | null
  category: string | null
  tags: string[]
}

export interface SearchHit {
  type: string
  name: string
  title: string
  meta: string
}

export interface TodoPayload {
  title: string
  detail: string | null
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  project: string | null
  module: string | null
  reporter: string | null
  dueDate: string | null
  completed: boolean
  dismissed: boolean
  pinned: boolean
  createdAt: string | null
}

export interface ProjectPayload {
  title: string
  modules: string[]
  status: string
  note: string | null
  links: string[]
  tags: string[]
}

export interface NotePayload {
  title: string
  content: string | null
  summary: string | null
  tags: string[]
  project: string | null
  module: string | null
  todos: string[]
}

const API_PREFIX = '/apis/api.workspace.halo.run/v1alpha1'
const TIMEOUT_MS = 20_000

export class WorkspaceApi {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string
  ) {}

  private async request<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${API_PREFIX}${path}`, {
        method: init.method ?? 'GET',
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${this.token}`,
          ...(init.body === undefined ? {} : { 'Content-Type': 'application/json' })
        },
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        signal: AbortSignal.timeout(TIMEOUT_MS)
      })
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error)
      throw new Error(`无法连接 ${this.baseUrl}（${reason}）`)
    }
    const text = await response.text()
    if (!response.ok) {
      throw new Error(apiError(response.status, text))
    }
    return (text ? JSON.parse(text) : undefined) as T
  }

  listTodos(params: {
    q?: string
    status?: string
    priority?: string
    page?: number
    size?: number
  }): Promise<TodoList> {
    const query = new URLSearchParams()
    if (params.q) query.set('q', params.q)
    if (params.status) query.set('status', params.status)
    if (params.priority) query.set('priority', params.priority)
    query.set('page', String(params.page ?? 1))
    query.set('size', String(params.size ?? 20))
    return this.request<TodoList>(`/todos?${query.toString()}`)
  }

  getTodo(name: string): Promise<Todo> {
    return this.request<Todo>(`/todos/${encodeURIComponent(name)}`)
  }

  createTodo(body: TodoPayload): Promise<Todo> {
    return this.request<Todo>('/todos', { method: 'POST', body })
  }

  updateTodo(name: string, body: TodoPayload): Promise<Todo> {
    return this.request<Todo>(`/todos/${encodeURIComponent(name)}`, { method: 'PUT', body })
  }

  listProjects(): Promise<Project[]> {
    return this.request<Project[]>('/projects')
  }

  createProject(body: ProjectPayload): Promise<Project> {
    return this.request<Project>('/projects', { method: 'POST', body })
  }

  updateProject(name: string, body: ProjectPayload): Promise<Project> {
    return this.request<Project>(`/projects/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body
    })
  }

  renameModule(name: string, from: string, to: string): Promise<Project> {
    return this.request<Project>(`/projects/${encodeURIComponent(name)}/modules/rename`, {
      method: 'PUT',
      body: { from, to }
    })
  }

  listNotes(keyword?: string): Promise<NoteSummary[]> {
    const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''
    return this.request<NoteSummary[]>(`/notes${query}`)
  }

  getNote(name: string): Promise<Note> {
    return this.request<Note>(`/notes/${encodeURIComponent(name)}`)
  }

  createNote(body: NotePayload): Promise<Note> {
    return this.request<Note>('/notes', { method: 'POST', body })
  }

  updateNote(name: string, body: NotePayload): Promise<Note> {
    return this.request<Note>(`/notes/${encodeURIComponent(name)}`, { method: 'PUT', body })
  }

  listCommands(): Promise<QuickCommand[]> {
    return this.request<QuickCommand[]>('/commands')
  }

  search(q: string): Promise<{ q: string; hits: SearchHit[] }> {
    return this.request<{ q: string; hits: SearchHit[] }>(
      `/search?q=${encodeURIComponent(q)}`
    )
  }
}

function apiError(status: number, text: string): string {
  let message = text.trim()
  try {
    const parsed = JSON.parse(text) as { error?: { message?: string } }
    if (parsed?.error?.message) message = parsed.error.message
  } catch {
    // keep raw text
  }
  if (message.length > 200) message = message.slice(0, 200) + '…'
  if (status === 401 || status === 403) {
    return `工作台拒绝了请求（${status}）：${message || '个人令牌无效或缺少权限'}`
  }
  if (status === 404) {
    return `未找到对应数据（404）：${message || '可能已被删除'}`
  }
  return `工作台接口返回 ${status}：${message}`
}
