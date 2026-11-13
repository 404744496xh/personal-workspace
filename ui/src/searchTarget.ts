let pending = ''

export function setSearchTarget(value: string): void {
  pending = value
}

export function takeSearchTarget(): string {
  const value = pending
  pending = ''
  return value
}

export interface NotesTarget {
  project?: string
  module?: string
  todo?: string
  noteName?: string
  create?: boolean
  includeProjectTodos?: boolean
}

let pendingNotes: NotesTarget | null = null

export function setNotesTarget(value: NotesTarget): void {
  pendingNotes = value
}

export function takeNotesTarget(): NotesTarget | null {
  const value = pendingNotes
  pendingNotes = null
  return value
}

export interface TodosTarget {
  project?: string
  module?: string
  status?: 'ALL' | 'OPEN' | 'DISMISSED'
  stale?: boolean
}

let pendingTodos: TodosTarget | null = null

export function setTodosTarget(value: TodosTarget): void {
  pendingTodos = value
}

export function takeTodosTarget(): TodosTarget | null {
  const value = pendingTodos
  pendingTodos = null
  return value
}
