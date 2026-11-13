<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import HomeView from './views/HomeView.vue'
import TodosView from './views/TodosView.vue'
import ProjectsView from './views/ProjectsView.vue'
import CommandsView from './views/CommandsView.vue'
import NotesView from './views/NotesView.vue'
import BackupView from './views/BackupView.vue'
import GlobalSearch from './components/GlobalSearch.vue'
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

const tab = ref<TabKey>('home')
const viewKey = ref(0)
const SIDEBAR_KEY = 'workspace:sidebar-hidden'
const sidebarHidden = ref(localStorage.getItem(SIDEBAR_KEY) === '1')
const rootElement = document.getElementById('workspace-app')
const initialUsername = rootElement?.dataset.username?.trim() ?? ''
const me = ref<{ username: string } | null>(initialUsername ? { username: initialUsername } : null)
const authState = ref<AuthState>(initialUsername ? 'ok' : 'unauthorized')
const loginUrl = '/login?redirect_uri=' + encodeURIComponent('/workspace')

interface ShortcutConfig {
  ctrl: boolean
  shift: boolean
  alt: boolean
  meta: boolean
  keys: string[]
}

function parseShortcut(raw: string): ShortcutConfig {
  const tokens = raw.toLowerCase().split('+').map((token) => token.trim()).filter(Boolean)
  const config: ShortcutConfig = { ctrl: false, shift: false, alt: false, meta: false, keys: [] }
  for (const token of tokens) {
    if (token === 'ctrl' || token === 'control') config.ctrl = true
    else if (token === 'shift') config.shift = true
    else if (token === 'alt' || token === 'option') config.alt = true
    else if (token === 'meta' || token === 'cmd' || token === 'command') config.meta = true
    else config.keys.push(token)
  }
  if (!config.keys.length) config.keys = ['z', 'd', 'v']
  return config
}

const shortcut = parseShortcut(rootElement?.dataset.collapseShortcut ?? 'z+d+v')
const hasModifiers = shortcut.ctrl || shortcut.shift || shortcut.alt || shortcut.meta
let shortcutProgress = 0
let shortcutTimer: ReturnType<typeof setTimeout> | undefined

function isEditableTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null
  if (!element) return false
  return element.tagName === 'INPUT' || element.tagName === 'TEXTAREA'
    || element.tagName === 'SELECT' || element.isContentEditable
}

function resetShortcut(): void {
  shortcutProgress = 0
  if (shortcutTimer) window.clearTimeout(shortcutTimer)
}

function toggleCollapseAll(): void {
  const collapsed = !(sidebarHidden.value && toolsCollapsed.value)
  sidebarHidden.value = collapsed
  localStorage.setItem(SIDEBAR_KEY, collapsed ? '1' : '0')
  toolsCollapsed.value = collapsed
}

function onShortcutKeydown(event: KeyboardEvent): void {
  if (event.repeat) return
  if (isEditableTarget(event.target)) {
    resetShortcut()
    return
  }
  const key = event.key.toLowerCase()
  if (hasModifiers) {
    if (shortcut.keys.length !== 1) return
    if (event.ctrlKey !== shortcut.ctrl || event.shiftKey !== shortcut.shift
      || event.altKey !== shortcut.alt || event.metaKey !== shortcut.meta) {
      return
    }
    if (key === shortcut.keys[0]) {
      event.preventDefault()
      toggleCollapseAll()
    }
    return
  }
  if (event.ctrlKey || event.metaKey || event.altKey) return
  if (key === shortcut.keys[shortcutProgress]) {
    shortcutProgress += 1
    if (shortcutProgress === shortcut.keys.length) {
      resetShortcut()
      event.preventDefault()
      toggleCollapseAll()
      return
    }
  } else {
    shortcutProgress = key === shortcut.keys[0] ? 1 : 0
  }
  if (shortcutTimer) window.clearTimeout(shortcutTimer)
  if (shortcutProgress > 0) {
    shortcutTimer = window.setTimeout(resetShortcut, 1500)
  }
}

onMounted(() => window.addEventListener('keydown', onShortcutKeydown, true))
onBeforeUnmount(() => {
  resetShortcut()
  window.removeEventListener('keydown', onShortcutKeydown, true)
})

const current = computed(() => {
  if (tab.value === 'home') return { label: '概览', subtitle: '日历与待办一览' }
  return navItems.find((item) => item.key === tab.value) ?? navItems[0]
})
const themeAction = computed(() => scheme.value === 'dark' ? '切换到浅色模式' : '切换到深色模式')

const SEARCH_TAB: Record<SearchType, TabKey> = {
  todo: 'todos',
  project: 'projects',
  command: 'commands',
  note: 'notes'
}

function onUnauthorized(): void {
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
      class="ws-shell mx-auto grid max-w-[1200px] grid-cols-1 gap-4 lg:gap-6"
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
  </div>
</template>
