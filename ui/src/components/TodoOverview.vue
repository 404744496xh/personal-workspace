<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Project, Todo } from '../types'
import type { TodosTarget } from '../searchTarget'
import { formatDate, todayString } from '../utils'
import Button from './ui/Button.vue'

const props = defineProps<{ todos: Todo[]; projects: Project[] }>()
const emit = defineEmits<{
  openTodos: [target: TodosTarget]
  openProjects: [title?: string]
}>()

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const today = todayString()
type ViewMode = 'month' | 'week'

const view = ref<ViewMode>('month')
const cursor = ref(startOfMonth(new Date()))
const weekCursor = ref(startOfWeek(new Date()))

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

function startOfWeek(date: Date): Date {
  const start = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7))
  return start
}

function shiftDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function keyOf(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function shiftPeriod(delta: number): void {
  if (view.value === 'month') {
    cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + delta, 1)
  } else {
    weekCursor.value = shiftDays(weekCursor.value, delta * 7)
  }
}

function resetPeriod(): void {
  if (view.value === 'month') cursor.value = startOfMonth(new Date())
  else weekCursor.value = startOfWeek(new Date())
}

const monthLabel = computed(() =>
  `${cursor.value.getFullYear()} 年 ${cursor.value.getMonth() + 1} 月`)

const weekLabel = computed(() => {
  const start = weekCursor.value
  const end = shiftDays(start, 6)
  const tail = end.getFullYear() === start.getFullYear()
    ? `${end.getMonth() + 1} 月 ${end.getDate()} 日`
    : `${end.getFullYear()} 年 ${end.getMonth() + 1} 月 ${end.getDate()} 日`
  return `${start.getFullYear()} 年 ${start.getMonth() + 1} 月 ${start.getDate()} 日 – ${tail}`
})

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

function cellOf(date: Date): DayCell {
  const key = keyOf(date)
  const counts = dayCounts.value.get(key) ?? { created: 0, completed: 0 }
  return { key, day: date.getDate(), ...counts, isToday: key === today }
}

const cells = computed<(DayCell | null)[]>(() => {
  const year = cursor.value.getFullYear()
  const month = cursor.value.getMonth()
  const offset = (new Date(year, month, 1).getDay() + 6) % 7
  const days = new Date(year, month + 1, 0).getDate()
  const list: (DayCell | null)[] = []
  for (let index = 0; index < offset; index += 1) list.push(null)
  for (let day = 1; day <= days; day += 1) {
    list.push(cellOf(new Date(year, month, day)))
  }
  while (list.length % 7 !== 0) list.push(null)
  return list
})

const weekDays = computed<DayCell[]>(() => {
  const start = weekCursor.value
  return Array.from({ length: 7 }, (_, index) => cellOf(shiftDays(start, index)))
})

function sumOf(days: (DayCell | null)[]): DayCount {
  let created = 0
  let completed = 0
  for (const day of days) {
    if (!day) continue
    created += day.created
    completed += day.completed
  }
  return { created, completed }
}

const summary = computed(() =>
  view.value === 'month' ? sumOf(cells.value) : sumOf(weekDays.value))
const summaryLabel = computed(() => (view.value === 'month' ? '本月' : '本周'))

const openCount = computed(() =>
  props.todos.filter((todo) => !todo.completed && !todo.dismissed).length)
const todayNew = computed(() =>
  props.todos.filter((todo) => !todo.completed && !todo.dismissed
    && todo.createdAt && formatDate(todo.createdAt) === today).length)
const staleCount = computed(() => {
  const cutoff = new Date()
  cutoff.setDate(cutoff.getDate() - 7)
  const key = keyOf(cutoff)
  return props.todos.filter((todo) => !todo.completed && !todo.dismissed
    && todo.createdAt && formatDate(todo.createdAt) < key).length
})

const activeProjects = computed(() =>
  props.projects.filter((project) => project.progress < 100))
const doneProjects = computed(() =>
  props.projects.filter((project) => project.progress >= 100))
const topProjects = computed(() =>
  [...activeProjects.value].sort((a, b) => b.progress - a.progress).slice(0, 3))

const trendDays = computed(() => {
  const list: { key: string; label: string; created: number; completed: number }[] = []
  for (let offset = 6; offset >= 0; offset -= 1) {
    const date = shiftDays(new Date(), -offset)
    const key = keyOf(date)
    const counts = dayCounts.value.get(key) ?? { created: 0, completed: 0 }
    list.push({ key, label: `${date.getMonth() + 1}/${date.getDate()}`, ...counts })
  }
  return list
})
const trendMax = computed(() => Math.max(1,
  ...trendDays.value.flatMap((day) => [day.created, day.completed])))

function barHeight(value: number): string {
  if (!value) return '2px'
  return `${Math.max(4, Math.round((value / trendMax.value) * 36))}px`
}

