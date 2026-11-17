<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ContextMenuIcon, ContextMenuItem } from '../contextMenu'

const props = defineProps<{
  x: number
  y: number
  items: ContextMenuItem[]
}>()

const emit = defineEmits<{ close: [] }>()

const MENU_WIDTH = 208
const ITEM_HEIGHT = 36
const DIVIDER_HEIGHT = 11
const VERTICAL_PADDING = 12
const EDGE_GAP = 8

const ICONS: Record<ContextMenuIcon, string[]> = {
  edit: ['M10.8 2.6a1.4 1.4 0 012 2L5 12.4l-2.6.6.6-2.6 7.8-7.8z'],
  check: ['M3.2 8.6l2.6 2.6 6.9-7'],
  restore: ['M3.5 7a4.5 4.5 0 1 1 1.4 4.3', 'M3.5 3.5V7H7'],
  dismiss: ['M3.5 8h9'],
  note: ['M4 2.5h6l2.5 2.5v8.5H4z', 'M10 2.5V5h2.5', 'M6 8h4M6 10.5h4'],
  copy: ['M5.5 5.5h8v8h-8z', 'M10.5 5.5v-1a1 1 0 00-1-1h-6a1 1 0 00-1 1v6a1 1 0 001 1h1'],
  title: ['M3.5 4h9', 'M8 4v8'],
  move: ['M2.5 5.5h4l1.2 1.5h5.8v5.5h-11z', 'M8 8.5h4', 'M10.2 6.7L12 8.5l-1.8 1.8'],
  trash: ['M2.5 4.5h11', 'M6.5 4.5V3h3v1.5', 'M4 4.5l.6 8.5h6.8l.6-8.5'],
  plus: ['M8 3v10', 'M3 8h10'],
  rename: ['M3 12.5l.6-2.4 6.8-6.8a1.2 1.2 0 011.7 1.7L5.3 11.9z'],
  expand: ['M4 6l4 4 4-4'],
  collapse: ['M4 10l4-4 4 4'],
  filter: ['M2.5 3.5h11l-4.2 5v4.2l-2.6-1.4V8.5z'],
  folder: ['M2.5 4.5h4l1.2 1.5h5.8v6.5h-11z'],
  module: ['M3 3h4v4H3z', 'M9 3h4v4H9z', 'M3 9h4v4H3z', 'M9 9h4v4H9z'],
  open: ['M6 3.5h6.5V10', 'M12.5 3.5L7 9'],
  calendar: ['M3.5 4h9v9h-9z', 'M3.5 6.5h9', 'M6 2.5v3M10 2.5v3'],
  checklist: ['M3 4.5h10', 'M3 8h10', 'M3 11.5h6'],
  home: ['M3 7.5L8 3l5 4.5V13H3z'],
  refresh: ['M12.5 6.5A4.5 4.5 0 109 12.5', 'M12.5 3.5v3h-3'],
  top: ['M8 12.5v-9', 'M4.5 7L8 3.5 11.5 7', 'M3.5 13.5h9'],
  search: ['M7 3.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7z', 'M9.8 9.8l3.2 3.2'],
  link: ['M6.5 9.5l3-3', 'M7.5 4.5l1-1a2.1 2.1 0 013 3l-1 1', 'M8.5 11.5l-1 1a2.1 2.1 0 01-3-3l1-1'],
  sun: ['M8 5.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z', 'M8 2v1.5M8 12.5V14M2 8h1.5M12.5 8H14M3.8 3.8l1 1M11.2 11.2l1 1M3.8 12.2l1-1M11.2 4.8l1-1'],
  moon: ['M13.2 9.8a5.6 5.6 0 01-7-7 5.8 5.8 0 107 7z'],
  text: ['M3.5 3.5h9', 'M8 3.5v9', 'M5.5 12.5h5'],
  close: ['M4 4l8 8M12 4l-8 8'],
  paste: ['M5.5 3.5h5V5h-5z', 'M4 5.5H2.5v8h11v-8H12', 'M6 8h4M6 10.5h4'],
  archive: ['M2.5 3.5h11v2.5h-11z', 'M4 6v7.5h8V6', 'M6.5 8.5h3'],
  clock: ['M8 2.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11z', 'M8 5v3.2l2.2 1.3'],
  redo: ['M12.5 6.5A4.5 4.5 0 113.5 9', 'M12.5 3.5v3h-3'],
  cut: ['M5.5 3.5l5 9', 'M10.5 3.5l-5 9', 'M4 13a1.6 1.6 0 100-3.2A1.6 1.6 0 004 13z', 'M12 13a1.6 1.6 0 100-3.2A1.6 1.6 0 0012 13z'],
  select: ['M3 3.5h2M7 3.5h2M11 3.5h2M3 7.5v2M13 7.5v2M3 11.5h2M7 12.5h2M11 12.5h2', 'M3 3.5v2M13 3.5v2M13 11.5v2M3 11.5v2']
}

const root = ref<HTMLElement | null>(null)
const active = ref(-1)
let openedAt = 0

const estimatedHeight = computed(() => props.items.reduce(
  (sum, item) => sum + (item.divider ? DIVIDER_HEIGHT : ITEM_HEIGHT), VERTICAL_PADDING))
const left = computed(() => Math.max(
  EDGE_GAP, Math.min(props.x, window.innerWidth - MENU_WIDTH - EDGE_GAP)))
