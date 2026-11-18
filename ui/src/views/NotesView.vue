<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  ApiError,
  createNote,
  deleteNote,
  generateNoteSummary,
  getNote,
  listNoteTemplates,
  listNotes,
  listProjects,
  listTodos,
  updateNote
} from '../api'
import type { Note, NotePayload, NoteSummary, NoteTemplate, Project, Todo } from '../types'
import { copyText, excerpt, formatDateTime, messageOf } from '../utils'
import { takeNotesTarget, takeSearchTarget, setSearchTarget } from '../searchTarget'
import type { TodosTarget } from '../searchTarget'
import { useToast } from '../lib/toast'
import Button from '../components/ui/Button.vue'
import Input from '../components/ui/Input.vue'
import SearchSelect from '../components/ui/SearchSelect.vue'
import NoteEditor from '../components/NoteEditor.vue'
import NoteTemplatesDialog from '../components/NoteTemplatesDialog.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Toast from '../components/Toast.vue'
import ToolsToggle from '../components/ToolsToggle.vue'
import ContextMenu from '../components/ContextMenu.vue'
import { useContextMenu } from '../contextMenu'
import type { ContextMenuItem } from '../contextMenu'
import { toolsCollapsed } from '../collapse'
import { readCache, writeCache } from '../cache'
import { DEFAULT_NOTE_TEMPLATES } from '../noteTemplates'

const cachedNotes = readCache<NoteSummary[]>('notes')
const cachedProjects = readCache<Project[]>('projects')
const cachedTodos = readCache<{ items: Todo[]; total: number }>('todos')

const emit = defineEmits<{
  unauthorized: []
  openProjects: [title: string]
  openTodos: [target: TodosTarget]
}>()

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
const projectStatus = ref('')
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
const noteTemplates = ref<NoteTemplate[]>(DEFAULT_NOTE_TEMPLATES.map((template) => ({ ...template })))
const templatesOpen = ref(false)
const { toast, show } = useToast()
const { menu: contextMenu, open: openContextMenu } = useContextMenu()

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

const projectOptions = computed(() => [
  { value: '', label: '全部项目' },
  { value: '__none__', label: '未关联项目' },
  ...projects.value.map((project) => ({ value: project.name, label: project.title }))
])

const projectStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'open', label: '未完成项目' },
  { value: 'done', label: '已完成项目' }
]

const moduleSelectOptions = computed(() => [
  { value: '', label: '全部模块' },
  { value: '__none__', label: '未分模块' },
  ...moduleOptions.value.map((item) => ({ value: item, label: item }))
])

const todoSelectOptions = computed(() => [
  { value: '', label: '全部待办' },
  ...todoOptions.value.map((todo) => ({
    value: todo.name,
    label: `${todo.title}${todo.dismissed ? '（不用处理）' : ''}`
  }))
])

const projectProgressOf = computed(() =>
  new Map(projects.value.map((project) => [project.name, project.progress])))

const hasFilters = computed(() => keyword.value.trim() !== ''
  || projectFilter.value !== '' || projectStatus.value !== ''
  || moduleFilter.value !== '' || todoFilter.value !== '')
