<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, listProjects, listTodos } from '../api'
import { readCache, writeCache } from '../cache'
import type { Project, Todo } from '../types'
import type { TodosTarget } from '../searchTarget'
import { messageOf } from '../utils'
import TodoOverview from '../components/TodoOverview.vue'
import Button from '../components/ui/Button.vue'

const PAGE_SIZE = 500
const cached = readCache<{ items: Todo[]; total: number }>('todos')
const cachedProjects = readCache<Project[]>('projects')
const emit = defineEmits<{
  unauthorized: []
  openTodos: [target: TodosTarget]
  openProjects: [title?: string]
}>()

const items = ref<Todo[]>(cached?.items ?? [])
const projects = ref<Project[]>(cachedProjects ?? [])
const loading = ref(!cached)
const error = ref('')

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
  </div>
</template>
