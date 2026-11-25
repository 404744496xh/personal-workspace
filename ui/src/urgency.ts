import type { Todo } from './types'
import { addDays, formatDate, todayString } from './utils'

const DUE_SOON_DAYS = 3
const STALE_DAYS = 7

export function isUrgent(todo: Todo, today = todayString()): boolean {
  if (todo.completed || todo.dismissed) return false
  if (todo.dueDate) return todo.dueDate <= addDays(today, DUE_SOON_DAYS)
  return Boolean(todo.createdAt
    && formatDate(todo.createdAt) < addDays(today, -STALE_DAYS))
}
