<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ApiError,
  createProject,
  deleteProject,
  listNotes,
  listProjects,
  listTodos,
  renameModule,
  updateProject
} from '../api'
import type { Project, ProjectPayload, Todo } from '../types'
import { messageOf, safeHref } from '../utils'
import { takeSearchTarget } from '../searchTarget'
import type { NotesTarget, TodosTarget } from '../searchTarget'
import { useToast } from '../lib/toast'
import Button from '../components/ui/Button.vue'
import Input from '../components/ui/Input.vue'
import ProjectForm from '../components/ProjectForm.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Toast from '../components/Toast.vue'
import { toolsCollapsed } from '../collapse'

interface ProjectStats {
  total: number
  open: number
  done: number
}

const emit = defineEmits<{
  unauthorized: []
  openNotes: [target: NotesTarget]
  openTodos: [target: TodosTarget]
}>()

const projects = ref<Project[]>([])
const todos = ref<Todo[]>([])
const keyword = ref(takeSearchTarget())
const loading = ref(true)
const error = ref('')

const formOpen = ref(false)
const editing = ref<Project | null>(null)
const saving = ref(false)
const pendingDelete = ref<Project | null>(null)
const noteBusy = ref<string | null>(null)
const { toast, show } = useToast()

const stats = computed(() => {
  const result = new Map<string, ProjectStats>()
  for (const todo of todos.value) {
    if (!todo.project) continue
    const entry = result.get(todo.project) ?? { total: 0, open: 0, done: 0 }
    entry.total += 1
    if (todo.completed) entry.done += 1
    else entry.open += 1
    result.set(todo.project, entry)
  }
  return result
})

const filteredProjects = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) return projects.value
  return projects.value.filter((project) =>
    [project.title, project.note ?? '', ...project.modules, ...project.links]
      .some((value) => value.toLowerCase().includes(needle)))
})

function statsOf(name: string): ProjectStats {
  return stats.value.get(name) ?? { total: 0, open: 0, done: 0 }
}

