<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Todo } from '../types'
import { formatDate, todayString } from '../utils'
import Button from './ui/Button.vue'

const props = defineProps<{ todos: Todo[] }>()

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const today = todayString()
const cursor = ref(startOfMonth(new Date()))

interface DayCount {
  created: number
  completed: number
}

interface DayCell {
  key: string
  day: number
  created: number
  completed: number
  isToday: boolean
}

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function shiftMonth(delta: number): void {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + delta, 1)
}

function resetMonth(): void {
  cursor.value = startOfMonth(new Date())
}

const monthLabel = computed(() =>
  `${cursor.value.getFullYear()} 年 ${cursor.value.getMonth() + 1} 月`)

const dayCounts = computed(() => {
  const counts = new Map<string, DayCount>()
  const bump = (key: string, field: keyof DayCount): void => {
    const entry = counts.get(key) ?? { created: 0, completed: 0 }
    entry[field] += 1
    counts.set(key, entry)
  }
  for (const todo of props.todos) {
    if (todo.createdAt) bump(formatDate(todo.createdAt), 'created')
    if (todo.completedAt) bump(formatDate(todo.completedAt), 'completed')
  }
  return counts
})

const cells = computed<(DayCell | null)[]>(() => {
  const year = cursor.value.getFullYear()
  const month = cursor.value.getMonth()
  const first = new Date(year, month, 1)
  const offset = (first.getDay() + 6) % 7
  const days = new Date(year, month + 1, 0).getDate()
  const list: (DayCell | null)[] = []
  for (let index = 0; index < offset; index += 1) list.push(null)
  for (let day = 1; day <= days; day += 1) {
    const key = `${year}-${pad(month + 1)}-${pad(day)}`
    const counts = dayCounts.value.get(key) ?? { created: 0, completed: 0 }
    list.push({ key, day, ...counts, isToday: key === today })
  }
  while (list.length % 7 !== 0) list.push(null)
  return list
})

const monthSummary = computed(() => {
  let created = 0
  let completed = 0
  for (const cell of cells.value) {
    if (!cell) continue
    created += cell.created
    completed += cell.completed
  }
  return { created, completed }
})

const openCount = computed(() =>
  props.todos.filter((todo) => !todo.completed && !todo.dismissed).length)
const todayNew = computed(() =>
  props.todos.filter((todo) => !todo.completed && !todo.dismissed
    && todo.createdAt && formatDate(todo.createdAt) === today).length)
const staleCount = computed(() => {
  const cutoff = new Date()
  cutoff.setDate(cutoff.getDate() - 7)
  const key = `${cutoff.getFullYear()}-${pad(cutoff.getMonth() + 1)}-${pad(cutoff.getDate())}`
  return props.todos.filter((todo) => !todo.completed && !todo.dismissed
    && todo.createdAt && formatDate(todo.createdAt) < key).length
})

function heatClass(completed: number): string {
  if (completed <= 0) return ''
  if (completed === 1) return 'bg-[#34C759]/[0.07]'
  if (completed === 2) return 'bg-[#34C759]/[0.13]'
  if (completed <= 4) return 'bg-[#34C759]/[0.20]'
  return 'bg-[#34C759]/[0.28]'
}
</script>

<template>
  <section class="ws-overview mb-4 grid gap-3 lg:grid-cols-[minmax(0,1fr)_240px]">
    <div
      class="ws-calendar rounded-[18px] border border-white/20 bg-white/[0.72] p-4 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <header class="mb-2 flex items-center gap-1">
        <h2 class="text-[13px] font-semibold tracking-[-0.01em]">{{ monthLabel }}</h2>
        <div class="ml-auto flex items-center gap-1">
          <Button variant="ghost" size="icon" aria-label="上个月" title="上个月" @click="shiftMonth(-1)">
            <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M9.5 4L5.5 8l4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </Button>
          <Button variant="ghost" size="sm" class="px-2" @click="resetMonth">今天</Button>
          <Button variant="ghost" size="icon" aria-label="下个月" title="下个月" @click="shiftMonth(1)">
            <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M6.5 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </Button>
        </div>
      </header>

      <div class="grid grid-cols-7 gap-1 text-center text-[10px] text-[#8e8e93] dark:text-[#98989d]">
        <span v-for="weekday in WEEKDAYS" :key="weekday" class="py-0.5">{{ weekday }}</span>
      </div>

      <div class="mt-1 grid grid-cols-7 gap-1">
        <template v-for="(cell, index) in cells" :key="cell?.key ?? `blank-${index}`">
          <div v-if="!cell" class="h-7 rounded-md"></div>
          <div
            v-else
            class="ws-calendar-day flex h-7 flex-col justify-center rounded-md px-1 text-[9px] leading-none tabular-nums text-[#6e6e73] dark:text-[#98989d]"
            :class="[
              heatClass(cell.completed),
              cell.isToday ? 'ring-1 ring-[#007AFF]/45 dark:ring-[#64B5FF]/45' : ''
            ]"
            :data-date="cell.key"
            :data-created="cell.created"
            :data-completed="cell.completed"
            :title="`${cell.key} · 新增 ${cell.created} · 完成 ${cell.completed}`"
          >
            <span
              class="text-[10px] font-medium"
              :class="cell.isToday ? 'text-[#007AFF] dark:text-[#64B5FF]' : 'text-[#1d1d1f]/70 dark:text-[#f5f5f7]/60'"
            >{{ cell.day }}</span>
            <span class="mt-0.5 flex items-center gap-1">
              <span v-if="cell.created" class="text-[#007AFF] dark:text-[#64B5FF]">+{{ cell.created }}</span>
              <span v-if="cell.completed" class="text-[#34C759] dark:text-[#30D158]">✓{{ cell.completed }}</span>
            </span>
          </div>
        </template>
      </div>

      <footer class="mt-2 flex items-center gap-3 text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
        <span>本月新增 <b class="font-semibold text-[#007AFF] dark:text-[#64B5FF]">{{ monthSummary.created }}</b></span>
        <span>完成 <b class="font-semibold text-[#34C759] dark:text-[#30D158]">{{ monthSummary.completed }}</b></span>
        <span class="ml-auto">净 <b class="font-semibold">{{ monthSummary.completed - monthSummary.created }}</b></span>
      </footer>
    </div>

    <div class="grid grid-cols-2 gap-3 lg:grid-cols-1">
      <div
        class="ws-overview-stat flex flex-col justify-center rounded-[18px] border border-white/20 bg-white/[0.72] px-4 py-3 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
      >
        <p class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">未完成</p>
        <p class="mt-0.5 text-[28px] font-semibold leading-none tabular-nums">{{ openCount }}</p>
        <p class="mt-1 text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
          今日 <span class="text-[#007AFF] dark:text-[#64B5FF]">+{{ todayNew }}</span>
        </p>
      </div>
      <div
        class="ws-overview-stat flex flex-col justify-center rounded-[18px] border border-white/20 bg-white/[0.72] px-4 py-3 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
      >
        <p class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">快逾期</p>
        <p class="ws-overview-stale mt-0.5 text-[28px] font-semibold leading-none tabular-nums"
          :class="staleCount > 0 ? 'text-[#FF9500]' : ''"
        >{{ staleCount }}</p>
        <p class="mt-1 text-[11px] text-[#6e6e73] dark:text-[#98989d]">创建超过 7 天</p>
      </div>
    </div>
  </section>
</template>
