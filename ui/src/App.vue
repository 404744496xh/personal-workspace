<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import HomeView from './views/HomeView.vue'
import TodosView from './views/TodosView.vue'
import ProjectsView from './views/ProjectsView.vue'
import CommandsView from './views/CommandsView.vue'
import NotesView from './views/NotesView.vue'
import BackupView from './views/BackupView.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import ContextMenu from './components/ContextMenu.vue'
import Toast from './components/Toast.vue'
import { useContextMenu } from './contextMenu'
import type { ContextMenuItem } from './contextMenu'
import { useToast } from './lib/toast'
import { copyText } from './utils'
import { setNotesTarget, setSearchTarget, setTodosTarget } from './searchTarget'
import type { NotesTarget, TodosTarget } from './searchTarget'
import { scheme, toggleScheme } from './theme'
import { toolsCollapsed } from './collapse'
import type { SearchType } from './types'

type TabKey = 'home' | 'todos' | 'projects' | 'commands' | 'notes' | 'backup'
type AuthState = 'ok' | 'unauthorized'

interface NavItem {
  key: TabKey
  label: string
  subtitle: string
}

const navItems: NavItem[] = [
  { key: 'todos', label: '待办', subtitle: '按项目与模块整理待办' },
  { key: 'projects', label: '项目', subtitle: '项目进度、模块与关联链接' },
  { key: 'commands', label: '命令', subtitle: '常用命令一键复制' },
  { key: 'notes', label: '笔记', subtitle: 'Markdown 笔记与关联项目待办' },
  { key: 'backup', label: '备份', subtitle: '导出与导入个人数据' }
]

const tab = ref<TabKey>(takeReloadTab())
const viewKey = ref(0)
const SIDEBAR_KEY = 'workspace:sidebar-hidden'
const sidebarHidden = ref(localStorage.getItem(SIDEBAR_KEY) === '1')
const rootElement = document.getElementById('workspace-app')
const initialUsername = rootElement?.dataset.username?.trim() ?? ''
const me = ref<{ username: string } | null>(initialUsername ? { username: initialUsername } : null)
const authState = ref<AuthState>(initialUsername ? 'ok' : 'unauthorized')
const loginUrl = '/login?redirect_uri=' + encodeURIComponent('/workspace')

const RELOAD_TAB_KEY = 'workspace:reload-tab'
const RELOAD_GUARD_KEY = 'workspace:unauthorized-reload-at'
const KEEP_ALIVE_MS = 5 * 60 * 1000
let keepAliveTimer: ReturnType<typeof setInterval> | undefined
let recoveringUnauthorized = false

function isTabKey(value: string | null): value is TabKey {
  return value === 'home' || navItems.some((item) => item.key === value)
}

function takeReloadTab(): TabKey {
  const value = sessionStorage.getItem(RELOAD_TAB_KEY)
  sessionStorage.removeItem(RELOAD_TAB_KEY)
  return isTabKey(value) ? value : 'home'
}

function keepSessionAlive(): void {
  if (authState.value !== 'ok') return
  fetch('/actuator/health', { credentials: 'same-origin' }).catch(() => {})
}

interface ShortcutConfig {
  ctrl: boolean
  shift: boolean
  alt: boolean
  meta: boolean
  keys: string[]
}

function parseShortcut(raw: string, fallback: string): ShortcutConfig {
  const tokens = raw.toLowerCase().split('+').map((token) => token.trim()).filter(Boolean)
  const config: ShortcutConfig = { ctrl: false, shift: false, alt: false, meta: false, keys: [] }
  for (const token of tokens) {
    if (token === 'ctrl' || token === 'control') config.ctrl = true
    else if (token === 'shift') config.shift = true
    else if (token === 'alt' || token === 'option') config.alt = true
    else if (token === 'meta' || token === 'cmd' || token === 'command') config.meta = true
    else config.keys.push(token)
  }
  if (!config.keys.length) {
    config.keys = fallback.toLowerCase().split('+').map((token) => token.trim()).filter(Boolean)
  }
  return config
}

interface ShortcutBinding {
  config: ShortcutConfig
  action: () => void
  progress: number
  timer?: ReturnType<typeof setTimeout>
}

function isEditableTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null
  if (!element) return false
  return element.tagName === 'INPUT' || element.tagName === 'TEXTAREA'
    || element.tagName === 'SELECT' || element.isContentEditable
}

