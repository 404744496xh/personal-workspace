import type { ProjectStatus } from './projectStatus'

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface Project {
  name: string
  title: string
  modules: string[]
  status: ProjectStatus
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
  projectStatus: ProjectStatus | null
  reporter: string | null
  dueDate: string | null
  completed: boolean
  completedAt: string | null
  dismissed: boolean
  dismissedAt: string | null
  pinned: boolean
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
  reporter?: string | null
  dueDate: string | null
  completed: boolean
  dismissed?: boolean
  pinned?: boolean
  createdAt?: string | null
}

export interface ProjectPayload {
  title: string
  modules: string[]
  status?: ProjectStatus
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
  summary: string | null
  tags: string[]
  project: string | null
  module: string | null
  todos: string[]
  updatedAt: string
  post: string | null
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
  post?: string | null
}

export interface NotePayload {
  title: string
  content: string
  summary?: string | null
  tags?: string[]
  project?: string | null
  module?: string | null
  todos?: string[]
}

export interface NoteTemplate {
  name: string
  content: string
}

export interface NoteTemplateResponse {
  templates: NoteTemplate[] | null
}

export interface ReportPayload {
  view: 'month' | 'week'
  start: string
  end: string
}

export interface ReportResult {
  title: string
  content: string
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

export interface AiImportRow {
  project: string
  module: string
  title: string
  detail?: string | null
  createdAt?: string | null
  reporter?: string | null
}

export interface AiImportResponse {
  rows: AiImportRow[]
}

export interface BackupCounts {
  todos: number
  projects: number
  commands: number
  notes: number
}
