import type { Recurrence } from './types'

export const RECURRENCE_OPTIONS: { value: Recurrence; label: string }[] = [
  { value: 'NONE', label: '不重复' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' }
]

export function recurrenceLabel(value: Recurrence | null | undefined): string {
  return RECURRENCE_OPTIONS.find((option) => option.value === value)?.label ?? ''
}