const top = computed(() => Math.max(
  EDGE_GAP, Math.min(props.y, window.innerHeight - estimatedHeight.value - EDGE_GAP)))

function actionable(item: ContextMenuItem | undefined): boolean {
  return Boolean(item && !item.divider && !item.disabled)
}

function firstActionable(): number {
  return props.items.findIndex((item) => actionable(item))
}

function focusActive(): void {
  void nextTick(() => {
    root.value
      ?.querySelector<HTMLButtonElement>(`[data-menu-index="${active.value}"]`)
      ?.focus()
  })
}

function moveActive(delta: number): void {
  if (!props.items.some((item) => actionable(item))) return
  let next = active.value
  do {
    next = (next + delta + props.items.length) % props.items.length
  } while (!actionable(props.items[next]))
  active.value = next
  focusActive()
}

function choose(item: ContextMenuItem): void {
  if (!actionable(item)) return
  emit('close')
  item.onSelect?.()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    emit('close')
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveActive(1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveActive(-1)
    return
  }
  if (event.key === 'Home') {
    event.preventDefault()
    active.value = firstActionable()
    focusActive()
    return
  }
  if (event.key === 'End') {
    event.preventDefault()
    for (let index = props.items.length - 1; index >= 0; index -= 1) {
      if (actionable(props.items[index])) {
        active.value = index
        focusActive()
        return
      }
    }
    return
  }
  if (event.key === 'Enter' || event.key === ' ') {
    const item = props.items[active.value]
    if (actionable(item)) {
      event.preventDefault()
      choose(item!)
    }
    return
  }
  if (event.key === 'Tab') emit('close')
}

function onScroll(event: Event): void {
  if (event.target instanceof Node && root.value?.contains(event.target)) return
  // 右键时浏览器可能先把目标行滚入视口，忽略刚打开这一瞬间的滚动。
  if (performance.now() - openedAt < 120) return
  emit('close')
}

function onResize(): void {
  emit('close')
}

onMounted(() => {
  openedAt = performance.now()
  active.value = firstActionable()
  focusActive()
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <Teleport to="#workspace-app">
    <div
      class="fixed inset-0 z-[1190]"
      aria-hidden="true"
      @pointerdown="emit('close')"
      @contextmenu.prevent="emit('close')"
    ></div>
    <div
      ref="root"
      class="ws-context-menu fixed z-[1200] overflow-y-auto rounded-[12px] border border-black/[0.08] bg-white/[0.82] p-1.5 font-sans text-[#1d1d1f] shadow-[0_16px_40px_rgba(0,0,0,0.18)] backdrop-blur-xl saturate-150 dark:border-white/[0.12] dark:bg-[#1c1c1e]/88 dark:text-[#f5f5f7]"
      :style="{
        left: `${left}px`,
        top: `${top}px`,
        width: `${MENU_WIDTH}px`,
        maxHeight: 'calc(100vh - 16px)'
      }"
      role="menu"
      aria-label="操作菜单"
      tabindex="-1"
      @contextmenu.prevent
      @keydown="onKeydown"
    >
      <template v-for="(item, index) in items" :key="index">
        <div
          v-if="item.divider"
          class="mx-1 my-1.5 border-t-2 border-dashed border-black/[0.08] dark:border-white/[0.12]"
          role="separator"
        ></div>
        <button
          v-else
          type="button"
          role="menuitem"
          :data-menu-index="index"
          :disabled="item.disabled"
          :aria-disabled="item.disabled"
          class="flex min-h-[36px] w-full items-center gap-2.5 rounded-lg border-0 bg-transparent px-2.5 text-left text-[13px] transition-[background-color,color,box-shadow,transform] duration-200 focus:outline-none active:scale-[0.98] disabled:cursor-default disabled:opacity-35 disabled:active:scale-100"
          :class="item.danger
            ? 'text-[#FF3B30] hover:bg-[#FF3B30] hover:text-white hover:shadow-[0_4px_14px_rgba(255,59,48,0.25)] focus:bg-[#FF3B30] focus:text-white disabled:hover:bg-transparent disabled:hover:text-[#FF3B30] disabled:hover:shadow-none dark:text-[#FF6961] dark:disabled:hover:text-[#FF6961]'
            : 'text-[#1d1d1f] hover:bg-[#007AFF] hover:text-white hover:shadow-[0_4px_14px_rgba(0,122,255,0.28)] focus:bg-[#007AFF] focus:text-white disabled:hover:bg-transparent disabled:hover:text-[#1d1d1f] disabled:hover:shadow-none dark:text-[#f5f5f7] dark:hover:bg-[#0A84FF] dark:focus:bg-[#0A84FF] dark:disabled:hover:bg-transparent dark:disabled:hover:text-[#f5f5f7]'"
          @mouseenter="active = index"
          @click="choose(item)"
        >
          <span class="flex h-5 w-5 shrink-0 items-center justify-center" aria-hidden="true">
            <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none">
              <path
                v-for="(path, pathIndex) in ICONS[item.icon ?? 'note']"
                :key="pathIndex"
                :d="path"
                stroke="currentColor"
                stroke-width="1.4"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </span>
          <span class="min-w-0 truncate" :title="item.label">{{ item.label }}</span>
        </button>
      </template>
    </div>
  </Teleport>
</template>
