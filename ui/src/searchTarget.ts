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