function handleError(err: unknown): void {
  if (err instanceof ApiError && err.status === 401) {
    emit('unauthorized')
    return
  }
  show(messageOf(err))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [projectList, todoList] = await Promise.all([
      listProjects(),
      listTodos({ size: 500 })
    ])
    projects.value = projectList
    todos.value = todoList.items
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

function openCreate(): void {
  editing.value = null
  formOpen.value = true
}

function openEdit(project: Project): void {
  editing.value = project
  formOpen.value = true
}

function closeForm(): void {
  if (saving.value) return
  formOpen.value = false
  editing.value = null
}

async function save(payload: ProjectPayload): Promise<void> {
  saving.value = true
  try {
    if (editing.value) {
      const updated = await updateProject(editing.value.name, payload)
      const index = projects.value.findIndex((item) => item.name === updated.name)
      if (index >= 0) projects.value[index] = updated
      show('已保存')
    } else {
      const created = await createProject(payload)
      projects.value = [...projects.value, created]
      show('已创建项目')
    }
    formOpen.value = false
    editing.value = null
  } catch (err) {
    handleError(err)
  } finally {
    saving.value = false
  }
}

function askDelete(project: Project): void {
  pendingDelete.value = project
}

async function openProjectNotes(project: Project): Promise<void> {
  if (noteBusy.value) return
  noteBusy.value = project.name
  try {
    const notes = await listNotes()
    const todoNames = new Set(
      todos.value.filter((todo) => todo.project === project.name).map((todo) => todo.name))
    const matched = notes.filter((note) => note.project === project.name
      || note.todos.some((name) => todoNames.has(name)))
    emit('openNotes', {
      project: project.name,
      includeProjectTodos: true,
      noteName: matched.length === 1 ? matched[0].name : undefined
    })
  } catch (err) {
    handleError(err)
  } finally {
    noteBusy.value = null
  }
}

function openProjectTodos(project: Project): void {
  emit('openTodos', { project: project.name })
}

async function onModuleRenamed(from: string, to: string): Promise<void> {
  const project = editing.value
  if (!project) return
  try {
    const updated = await renameModule(project.name, from, to)
    const index = projects.value.findIndex((item) => item.name === updated.name)
    if (index >= 0) projects.value[index] = updated
    todos.value = todos.value.map((todo) =>
      todo.project === project.name && todo.module === from ? { ...todo, module: to } : todo)
    show('已重命名模块')
  } catch (err) {
    handleError(err)
  }
}

async function removeConfirmed(): Promise<void> {
  const project = pendingDelete.value
  if (!project) return
  pendingDelete.value = null
  try {
    await deleteProject(project.name)
    projects.value = projects.value.filter((item) => item.name !== project.name)
    show('已删除项目')
  } catch (err) {
    handleError(err)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ws-view">
    <template v-if="!toolsCollapsed">
    <div class="ws-toolbar mb-4 flex flex-wrap items-center gap-2">
      <div class="relative min-w-[180px] flex-1 sm:max-w-[340px]">
        <svg
          class="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-black/30 dark:text-white/30"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.5" />
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
        <Input v-model="keyword" class="ws-search pl-10" placeholder="搜索项目、模块或备注" />
      </div>
      <Button variant="primary" class="ml-auto" @click="openCreate">
        <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        新建项目
      </Button>
    </div>
    </template>

    <section
      v-if="error"
      class="ws-state ws-state-error flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.72] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <p class="ws-state-title text-[15px] font-semibold">加载失败</p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">{{ error }}</p>
      <Button class="mt-2" @click="load">重试</Button>
    </section>

    <div v-else-if="loading" class="ws-skeleton flex flex-col gap-3" aria-label="正在加载">
      <div
        v-for="index in 3"
        :key="index"
        class="ws-skeleton-row h-40 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"
      ></div>
    </div>

    <section
      v-else-if="!filteredProjects.length"
      class="ws-state ws-empty flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.6] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/50"
    >
      <p class="ws-state-title text-[15px] font-semibold">
        {{ keyword.trim() ? '没有匹配的项目' : '还没有项目' }}
      </p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">
        {{ keyword.trim() ? '换个关键词试试' : '把正在做的事建成项目，集中管理模块、进度和链接' }}
      </p>
      <Button v-if="!keyword.trim()" class="mt-2" variant="primary" @click="openCreate">
        新建项目
      </Button>
    </section>

    <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <article
        v-for="project in filteredProjects"
        :key="project.name"
        class="ws-project-card group cursor-pointer rounded-[18px] border border-white/20 bg-white/[0.72] p-5 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] transition-colors duration-200 ease-[var(--ws-ease)] hover:bg-white/[0.82] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70 dark:hover:bg-[#1c1c1e]/85"
        @click="openEdit(project)"
      >
        <header class="mb-3 flex items-start justify-between gap-2">
          <div class="min-w-0">
            <h3 class="truncate text-[16px] font-semibold tracking-[-0.01em]">{{ project.title }}</h3>
            <p class="mt-0.5 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
              {{ statsOf(project.name).total }} 个待办 · {{ statsOf(project.name).open }} 个未完成
            </p>
          </div>
          <div class="flex shrink-0 items-center opacity-100 transition-opacity lg:opacity-0 lg:group-hover:opacity-100">
            <Button
              variant="ghost"
              size="icon"
              :disabled="noteBusy !== null"
              :aria-label="`查看 ${project.title} 的关联笔记`"
              title="查看关联笔记"
              @click.stop="openProjectNotes(project)"
            >
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M3.5 2.5h7a1 1 0 011 1v9a1 1 0 01-1 1h-7a1 1 0 01-1-1v-9a1 1 0 011-1z"
                  stroke="currentColor"
                  stroke-width="1.4"
                />
                <path d="M5.5 5h4M5.5 7.5h4M5.5 10h2.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
              </svg>
            </Button>
            <Button
              variant="ghost"
              size="icon"
              :aria-label="`查看 ${project.title} 的待办`"
              title="查看待办"
              @click.stop="openProjectTodos(project)"
            >
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <rect x="2.5" y="2.5" width="11" height="11" rx="2.5" stroke="currentColor" stroke-width="1.4" />
                <path d="M5.5 8.2l1.8 1.8 3.2-3.6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </Button>
            <Button variant="ghost" size="icon" aria-label="编辑" title="编辑" @click.stop="openEdit(project)">
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M10.8 2.6a1.4 1.4 0 012 2L5 12.4l-2.6.6.6-2.6 7.8-7.8z"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linejoin="round"
                />
              </svg>
            </Button>
            <Button
              variant="ghost"
              size="icon"
              class="text-[#FF3B30]/75 hover:text-[#FF3B30]"
              aria-label="删除"
              title="删除"
              @click.stop="askDelete(project)"
            >
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M2.5 4.5h11M6.5 4.5V3h3v1.5M4 4.5l.6 8.5h6.8l.6-8.5"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </Button>
          </div>
        </header>

        <div class="mb-3 flex items-center gap-3">
          <div class="h-1.5 flex-1 overflow-hidden rounded-full bg-black/[0.07] dark:bg-white/[0.1]">
            <div
              class="h-full rounded-full bg-[#007AFF] transition-[width] duration-300 ease-[var(--ws-ease)] dark:bg-[#0A84FF]"
              :style="{ width: `${project.progress}%` }"
            ></div>
          </div>
          <span class="w-9 text-right text-[12px] font-medium tabular-nums text-[#6e6e73] dark:text-[#98989d]">
            {{ project.progress }}%
          </span>
        </div>

        <div v-if="project.modules.length" class="mb-3 flex flex-wrap gap-1.5">
          <span
            v-for="item in project.modules"
            :key="item"
            class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/70 dark:bg-white/[0.08] dark:text-[#f5f5f7]/70"
          >{{ item }}</span>
        </div>

        <p
          v-if="project.note"
          class="mb-2 line-clamp-2 text-[13px] text-[#6e6e73] dark:text-[#98989d]"
        >{{ project.note }}</p>

        <div v-if="project.links.length" class="flex flex-wrap gap-x-3 gap-y-1">
          <a
            v-for="link in project.links"
            :key="link"
            :href="safeHref(link)"
            target="_blank"
            rel="noopener noreferrer"
            class="max-w-full truncate text-[12px] font-medium text-[#007AFF] hover:underline dark:text-[#64B5FF]"
            @click.stop
          >{{ link }}</a>
        </div>
      </article>
    </div>

    <ProjectForm
      v-if="formOpen"
      :project="editing"
      :saving="saving"
      @save="save"
      @renamed="onModuleRenamed"
      @close="closeForm"
    />

    <ConfirmDialog
      v-if="pendingDelete"
      title="删除项目"
      :message="`确定删除「${pendingDelete.title}」？项目下仍有待办时无法删除。`"
      confirm-text="删除"
      @confirm="removeConfirmed"
      @close="pendingDelete = null"
    />

    <Toast :message="toast" />
  </div>
</template>
