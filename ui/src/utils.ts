import { ApiError } from './api'
import type { Priority } from './types'

const LABELS: Record<Priority, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低'
}

const CLASSES: Record<Priority, string> = {
  HIGH: 'text-[#FF3B30]',
  MEDIUM: 'text-[#FF9500]',
  LOW: 'text-[#8E8E93] dark:text-[#98989D]'
}

export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '请求失败，请稍后重试'
}

export function priorityLabel(priority: string): string {
  return LABELS[priority as Priority] ?? priority
}

export function priorityClass(priority: string): string {
  return CLASSES[priority as Priority] ?? ''
}

export function todayString(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

export function formatDateTime(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatDate(value: string | null): string {
  return formatDateTime(value).slice(0, 10)
}

export function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

export function safeHref(url: string): string {
  return /^https?:\/\//i.test(url) || url.startsWith('/') ? url : '#'
}

export function excerpt(markdown: string, max = 140): string {
  const text = (markdown || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[#>*_`~]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > max ? `${text.slice(0, max)}…` : text
}
