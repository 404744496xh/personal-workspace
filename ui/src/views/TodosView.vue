<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  ApiError,
  createProject,
  createTodo,
  deleteTodo,
  listNotes,
  listProjects,
  listTodos,
  renameModule,
  updateProject,
  updateTodo
} from '../api'
import type { Priority, Project, Todo, TodoPayload } from '../types'
import { formatDate, formatDateTime, messageOf, priorityClass, priorityLabel, todayString } from '../utils'
import { takeSearchTarget, takeTodosTarget } from '../searchTarget'
import type { NotesTarget } from '../searchTarget'
import TodoForm from '../components/TodoForm.vue'
import QuickAddTodo from '../components/QuickAddTodo.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import ModuleEditor from '../components/ModuleEditor.vue'
import ToolsToggle from '../components/ToolsToggle.vue'
import TodoOverview from '../components/TodoOverview.vue'
import { toolsCollapsed } from '../collapse'
import { readCache, writeCache } from '../cache'
import Button from '../components/ui/Button.vue'
import Input from '../components/ui/Input.vue'
import Select from '../components/ui/Select.vue'

type StatusFilter = 'ALL' | 'OPEN' | 'DONE' | 'DISMISSED'
type SortField = 'created' | 'completed' | 'due' | 'priority'
type SortDirection = 'desc' | 'asc'

interface ModuleGroup {
  key: string
  label: string
  todos: Todo[]
}

interface ProjectGroup {
  key: string
  title: string
  count: number
  modules: ModuleGroup[]
}

const PAGE_SIZE = 500

const cachedTodos = readCache<{ items: Todo[]; total: number }>('todos')
const cachedProjects = readCache<Project[]>('projects')

const emit = defineEmits<{ unauthorized: []; openNotes: [target: NotesTarget] }>()

const items = ref<Todo[]>(cachedTodos?.items ?? [])
const projects = ref<Project[]>(cachedProjects ?? [])
const total = ref(cachedTodos?.total ?? 0)
const loading = ref(!cachedTodos)
const error = ref('')

const keyword = ref(takeSearchTarget())
const todosTarget = takeTodosTarget()
const status = ref<StatusFilter>('ALL')
const priority = ref<Priority | ''>('')
const activeProject = ref<string | null>(todosTarget?.project ?? null)
const activeModule = ref<string | null>(todosTarget?.module ?? null)

const formOpen = ref(false)
const editing = ref<Todo | null>(null)
const saving = ref(false)
const pendingDelete = ref<Todo | null>(null)
const busy = ref<string | null>(null)
const noteBusy = ref<string | null>(null)
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | undefined
let requestSeq = 0

const creatingProject = ref(false)
const newProjectTitle = ref('')
const newProjectModules = ref<string[]>([])
const projectEditor = ref<InstanceType<typeof ModuleEditor> | null>(null)
const savingProject = ref(false)
const projectError = ref('')
const addingModuleFor = ref<string | null>(null)
const newModuleName = ref('')
const savingModule = ref(false)
const moduleError = ref('')

const sortField = ref<SortField>('created')
const sortDir = ref<SortDirection>('desc')
const createdFrom = ref('')
const createdTo = ref('')
const completedFrom = ref('')
const completedTo = ref('')
const selecting = ref(false)
const selected = ref<Set<string>>(new Set())
const showFinished = ref(
  todosTarget?.project
    ? (cachedProjects ?? []).some((project) =>
      project.name === todosTarget.project && project.progress >= 100)
    : false)
let jumpApplied = showFinished.value || !todosTarget?.project
const timeOpen = ref(false)
const renaming = ref<{ project: string; from: string; text: string } | null>(null)
const savingRename = ref(false)
const renameError = ref('')

const hasFilters = computed(() =>
  keyword.value.trim() !== '' || status.value !== 'ALL' || priority.value !== ''
  || activeProject.value !== null || createdFrom.value !== '' || createdTo.value !== ''
  || completedFrom.value !== '' || completedTo.value !== '')
const hasCreatedFilter = computed(() => createdFrom.value !== '' || createdTo.value !== '')
const hasCompletedFilter = computed(() => completedFrom.value !== '' || completedTo.value !== '')
const hasTimeFilters = computed(() => hasCreatedFilter.value || hasCompletedFilter.value)
const hasNonKeywordFilters = computed(() =>
  status.value !== 'ALL' || priority.value !== '' || activeProject.value !== null
  || hasTimeFilters.value)
const timeFilterTitle = computed(() => {
  const parts = [
    hasCreatedFilter.value ? '创建时间' : '',
    hasCompletedFilter.value ? '完成时间' : ''
  ].filter(Boolean)
  return parts.length ? `时间筛选：已设置${parts.join('、')}` : '时间筛选'
})
const sortDirectionLabel = computed(() => {
  const desc = sortDir.value === 'desc'
  if (sortField.value === 'priority') return desc ? '高→低' : '低→高'
  if (sortField.value === 'due') return desc ? '截止晚→早' : '截止早→晚'
  return desc ? '新→旧' : '旧→新'
})
const hiddenProjectNames = computed(() => new Set(
  projects.value.filter((project) => project.progress >= 100)
    .map((project) => project.name)))
const hiddenTodoCount = computed(() =>
  showFinished.value
    ? 0
    : items.value.filter((todo) => todo.project && hiddenProjectNames.value.has(todo.project)).length)
const dismissedCount = computed(() =>
  status.value === 'ALL' ? items.value.filter((todo) => todo.dismissed).length : 0)
const visibleItems = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  const filtered = items.value.filter((todo) => {
    if (!showFinished.value && todo.project
      && hiddenProjectNames.value.has(todo.project)) return false
    if (!matchesStatus(todo)) return false
    if (needle) {
      const haystack = `${todo.title} ${todo.detail ?? ''}`.toLowerCase()
      if (!haystack.includes(needle)) return false
    }
    if (priority.value && todo.priority !== priority.value) return false
    if (activeProject.value !== null && projectKeyOf(todo) !== activeProject.value) return false
    if (activeModule.value !== null && (todo.module ?? '') !== activeModule.value) return false
    if (!matchesCreatedRange(todo)) return false
    if (!matchesCompletedRange(todo)) return false
    return true
  })
  return sortTodos(filtered)
})
const groups = computed(() =>
  buildGroups(visibleItems.value, projects.value, activeProject.value))
const orderedVisible = computed(() =>
  groups.value.flatMap((group) => group.modules.flatMap((module) => module.todos)))
const selectedTodos = computed(() =>
  orderedVisible.value.filter((todo) => selected.value.has(todo.name)))
const allSelected = computed(() =>
  orderedVisible.value.length > 0
  && orderedVisible.value.every((todo) => selected.value.has(todo.name)))
