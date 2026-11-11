const pad = (value: number): string => String(value).padStart(2, '0')

export interface ParsedDateTime {
  iso: string | null
  error: string
}

// 接受 2026-11-10、2026/11/10 16:46、2026-11-10T16:46、带时区的 ISO；本地时间按浏览器时区换算
export function parseDateTimeInput(value: string): ParsedDateTime {
  const text = value.trim()
  if (!text) return { iso: null, error: '' }
  if (/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(text)) {
    const date = new Date(text)
    if (!Number.isNaN(date.getTime())) return { iso: date.toISOString(), error: '' }
  }
  const match = text.match(
    /^(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$/
  )
  if (!match) return { iso: null, error: '创建时间格式应为 2026-11-10 16:46' }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const hour = match[4] === undefined ? 0 : Number(match[4])
  const minute = match[5] === undefined ? 0 : Number(match[5])
  const second = match[6] === undefined ? 0 : Number(match[6])
  const date = new Date(year, month - 1, day, hour, minute, second)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day
    || date.getHours() !== hour || date.getMinutes() !== minute || date.getSeconds() !== second) {
    return { iso: null, error: '创建时间不是一个有效日期' }
  }
  return { iso: date.toISOString(), error: '' }
}

export function toDateTimeLocal(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function localDateTimeToIso(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
