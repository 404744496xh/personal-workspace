<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, listProjects, listTodos, updateTodo } from '../api'
import { readCache, writeCache } from '../cache'
import type { Project, Todo } from '../types'
import type { TodosTarget } from '../searchTarget'
import { formatDate, messageOf } from '../utils'
import { useToast } from '../lib/toast'
import TodoOverview from '../components/TodoOverview.vue'
import Toast from '../components/Toast.vue'
import Button from '../components/ui/Button.vue'

const PAGE_SIZE = 500
const ACTIVE_SIZE = 8
const cached = readCache<{ items: Todo[]; total: number }>('todos')
const cachedProjects = readCache<Project[]>('projects')
const emit = defineEmits<{
  unauthorized: []
  openTodos: [target: TodosTarget]
  openProjects: [title?: string]
}>()

const items = ref<Todo[]>(cached?.items ?? [])
const projects = ref<Project[]>(cachedProjects ?? [])
const total = ref(cached?.total ?? 0)
const loading = ref(!cached)
const error = ref('')
const busy = ref<string | null>(null)
const { toast, show } = useToast()

const openItems = computed(() => items.value
  .filter((todo) => !todo.completed && !todo.dismissed)
  .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')))
const inProgress = computed(() => openItems.value.slice(0, ACTIVE_SIZE))

function projectLabel(todo: Todo): string {
  if (!todo.project) return '未分类'
  return projects.value.find((project) => project.name === todo.project)?.title ?? '未分类'
}

function openTodoContext(todo: Todo): void {
  emit('openTodos', todo.project ? { status: 'OPEN', project: todo.project } : { status: 'OPEN' })
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
    />

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
            {{ projectLabel(todo) }}<template v-if="todo.module"> / {{ todo.module }}</template><template
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

    <Toast :message="toast" />
  </div>
</template>