const activeProjectTitle = computed(() => {
  if (activeProject.value === null) return ''
  if (activeProject.value === '') return '未分类'
  return projects.value.find((project) => project.name === activeProject.value)?.title ?? '未分类'
})
const displayedTotal = computed(() =>
  hasFilters.value || hiddenTodoCount.value + dismissedCount.value > 0
    ? visibleItems.value.length
    : total.value)
const truncated = computed(() =>
  activeProject.value === null && total.value > items.value.length)

function matchesStatus(todo: Todo): boolean {
  if (status.value === 'OPEN') return !todo.completed && !todo.dismissed
  if (status.value === 'DONE') return todo.completed
  if (status.value === 'DISMISSED') return todo.dismissed
  return !todo.dismissed
}

function projectKeyOf(todo: Todo): string {
  return todo.project && projects.value.some((project) => project.name === todo.project)
    ? todo.project
    : ''
}

function toggleProject(key: string): void {
  if (activeProject.value === key && activeModule.value === null) {
    activeProject.value = null
  } else {
    activeProject.value = key
    activeModule.value = null
  }
}

function toggleModule(projectKey: string, moduleKey: string): void {
  if (activeProject.value === projectKey && activeModule.value === moduleKey) {
    activeModule.value = null
  } else {
    activeProject.value = projectKey
    activeModule.value = moduleKey
  }
}

function clearSelection(): void {
  activeProject.value = null
  activeModule.value = null
}

function clearAllFilters(): void {
  clearSelection()
  keyword.value = ''
  status.value = 'ALL'
  priority.value = ''
  createdFrom.value = ''
  createdTo.value = ''
  completedFrom.value = ''
  completedTo.value = ''
}

function createNoteHere(target: NotesTarget): void {
  emit('openNotes', { ...target, create: true })
}

async function openRelatedNotes(key: string, target: NotesTarget): Promise<void> {
  if (noteBusy.value) return
  noteBusy.value = key
  try {
    const notes = await listNotes()
    const matched = notes.filter((note) => {
      if (target.project !== undefined && (note.project ?? '') !== target.project) return false
      if (target.module !== undefined && (note.module ?? '') !== target.module) return false
      if (target.todo !== undefined && !note.todos.includes(target.todo)) return false
      return true
    })
    emit('openNotes', {
      ...target,
      noteName: matched.length === 1 ? matched[0].name : undefined
    })
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
    } else {
      showToast(messageOf(err))
    }
  } finally {
    noteBusy.value = null
  }
}

function matchesCreatedRange(todo: Todo): boolean {
  if (!createdFrom.value && !createdTo.value) return true
  if (!todo.createdAt) return false
  const day = formatDate(todo.createdAt)
  if (createdFrom.value && day < createdFrom.value) return false
  if (createdTo.value && day > createdTo.value) return false
  return true
}

function matchesCompletedRange(todo: Todo): boolean {
  if (!completedFrom.value && !completedTo.value) return true
  if (!todo.completedAt) return false
  const day = formatDate(todo.completedAt)
  if (completedFrom.value && day < completedFrom.value) return false
  if (completedTo.value && day > completedTo.value) return false
  return true
}

const PRIORITY_RANK: Record<Priority, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 }

function sortTodos(list: Todo[]): Todo[] {
  const direction = sortDir.value === 'desc' ? -1 : 1
  const keyOf = (todo: Todo): string | null => {
    if (sortField.value === 'created') return todo.createdAt
    if (sortField.value === 'completed') return todo.completedAt
    return todo.dueDate
  }
  return [...list].sort((a, b) => {
    if (sortField.value === 'priority') {
      return (PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority]) * direction
    }
    const left = keyOf(a)
    const right = keyOf(b)
    // 无完成时间 / 无截止时间的待办始终排在末尾
    if (!left && !right) return 0
    if (!left) return 1
    if (!right) return -1
    return left < right ? -direction : left > right ? direction : 0
  })
}

function toggleSortDir(): void {
  sortDir.value = sortDir.value === 'desc' ? 'asc' : 'desc'
}

function buildGroups(todos: Todo[], projectList: Project[],
  expandProject: string | null): ProjectGroup[] {
  const projectMap = new Map(projectList.map((project) => [project.name, project]))
  const buckets = new Map<string, Map<string, Todo[]>>()
  for (const todo of todos) {
    const projectName = todo.project && projectMap.has(todo.project) ? todo.project : ''
    const moduleName = todo.module ?? ''
    const modules = buckets.get(projectName) ?? new Map<string, Todo[]>()
    const list = modules.get(moduleName) ?? []
    list.push(todo)
    modules.set(moduleName, list)
    buckets.set(projectName, modules)
  }
  const result: ProjectGroup[] = []
  for (const project of projectList) {
    const modules = buckets.get(project.name)
    if (!modules && project.name !== expandProject) continue
    const moduleBuckets = modules ?? new Map<string, Todo[]>()
    result.push({
      key: project.name,
      title: project.title,
      count: countTodos(moduleBuckets),
      modules: buildModuleGroups(
        moduleBuckets,
        project.modules,
        project.name === expandProject
      )
    })
  }
  const unclassified = buckets.get('')
  if (unclassified) {
    result.push({
      key: '',
      title: '未分类',
      count: countTodos(unclassified),
      modules: buildModuleGroups(unclassified, [], false)
    })
  }
  return result
}

function countTodos(modules: Map<string, Todo[]>): number {
  let count = 0
  for (const list of modules.values()) count += list.length
  return count
}

function buildModuleGroups(bucket: Map<string, Todo[]>, preferred: string[],
  includeEmpty: boolean): ModuleGroup[] {
  const result: ModuleGroup[] = []
  const used = new Set<string>()
  for (const name of preferred) {
    const todos = bucket.get(name)
    if (todos || includeEmpty) {
      result.push({ key: name, label: name, todos: todos ?? [] })
      used.add(name)
    }
  }
  for (const [name, todos] of bucket) {
    if (name === '' || used.has(name)) continue
    result.push({ key: name, label: name, todos })
  }
  const unassigned = bucket.get('')
  if (unassigned) {
    result.push({ key: '', label: '', todos: unassigned })
  }
  return result
}

function showToast(message: string): void {
  toast.value = message
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 2400)
}

function handleError(err: unknown): void {
  if (err instanceof ApiError && err.status === 401) {
    emit('unauthorized')
    return
  }
  showToast(messageOf(err))
}

function inlineError(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    emit('unauthorized')
    return ''
  }
  return messageOf(err)
}

function persistCache(): void {
  writeCache('todos', { items: items.value, total: total.value })
  writeCache('projects', projects.value)
}