function hasModifiers(config: ShortcutConfig): boolean {
  return config.ctrl || config.shift || config.alt || config.meta
}

function resetBinding(binding: ShortcutBinding): void {
  binding.progress = 0
  if (binding.timer) window.clearTimeout(binding.timer)
}

function resetShortcut(): void {
  for (const binding of shortcutBindings) resetBinding(binding)
}

function toggleCollapseAll(): void {
  const collapsed = !(sidebarHidden.value && toolsCollapsed.value)
  sidebarHidden.value = collapsed
  localStorage.setItem(SIDEBAR_KEY, collapsed ? '1' : '0')
  toolsCollapsed.value = collapsed
}

function openQuickTodo(): void {
  setTodosTarget({ create: true })
  tab.value = 'todos'
  viewKey.value += 1
}

function focusGlobalSearch(): void {
  if (sidebarHidden.value) {
    sidebarHidden.value = false
    localStorage.setItem(SIDEBAR_KEY, '0')
    void nextTick(() => window.dispatchEvent(new CustomEvent('workspace:focus-search')))
    return
  }
  window.dispatchEvent(new CustomEvent('workspace:focus-search'))
}

function createBinding(config: ShortcutConfig, action: () => void): ShortcutBinding {
  return { config, action, progress: 0 }
}

const shortcutBindings: ShortcutBinding[] = [
  createBinding(parseShortcut(rootElement?.dataset.collapseShortcut ?? '', 'z+d+v'), toggleCollapseAll),
  createBinding(parseShortcut(rootElement?.dataset.quickTodoShortcut ?? '', 'n+t'), openQuickTodo),
  createBinding(parseShortcut(rootElement?.dataset.searchShortcut ?? '', 's+s'), focusGlobalSearch)
]

function onShortcutKeydown(event: KeyboardEvent): void {
  if (event.repeat) return
  if (isEditableTarget(event.target)) {
    resetShortcut()
    return
  }
  const key = event.key.toLowerCase()
  for (const binding of shortcutBindings) {
    const config = binding.config
    if (hasModifiers(config)) {
      if (config.keys.length !== 1) continue
      if (event.ctrlKey !== config.ctrl || event.shiftKey !== config.shift
        || event.altKey !== config.alt || event.metaKey !== config.meta) {
        continue
      }
      if (key === config.keys[0]) {
        event.preventDefault()
        resetBinding(binding)
        binding.action()
      }
      continue
    }
    if (event.ctrlKey || event.metaKey || event.altKey) continue
    if (key === config.keys[binding.progress]) {
      binding.progress += 1
      if (binding.progress === config.keys.length) {
        resetBinding(binding)
        event.preventDefault()
        binding.action()
        return
      }
    } else {
      binding.progress = key === config.keys[0] ? 1 : 0
    }
    if (binding.timer) window.clearTimeout(binding.timer)
    if (binding.progress > 0) {
      binding.timer = window.setTimeout(() => resetBinding(binding), 1500)
    }
  }
}

onMounted(() => {
  window.addEventListener('keydown', onShortcutKeydown, true)
  window.addEventListener('contextmenu', onGlobalContextMenu)
  document.addEventListener('visibilitychange', keepSessionAlive)
  keepAliveTimer = window.setInterval(keepSessionAlive, KEEP_ALIVE_MS)
})
onBeforeUnmount(() => {
  resetShortcut()
  window.removeEventListener('keydown', onShortcutKeydown, true)
  window.removeEventListener('contextmenu', onGlobalContextMenu)
  document.removeEventListener('visibilitychange', keepSessionAlive)
  if (keepAliveTimer) window.clearInterval(keepAliveTimer)
})

const current = computed(() => {
  if (tab.value === 'home') return { label: '概览', subtitle: '日历与待办一览' }
  return navItems.find((item) => item.key === tab.value) ?? navItems[0]
})
const themeAction = computed(() => scheme.value === 'dark' ? '切换到浅色模式' : '切换到深色模式')

const { menu: globalMenu, open: openGlobalMenu } = useContextMenu()
const { toast: globalToast, show: showGlobalToast } = useToast()

type EditableElement = HTMLInputElement | HTMLTextAreaElement | HTMLElement

interface EditableSnapshot {
  element: EditableElement
  start: number | null
  end: number | null
  range: Range | null
}

