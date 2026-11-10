<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ApiError,
  createNote,
  deleteNote,
  getNote,
  listNotes,
  listProjects,
  listTodos,
  updateNote
} from '../api'
import type { Note, NotePayload, NoteSummary, Project, Todo } from '../types'
import { excerpt, formatDateTime, messageOf } from '../utils'
import { takeNotesTarget, takeSearchTarget } from '../searchTarget'
import { useToast } from '../lib/toast'
import Button from '../components/ui/Button.vue'
import Input from '../components/ui/Input.vue'
import Select from '../components/ui/Select.vue'
import NoteEditor from '../components/NoteEditor.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Toast from '../components/Toast.vue'
import ToolsToggle from '../components/ToolsToggle.vue'
import { toolsCollapsed } from '../collapse'
import { readCache, writeCache } from '../cache'

const cachedNotes = readCache<NoteSummary[]>('notes')
const cachedProjects = readCache<Project[]>('projects')
const cachedTodos = readCache<{ items: Todo[]; total: number }>('todos')

const emit = defineEmits<{ unauthorized: [] }>()

const target = takeNotesTarget()
const notes = ref<NoteSummary[]>(cachedNotes ?? [])
const projects = ref<Project[]>(cachedProjects ?? [])
const todos = ref<Todo[]>(cachedTodos?.items ?? [])
const keyword = ref(takeSearchTarget())
const projectFilter = ref(
  target?.project !== undefined ? (target.project === '' ? '__none__' : target.project) : ''
)
const moduleFilter = ref(
  target?.module !== undefined ? (target.module === '' ? '__none__' : target.module) : ''
)
const todoFilter = ref(target?.todo ?? '')
const includeProjectTodos = ref(target?.includeProjectTodos === true)
const pendingNoteName = ref(target?.noteName ?? null)
const pendingCreate = ref(target?.create === true)
const loading = ref(!cachedNotes)
const error = ref('')

const editorOpen = ref(false)
const editing = ref<Note | null>(null)
const opening = ref(false)
const createDefaults = ref<{ project?: string; module?: string; todo?: string }>({})
const saving = ref(false)
const pendingDelete = ref<NoteSummary | null>(null)
const { toast, show } = useToast()

const sortedNotes = computed(() => [...notes.value].sort((left, right) =>
  (right.updatedAt ?? '').localeCompare(left.updatedAt ?? '')))

function projectTitleOf(name: string | null): string {
  if (!name) return ''
  return projects.value.find((project) => project.name === name)?.title ?? ''
}

function todoTitleOf(name: string): string {
  return todos.value.find((todo) => todo.name === name)?.title ?? ''
}

const moduleOptions = computed(() => {
  if (projectFilter.value && projectFilter.value !== '__none__') {
    return projects.value.find((project) => project.name === projectFilter.value)?.modules ?? []
  }
  return [...new Set(projects.value.flatMap((project) => project.modules))]
})

const todoProjectOf = computed(() =>
  new Map(todos.value.map((todo) => [todo.name, todo.project])))

const todoOptions = computed(() =>
  todos.value.filter((todo) => {
    if (projectFilter.value && projectFilter.value !== '__none__'
      && (todo.project ?? '') !== projectFilter.value) {
      return false
    }
    if (moduleFilter.value && moduleFilter.value !== '__none__'
      && (todo.module ?? '') !== moduleFilter.value) {
      return false
    }
    return true
  })
)

const hasFilters = computed(() => keyword.value.trim() !== ''
  || projectFilter.value !== '' || moduleFilter.value !== '' || todoFilter.value !== '')
const hasHiddenFilters = computed(() => projectFilter.value !== ''
  || moduleFilter.value !== '' || todoFilter.value !== '')