async function load(options: { silent?: boolean } = {}): Promise<void> {
  const seq = ++requestSeq
  if (!options.silent) loading.value = true
  error.value = ''
  try {
    const [projectList, todoList] = await Promise.all([
      listProjects(),
      listTodos({ size: PAGE_SIZE })
    ])
    if (seq !== requestSeq) return
    projects.value = projectList
    items.value = todoList.items
    total.value = todoList.total
    if (!jumpApplied && todosTarget?.project
      && projectList.some((project) =>
        project.name === todosTarget.project && project.progress >= 100)) {
      showFinished.value = true
    }
    jumpApplied = true
    if (selected.value.size) {
      const alive = new Set(todoList.items.map((todo) => todo.name))
      selected.value = new Set([...selected.value].filter((name) => alive.has(name)))
    }
    if (activeProject.value !== null && activeProject.value !== ''
      && !projectList.some((project) => project.name === activeProject.value)) {
      clearSelection()
    }
    persistCache()
  } catch (err) {
    if (seq !== requestSeq) return
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    error.value = messageOf(err)
  } finally {
    if (seq === requestSeq && !options.silent) loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  formOpen.value = true
}
function openEdit(todo: Todo): void {
  editing.value = todo
  formOpen.value = true
}

function closeForm(): void {
  if (saving.value) return
  formOpen.value = false
  editing.value = null
}

async function save(payload: TodoPayload): Promise<void> {
  saving.value = true
  const current = editing.value
  try {
    await ensureProjectModule(payload.project, payload.module)
    if (current) {
      const updated = await updateTodo(current.name, payload)
      const index = items.value.findIndex((item) => item.name === updated.name)
      if (index >= 0) items.value[index] = updated
      showToast('已保存')
    } else {
      const created = await createTodo(payload)
      if (!hasServerFilters() && matchesClientFilters(created)) {
        items.value = [created, ...items.value]
        total.value += 1
      } else {
        await load({ silent: true })
      }
      showToast('已创建')
    }
    persistCache()
    formOpen.value = false
    editing.value = null
  } catch (err) {
    handleError(err)
  } finally {
    saving.value = false
  }
}

function hasServerFilters(): boolean {
  return keyword.value.trim() !== '' || status.value !== 'ALL' || priority.value !== ''
}

function matchesSelection(todo: Todo): boolean {
  if (activeProject.value !== null && projectKeyOf(todo) !== activeProject.value) return false
  if (activeModule.value !== null && (todo.module ?? '') !== activeModule.value) return false
  return true
}

function matchesClientFilters(todo: Todo): boolean {
  return matchesSelection(todo) && matchesCreatedRange(todo) && matchesCompletedRange(todo)
}

async function quickCreate(projectKey: string, moduleName: string,
  title: string): Promise<boolean> {
  const trimmed = title.trim()
  if (!trimmed) return false
  if (trimmed.length > 200) {
    showToast('名称不能超过 200 个字符')
    return false
  }
  const payload: TodoPayload = {
    title: trimmed,
    detail: '',
    priority: 'MEDIUM',
    project: projectKey || null,
    module: projectKey ? (moduleName || null) : null,
    dueDate: null,
    completed: false
  }
  try {
    await ensureProjectModule(payload.project, payload.module)
    const created = await createTodo(payload)
    if (!hasServerFilters() && matchesClientFilters(created)) {
      items.value = [created, ...items.value]
      total.value += 1
    } else {
      await load({ silent: true })
    }
    persistCache()
    return true
  } catch (err) {
    handleError(err)
    return false
  }
}

async function ensureProjectModule(projectName: string | null,
  moduleName: string | null): Promise<void> {
  if (!projectName || !moduleName) return
  const project = projects.value.find((item) => item.name === projectName)
  if (!project || project.modules.includes(moduleName)) return
  const updated = await updateProject(project.name, {
    title: project.title,
    modules: [...project.modules, moduleName]
  })
  const index = projects.value.findIndex((item) => item.name === updated.name)
  if (index >= 0) projects.value[index] = updated
  persistCache()
}

function startCreateProject(): void {
  creatingProject.value = true
  newProjectTitle.value = ''
  newProjectModules.value = []
  projectError.value = ''
}

function cancelCreateProject(): void {
  if (savingProject.value) return
  creatingProject.value = false
  projectError.value = ''
}

async function createInlineProject(): Promise<void> {
  if (!(projectEditor.value?.commit() ?? true)) return
  const titleValue = newProjectTitle.value.trim()
  if (!titleValue) {
    projectError.value = '请输入项目名称'
    return
  }
  if (titleValue.length > 200) {
    projectError.value = '项目名称不能超过 200 个字符'
    return
  }
  savingProject.value = true
  projectError.value = ''
  try {
    const created = await createProject({
      title: titleValue,
      modules: [...newProjectModules.value]
    })
    projects.value = [...projects.value, created]
    creatingProject.value = false
    newProjectTitle.value = ''
    newProjectModules.value = []
    activeProject.value = created.name
    activeModule.value = null
    persistCache()
    showToast('已创建项目')
  } catch (err) {
    projectError.value = inlineError(err)
  } finally {
    savingProject.value = false
  }
}

function startAddModule(projectKey: string): void {
  if (!projectKey) return
  addingModuleFor.value = projectKey
  newModuleName.value = ''
  moduleError.value = ''
}

function cancelAddModule(): void {
  if (savingModule.value) return
  addingModuleFor.value = null
  moduleError.value = ''
}

async function saveModule(projectKey: string): Promise<void> {
  const name = newModuleName.value.trim()
  if (!name) {
    moduleError.value = '请输入模块名称'
    return
  }
  if (name.length > 32) {
    moduleError.value = '模块不能超过 32 个字符'
    return
  }
  const project = projects.value.find((item) => item.name === projectKey)
  if (!project) return
  if (project.modules.includes(name)) {
    activeProject.value = projectKey
    activeModule.value = name
    addingModuleFor.value = null
    return
  }
  if (project.modules.length >= 20) {
    moduleError.value = '模块最多 20 个'
    return
  }
  savingModule.value = true
  moduleError.value = ''
  try {
    const updated = await updateProject(project.name, {
      title: project.title,
      modules: [...project.modules, name]
    })
    const index = projects.value.findIndex((item) => item.name === updated.name)
    if (index >= 0) projects.value[index] = updated
    addingModuleFor.value = null
    newModuleName.value = ''
    activeProject.value = updated.name
    activeModule.value = null
    persistCache()
    showToast('已添加模块')
  } catch (err) {
    moduleError.value = inlineError(err)
  } finally {
    savingModule.value = false
  }
}

function toggleSelecting(): void {
  selecting.value = !selecting.value
  if (!selecting.value) selected.value = new Set()
}

function toggleSelect(name: string): void {
  const next = new Set(selected.value)
  if (next.has(name)) next.delete(name)
  else next.add(name)
  selected.value = next
}

function toggleSelectAll(): void {
  const next = new Set(selected.value)
  if (allSelected.value) {
    for (const todo of orderedVisible.value) next.delete(todo.name)
  } else {
    for (const todo of orderedVisible.value) next.add(todo.name)
  }
  selected.value = next
}

function todoNamesOfGroup(group: ProjectGroup | ModuleGroup): string[] {
  if ('modules' in group) return group.modules.flatMap((module) => module.todos.map((todo) => todo.name))
  return group.todos.map((todo) => todo.name)
}

function groupAllSelected(names: string[]): boolean {
  return names.length > 0 && names.every((name) => selected.value.has(name))
}

function groupPartiallySelected(names: string[]): boolean {
  const count = names.filter((name) => selected.value.has(name)).length
  return count > 0 && count < names.length
}

function toggleSelectGroup(names: string[]): void {
  if (!names.length) return
  const next = new Set(selected.value)
  if (groupAllSelected(names)) {
    for (const name of names) next.delete(name)
  } else {
    for (const name of names) next.add(name)
  }
  selected.value = next
}

function clearSelected(): void {
  selected.value = new Set()
}

function projectTitleOf(todo: Todo): string {
  if (!todo.project) return '未分类'
  return projects.value.find((project) => project.name === todo.project)?.title ?? '未分类'
}

function buildCopyText(todos: Todo[]): string {
  return todos.map((todo) => {
    const marker = todo.completed ? '[x]' : todo.dismissed ? '[-]' : '[ ]'
    let line = `${marker} ${todo.title}（${priorityLabel(todo.priority)}） — ${projectTitleOf(todo)}`
    if (todo.module) line += ` / ${todo.module}`
    if (todo.dueDate) line += ` · 截止 ${todo.dueDate}`
    if (todo.dismissed) line += ' · 不用处理'
    if (todo.completedAt) line += ` · 完成于 ${formatDateTime(todo.completedAt)}`
    if (todo.detail) line += `\n    ${todo.detail.replace(/\r?\n/g, '\n    ')}`
    return line
  }).join('\n')
}

async function writeClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Fall back to the legacy path below when the async API is unavailable.
    }
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(area)
  return ok
}

