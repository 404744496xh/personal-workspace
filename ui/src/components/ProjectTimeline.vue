<script setup lang="ts">
import { computed } from 'vue'
import type { ProjectStatusChange } from '../types'
import { projectStatusMeta } from '../projectStatus'
import { formatDate } from '../utils'

const props = defineProps<{
  entries: ProjectStatusChange[]
}>()

interface Segment {
  status: string
  label: string
  dot: string
  text: string
  start: string
  title: string
  grow: number
  compact: boolean
}

function shortDate(value: string | null): string {
  const full = formatDate(value)
  if (!full) return ''
  const year = new Date(value as string).getFullYear()
  return year === new Date().getFullYear() ? full.slice(5) : full.slice(2)
}

function durationOf(startedAt: string | null, endedAt: string | null): string {
  if (!startedAt) return ''
  const start = new Date(startedAt).getTime()
  const end = endedAt ? new Date(endedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) return ''
  const days = Math.max(1, Math.ceil((end - start) / 86_400_000))
  return `${days} 天`
}

const DAY = 86_400_000

function daysOf(startedAt: string | null, endedAt: string | null): number {
  if (!startedAt) return 1
  const start = new Date(startedAt).getTime()
  const end = endedAt ? new Date(endedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) return 1
  return Math.max(1, Math.ceil((end - start) / DAY))
}

const segments = computed<Segment[]>(() => {
  const list = (props.entries ?? []).filter((entry) => entry?.status)
  const prepared = list.map((entry, index) => {
    const meta = projectStatusMeta(entry.status)
    const next = list[index + 1]
    const fullStart = formatDate(entry.startedAt) || '时间未知'
    const fullEnd = next ? formatDate(next.startedAt) : '至今'
    const days = daysOf(entry.startedAt, next?.startedAt ?? null)
    const duration = durationOf(entry.startedAt, next?.startedAt ?? null)
    return {
      status: entry.status,
      label: meta.label,
      dot: meta.dot,
      text: meta.text,
      start: shortDate(entry.startedAt),
      days,
      title: `${meta.label}：${fullStart} 至 ${fullEnd}${duration ? ` · ${duration}` : ''}`
    }
  })
  const total = prepared.reduce((sum, segment) => sum + segment.days, 0) || 1
  return prepared.map((segment) => ({
    ...segment,
    grow: segment.days,
    // 极短状态仍按比例占位，但放不下日期/标签时只留色点，信息放在 title 里
    compact: segment.days / total < 0.08
  }))
})
</script>

<template>
  <ol
    v-if="segments.length"
    class="ws-project-timeline flex w-full items-start"
    aria-label="项目状态时间线"
  >
    <li
      v-for="(segment, index) in segments"
      :key="`${segment.status}-${index}`"
      class="min-w-0 overflow-hidden"
      :style="{ flexGrow: segment.grow, flexBasis: '0%', minWidth: '10px' }"
      :title="segment.title"
    >
      <div
        class="flex items-center justify-between gap-1 text-[10px] leading-4 tabular-nums text-[#6e6e73] dark:text-[#98989d]"
        :class="{ invisible: segment.compact }"
      >
        <span class="truncate">{{ segment.start }}</span>
        <span v-if="index === segments.length - 1" class="shrink-0">至今</span>
      </div>
      <div class="mt-1 flex items-center">
        <i class="h-2 w-2 shrink-0 rounded-full" :class="segment.dot" aria-hidden="true"></i>
        <span class="h-[2px] flex-1 opacity-40" :class="segment.dot" aria-hidden="true"></span>
        <i
          v-if="index === segments.length - 1"
          class="h-2 w-2 shrink-0 rounded-full border border-black/15 bg-white dark:border-white/25 dark:bg-[#1c1c1e]"
          aria-hidden="true"
        ></i>
      </div>
      <span
        class="mt-1 block truncate text-[10px] font-medium leading-4"
        :class="[segment.text, { invisible: segment.compact }]"
      >{{ segment.label }}</span>
    </li>
  </ol>
</template>