const hasHiddenFilters = computed(() => projectFilter.value !== ''
  || projectStatus.value !== '' || moduleFilter.value !== '' || todoFilter.value !== '')

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
  if (projectStatus.value) {
    const progress = note.project ? projectProgressOf.value.get(note.project) : undefined
    if (progress == null) return false
    if (projectStatus.value === 'done' ? progress < 100 : progress >= 100) return false
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

const filteredNotes = computed(() =>
  sortedNotes.value.filter((note) => matchesFilters(note)))

let searchTimer: ReturnType<typeof setTimeout> | undefined
let searchSeq = 0

async function searchNotes(): Promise<void> {
  const seq = ++searchSeq
  const term = keyword.value.trim()
  try {
    const noteList = await listNotes(term || undefined)
    if (seq !== searchSeq) return
    notes.value = noteList
    if (!term) writeCache('notes', noteList)
  } catch (err) {
    if (seq !== searchSeq) return
    handleError(err)
  }
}

watch(keyword, () => {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    void searchNotes()
  }, 250)
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
  projectStatus.value = ''
  moduleFilter.value = ''
  todoFilter.value = ''
  includeProjectTodos.value = false
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
    const [noteList, projectList, todoList, templateResponse] = await Promise.all([
      listNotes(keyword.value.trim() || undefined),
      listProjects(),
      listTodos({ size: 500 }),
      listNoteTemplates().catch(() => null)
    ])
    notes.value = noteList
    projects.value = projectList
    todos.value = todoList.items
    if (templateResponse?.templates) {
      noteTemplates.value = templateResponse.templates
    }
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
    summary: note.summary,
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

function applyTemplates(templates: NoteTemplate[]): void {
  noteTemplates.value = templates
}

async function persist(payload: NotePayload): Promise<boolean> {
  if (saving.value) return false
  saving.value = true
  try {
    if (editing.value) {
      const updated = await updateNote(editing.value.name, payload)
      const index = notes.value.findIndex((item) => item.name === updated.name)
      if (index >= 0) notes.value[index] = toSummary(updated)
      editing.value = updated
    } else {
      const created = await createNote(payload)
      notes.value = [toSummary(created), ...notes.value]
      editing.value = created
    }
    writeCache('notes', notes.value)
    return true
  } catch (err) {
    handleError(err)
    return false
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

const todoMenuNote = ref<string | null>(null)

function jumpToProject(note: NoteSummary): void {
  const title = projectTitleOf(note.project)
  if (title) emit('openProjects', title)
}

function jumpToModule(note: NoteSummary): void {
  emit('openTodos', {
    project: note.project ?? undefined,
    module: note.module ?? undefined
  })
}

function jumpToTodo(note: NoteSummary, name: string): void {
  todoMenuNote.value = null
  const title = todoTitleOf(name)
  if (!title) {
    jumpToModule(note)
    return
  }
  setSearchTarget(title)
  emit('openTodos', { status: 'ALL' })
}

function toggleTodoMenu(name: string): void {
  todoMenuNote.value = todoMenuNote.value === name ? null : name
}

function copyFromMenu(text: string, label: string): void {
  void copyText(text).then((ok) => {
    show(ok ? label : '复制失败')
  })
}

function safeFileName(title: string): string {
  return (title.replace(/[\\/:*?"<>|]/g, '_').trim() || 'note').slice(0, 80)
}

async function exportNoteMarkdown(note: NoteSummary): Promise<void> {
  try {
    const full = await getNote(note.name)
    const url = URL.createObjectURL(
      new Blob([full.content ?? ''], { type: 'text/markdown;charset=utf-8' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${safeFileName(note.title)}.md`
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    show('已导出 Markdown')
  } catch (err) {
    handleError(err)
  }
}

const summaryBusy = ref<string | null>(null)

async function generateSummaryFor(note: NoteSummary): Promise<void> {
  if (summaryBusy.value) return
  summaryBusy.value = note.name
  try {
    const full = await getNote(note.name)
    const result = await generateNoteSummary({ title: full.title, content: full.content })
    const updated = await updateNote(note.name, {
      title: full.title,
      content: full.content,
      summary: result.summary,
      tags: full.tags,
      project: full.project,
      module: full.module,
      todos: full.todos
    })
    notes.value = notes.value.map((item) => item.name === note.name
      ? { ...item, title: updated.title, summary: updated.summary ?? result.summary }
      : item)
    show('已生成 AI 摘要')
  } catch (err) {
    handleError(err)
  } finally {
    summaryBusy.value = null
  }
}

async function generateSummaryFromEditor(payload: { title: string; content: string }): Promise<void> {
  const note = editing.value
  if (!note?.name) {
    show('请先保存笔记，再生成摘要')
    return
  }
  if (summaryBusy.value) return
  summaryBusy.value = note.name
  try {
    const result = await generateNoteSummary(payload)
    const updated = await updateNote(note.name, {
      title: payload.title || note.title,
      content: payload.content,
      summary: result.summary,
      tags: note.tags,
      project: note.project,
      module: note.module,
      todos: note.todos
    })
    notes.value = notes.value.map((item) => item.name === note.name
      ? { ...item, title: updated.title, summary: updated.summary ?? result.summary }
      : item)
    show('已生成 AI 摘要')
  } catch (err) {
    handleError(err)
  } finally {
    summaryBusy.value = null
  }
}

function openNoteMenu(event: MouseEvent, note: NoteSummary): void {
  const items: ContextMenuItem[] = [
    { label: '打开编辑', icon: 'edit', onSelect: () => openEdit(note) },
    { label: '复制标题', icon: 'title', onSelect: () => copyFromMenu(note.title, '已复制标题') }
  ]
  if (note.excerpt) {
    items.push({
      label: '复制摘要',
      icon: 'copy',
      onSelect: () => copyFromMenu(note.excerpt, '已复制摘要')
    })
  }
  items.push({
    label: '导出 Markdown',
    icon: 'download',
    onSelect: () => void exportNoteMarkdown(note)
  })
  items.push({
    label: note.summary ? '重新生成 AI 摘要' : 'AI 生成摘要',
    icon: 'sparkle',
    disabled: summaryBusy.value !== null,
    onSelect: () => void generateSummaryFor(note)
  })
  const hasTargets = Boolean(
    (note.project && projectTitleOf(note.project)) || note.module || note.todos.length)
  if (hasTargets) items.push({ divider: true, label: '' })
  if (note.project && projectTitleOf(note.project)) {
    items.push({ label: '打开项目', icon: 'folder', onSelect: () => jumpToProject(note) })
  }
  if (note.module) {
    items.push({ label: '查看模块待办', icon: 'filter', onSelect: () => jumpToModule(note) })
  }
  for (const name of note.todos) {
    items.push({
      label: `待办 · ${todoTitleOf(name) || '已删除'}`,
      icon: 'checklist',
      onSelect: () => jumpToTodo(note, name)
    })
  }
  items.push(
    { divider: true, label: '' },
    {
      label: '删除',
      icon: 'trash',
      danger: true,
      onSelect: () => {
        pendingDelete.value = note
      }
    }
  )
  openContextMenu(event, items)
}

function openEmptyMenu(event: MouseEvent): void {
  openContextMenu(event, [
    { label: '新建笔记', icon: 'plus', onSelect: openCreate },
    { label: '管理笔记模板', icon: 'note', onSelect: () => { templatesOpen.value = true } }
  ])
}

function closeTodoMenu(event: MouseEvent): void {
  const element = event.target as Element | null
  if (!element?.closest?.('.ws-note-todo-wrap')) todoMenuNote.value = null
}

function closeTodoMenuOnEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape') todoMenuNote.value = null
}

onMounted(() => {
  void load({ silent: Boolean(cachedNotes) })
  document.addEventListener('mousedown', closeTodoMenu)
  window.addEventListener('keydown', closeTodoMenuOnEscape)
})

onUnmounted(() => {
  if (searchTimer) window.clearTimeout(searchTimer)
  document.removeEventListener('mousedown', closeTodoMenu)
  window.removeEventListener('keydown', closeTodoMenuOnEscape)
})
</script>

<template>
  <div class="ws-view" :class="editorOpen ? 'ws-view-editor' : ''">
    <NoteTemplatesDialog
      v-if="templatesOpen"
      :templates="noteTemplates"
      @close="templatesOpen = false"
      @change="applyTemplates"
    />

    <NoteEditor
      v-if="editorOpen"
      :note="editing"
      :projects="projects"
      :todos="todos"
      :templates="noteTemplates"
      :saving="saving"
      :summary-busy="summaryBusy !== null"
      :persist="persist"
      :default-project="createDefaults.project"
      :default-module="createDefaults.module"
      :default-todo="createDefaults.todo"
      @close="closeEditor"
      @manage-templates="templatesOpen = true"
      @summary="generateSummaryFromEditor"
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
      <div class="relative min-w-[150px] flex-1 sm:max-w-[340px]">
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
      <template v-if="notes.length">
        <SearchSelect
          v-model="projectFilter"
          :options="projectOptions"
          aria-label="按项目筛选"
          class="w-[140px]"
        />
        <span
          v-if="includeProjectTodos && projectFilter && projectFilter !== '__none__'"
          class="inline-flex min-h-8 items-center rounded-full bg-[#007AFF]/[0.10] px-2.5 text-[11px] font-medium text-[#007AFF] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF]"
          title="包含该项目下模块与待办关联的笔记"
        >含待办笔记</span>
        <SearchSelect
          v-model="projectStatus"
          :options="projectStatusOptions"
          aria-label="按项目状态筛选"
          class="w-[120px]"
        />
        <SearchSelect
          v-model="moduleFilter"
          :options="moduleSelectOptions"
          :disabled="!moduleOptions.length"
          aria-label="按模块筛选"
          class="w-[120px]"
        />
        <SearchSelect
          v-model="todoFilter"
          :options="todoSelectOptions"
          :disabled="!todoOptions.length"
          aria-label="按待办筛选"
          class="w-[150px]"
        />
        <Button
          v-if="hasFilters"
          variant="ghost"
          size="icon"
          aria-label="清除筛选"
          title="清除筛选"
          @click="clearFilters"
        >
          <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M4 4l8 8M12 4l-8 8"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
          </svg>
        </Button>
      </template>
      <Button
        variant="ghost"
        class="ml-auto"
        title="管理笔记模板"
        @click="templatesOpen = true"
      >模板</Button>
      <Button variant="primary" @click="openCreate">
        <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        新建笔记
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
        class="ws-skeleton-row h-32 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"
      ></div>
    </div>

    <section
      v-else-if="!filteredNotes.length"
      class="ws-state ws-empty flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.6] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/50"
      @contextmenu.prevent.stop="openEmptyMenu($event)"
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
        :class="{ 'relative z-30': todoMenuNote === note.name }"
        @click="openEdit(note)"
        @contextmenu.prevent.stop="openNoteMenu($event, note)"
      >
        <header class="mb-2 flex items-start justify-between gap-2">
          <div class="min-w-0">
            <h3 class="truncate text-[15px] font-semibold">{{ note.title }}</h3>
            <p class="mt-0.5 text-[11px] text-[#6e6e73] dark:text-[#98989d]">
              更新于 {{ formatDateTime(note.updatedAt) }}
            </p>
          </div>
          <div class="flex shrink-0 items-center opacity-100 transition-opacity lg:opacity-0 lg:group-hover:opacity-100">
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
          <template v-if="summaryBusy === note.name">AI 摘要生成中…</template>
          <template v-else-if="note.summary">
            <span
              class="ws-ai-summary-tag mr-1.5 inline-flex items-center rounded-full bg-[#007AFF]/[0.10] px-1.5 py-0.5 align-[1px] text-[10px] font-medium text-[#007AFF] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF]"
            >AI 摘要</span>{{ note.summary }}
          </template>
          <template v-else>{{ note.excerpt || '空白笔记' }}</template>
        </p>

        <div
          v-if="note.project || note.module || note.todos.length"
          class="mt-3 flex flex-wrap items-center gap-1.5"
        >
          <button
            v-if="note.project"
            type="button"
            class="rounded-full bg-[#007AFF]/[0.10] px-2 py-0.5 text-[11px] font-medium text-[#007AFF] transition-colors duration-150 hover:bg-[#007AFF]/[0.18] disabled:cursor-default dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF] dark:hover:bg-[#0A84FF]/[0.26]"
            title="打开项目页"
            :disabled="!projectTitleOf(note.project)"
            @click.stop="jumpToProject(note)"
          >{{ projectTitleOf(note.project) || '原项目已删除' }}</button>
          <button
            v-if="note.module"
            type="button"
            class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/60 transition-colors duration-150 hover:bg-black/[0.1] dark:bg-white/[0.08] dark:text-[#f5f5f7]/60 dark:hover:bg-white/[0.14]"
            title="查看该模块的待办"
            @click.stop="jumpToModule(note)"
          >模块 · {{ note.module }}</button>
          <div v-if="note.todos.length" class="ws-note-todo-wrap relative">
            <button
              type="button"
              class="rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/60 transition-colors duration-150 hover:bg-black/[0.1] dark:bg-white/[0.08] dark:text-[#f5f5f7]/60 dark:hover:bg-white/[0.14]"
              :title="note.todos.length > 1 ? '选择关联待办' : '跳转到待办'"
              :aria-haspopup="note.todos.length > 1 ? 'menu' : undefined"
              @click.stop="note.todos.length === 1 ? jumpToTodo(note, note.todos[0]) : toggleTodoMenu(note.name)"
            >待办 · {{ note.todos.length }}</button>
            <div
              v-if="todoMenuNote === note.name"
              class="ws-note-todo-menu absolute bottom-full left-0 z-30 mb-1 max-h-56 min-w-[180px] max-w-[280px] overflow-y-auto rounded-xl border border-black/[0.08] bg-white py-1 shadow-[0_12px_32px_rgba(0,0,0,0.18)] dark:border-white/[0.12] dark:bg-[#2c2c2e]"
              role="menu"
              aria-label="关联待办"
            >
              <button
                v-for="name in note.todos"
                :key="name"
                type="button"
                role="menuitem"
                class="block w-full truncate px-3 py-2 text-left text-[12px] text-[#1d1d1f] transition-colors duration-150 hover:bg-black/[0.04] dark:text-[#f5f5f7] dark:hover:bg-white/[0.06]"
                @click.stop="jumpToTodo(note, name)"
              >{{ todoTitleOf(name) || '待办已删除' }}</button>
            </div>
          </div>
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
