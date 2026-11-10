export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface Project {
  name: string
  title: string
  modules: string[]
  progress: number
  note: string | null
  links: string[]
  tags: string[]
}

export interface Todo {
  name: string
  title: string
  detail: string
  priority: Priority
  project: string | null
  module: string | null
  dueDate: string | null
  completed: boolean
  completedAt: string | null
  dismissed: boolean
  dismissedAt: string | null
  createdAt: string | null
}

export interface TodoListResponse {
  items: Todo[]
  total: number
  page: number
  size: number
}

export interface TodoPayload {
  title: string
  detail: string
  priority: Priority
  project: string | null
  module: string | null
  dueDate: string | null
  completed: boolean
  dismissed?: boolean
}

export interface ProjectPayload {
  title: string
  modules: string[]
  progress?: number
  note?: string | null
  links?: string[]
  tags?: string[]
}

export interface QuickCmd {
  name: string
  title: string
  command: string
  note: string | null
  category: string | null
  tags: string[]
}

export interface QuickCmdPayload {
  title: string
  command: string
  note?: string | null
  category?: string | null
  tags?: string[]
}

export interface Note {
  name: string
  title: string
  content: string
  tags: string[]
  project: string | null
  module: string | null
  todos: string[]
  updatedAt: string
}

export interface NoteSummary {
  name: string
  title: string
  excerpt: string
  tags: string[]
  project: string | null
  module: string | null
  todos: string[]
  updatedAt: string
}

export interface NotePayload {
  title: string
  content: string
  tags?: string[]
  project?: string | null
  module?: string | null
  todos?: string[]
}

export type SearchType = 'todo' | 'project' | 'command' | 'note'

export interface SearchHit {
  type: SearchType
  name: string
  title: string
  meta: string
}

export interface SearchResponse {
  q: string
  hits: SearchHit[]
}

export interface BackupTodo {
  name: string | null
  title: string
  detail: string | null
  priority: Priority | null
  project: string | null
  module: string | null
  dueDate: string | null
  completed: boolean | null
  completedAt: string | null
  dismissed: boolean | null
  dismissedAt: string | null
}

export interface BackupProject {
  name: string | null
  title: string
  modules: string[] | null
  progress: number | null
  note: string | null
  links: string[] | null
  tags: string[] | null
}

export interface BackupCommand {
  title: string
  command: string
  note: string | null
  category: string | null
  tags: string[] | null
}

export interface BackupNote {
  title: string
  content: string | null
  tags: string[] | null
  project: string | null
  module: string | null
  todos: string[] | null
  updatedAt: string | null
}

export interface BackupData {
  version: string
  exportedAt: string | null
  todos: BackupTodo[]
  projects: BackupProject[]
  commands: BackupCommand[]
  notes: BackupNote[]
}

export interface BackupCounts {
  todos: number
  projects: number
  commands: number
  notes: number
}