async function copySelected(): Promise<void> {
  const todos = selectedTodos.value
  if (!todos.length) return
  const ok = await writeClipboard(buildCopyText(todos))
  showToast(ok ? `已复制 ${todos.length} 条待办` : '复制失败，请手动选择文本')
}

function startRenameModule(projectKey: string, from: string): void {
  if (!projectKey) return
  renaming.value = { project: projectKey, from, text: from }
  renameError.value = ''
}

function cancelRenameModule(): void {
  if (savingRename.value) return
  renaming.value = null
  renameError.value = ''
}

async function saveRenameModule(): Promise<void> {
  const current = renaming.value
  if (!current) return
  const to = current.text.trim()
  if (!to) {
    renameError.value = '模块名称不能为空'
    return
  }
  if (to.length > 32) {
    renameError.value = '单个模块不能超过 32 个字符'
    return
  }
  if (to === current.from) {
    cancelRenameModule()
    return
  }
  const project = projects.value.find((item) => item.name === current.project)
  if (project && project.modules.includes(to)) {
    renameError.value = '已存在同名模块'
    return
  }
  savingRename.value = true
  renameError.value = ''
  try {
    const updated = await renameModule(current.project, current.from, to)
    const index = projects.value.findIndex((item) => item.name === updated.name)
    if (index >= 0) projects.value[index] = updated
    items.value = items.value.map((todo) =>
      todo.project === current.project && todo.module === current.from
        ? { ...todo, module: to } : todo)
    if (activeProject.value === current.project && activeModule.value === current.from) {
      activeModule.value = to
    }
    renaming.value = null
    showToast('已重命名模块')
    void load({ silent: true })
  } catch (err) {
    renameError.value = inlineError(err)
  } finally {
    savingRename.value = false
  }
}

async function toggle(todo: Todo): Promise<void> {
  if (busy.value) return
  busy.value = todo.name
  const payload: TodoPayload = {
    title: todo.title,
    detail: todo.detail,
    priority: todo.priority,
    project: todo.project,
    module: todo.module,
    dueDate: todo.dueDate,
    completed: !todo.completed
  }
  const nextCompleted = !todo.completed
  const nextTodo: Todo = {
    ...todo,
    completed: nextCompleted,
    completedAt: nextCompleted ? new Date().toISOString() : null,
    dismissed: nextCompleted ? false : todo.dismissed
  }
  const snapshotItems = items.value.slice()
  const index = items.value.findIndex((item) => item.name === todo.name)
  if (index >= 0) items.value[index] = nextTodo
  try {
    const updated = await updateTodo(todo.name, payload)
    const updatedIndex = items.value.findIndex((item) => item.name === todo.name)
    if (updatedIndex >= 0) items.value[updatedIndex] = updated
  } catch (err) {
    items.value = snapshotItems
    handleError(err)
  } finally {
    busy.value = null
    persistCache()
  }
}

async function setDismissed(todo: Todo, dismissed: boolean): Promise<void> {
  if (busy.value) return
  busy.value = todo.name
  const payload: TodoPayload = {
    title: todo.title,
    detail: todo.detail,
    priority: todo.priority,
    project: todo.project,
    module: todo.module,
    dueDate: todo.dueDate,
    completed: dismissed ? false : todo.completed,
    dismissed
  }
  const nextTodo: Todo = {
    ...todo,
    dismissed,
    completed: dismissed ? false : todo.completed,
    completedAt: dismissed ? null : todo.completedAt
  }
  const snapshotItems = items.value.slice()
  const index = items.value.findIndex((item) => item.name === todo.name)
  if (index >= 0) items.value[index] = nextTodo
  try {
    const updated = await updateTodo(todo.name, payload)
    const updatedIndex = items.value.findIndex((item) => item.name === todo.name)
    if (updatedIndex >= 0) items.value[updatedIndex] = updated
    showToast(dismissed ? '已标记为不用处理' : '已恢复到待办')
  } catch (err) {
    items.value = snapshotItems
    handleError(err)
  } finally {
    busy.value = null
    persistCache()
  }
}

function askDelete(todo: Todo): void {
  pendingDelete.value = todo
}async function removeConfirmed(): Promise<void> {
  const todo = pendingDelete.value
  if (!todo) return
  const snapshotItems = items.value.slice()
  const snapshotTotal = total.value
  pendingDelete.value = null
  items.value = items.value.filter((item) => item.name !== todo.name)
  total.value = Math.max(0, total.value - 1)
  try {
    await deleteTodo(todo.name)
    showToast('已删除')
  } catch (err) {
    items.value = snapshotItems
    total.value = snapshotTotal
    handleError(err)
  }
  persistCache()
}

function isOverdue(todo: Todo): boolean {
  return Boolean(todo.dueDate && !todo.completed && !todo.dismissed
    && todo.dueDate < todayString())
}

onMounted(() => {
  void load({ silent: Boolean(cachedTodos) })
})

onUnmounted(() => {
  if (toastTimer) window.clearTimeout(toastTimer)
})
</script>