let editableSnapshot: EditableSnapshot | null = null

function editableHost(target: EventTarget | null): EditableElement | null {
  const element = target instanceof Element ? target : null
  if (!element) return null
  const field = element.closest('input, textarea')
  if (field instanceof HTMLInputElement) {
    if (['checkbox', 'radio', 'button', 'submit', 'reset', 'file', 'color', 'range'].includes(field.type)) return null
    return field.readOnly || field.disabled ? null : field
  }
  if (field instanceof HTMLTextAreaElement) {
    return field.readOnly || field.disabled ? null : field
  }
  const editable = element.closest(
    '[contenteditable=""], [contenteditable="true"], [contenteditable="plaintext-only"]')
  return editable instanceof HTMLElement ? editable : null
}

function captureEditable(host: EditableElement): EditableSnapshot {
  if (host instanceof HTMLInputElement || host instanceof HTMLTextAreaElement) {
    return { element: host, start: host.selectionStart, end: host.selectionEnd, range: null }
  }
  const selection = window.getSelection()
  const range = selection && selection.rangeCount > 0
    ? selection.getRangeAt(0).cloneRange()
    : null
  return { element: host, start: null, end: null, range }
}

function hasEditableSelection(snapshot: EditableSnapshot): boolean {
  if (snapshot.element instanceof HTMLInputElement || snapshot.element instanceof HTMLTextAreaElement) {
    return snapshot.start != null && snapshot.end != null && snapshot.start !== snapshot.end
  }
  return Boolean(snapshot.range && !snapshot.range.collapsed)
}

function restoreEditable(snapshot: EditableSnapshot): void {
  snapshot.element.focus({ preventScroll: true })
  if (snapshot.element instanceof HTMLInputElement || snapshot.element instanceof HTMLTextAreaElement) {
    if (snapshot.start != null && snapshot.end != null) {
      snapshot.element.setSelectionRange(snapshot.start, snapshot.end)
    }
    return
  }
  if (snapshot.range) {
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(snapshot.range)
  }
}

function runEditCommand(command: 'undo' | 'redo' | 'cut' | 'copy' | 'selectAll'): void {
  const snapshot = editableSnapshot
  if (!snapshot) return
  restoreEditable(snapshot)
  document.execCommand(command)
}

async function pasteEditable(): Promise<void> {
  const snapshot = editableSnapshot
  if (!snapshot) return
  if (!navigator.clipboard?.readText) {
    showGlobalToast('当前浏览器不支持读取剪贴板，请按 Ctrl+V 粘贴')
    return
  }
  try {
    const text = await navigator.clipboard.readText()
    restoreEditable(snapshot)
    if (!document.execCommand('insertText', false, text)) {
      showGlobalToast('粘贴失败，请按 Ctrl+V 粘贴')
    }
  } catch {
    showGlobalToast('无法读取剪贴板，请按 Ctrl+V 粘贴')
  }
}

function editableMenuItems(snapshot: EditableSnapshot): ContextMenuItem[] {
  const selected = hasEditableSelection(snapshot)
  return [
    { label: '撤销', icon: 'restore', onSelect: () => runEditCommand('undo') },
    { label: '重做', icon: 'redo', onSelect: () => runEditCommand('redo') },
    { divider: true, label: '' },
    { label: '剪切', icon: 'cut', disabled: !selected, onSelect: () => runEditCommand('cut') },
    { label: '复制', icon: 'copy', disabled: !selected, onSelect: () => runEditCommand('copy') },
    { label: '粘贴', icon: 'paste', onSelect: () => void pasteEditable() },
    { label: '全选', icon: 'select', onSelect: () => runEditCommand('selectAll') }
  ]
}

function copyWithFeedback(text: string, success: string): void {
  void copyText(text).then((ok) => showGlobalToast(ok ? success : '复制失败'))
}