function heatClass(completed: number): string {
  if (completed <= 0) return ''
  if (completed === 1) return 'bg-[#34C759]/[0.07]'
  if (completed === 2) return 'bg-[#34C759]/[0.13]'
  if (completed <= 4) return 'bg-[#34C759]/[0.20]'
  return 'bg-[#34C759]/[0.28]'
}

function openStaleTodos(): void {
  emit('openTodos', { status: 'OPEN', stale: true })
}
</script>

<template>
  <div class="ws-overview mb-4 flex flex-col gap-3">
    <div class="grid items-start gap-3 lg:grid-cols-[minmax(0,1fr)_260px]">
      <div
        class="ws-calendar rounded-[18px] border border-white/20 bg-white/[0.72] p-4 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
      >
        <header class="mb-2 flex flex-wrap items-center gap-1">
          <h2 class="text-[13px] font-semibold tracking-[-0.01em]">
            {{ view === 'month' ? monthLabel : weekLabel }}
          </h2>
          <div class="ml-auto flex items-center gap-1">
            <div class="flex rounded-full bg-black/[0.04] p-0.5 dark:bg-white/[0.08]">
              <button
                type="button"
                class="min-h-8 rounded-full px-2.5 text-[11px] font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
                :class="view === 'month'
                  ? 'bg-white text-[#1d1d1f] shadow-[0_1px_2px_rgba(0,0,0,0.08)] dark:bg-white/[0.14] dark:text-[#f5f5f7]'
                  : 'text-[#6e6e73] hover:text-[#1d1d1f] dark:text-[#98989d] dark:hover:text-[#f5f5f7]'"
                aria-label="按月查看"
                @click="view = 'month'"
              >月</button>
              <button
                type="button"
                class="min-h-8 rounded-full px-2.5 text-[11px] font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
                :class="view === 'week'
                  ? 'bg-white text-[#1d1d1f] shadow-[0_1px_2px_rgba(0,0,0,0.08)] dark:bg-white/[0.14] dark:text-[#f5f5f7]'
                  : 'text-[#6e6e73] hover:text-[#1d1d1f] dark:text-[#98989d] dark:hover:text-[#f5f5f7]'"
                aria-label="按周查看"
                @click="view = 'week'"
              >周</button>
            </div>
            <Button variant="ghost" size="icon" aria-label="上一页" title="上一页" @click="shiftPeriod(-1)">
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M9.5 4L5.5 8l4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </Button>
            <Button variant="ghost" size="sm" class="px-2" @click="resetPeriod">今天</Button>
            <Button variant="ghost" size="icon" aria-label="下一页" title="下一页" @click="shiftPeriod(1)">
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M6.5 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </Button>
          </div>
        </header>

        <div class="grid grid-cols-7 gap-1 text-center text-[10px] text-[#8e8e93] dark:text-[#98989d]">
          <span v-for="weekday in WEEKDAYS" :key="weekday" class="py-0.5">{{ weekday }}</span>
        </div>

        <div v-if="view === 'month'" class="mt-1 grid grid-cols-7 gap-1">
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

        <div v-else class="ws-week mt-1 grid grid-cols-7 gap-1">
          <div
            v-for="cell in weekDays"
            :key="cell.key"
            class="ws-calendar-day flex h-16 flex-col items-center justify-center rounded-lg px-1 text-center leading-none tabular-nums"
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
              class="text-[14px] font-semibold"
              :class="cell.isToday ? 'text-[#007AFF] dark:text-[#64B5FF]' : 'text-[#1d1d1f]/80 dark:text-[#f5f5f7]/70'"
            >{{ cell.day }}</span>
            <span class="mt-1 flex flex-col items-center gap-0.5 text-[10px]">
              <span v-if="cell.created" class="text-[#007AFF] dark:text-[#64B5FF]">+{{ cell.created }}</span>
              <span v-if="cell.completed" class="text-[#34C759] dark:text-[#30D158]">✓{{ cell.completed }}</span>
            </span>
          </div>
        </div>

        <footer class="mt-2 flex items-center gap-3 text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
          <span>{{ summaryLabel }}新增 <b class="font-semibold text-[#007AFF] dark:text-[#64B5FF]">{{ summary.created }}</b></span>
          <span>完成 <b class="font-semibold text-[#34C759] dark:text-[#30D158]">{{ summary.completed }}</b></span>
          <span class="ml-auto">净 <b class="font-semibold">{{ summary.completed - summary.created }}</b></span>
        </footer>
      </div>

      <div class="grid grid-cols-2 gap-3 lg:grid-cols-1">
        <button
          type="button"
          class="ws-overview-stat ws-overview-open flex flex-col justify-center rounded-[18px] border border-white/20 bg-white/[0.72] px-4 py-3 text-left shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] transition-colors duration-150 hover:bg-white/[0.92] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:border-white/[0.08] dark:bg-[#1c1c1e]/70 dark:hover:bg-[#232325]/80"
          aria-label="查看未完成待办"
          title="查看未完成待办"
          @click="emit('openTodos', { status: 'OPEN' })"
        >
          <p class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">未完成</p>
          <p class="mt-0.5 text-[28px] font-semibold leading-none tabular-nums">{{ openCount }}</p>
          <p class="mt-1 text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
            今日 <span class="text-[#007AFF] dark:text-[#64B5FF]">+{{ todayNew }}</span>
          </p>
        </button>
        <button
          type="button"
          class="ws-overview-stat ws-overview-stale-card flex flex-col justify-center rounded-[18px] border border-white/20 bg-white/[0.72] px-4 py-3 text-left shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] transition-colors duration-150 hover:bg-white/[0.92] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:border-white/[0.08] dark:bg-[#1c1c1e]/70 dark:hover:bg-[#232325]/80"
          aria-label="查看创建超过 7 天的待办"
          title="查看创建超过 7 天的待办"
          @click="openStaleTodos"
        >
          <p class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">快逾期</p>
          <p class="ws-overview-stale mt-0.5 text-[28px] font-semibold leading-none tabular-nums"
            :class="staleCount > 0 ? 'text-[#FF9500]' : ''"
          >{{ staleCount }}</p>
          <p class="mt-1 text-[11px] text-[#6e6e73] dark:text-[#98989d]">创建超过 7 天</p>
        </button>
        <div
          class="ws-overview-projects col-span-2 rounded-[18px] border border-white/20 bg-white/[0.72] px-4 py-3 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70 lg:col-span-1"
        >
          <div class="flex items-baseline gap-2">
            <p class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">项目</p>
            <p class="ml-auto text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
              进行中 {{ activeProjects.length }} · 已完结 {{ doneProjects.length }}
            </p>
          </div>
          <ul v-if="topProjects.length" class="mt-2 flex flex-col gap-2">
            <li v-for="project in topProjects" :key="project.name">
              <button
                type="button"
                class="ws-overview-project w-full rounded-md text-left transition-opacity duration-150 hover:opacity-75 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
                :aria-label="`查看项目 ${project.title}`"
                :title="`查看项目 ${project.title}`"
                @click="emit('openProjects', project.title)"
              >
                <span class="flex items-center gap-2 text-[11px]">
                  <span class="truncate text-[#1d1d1f]/80 dark:text-[#f5f5f7]/70">{{ project.title }}</span>
                  <span class="ml-auto shrink-0 tabular-nums text-[#6e6e73] dark:text-[#98989d]">{{ project.progress }}%</span>
                </span>
                <span class="mt-1 block h-1 overflow-hidden rounded-full bg-black/[0.06] dark:bg-white/[0.10]">
                  <span
                    class="block h-full rounded-full bg-[#007AFF] dark:bg-[#0A84FF]"
                    :style="{ width: `${project.progress}%` }"
                  ></span>
                </span>
              </button>
            </li>
          </ul>
          <p v-else class="mt-2 text-[11px] text-[#6e6e73] dark:text-[#98989d]">还没有进行中的项目</p>
        </div>
      </div>
    </div>

    <div
      class="ws-trend rounded-[18px] border border-white/20 bg-white/[0.72] p-4 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <header class="flex items-center gap-2">
        <h2 class="text-[13px] font-semibold tracking-[-0.01em]">近 7 日</h2>
        <div class="ml-auto flex items-center gap-3 text-[11px] text-[#6e6e73] dark:text-[#98989d]">
          <span class="flex items-center gap-1">
            <i class="h-1.5 w-1.5 rounded-full bg-[#007AFF] dark:bg-[#64B5FF]" aria-hidden="true"></i>新增
          </span>
          <span class="flex items-center gap-1">
            <i class="h-1.5 w-1.5 rounded-full bg-[#34C759] dark:bg-[#30D158]" aria-hidden="true"></i>完成
          </span>
        </div>
      </header>
      <div class="mt-3 flex items-end gap-2">
        <div
          v-for="day in trendDays"
          :key="day.key"
          class="ws-trend-day flex flex-1 flex-col items-center gap-1"
          :data-date="day.key"
          :data-created="day.created"
          :data-completed="day.completed"
          :title="`${day.key} · 新增 ${day.created} · 完成 ${day.completed}`"
        >
          <div class="flex h-10 items-end gap-1">
            <span
              class="w-1.5 rounded-full bg-[#007AFF]/70 dark:bg-[#64B5FF]/70"
              :class="day.created ? '' : 'opacity-30'"
              :style="{ height: barHeight(day.created) }"
            ></span>
            <span
              class="w-1.5 rounded-full bg-[#34C759]/80 dark:bg-[#30D158]/80"
              :class="day.completed ? '' : 'opacity-30'"
              :style="{ height: barHeight(day.completed) }"
            ></span>
          </div>
          <span class="text-[9px] tabular-nums text-[#8e8e93] dark:text-[#98989d]">{{ day.label }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