function matchesFilters(note: NoteSummary): boolean {
  if (projectFilter.value) {
    if (projectFilter.value === '__none__') {
      if (note.project) return false
    } else if (note.project !== projectFilter.value) {
      // 项目跳转时把关联到该项目下待办的笔记也算进来
      const sameProjectTodo = note.todos.some(
        (name) => todoProjectOf.value.get(name) === projectFilter.value)
      if (!includeProjectTodos.value || !sameProjectTodo) return false
    }
  }
  if (moduleFilter.value) {
    if (moduleFilter.value === '__none__') {
      if (note.module) return false
    } else if (note.module !== moduleFilter.value) {
      return false
    }
  }
  if (todoFilter.value && !note.todos.includes(todoFilter.value)) {
    return false
  }
  return true
}

const filteredNotes = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  return sortedNotes.value.filter((note) => {
    if (!matchesFilters(note)) return false
    if (!needle) return true
    const haystack = [
      note.title,
      note.excerpt,
      projectTitleOf(note.project),
      note.module ?? '',
      ...note.todos.map(todoTitleOf)
    ].join(' ').toLowerCase()
    return haystack.includes(needle)
  })
})

watch(projectFilter, () => {
  includeProjectTodos.value = false
  if (moduleFilter.value && moduleFilter.value !== '__none__'
    && !moduleOptions.value.includes(moduleFilter.value)) {
    moduleFilter.value = ''
  }
  if (todoFilter.value && !todoOptions.value.some((todo) => todo.name === todoFilter.value)) {
    todoFilter.value = ''
  }
})

watch(moduleFilter, () => {
  if (todoFilter.value && !todoOptions.value.some((todo) => todo.name === todoFilter.value)) {
    todoFilter.value = ''
  }
})

function clearFilters(): void {
  keyword.value = ''
  projectFilter.value = ''
  moduleFilter.value = ''
  todoFilter.value = ''
  includeProjectTodos.value = false
}

function setProjectFilter(value: string | null): void {
  projectFilter.value = value ?? ''
}

function setModuleFilter(value: string | null): void {
  moduleFilter.value = value ?? ''
}

function handleError(err: unknown): void {
  if (err instanceof ApiError && err.status === 401) {
    emit('unauthorized')
    return
  }
  show(messageOf(err))
}