<template>
  <div class="ws-view">
    <template v-if="!toolsCollapsed">
    <div class="ws-toolbar mb-4 flex flex-wrap items-center gap-1.5">
      <div class="relative min-w-[130px] flex-1 sm:max-w-[200px]">
        <svg
          class="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-black/30 dark:text-white/30"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.5" />
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
        <Input
          v-model="keyword"
          class="ws-search pl-10"
          type="search"
          placeholder="搜索标题或详情"
          aria-label="搜索待办"
        />
      </div>
      <Select v-model="status" class="w-[104px]" aria-label="完成状态">
        <option value="ALL">全部状态</option>
        <option value="OPEN">未完成</option>
        <option value="DONE">已完成</option>
        <option value="DISMISSED">不用处理</option>
      </Select>
      <Select v-model="priority" class="w-[118px]" aria-label="优先级">
        <option value="">全部优先级</option>
        <option value="HIGH">高</option>
        <option value="MEDIUM">中</option>
        <option value="LOW">低</option>
      </Select>
      <Select v-model="sortField" class="w-[112px]" aria-label="排序字段">
        <option value="created">创建时间</option>
        <option value="completed">完成时间</option>
        <option value="due">截止时间</option>
        <option value="priority">优先级</option>
      </Select>
      <Button
        variant="secondary"
        size="icon"
        class="ws-sort-dir"
        :aria-label="`排序方向：${sortDirectionLabel}`"
        :title="`排序方向：${sortDirectionLabel}（点击切换）`"
        @click="toggleSortDir"
      >
        <svg v-if="sortDir === 'desc'" class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M8 3v10M4.5 9.5L8 13l3.5-3.5"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <svg v-else class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M8 13V3M4.5 6.5L8 3l3.5 3.5"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </Button>
      <Button
        :variant="timeOpen ? 'primary' : 'secondary'"
        size="sm"
        class="ws-time-toggle relative"
        :aria-expanded="timeOpen"
        aria-controls="ws-time-filter"
        :aria-label="timeOpen ? '收起时间筛选' : '展开时间筛选'"
        :title="timeFilterTitle"
        @click="timeOpen = !timeOpen"
      >
        <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <rect x="2.5" y="3.5" width="11" height="10" rx="2" stroke="currentColor" stroke-width="1.3" />
          <path d="M2.5 6.5h11M5.5 2.5v2M10.5 2.5v2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
        </svg>
        时间
        <span
          v-if="hasTimeFilters"
          class="absolute right-2 top-2 h-1.5 w-1.5 rounded-full"
          :class="timeOpen ? 'bg-white dark:bg-[#0A0A0C]' : 'bg-[#007AFF] dark:bg-[#0A84FF]'"
          aria-hidden="true"
        ></span>
      </Button>
      <Button
        :variant="showFinished ? 'primary' : 'secondary'"
        size="icon"
        :aria-label="showFinished ? '隐藏已完结项目（进度 100%）' : '显示已完结项目（进度 100%）'"
        :title="showFinished ? '隐藏已完结项目' : '显示已完结项目'"
        :aria-pressed="showFinished"
        @click="showFinished = !showFinished"
      >
        <svg v-if="showFinished" class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M1.6 8s2.2-4.2 6.4-4.2S14.4 8 14.4 8s-2.2 4.2-6.4 4.2S1.6 8 1.6 8z"
            stroke="currentColor"
            stroke-width="1.3"
          />
          <circle cx="8" cy="8" r="1.9" stroke="currentColor" stroke-width="1.3" />
        </svg>
        <svg v-else class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M1.6 8s2.2-4.2 6.4-4.2S14.4 8 14.4 8s-2.2 4.2-6.4 4.2S1.6 8 1.6 8z"
            stroke="currentColor"
            stroke-width="1.3"
          />
          <circle cx="8" cy="8" r="1.9" stroke="currentColor" stroke-width="1.3" />
          <path d="M3 13L13 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        </svg>
      </Button>
      <div class="ml-auto flex flex-wrap items-center gap-1.5">
        <span class="hidden h-5 w-px bg-black/[0.07] dark:bg-white/10 sm:block" aria-hidden="true"></span>
        <Button
          :variant="selecting ? 'primary' : 'secondary'"
          size="icon"
          :aria-label="selecting ? '退出多选' : '多选'"
          :title="selecting ? '退出多选' : '多选'"
          :aria-pressed="selecting"
          @click="toggleSelecting"
        >
          <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="2.5" y="2.5" width="11" height="11" rx="2.5" stroke="currentColor" stroke-width="1.4" />
            <path d="M5.5 8.2l1.8 1.8 3.2-3.6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </Button>
        <Button variant="primary" @click="openCreate">
          <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          新建待办
        </Button>
      </div>
    </div>

    <div
      v-if="timeOpen"
      id="ws-time-filter"
      class="ws-date-filter mb-4 inline-flex max-w-full flex-wrap items-center gap-x-5 gap-y-2 rounded-[18px] border border-black/[0.06] bg-white/[0.55] px-4 py-3 text-[12px] text-[#6e6e73] backdrop-blur-xl dark:border-white/[0.08] dark:bg-white/[0.04] dark:text-[#98989d]"
    >
      <div class="flex flex-wrap items-center gap-2">
        <span class="font-medium">创建时间</span>
        <input
          v-model="createdFrom"
          type="date"
          class="ws-date"
          aria-label="创建时间起"
        />
        <span>至</span>
        <input
          v-model="createdTo"
          type="date"
          class="ws-date"
          aria-label="创建时间止"
        />
        <Button
          v-if="hasCreatedFilter"
          variant="ghost"
          size="sm"
          @click="createdFrom = ''; createdTo = ''"
        >清除</Button>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="font-medium">完成时间</span>
        <input
          v-model="completedFrom"
          type="date"
          class="ws-date"
          aria-label="完成时间起"
        />
        <span>至</span>
        <input
          v-model="completedTo"
          type="date"
          class="ws-date"
          aria-label="完成时间止"
        />
        <Button
          v-if="hasCompletedFilter"
          variant="ghost"
          size="sm"
          @click="completedFrom = ''; completedTo = ''"
        >清除</Button>
      </div>
    </div>
    </template>

    <ToolsToggle
      v-if="toolsCollapsed"
      v-model="keyword"
      :active="hasNonKeywordFilters"
      placeholder="搜索标题或详情"
      search-label="搜索待办"
      class="ws-todos-tools-collapsed"
      @expand="toolsCollapsed = false"
    />

    <div
      v-if="selecting"
      class="ws-select-bar mb-4 flex flex-wrap items-center gap-3 rounded-[18px] border border-white/20 bg-white/[0.72] px-4 py-2 backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <label class="flex min-h-11 cursor-pointer items-center gap-2 text-[13px] font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">
        <input
          type="checkbox"
          class="ws-select-all h-5 w-5 accent-[#007AFF]"
          :checked="allSelected"
          :indeterminate.prop="selectedTodos.length > 0 && !allSelected"
          aria-label="全选当前列表"
          @change="toggleSelectAll"
        />
        全选
      </label>
      <span class="ws-select-count text-[13px] tabular-nums text-[#6e6e73] dark:text-[#98989d]">
        已选 {{ selectedTodos.length }} 项
      </span>
      <div class="ml-auto flex items-center gap-2">
        <Button variant="ghost" size="sm" :disabled="!selectedTodos.length" @click="clearSelected">
          清除选择
        </Button>
        <Button variant="primary" size="sm" :disabled="!selectedTodos.length" @click="copySelected">
          <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" stroke="currentColor" stroke-width="1.4" />
            <path d="M10.5 5.5V4a1.5 1.5 0 00-1.5-1.5H4A1.5 1.5 0 002.5 4v5A1.5 1.5 0 004 10.5h1.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
          复制为文本
        </Button>
      </div>
    </div>

    <div v-if="activeProject !== null" class="ws-filter-bar mb-4 flex flex-wrap items-center gap-2">
      <span
        class="ws-filter-text inline-flex min-h-8 items-center rounded-full bg-[#007AFF]/[0.08] px-3 text-[12px] font-medium text-[#007AFF] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF]"
      >
        当前筛选：{{ activeProjectTitle }}<template v-if="activeModule !== null">
          / {{ activeModule || '未分模块' }}</template>
      </span>
      <Button variant="ghost" size="sm" @click="clearSelection">清除</Button>
    </div>

    <TodoOverview
      v-if="!selecting && !error && (!loading || items.length)"
      :todos="items"
    />

    <section
      v-if="error"
      class="ws-state ws-state-error flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.72] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <p class="ws-state-title text-[15px] font-semibold">加载失败</p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">{{ error }}</p>
      <Button class="mt-2" @click="load()">重试</Button>
    </section>

    <div v-else-if="loading" class="ws-skeleton flex flex-col gap-3" aria-label="正在加载">
      <div
        v-for="index in 3"
        :key="index"
        class="ws-skeleton-row h-24 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"
      ></div>
    </div>

    <div v-else class="ws-groups flex flex-col gap-4">
      <section
        v-if="!groups.length"
        class="ws-state ws-empty flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.6] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/50"
      >
        <p class="ws-state-title text-[15px] font-semibold">
          {{ hasFilters ? '没有匹配的待办' : dismissedCount > 0 ? '待办都已标为不用处理' : '还没有待办' }}
        </p>
        <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">
          {{ hasFilters ? '换个关键词或筛选条件试试'
            : dismissedCount > 0 ? '需要时可在状态筛选切到「不用处理」找回' : '先写下今天最重要的一件事' }}
        </p>
        <Button
          v-if="hasFilters"
          class="mt-2"
          variant="secondary"
          @click="clearAllFilters"
        >清除筛选</Button>
        <Button
          v-else-if="dismissedCount > 0"
          class="mt-2"
          variant="secondary"
          @click="status = 'DISMISSED'"
        >查看不用处理</Button>
        <Button
          v-else
          class="mt-2"
          variant="primary"
          @click="openCreate"
        >新建待办</Button>
      </section>

      <section
        v-for="group in groups"
        :key="group.key || 'unclassified'"
        class="ws-group overflow-hidden rounded-[18px] border shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] transition-colors duration-200 ease-[var(--ws-ease)]"
        :class="activeProject === group.key && activeModule === null
          ? 'border-black/[0.07] bg-black/[0.02] dark:border-white/[0.12] dark:bg-white/[0.035]'
          : 'border-white/20 bg-white/[0.72] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70'"
      >
        <header class="ws-group-header group/project flex items-center gap-1 px-2 pb-1 pt-2">
          <label
            v-if="selecting && group.count"
            class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center"
          >
            <input
              type="checkbox"
              class="ws-select h-5 w-5 accent-[#007AFF]"
              :checked="groupAllSelected(todoNamesOfGroup(group))"
              :indeterminate.prop="groupPartiallySelected(todoNamesOfGroup(group))"
              :aria-label="`全选 ${group.title} 的待办`"
              @change="toggleSelectGroup(todoNamesOfGroup(group))"
            />
          </label>
          <button
            type="button"
            class="ws-group-head flex min-h-11 min-w-0 flex-1 items-center gap-2 rounded-full px-3 text-left transition-colors duration-200 ease-[var(--ws-ease)] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
            :class="activeProject === group.key
              ? 'text-[#007AFF] dark:text-[#64B5FF]'
              : 'text-[#1d1d1f] hover:bg-black/[0.04] dark:text-[#f5f5f7] dark:hover:bg-white/[0.06]'"
            :aria-pressed="activeProject === group.key"
            @click="toggleProject(group.key)"
          >
            <span class="ws-group-title truncate text-[15px] font-semibold tracking-[-0.01em]">
              {{ group.title }}
            </span>
            <span
              class="ws-group-count rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] font-medium tabular-nums text-[#6e6e73] dark:bg-white/[0.08] dark:text-[#98989d]"
            >{{ group.count }}</span>
            <svg
              class="ml-auto h-3.5 w-3.5 shrink-0 opacity-40 transition-transform duration-200 ease-[var(--ws-ease)]"
              :class="{ 'rotate-90': activeProject === group.key }"
              viewBox="0 0 16 16"
              fill="none"
              aria-hidden="true"
            >
              <path
                d="M6 3l4.5 5L6 13"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
          <Button
            variant="ghost"
            size="icon"
            class="shrink-0 transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover/project:opacity-100 lg:group-focus-within/project:opacity-100"
            :disabled="noteBusy !== null"
            :aria-label="`查看 ${group.title} 的关联笔记`"
            title="查看关联笔记"
            @click="openRelatedNotes(`p:${group.key}`, { project: group.key })"
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
            class="shrink-0 transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover/project:opacity-100 lg:group-focus-within/project:opacity-100"
            :aria-label="`为 ${group.title} 新建笔记`"
            title="新建笔记"
            @click="createNoteHere({ project: group.key })"
          >
            <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M3.5 2.5h7a1 1 0 011 1v9a1 1 0 01-1 1h-7a1 1 0 01-1-1v-9a1 1 0 011-1z"
                stroke="currentColor"
                stroke-width="1.4"
              />
              <path d="M7 5.5v5M4.5 8h5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
          </Button>
          <Button
            v-if="group.key"
            variant="ghost"
            size="icon"
            :aria-label="`在 ${group.title} 下添加模块`"
            title="添加模块"
            @click="startAddModule(group.key)"
          >
            <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            </svg>
          </Button>
        </header>

        <form
          v-if="addingModuleFor === group.key"
          class="ws-module-add flex flex-wrap items-start gap-2 px-4 pb-3"
          @submit.prevent="saveModule(group.key)"
        >
          <Input
            v-model="newModuleName"
            class="min-w-[160px] flex-1"
            maxlength="32"
            autofocus
            :placeholder="`为「${group.title}」添加模块`"
            :aria-label="`新模块名称`"
            @keydown.esc="cancelAddModule"
          />
          <Button type="submit" variant="primary" :disabled="savingModule">
            {{ savingModule ? '添加中…' : '添加' }}
          </Button>
          <Button type="button" variant="ghost" :disabled="savingModule" @click="cancelAddModule">
            取消
          </Button>
          <p
            v-if="moduleError"
            class="ws-form-error w-full text-[12px] text-[#FF3B30]"
            role="alert"
          >{{ moduleError }}</p>
        </form>

        <div class="px-2 pb-3">
          <section
            v-for="moduleGroup in group.modules"
            :key="moduleGroup.key || 'unassigned'"
            class="ws-module group/module rounded-2xl transition-colors duration-200 ease-[var(--ws-ease)]"
            :class="activeProject === group.key && activeModule === moduleGroup.key
              ? 'bg-black/[0.035] dark:bg-white/[0.055]'
              : ''"
          >
            <div v-if="moduleGroup.label" class="ws-module-header flex items-center gap-1">
              <form
                v-if="renaming && renaming.project === group.key && renaming.from === moduleGroup.key"
                class="ws-module-rename flex flex-1 flex-wrap items-center gap-2 px-1 py-1"
                @submit.prevent="saveRenameModule"
              >
                <Input
                  v-model="renaming.text"
                  class="min-w-[140px] flex-1"
                  maxlength="32"
                  autofocus
                  :aria-label="`重命名模块 ${moduleGroup.label}`"
                  @keydown.esc="cancelRenameModule"
                />
                <Button type="submit" variant="primary" size="sm" :disabled="savingRename">
                  {{ savingRename ? '保存中…' : '保存' }}
                </Button>
                <Button type="button" variant="ghost" size="sm" :disabled="savingRename" @click="cancelRenameModule">
                  取消
                </Button>
                <p
                  v-if="renameError"
                  class="ws-form-error w-full text-[12px] text-[#FF3B30]"
                  role="alert"
                >{{ renameError }}</p>
              </form>
              <template v-else>
                <label
                  v-if="selecting && moduleGroup.todos.length"
                  class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center"
                >
                  <input
                    type="checkbox"
                    class="ws-select h-5 w-5 accent-[#007AFF]"
                    :checked="groupAllSelected(todoNamesOfGroup(moduleGroup))"
                    :indeterminate.prop="groupPartiallySelected(todoNamesOfGroup(moduleGroup))"
                    :aria-label="`全选 ${moduleGroup.label} 的待办`"
                    @change="toggleSelectGroup(todoNamesOfGroup(moduleGroup))"
                  />
                </label>
                <button
                  type="button"
                  class="ws-module-title flex min-h-11 min-w-0 flex-1 items-center gap-2 rounded-xl px-3 text-left text-[12px] font-medium transition-colors duration-200 ease-[var(--ws-ease)] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
                  :class="activeProject === group.key && activeModule === moduleGroup.key
                    ? 'text-[#007AFF] dark:text-[#64B5FF]'
                    : 'text-[#6e6e73] hover:bg-black/[0.04] hover:text-[#1d1d1f] dark:text-[#98989d] dark:hover:bg-white/[0.06] dark:hover:text-[#f5f5f7]'"
                  :aria-pressed="activeProject === group.key && activeModule === moduleGroup.key"
                  @click="toggleModule(group.key, moduleGroup.key)"
                >
                  <span class="truncate">{{ moduleGroup.label }}</span>
                  <span class="text-[11px] tabular-nums opacity-50">{{ moduleGroup.todos.length }}</span>
                </button>
                <Button
                  variant="ghost"
                  size="icon"
                  class="shrink-0 transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover/module:opacity-100 lg:group-focus-within/module:opacity-100"
                  :disabled="noteBusy !== null"
                  :aria-label="`查看 ${moduleGroup.label} 的关联笔记`"
                  title="查看关联笔记"
                  @click="openRelatedNotes(`m:${group.key}:${moduleGroup.key}`, { project: group.key, module: moduleGroup.key })"
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
                  class="shrink-0 transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover/module:opacity-100 lg:group-focus-within/module:opacity-100"
                  :aria-label="`为 ${moduleGroup.label} 新建笔记`"
                  title="新建笔记"
                  @click="createNoteHere({ project: group.key, module: moduleGroup.key })"
                >
                  <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M3.5 2.5h7a1 1 0 011 1v9a1 1 0 01-1 1h-7a1 1 0 01-1-1v-9a1 1 0 011-1z"
                      stroke="currentColor"
                      stroke-width="1.4"
                    />
                    <path d="M7 5.5v5M4.5 8h5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
                  </svg>
                </Button>
                <Button
                  v-if="group.key"
                  variant="ghost"
                  size="icon"
                  class="shrink-0 transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover/module:opacity-100 lg:group-focus-within/module:opacity-100"
                  :aria-label="`重命名模块 ${moduleGroup.label}`"
                  title="重命名模块"
                  @click="startRenameModule(group.key, moduleGroup.key)"
                >
                  <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M10.8 2.6a1.4 1.4 0 012 2L5 12.4l-2.6.6.6-2.6 7.8-7.8z"
                      stroke="currentColor"
                      stroke-width="1.4"
                      stroke-linejoin="round"
                    />
                  </svg>
                </Button>
              </template>
            </div>
            <p
              v-if="moduleGroup.label && !moduleGroup.todos.length"
              class="ws-module-empty px-3 pb-2 pt-1 text-[12px] text-[#1d1d1f]/35 dark:text-[#f5f5f7]/30"
            >暂无待办</p>
            <article
              v-for="todo in moduleGroup.todos"
              :key="todo.name"
              class="ws-todo group flex items-start gap-1 rounded-xl px-1 transition-colors duration-200 ease-[var(--ws-ease)] hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
              :class="{
                done: todo.completed,
                dismissed: todo.dismissed,
                'bg-[#007AFF]/[0.06] dark:bg-[#0A84FF]/[0.10]': selecting && selected.has(todo.name)
              }"
            >
              <label
                v-if="selecting"
                class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center"
              >
                <input
                  class="ws-select h-5 w-5 accent-[#007AFF]"
                  type="checkbox"
                  :checked="selected.has(todo.name)"
                  :aria-label="`选择 ${todo.title}`"
                  @change="toggleSelect(todo.name)"
                />
              </label>
              <label
                v-else
                class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center"
              >
                <input
                  class="ws-check h-5 w-5 accent-[#007AFF]"
                  type="checkbox"
                  :checked="todo.completed"
                  :disabled="busy === todo.name"
                  :aria-label="`标记 ${todo.title} 完成`"
                  @change="toggle(todo)"
                />
              </label>
              <div
                class="ws-todo-body min-w-0 flex-1 py-2.5"
                :class="{ 'cursor-pointer': selecting }"
                @click="selecting && toggleSelect(todo.name)"
              >
                <div class="ws-todo-top flex items-baseline gap-2">
                  <h4
                    class="min-w-0 flex-1 truncate text-[14px]"
                    :class="todo.completed || todo.dismissed
                      ? 'text-[#1d1d1f]/45 dark:text-[#f5f5f7]/40'
                      : 'text-[#1d1d1f] dark:text-[#f5f5f7]'"
                  >{{ todo.title }}</h4>
                  <span
                    v-if="todo.dismissed"
                    class="ws-dismissed-tag shrink-0 text-[11px] text-[#8e8e93] dark:text-[#98989d]"
                  >不用处理</span>
                  <span
                    class="ws-prio shrink-0 text-[11px] font-medium tabular-nums"
                    :class="priorityClass(todo.priority)"
                  >{{ priorityLabel(todo.priority) }}</span>
                </div>
                <p
                  v-if="todo.detail"
                  class="ws-todo-detail line-clamp-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
                >{{ todo.detail }}</p>
                <div
                  v-if="todo.dueDate || todo.completedAt || todo.createdAt"
                  class="ws-meta mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]"
                >
                  <span
                    v-if="todo.dueDate"
                    class="ws-due"
                    :class="isOverdue(todo) ? 'text-[#FF3B30]' : ''"
                  >{{ todo.dueDate }}</span>
                  <span v-if="todo.completedAt" class="ws-completed-at">
                    完成于 {{ formatDateTime(todo.completedAt) }}
                  </span>
                  <span v-if="todo.createdAt" class="ws-created-at">
                    创建于 {{ formatDateTime(todo.createdAt) }}
                  </span>
                </div>
              </div>
              <div
                class="ws-todo-actions flex shrink-0 items-center self-center transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover:opacity-100 lg:group-focus-within:opacity-100"
              >
                <Button
                  variant="ghost"
                  size="icon"
                  :aria-label="`为 ${todo.title} 新建笔记`"
                  title="新建笔记"
                  @click="createNoteHere({ todo: todo.name })"
                >
                  <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M3.5 2.5h7a1 1 0 011 1v9a1 1 0 01-1 1h-7a1 1 0 01-1-1v-9a1 1 0 011-1z"
                      stroke="currentColor"
                      stroke-width="1.4"
                    />
                    <path d="M7 5.5v5M4.5 8h5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
                  </svg>
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  :disabled="noteBusy !== null"
                  :aria-label="`查看 ${todo.title} 的关联笔记`"
                  title="查看关联笔记"
                  @click="openRelatedNotes(`t:${todo.name}`, { todo: todo.name })"
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
                  :disabled="busy === todo.name"
                  :aria-label="todo.dismissed
                    ? `恢复 ${todo.title} 到待办`
                    : `将 ${todo.title} 标记为不用处理`"
                  :title="todo.dismissed ? '恢复到待办' : '不用处理'"
                  @click="setDismissed(todo, !todo.dismissed)"
                >
                  <svg v-if="todo.dismissed" class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M3.5 7a4.8 4.8 0 118.2 3.4M3.5 3.5V7h3.5"
                      stroke="currentColor"
                      stroke-width="1.4"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                  <svg v-else class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M2.5 4h11v2.2h-11zM3.7 6.2V13h8.6V6.2M6.3 9h3.4"
                      stroke="currentColor"
                      stroke-width="1.4"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </Button>
                <Button variant="ghost" size="icon" aria-label="编辑" title="编辑" @click="openEdit(todo)">
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
                  @click="askDelete(todo)"
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
            </article>
            <QuickAddTodo
              v-if="moduleGroup.label && !selecting"
              :create="(title) => quickCreate(group.key, moduleGroup.key, title)"
            />
          </section>
          <p
            v-if="!group.modules.length && group.key"
            class="px-3 pb-1 text-[12px] text-[#1d1d1f]/35 dark:text-[#f5f5f7]/30"
          >还没有模块，点右侧 + 添加。</p>
          <QuickAddTodo
            v-if="!selecting"
            :create="(title) => quickCreate(group.key, '', title)"
            :placeholder="group.key ? `添加到「${group.title}」（不分模块）…` : '添加待办…'"
          />
        </div>
      </section>

      <div
        class="ws-create-project rounded-[18px] border border-dashed border-black/[0.14] bg-white/50 p-2 backdrop-blur-xl dark:border-white/[0.14] dark:bg-white/[0.03]"
      >
        <Button
          v-if="!creatingProject"
          variant="ghost"
          class="w-full justify-start gap-2 px-4"
          @click="startCreateProject"
        >
          <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          新建项目
        </Button>
        <form
          v-else
          class="ws-project-form flex flex-col gap-4 p-3"
          @submit.prevent="createInlineProject"
        >
          <div class="flex flex-col gap-1.5">
            <label class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">
              项目名称
            </label>
            <Input
              v-model="newProjectTitle"
              maxlength="200"
              autofocus
              autocomplete="off"
              placeholder="如：个人工作台"
            />
          </div>
          <div class="flex flex-col gap-1.5">
            <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">
              模块（可选，回车或逗号分隔）
            </span>
            <ModuleEditor ref="projectEditor" v-model="newProjectModules" />
          </div>
          <p
            v-if="projectError"
            class="ws-form-error rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
            role="alert"
          >{{ projectError }}</p>
          <div class="flex justify-end gap-2">
            <Button type="button" variant="ghost" :disabled="savingProject" @click="cancelCreateProject">
              取消
            </Button>
            <Button type="submit" variant="primary" :disabled="savingProject">
              {{ savingProject ? '创建中…' : '创建项目' }}
            </Button>
          </div>
        </form>
      </div>
    </div>

    <div
      v-if="!loading && !error && visibleItems.length"
      class="ws-pager mt-4 text-center text-[12px] text-[#6e6e73] dark:text-[#98989d]"
    >
      <span class="ws-pager-total">
        共 {{ displayedTotal }} 条<template v-if="truncated">（仅显示最近 {{ PAGE_SIZE }} 条）</template>
      </span>
    </div>

    <TodoForm
      v-if="formOpen"
      :todo="editing"
      :projects="projects"
      :saving="saving"
      :default-project="activeProject ?? ''"
      :default-module="activeModule ?? ''"
      @submit="save"
      @close="closeForm"
    />

    <ConfirmDialog
      v-if="pendingDelete"
      title="删除待办"
      :message="`确定删除「${pendingDelete.title}」？删除后不可恢复。`"
      confirm-text="删除"
      @confirm="removeConfirmed"
      @close="pendingDelete = null"
    />

    <div
      v-if="toast"
      class="ws-toast fixed bottom-6 left-1/2 z-[1100] -translate-x-1/2 rounded-full border border-white/20 bg-[#1d1d1f]/85 px-4 py-2 text-[12px] font-medium text-white shadow-[0_8px_32px_rgba(0,0,0,0.24)] backdrop-blur-xl dark:bg-white/90 dark:text-[#1d1d1f]"
      role="status"
    >{{ toast }}</div>
  </div>
</template>