function onGlobalContextMenu(event: MouseEvent): void {
  if (event.defaultPrevented) return
  if (!rootElement || !(event.target instanceof Node) || !rootElement.contains(event.target)) return
  editableSnapshot = null
  const host = editableHost(event.target)
  if (host) {
    editableSnapshot = captureEditable(host)
    openGlobalMenu(event, editableMenuItems(editableSnapshot))
    return
  }
  const items: ContextMenuItem[] = []
  if (tab.value !== 'home') {
    items.push({ label: '回到首屏', icon: 'home', onSelect: () => { tab.value = 'home' } })
  }
  items.push({ label: '新建待办', icon: 'plus', onSelect: openQuickTodo })
  items.push({ label: '全局搜索', icon: 'search', onSelect: focusGlobalSearch })
  const selection = window.getSelection()?.toString().trim() ?? ''
  const anchor = event.target instanceof Element ? event.target.closest('a') : null
  if (selection || anchor) items.push({ divider: true, label: '' })
  if (selection) {
    items.push({
      label: '复制选中文本',
      icon: 'text',
      onSelect: () => copyWithFeedback(selection, '已复制选中文本')
    })
  }
  if (anchor instanceof HTMLAnchorElement && anchor.href) {
    const href = anchor.href
    items.push({ label: '在新窗口打开', icon: 'open', onSelect: () => window.open(href, '_blank', 'noopener') })
    items.push({ label: '复制链接地址', icon: 'link', onSelect: () => copyWithFeedback(href, '已复制链接地址') })
  }
  items.push({ divider: true, label: '' })
  items.push({ label: '刷新页面', icon: 'refresh', onSelect: () => window.location.reload() })
  items.push({
    label: '回到顶部',
    icon: 'top',
    disabled: window.scrollY <= 0,
    onSelect: () => window.scrollTo({ top: 0, behavior: 'smooth' })
  })
  items.push({ label: '复制页面链接', icon: 'link', onSelect: () => copyWithFeedback(window.location.href, '已复制页面链接') })
  items.push({ divider: true, label: '' })
  items.push({
    label: themeAction.value,
    icon: scheme.value === 'dark' ? 'sun' : 'moon',
    onSelect: toggleScheme
  })
  openGlobalMenu(event, items)
}

const SEARCH_TAB: Record<SearchType, TabKey> = {
  todo: 'todos',
  project: 'projects',
  command: 'commands',
  note: 'notes'
}

function onUnauthorized(): void {
  // 会话过期时整页重新请求可让 Halo 凭 remember-me 恢复登录，等价于用户手动刷新一次
  if (!recoveringUnauthorized) {
    recoveringUnauthorized = true
    const last = Number(sessionStorage.getItem(RELOAD_GUARD_KEY) ?? '0')
    if (Date.now() - last > 60_000) {
      sessionStorage.setItem(RELOAD_GUARD_KEY, String(Date.now()))
      sessionStorage.setItem(RELOAD_TAB_KEY, tab.value)
      window.location.reload()
      return
    }
  }
  me.value = null
  authState.value = 'unauthorized'
}

function onSearchSelect(type: SearchType, title: string): void {
  tab.value = SEARCH_TAB[type]
  setSearchTarget(title)
  viewKey.value += 1
}

function onOpenNotes(target: NotesTarget): void {
  setNotesTarget(target)
  tab.value = 'notes'
  viewKey.value += 1
}

function onOpenTodos(target: TodosTarget): void {
  setTodosTarget(target)
  tab.value = 'todos'
  viewKey.value += 1
}

function onHomeOpenProjects(title?: string): void {
  if (title) setSearchTarget(title)
  tab.value = 'projects'
  viewKey.value += 1
}

function toggleSidebar(): void {
  sidebarHidden.value = !sidebarHidden.value
  localStorage.setItem(SIDEBAR_KEY, sidebarHidden.value ? '1' : '0')
  // 从折叠态展开侧栏时，一并恢复被快捷键收起的工具区
  if (!sidebarHidden.value) toolsCollapsed.value = false
}
</script>

