<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, listProjects, listTodos, updateTodo } from '../api'
import { readCache, writeCache } from '../cache'
import type { Project, Todo } from '../types'
import type { NotesTarget, TodosTarget } from '../searchTarget'
import { copyText, formatDate, messageOf, todayString } from '../utils'
import { useToast } from '../lib/toast'
import TodoOverview from '../components/TodoOverview.vue'
import Toast from '../components/Toast.vue'
import ContextMenu from '../components/ContextMenu.vue'
import Button from '../components/ui/Button.vue'
import { useContextMenu } from '../contextMenu'

const PAGE_SIZE = 500
const ACTIVE_SIZE = 8
const cached = readCache<{ items: Todo[]; total: number }>('todos')
const cachedProjects = readCache<Project[]>('projects')
const emit = defineEmits<{
  unauthorized: []
  openTodos: [target: TodosTarget]
  openProjects: [title?: string]
  openNotes: [target: NotesTarget]
}>()

const items = ref<Todo[]>(cached?.items ?? [])
const projects = ref<Project[]>(cachedProjects ?? [])
const total = ref(cached?.total ?? 0)
const loading = ref(!cached)
const error = ref('')
const busy = ref<string | null>(null)
const { toast, show } = useToast()
const { menu: contextMenu, open: openContextMenu } = useContextMenu()

const openItems = computed(() => items.value
  .filter((todo) => !todo.completed && !todo.dismissed)
  .sort((a, b) => Number(b.pinned) - Number(a.pinned)
    || (b.createdAt ?? '').localeCompare(a.createdAt ?? '')))
const inProgress = computed(() => openItems.value.slice(0, ACTIVE_SIZE))

function addDays(key: string, days: number): string {
  const date = new Date(`${key}T00:00:00`)
  date.setDate(date.getDate() + days)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

const today = todayString()
const dueSoonLimit = addDays(today, 3)
const dueGroups = computed(() => {
  const overdue: Todo[] = []
  const dueToday: Todo[] = []
  const soon: Todo[] = []
  for (const todo of openItems.value) {
    if (!todo.dueDate) continue
    if (todo.dueDate < today) overdue.push(todo)
    else if (todo.dueDate === today) dueToday.push(todo)
    else if (todo.dueDate <= dueSoonLimit) soon.push(todo)
  }
  const byDate = (a: Todo, b: Todo): number => (a.dueDate ?? '').localeCompare(b.dueDate ?? '')
  overdue.sort(byDate)
  soon.sort(byDate)
  return { overdue, dueToday, soon }
})
const dueTotal = computed(() =>
  dueGroups.value.overdue.length + dueGroups.value.dueToday.length + dueGroups.value.soon.length)
const nextDue = computed(() =>
  dueGroups.value.overdue[0] ?? dueGroups.value.dueToday[0] ?? dueGroups.value.soon[0] ?? null)

function openDue(due: 'overdue' | 'today' | 'soon'): void {
  emit('openTodos', { status: 'OPEN', due })
}

function projectLabel(todo: Todo): string {
  if (!todo.project) return '未分类'
  return projects.value.find((project) => project.name === todo.project)?.title ?? '未分类'
}

function moduleOf(todo: Todo): string {
  if (!todo.module) return ''
  const project = todo.project
    ? projects.value.find((item) => item.name === todo.project)
    : undefined
  return project?.modules.includes(todo.module) ? todo.module : ''
}

function openTodoContext(todo: Todo): void {
  emit('openTodos', todo.project ? { status: 'OPEN', project: todo.project } : { status: 'OPEN' })
}

function openHomeTodoMenu(event: MouseEvent, todo: Todo): void {
  openContextMenu(event, [
    {
      label: '标记完成',
      icon: 'check',
      disabled: busy.value !== null,
      onSelect: () => void toggleTodo(todo)
    },
    {
      label: '在待办中打开',
      icon: 'checklist',
      onSelect: () => openTodoContext(todo)
    },
    {
      label: '查看关联笔记',
      icon: 'note',
      onSelect: () => emit('openNotes', { todo: todo.name })
    },
    { divider: true, label: '' },
    {
      label: '复制标题',
      icon: 'title',
      onSelect: () => {
        void copyText(todo.title).then((ok) => show(ok ? '已复制标题' : '复制失败'))
      }
    }
  ])
}

function openHomeProjectMenu(event: MouseEvent, project: Project): void {
  openContextMenu(event, [
    {
      label: '打开项目',
      icon: 'folder',
      onSelect: () => emit('openProjects', project.title)
    },
    {
      label: '查看该项目待办',
      icon: 'checklist',
      onSelect: () => emit('openTodos', { project: project.name })
    },
    { divider: true, label: '' },
    {
      label: '复制项目名',
      icon: 'copy',
      onSelect: () => {
        void copyText(project.title).then((ok) => show(ok ? '已复制项目名' : '复制失败'))
      }
    }
  ])
}

async function toggleTodo(todo: Todo): Promise<void> {
  if (busy.value) return
  busy.value = todo.name
  const snapshot = items.value.slice()
  const index = items.value.findIndex((item) => item.name === todo.name)
  if (index >= 0) {
    items.value[index] = {
      ...todo,
      completed: true,
      completedAt: new Date().toISOString(),
      dismissed: false,
      dismissedAt: null
    }
  }
  try {
    const updated = await updateTodo(todo.name, {
      title: todo.title,
      detail: todo.detail,
      priority: todo.priority,
      project: todo.project,
      module: todo.module,
      dueDate: todo.dueDate,
      completed: true
    })
    const updatedIndex = items.value.findIndex((item) => item.name === updated.name)
    if (updatedIndex >= 0) items.value[updatedIndex] = updated
    writeCache('todos', { items: items.value, total: total.value })
  } catch (err) {
    items.value = snapshot
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
    } else {
      show(messageOf(err))
    }
  } finally {
    busy.value = null
  }
}