async function load(options: { silent?: boolean } = {}): Promise<void> {
  if (!options.silent) loading.value = true
  error.value = ''
  try {
    const [noteList, projectList, todoList] = await Promise.all([
      listNotes(),
      listProjects(),
      listTodos({ size: 500 })
    ])
    notes.value = noteList
    projects.value = projectList
    todos.value = todoList.items
    writeCache('notes', noteList)
    writeCache('projects', projectList)
    writeCache('todos', { items: todoList.items, total: todoList.total })
    if (pendingCreate.value) {
      pendingCreate.value = false
      openCreateFromTarget()
    } else if (pendingNoteName.value) {
      const note = noteList.find((item) => item.name === pendingNoteName.value)
      pendingNoteName.value = null
      if (note) void openEdit(note)
    }
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
  const project = projectFilter.value && projectFilter.value !== '__none__'
    ? projectFilter.value
    : ''
  createDefaults.value = {
    project: project || undefined,
    module: project && moduleFilter.value && moduleFilter.value !== '__none__'
      ? moduleFilter.value
      : undefined,
    todo: todoFilter.value || undefined
  }
  editing.value = null
  editorOpen.value = true
}

function openCreateFromTarget(): void {
  const todo = target?.todo
    ? todos.value.find((item) => item.name === target.todo)
    : undefined
  createDefaults.value = {
    project: target?.project || todo?.project || undefined,
    module: target?.module ?? todo?.module ?? undefined,
    todo: target?.todo || undefined
  }
  editing.value = null
  editorOpen.value = true
}

function openEdit(note: NoteSummary): void {
  if (opening.value) return
  opening.value = true
  void getNote(note.name)
    .then((full) => {
      createDefaults.value = {}
      editing.value = full
      editorOpen.value = true
    })
    .catch(handleError)
    .finally(() => {
      opening.value = false
    })
}

function toSummary(note: Note): NoteSummary {
  return {
    name: note.name,
    title: note.title,
    excerpt: excerpt(note.content, 220),
    tags: note.tags,
    project: note.project,
    module: note.module,
    todos: note.todos,
    updatedAt: note.updatedAt
  }
}

function closeEditor(): void {
  if (saving.value) return
  editorOpen.value = false
  editing.value = null
}

async function save(payload: NotePayload): Promise<void> {
  saving.value = true
  try {
    if (editing.value) {
      const updated = await updateNote(editing.value.name, payload)
      const index = notes.value.findIndex((item) => item.name === updated.name)
      if (index >= 0) notes.value[index] = toSummary(updated)
      show('已保存')
    } else {
      const created = await createNote(payload)
      notes.value = [toSummary(created), ...notes.value]
      show('已创建笔记')
    }
    writeCache('notes', notes.value)
    editorOpen.value = false
    editing.value = null
  } catch (err) {
    handleError(err)
  } finally {
    saving.value = false
  }
}

async function removeConfirmed(): Promise<void> {
  const note = pendingDelete.value
  if (!note) return
  pendingDelete.value = null
  try {
    await deleteNote(note.name)
    notes.value = notes.value.filter((item) => item.name !== note.name)
    writeCache('notes', notes.value)
    show('已删除笔记')
  } catch (err) {
    handleError(err)
  }
}

onMounted(() => {
  void load({ silent: Boolean(cachedNotes) })
})
</script>

<template>
  <div class="ws-view" :class="editorOpen ? 'ws-view-editor' : ''">
    <NoteEditor
      v-if="editorOpen"
      :note="editing"
      :projects="projects"
      :todos="todos"
      :saving="saving"
      :default-project="createDefaults.project"
      :default-module="createDefaults.module"
      :default-todo="createDefaults.todo"
      @save="save"
      @close="closeEditor"
    />

    <template v-else>
      <ToolsToggle
        v-if="toolsCollapsed"
        v-model="keyword"
        :active="hasHiddenFilters"
        placeholder="搜索笔记标题或正文"
        search-label="搜索笔记"
        @expand="toolsCollapsed = false"
      />
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
        <Input v-model="keyword" class="ws-search pl-10" placeholder="搜索笔记标题或正文" />
      </div>
      <Button variant="primary" class="ml-auto" @click="openCreate">
        <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        新建笔记
      </Button>
    </div>

    <div
      v-if="notes.length"
      class="ws-note-filter mb-4 flex flex-wrap items-center gap-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
    >
      <Select v-model="projectFilter" class="w-[180px]" aria-label="按项目筛选">
        <option value="">全部项目</option>
        <option value="__none__">未关联项目</option>
        <option v-for="project in projects" :key="project.name" :value="project.name">
          {{ project.title }}
        </option>
      </Select>
      <span
        v-if="includeProjectTodos && projectFilter && projectFilter !== '__none__'"
        class="inline-flex min-h-7 items-center rounded-full bg-[#007AFF]/[0.10] px-2.5 text-[11px] font-medium text-[#007AFF] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF]"
        title="包含该项目下模块与待办关联的笔记"
      >含待办笔记</span>
      <Select v-model="moduleFilter" class="w-[150px]" aria-label="按模块筛选" :disabled="!moduleOptions.length">
        <option value="">全部模块</option>
        <option value="__none__">未分模块</option>
        <option v-for="item in moduleOptions" :key="item" :value="item">{{ item }}</option>
      </Select>
      <Select v-model="todoFilter" class="w-[200px]" aria-label="按待办筛选" :disabled="!todoOptions.length">
        <option value="">全部待办</option>
        <option v-for="todo in todoOptions" :key="todo.name" :value="todo.name">
          {{ todo.title }}
        </option>
      </Select>
      <Button v-if="hasFilters" variant="ghost" size="sm" @click="clearFilters">清除筛选</Button>
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
        class="ws-skeleton-row h-32 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"
      ></div>
    </div>

    <section
      v-else-if="!filteredNotes.length"
      class="ws-state ws-empty flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.6] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/50"
    >
      <p class="ws-state-title text-[15px] font-semibold">
        {{ notes.length ? '没有匹配的笔记' : '还没有笔记' }}
      </p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">
        {{ notes.length ? '调整筛选条件试试' : '用 Markdown 记录排查过程、方案和零散想法' }}
      </p>
      <Button v-if="hasFilters" class="mt-2" @click="clearFilters">清除筛选</Button>
      <Button v-else-if="!notes.length" class="mt-2" variant="primary" @click="openCreate">
        新建笔记
      </Button>
    </section>

    <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <article
        v-for="note in filteredNotes"
        :key="note.name"
        class="ws-note-card group cursor-pointer rounded-[18px] border border-white/20 bg-white/[0.72] p-5 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] transition-colors duration-200 ease-[var(--ws-ease)] hover:bg-white/[0.82] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70 dark:hover:bg-[#1c1c1e]/85"
        @click="openEdit(note)"
      >
        <header class="mb-2 flex items-start justify-between gap-2">
          <div class="min-w-0">
            <h3 class="truncate text-[15px] font-semibold">{{ note.title }}</h3>
            <p class="mt-0.5 text-[11px] text-[#6e6e73] dark:text-[#98989d]">
              更新于 {{ formatDateTime(note.updatedAt) }}
            </p>
          </div>
          <div class="flex shrink-0 items-center opacity-100 transition-opacity lg:opacity-0 lg:group-hover:opacity-100">
            <Button variant="ghost" size="icon" aria-label="编辑" title="编辑" @click.stop="openEdit(note)">
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
              @click.stop="pendingDelete = note"
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

        <p class="line-clamp-4 min-h-[64px] text-[13px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
          {{ note.excerpt || '空白笔记' }}
        </p>

        <div
          v-if="note.project || note.module || note.todos.length"
          class="mt-3 flex flex-wrap items-center gap-1.5"
        >
          <button
            v-if="note.project"
            type="button"
            class="rounded-full bg-[#007AFF]/[0.10] px-2 py-0.5 text-[11px] font-medium text-[#007AFF] transition-colors duration-150 hover:bg-[#007AFF]/[0.18] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF] dark:hover:bg-[#0A84FF]/[0.26]"
            title="按此项目筛选"
            @click.stop="setProjectFilter(note.project)"
          >{{ projectTitleOf(note.project) || '原项目已删除' }}</button>
          <button
            v-if="note.module"
            type="button"
            class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/60 transition-colors duration-150 hover:bg-black/[0.1] dark:bg-white/[0.08] dark:text-[#f5f5f7]/60 dark:hover:bg-white/[0.14]"
            title="按此模块筛选"
            @click.stop="setModuleFilter(note.module)"
          >模块 · {{ note.module }}</button>
          <span
            v-if="note.todos.length"
            class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/60 dark:bg-white/[0.08] dark:text-[#f5f5f7]/60"
          >待办 · {{ note.todos.length }}</span>
        </div>

        <div v-if="note.tags.length" class="mt-3 flex flex-wrap gap-1.5">
          <span
            v-for="item in note.tags"
            :key="item"
            class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/60 dark:bg-white/[0.08] dark:text-[#f5f5f7]/60"
          >{{ item }}</span>
        </div>
      </article>
    </div>

    </template>

    <ConfirmDialog
      v-if="pendingDelete"
      title="删除笔记"
      :message="`确定删除「${pendingDelete.title}」？删除后不可恢复。`"
      confirm-text="删除"
      @confirm="removeConfirmed"
      @close="pendingDelete = null"
    />

    <Toast :message="toast" />
  </div>
</template>