<template>
  <div
    class="ws-root min-h-[560px] bg-[#f5f5f7] p-3 text-[#1d1d1f] transition-colors duration-300 ease-[var(--ws-ease)] sm:p-6 lg:p-8 dark:bg-[#0b0b0c] dark:text-[#f5f5f7]"
  >
    <div
      class="ws-shell mx-auto grid max-w-[1360px] grid-cols-1 gap-4 lg:gap-6"
      :class="sidebarHidden ? 'lg:grid-cols-1' : 'lg:grid-cols-[208px_minmax(0,1fr)]'"
    >
      <aside
        class="ws-sidebar relative z-30 flex-col gap-4 rounded-[18px] border border-white/20 bg-white/[0.72] p-3 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/70 lg:sticky lg:top-6 lg:h-[calc(100vh-3rem)]"
        :class="sidebarHidden ? 'flex lg:hidden' : 'flex'"
      >
        <div class="ws-brand flex items-center gap-2.5 px-2 py-1.5">
          <button
            type="button"
            class="ws-brand-home -ml-1 flex min-w-0 items-center gap-2.5 rounded-lg px-1 py-0.5 text-left transition-colors duration-150 hover:bg-black/[0.05] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:hover:bg-white/[0.08]"
            aria-label="回到首屏"
            title="回到首屏"
            @click="tab = 'home'"
          >
            <span
              class="ws-logo flex h-7 w-7 shrink-0 items-center justify-center overflow-hidden rounded-[8px] shadow-[0_2px_6px_rgba(0,122,255,0.28)]"
              aria-hidden="true"
            >
              <svg class="h-full w-full" viewBox="0 0 28 28" fill="none">
                <defs>
                  <linearGradient
                    id="ws-logo-gradient"
                    x1="2"
                    y1="1"
                    x2="26"
                    y2="27"
                    gradientUnits="userSpaceOnUse"
                  >
                    <stop stop-color="#5AC8FA" />
                    <stop offset="0.55" stop-color="#007AFF" />
                    <stop offset="1" stop-color="#0A5BD3" />
                  </linearGradient>
                </defs>
                <rect width="28" height="28" rx="8" fill="url(#ws-logo-gradient)" />
                <path
                  d="M7.6 9.2l1.3 1.3 2.2-2.4"
                  stroke="#fff"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <path d="M14.4 9.1h6.2" stroke="#fff" stroke-width="1.6" stroke-linecap="round" />
                <path
                  d="M7.6 15l1.3 1.3 2.2-2.4"
                  stroke="#fff"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <path d="M14.4 14.9h6.2" stroke="#fff" stroke-width="1.6" stroke-linecap="round" />
                <path
                  d="M7.6 20.8l1.3 1.3 2.2-2.4"
                  stroke="#fff"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <path d="M14.4 20.7h4.4" stroke="#fff" stroke-width="1.6" stroke-linecap="round" />
              </svg>
            </span>
            <div class="flex flex-col leading-tight">
              <strong class="text-[14px] font-semibold tracking-[-0.01em]">个人工作台</strong>
              <small
                class="text-[11px] font-medium uppercase tracking-[0.08em] text-[#6e6e73] dark:text-[#98989d]"
              >Workspace</small>
            </div>
          </button>
          <button
            type="button"
            class="ws-sidebar-toggle ml-auto hidden h-8 w-8 items-center justify-center rounded-full text-[#6e6e73] transition-colors duration-150 hover:bg-black/[0.05] hover:text-[#1d1d1f] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 lg:inline-flex dark:text-[#98989d] dark:hover:bg-white/[0.08] dark:hover:text-[#f5f5f7]"
            aria-label="折叠导航"
            title="折叠导航"
            @click="toggleSidebar"
          >
            <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M8.5 4L4.5 8l4 4M12 4L8 8l4 4"
                stroke="currentColor"
                stroke-width="1.4"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </div>

        <div class="ws-page-context min-w-0 px-2">
          <h1 class="truncate text-[20px] font-semibold leading-tight tracking-[-0.02em]">{{ current.label }}</h1>
          <p class="ws-subtitle mt-1 text-[12px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
            {{ current.subtitle }}
          </p>
        </div>

        <nav class="ws-nav flex gap-1 overflow-x-auto lg:flex-col" aria-label="工作台导航">
          <button
            v-for="item in navItems"
            :key="item.key"
            type="button"
            class="ws-nav-item inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3 text-[13px] font-medium transition-colors duration-200 ease-[var(--ws-ease)] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25"
            :class="tab === item.key
              ? 'bg-white text-[#1d1d1f] shadow-[0_1px_2px_rgba(0,0,0,0.06)] dark:bg-white/[0.10] dark:text-[#f5f5f7]'
              : 'text-[#6e6e73] hover:bg-black/[0.04] hover:text-[#1d1d1f] dark:text-[#98989d] dark:hover:bg-white/[0.06] dark:hover:text-[#f5f5f7]'"
            :aria-current="tab === item.key ? 'page' : undefined"
            @click="tab = item.key"
          >
            {{ item.label }}
          </button>
        </nav>

        <div class="ws-sidebar-tools mt-auto flex flex-col gap-2">
          <GlobalSearch
            v-if="authState === 'ok'"
            @select="onSearchSelect"
            @unauthorized="onUnauthorized"
          />
          <div
            class="ws-sidebar-footer flex items-center gap-1.5 border-t border-black/[0.06] px-1 pt-2 text-[12px] dark:border-white/[0.08]"
          >
            <span class="ws-user min-w-0 flex-1 truncate text-[#6e6e73] dark:text-[#98989d]" :title="me?.username ?? ''">
              {{ me?.username ?? '…' }}
            </span>
            <button
              type="button"
              class="ws-theme-toggle inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[#6e6e73] transition-colors duration-150 hover:bg-black/[0.05] hover:text-[#1d1d1f] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:text-[#98989d] dark:hover:bg-white/[0.08] dark:hover:text-[#f5f5f7]"
              :aria-label="themeAction"
              :title="themeAction"
              @click="toggleScheme"
            >
              <svg v-if="scheme === 'dark'" class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.4" />
                <path
                  d="M8 1.5v1.8M8 12.7v1.8M1.5 8h1.8M12.7 8h1.8M3.4 3.4l1.3 1.3M11.3 11.3l1.3 1.3M3.4 12.6l1.3-1.3M11.3 4.7l1.3-1.3"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linecap="round"
                />
              </svg>
              <svg v-else class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M13.2 9.8a5.6 5.6 0 01-7-7 5.8 5.8 0 107 7z"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
            <a
              class="ws-link shrink-0 rounded-full px-2 py-1 font-medium text-[#007AFF] transition-opacity duration-150 hover:opacity-70 dark:text-[#64B5FF]"
              href="/"
            >返回站点</a>
          </div>
        </div>
      </aside>

      <main class="ws-main min-w-0">
        <section
          v-if="authState === 'unauthorized'"
          class="ws-state flex flex-col items-center justify-center gap-3 rounded-[18px] border border-white/20 bg-white/[0.72] px-6 py-16 text-center backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/70"
        >
          <p class="ws-state-title text-[15px] font-semibold">请先登录</p>
          <p class="ws-muted max-w-[360px] text-[13px] text-[#6e6e73] dark:text-[#98989d]">
            登录后即可访问自己的待办、项目、命令与笔记。
          </p>
          <a
            class="ws-btn inline-flex min-h-11 items-center justify-center rounded-full bg-[#007AFF] px-4 text-[13px] font-medium text-white transition-colors duration-200 ease-[var(--ws-ease)] hover:bg-[#0071EB] dark:bg-[#0A84FF] dark:hover:bg-[#2E96FF]"
            :href="loginUrl"
          >去登录</a>
        </section>

        <HomeView
          v-else-if="tab === 'home'"
          @unauthorized="onUnauthorized"
          @open-todos="onOpenTodos"
          @open-projects="onHomeOpenProjects"
          @open-notes="onOpenNotes"
        />
        <TodosView
          v-else-if="tab === 'todos'"
          :key="viewKey"
          @unauthorized="onUnauthorized"
          @open-notes="onOpenNotes"
        />
        <ProjectsView
          v-else-if="tab === 'projects'"
          :key="viewKey"
          @unauthorized="onUnauthorized"
          @open-notes="onOpenNotes"
          @open-todos="onOpenTodos"
        />
        <CommandsView
          v-else-if="tab === 'commands'"
          :key="viewKey"
          @unauthorized="onUnauthorized"
        />
        <NotesView
          v-else-if="tab === 'notes'"
          :key="viewKey"
          @unauthorized="onUnauthorized"
          @open-projects="onHomeOpenProjects"
          @open-todos="onOpenTodos"
        />
        <BackupView
          v-else
          :key="viewKey"
          @unauthorized="onUnauthorized"
        />
      </main>
    </div>

    <button
      v-if="sidebarHidden"
      type="button"
      class="ws-sidebar-expand-rail hidden lg:inline-flex"
      aria-label="展开导航"
      title="展开导航"
      @click="toggleSidebar"
    >
      »
    </button>

    <ContextMenu
      v-if="globalMenu.open"
      :x="globalMenu.x"
      :y="globalMenu.y"
      :items="globalMenu.items"
      @close="globalMenu.open = false"
    />
    <Toast :message="globalToast" />
  </div>
</template>