async function load(): Promise<void> {
  loading.value = items.value.length === 0
  error.value = ''
  try {
    const [todoList, projectList] = await Promise.all([
      listTodos({ size: PAGE_SIZE }),
      listProjects()
    ])
    items.value = todoList.items
    projects.value = projectList
    total.value = todoList.total
    writeCache('todos', { items: todoList.items, total: todoList.total })
    writeCache('projects', projectList)
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="ws-home">
    <section
      v-if="error"
      class="ws-state ws-state-error flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.72] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <p class="ws-state-title text-[15px] font-semibold">加载失败</p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">{{ error }}</p>
      <Button class="mt-2" @click="load">重试</Button>
    </section>

    <div v-else-if="loading" class="ws-skeleton flex flex-col gap-3" aria-label="正在加载">
      <div class="ws-skeleton-row h-64 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"></div>
      <div class="grid grid-cols-2 gap-3">
        <div class="h-24 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"></div>
        <div class="h-24 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"></div>
      </div>
    </div>

    <TodoOverview
      v-else
      :todos="items"
      :projects="projects"
      @open-todos="(target) => emit('openTodos', target)"
      @open-projects="(title) => emit('openProjects', title)"
      @open-notes="(target) => emit('openNotes', target)"
      @todo-contextmenu="openHomeTodoMenu"
      @project-contextmenu="openHomeProjectMenu"
    />

    <section
      v-if="!loading && !error && dueTotal"
      class="ws-home-due mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-[18px] border border-[#FF9500]/20 bg-white/[0.72] px-4 py-3 text-[12px] shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-[#FF9F0A]/20 dark:bg-[#1c1c1e]/70"
    >
      <span class="flex items-center gap-2 font-semibold text-[13px]">
        <svg class="h-4 w-4 text-[#FF9500] dark:text-[#FF9F0A]" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="5.5" stroke="currentColor" stroke-width="1.3" />
          <path d="M8 5v3.2l2.2 1.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
        </svg>
        到期
      </span>
      <button
        type="button"
        class="ws-home-due-overdue inline-flex min-h-8 items-center gap-1 rounded-full px-2 tabular-nums transition-colors hover:bg-[#FF3B30]/[0.08] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
        :class="dueGroups.overdue.length ? 'text-[#FF3B30]' : 'text-[#6e6e73] dark:text-[#98989d]'"
        @click="openDue('overdue')"
      >逾期 <b class="font-semibold">{{ dueGroups.overdue.length }}</b></button>
      <button
        type="button"
        class="ws-home-due-today inline-flex min-h-8 items-center gap-1 rounded-full px-2 tabular-nums transition-colors hover:bg-[#FF9500]/[0.08] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
        :class="dueGroups.dueToday.length ? 'text-[#FF9500] dark:text-[#FF9F0A]' : 'text-[#6e6e73] dark:text-[#98989d]'"
        @click="openDue('today')"
      >今天 <b class="font-semibold">{{ dueGroups.dueToday.length }}</b></button>
      <button
        type="button"
        class="ws-home-due-soon inline-flex min-h-8 items-center gap-1 rounded-full px-2 tabular-nums text-[#6e6e73] transition-colors hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:text-[#98989d] dark:hover:bg-white/[0.06]"
        @click="openDue('soon')"
      >3 天内 <b class="font-semibold">{{ dueGroups.soon.length }}</b></button>
      <span
        v-if="nextDue"
        class="ml-auto min-w-0 max-w-full truncate text-[#6e6e73] dark:text-[#98989d]"
        :title="nextDue.title"
      >最近：{{ nextDue.title }} · {{ nextDue.dueDate }}</span>
    </section>

    <section
      v-if="!loading && !error && inProgress.length"
      class="ws-home-active mt-4 overflow-hidden rounded-[18px] border border-white/20 bg-white/[0.72] px-2 pb-2 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <header class="flex items-center gap-2 px-2 pb-1 pt-3">
        <h2 class="text-[15px] font-semibold tracking-[-0.01em]">进行中</h2>
        <span
          class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] font-medium tabular-nums text-[#6e6e73] dark:bg-white/[0.08] dark:text-[#98989d]"
        >{{ openItems.length }}</span>
        <span class="text-[11px] text-[#6e6e73] dark:text-[#98989d]">最新创建在前，勾选即完成</span>
        <Button
          variant="ghost"
          size="sm"
          class="ml-auto"
          @click="emit('openTodos', { status: 'OPEN' })"
        >查看全部</Button>
      </header>
      <article
        v-for="todo in inProgress"
        :key="todo.name"
        class="ws-home-active-row flex items-start gap-1 rounded-xl transition-colors duration-200 ease-[var(--ws-ease)] hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
        @contextmenu.prevent.stop="openHomeTodoMenu($event, todo)"
      >
        <label class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center">
          <input
            class="ws-home-check h-5 w-5 accent-[#007AFF]"
            type="checkbox"
            :checked="false"
            :disabled="busy !== null"
            :aria-label="`标记 ${todo.title} 完成`"
            @change="toggleTodo(todo)"
          />
        </label>
        <button
          type="button"
          class="min-w-0 flex-1 py-2.5 text-left focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
          :title="`到待办页查看「${projectLabel(todo)}」的未完成待办`"
          @click="openTodoContext(todo)"
        >
          <span class="block truncate text-[14px] text-[#1d1d1f] dark:text-[#f5f5f7]">{{ todo.title }}</span>
          <span class="mt-0.5 block truncate text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
            {{ projectLabel(todo) }}<template v-if="moduleOf(todo)"> / {{ moduleOf(todo) }}</template><template
              v-if="todo.createdAt"
            > · 创建于 {{ formatDate(todo.createdAt) }}</template>
          </span>
        </button>
      </article>
      <p
        v-if="openItems.length > inProgress.length"
        class="px-3 pb-1 pt-1 text-[11px] text-[#6e6e73] dark:text-[#98989d]"
      >仅显示最新创建的 {{ inProgress.length }} 条，其余 {{ openItems.length - inProgress.length }} 条在待办页</p>
    </section>

    <ContextMenu
      v-if="contextMenu.open"
      :x="contextMenu.x"
      :y="contextMenu.y"
      :items="contextMenu.items"
      @close="contextMenu.open = false"
    />

    <Toast :message="toast" />
  </div>
</template>
