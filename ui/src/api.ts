import type {
  BackupCounts,
  BackupData,
  Note,
  NotePayload,
  NoteSummary,
  Project,
  ProjectPayload,
  QuickCmd,
  QuickCmdPayload,
  SearchResponse,
  Todo,
  TodoListResponse,
  TodoPayload
} from './types'

const API_BASE = '/apis/api.workspace.halo.run/v1alpha1'

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body != null && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'same-origin',
    headers
  })
  if (response.status === 401) {
    throw new ApiError(401, 'UNAUTHORIZED', '请先登录')
  }
  if (!response.ok) {
    let message = '请求失败，请稍后重试'
    let code = `HTTP_${response.status}`
    try {
      const body = await response.json()
      if (body?.error?.message) {
        message = body.error.message
      }
      if (body?.error?.code) {
        code = body.error.code
      }
    } catch {
      // Keep the generic message when the response is not JSON.
    }
    throw new ApiError(response.status, code, message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

export function listTodos(params: {
  q?: string
  status?: string
  priority?: string
  page?: number
  size?: number
}): Promise<TodoListResponse> {
  const search = new URLSearchParams()
  if (params.q) search.set('q', params.q)
  if (params.status && params.status !== 'ALL') search.set('status', params.status)
  if (params.priority) search.set('priority', params.priority)
  if (params.page) search.set('page', String(params.page))
  if (params.size) search.set('size', String(params.size))
  const query = search.toString()
  return request(`/todos${query ? `?${query}` : ''}`)
}

export function createTodo(payload: TodoPayload): Promise<Todo> {
  return request('/todos', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function updateTodo(name: string, payload: TodoPayload): Promise<Todo> {
  return request(`/todos/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  })
}

export function deleteTodo(name: string): Promise<void> {
  return request(`/todos/${encodeURIComponent(name)}?confirm=true`, {
    method: 'DELETE'
  })
}

export function listProjects(): Promise<Project[]> {
  return request('/projects')
}

export function createProject(payload: ProjectPayload): Promise<Project> {
  return request('/projects', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function updateProject(name: string, payload: ProjectPayload): Promise<Project> {
  return request(`/projects/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  })
}

export function renameModule(name: string, from: string, to: string): Promise<Project> {
  return request(`/projects/${encodeURIComponent(name)}/modules/rename`, {
    method: 'PUT',
    body: JSON.stringify({ from, to })
  })
}

export function deleteProject(name: string): Promise<void> {
  return request(`/projects/${encodeURIComponent(name)}?confirm=true`, {
    method: 'DELETE'
  })
}

export function listCommands(): Promise<QuickCmd[]> {
  return request('/commands')
}

export function createCommand(payload: QuickCmdPayload): Promise<QuickCmd> {
  return request('/commands', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function updateCommand(name: string, payload: QuickCmdPayload): Promise<QuickCmd> {
  return request(`/commands/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  })
}

export function deleteCommand(name: string): Promise<void> {
  return request(`/commands/${encodeURIComponent(name)}?confirm=true`, {
    method: 'DELETE'
  })
}

export function listNotes(): Promise<NoteSummary[]> {
  return request('/notes')
}

export function getNote(name: string): Promise<Note> {
  return request(`/notes/${encodeURIComponent(name)}`)
}

export function createNote(payload: NotePayload): Promise<Note> {
  return request('/notes', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function updateNote(name: string, payload: NotePayload): Promise<Note> {
  return request(`/notes/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  })
}

export function deleteNote(name: string): Promise<void> {
  return request(`/notes/${encodeURIComponent(name)}?confirm=true`, {
    method: 'DELETE'
  })
}

export function uploadImage(file: File): Promise<{ url: string }> {
  const form = new FormData()
  form.append('file', file)
  return request('/images', { method: 'POST', body: form })
}

export function searchWorkspace(keyword: string): Promise<SearchResponse> {
  const query = new URLSearchParams({ q: keyword })
  return request(`/search?${query.toString()}`)
}

export function exportBackup(): Promise<BackupData> {
  return request('/backup')
}

export function importBackup(payload: BackupData, replace: boolean): Promise<BackupCounts> {
  return request(`/backup?replace=${replace ? 'true' : 'false'}`, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}
