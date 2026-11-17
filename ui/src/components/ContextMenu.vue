<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ContextMenuItem } from '../contextMenu'

const props = defineProps<{
  x: number
  y: number
  items: ContextMenuItem[]
}>()

const emit = defineEmits<{ close: [] }>()

const MENU_WIDTH = 200
const ITEM_HEIGHT = 38
const DIVIDER_HEIGHT = 9
const VERTICAL_PADDING = 16
const EDGE_GAP = 8

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
  <Teleport to="body">
    <div
      class="fixed inset-0 z-[1190]"
      aria-hidden="true"
      @pointerdown="emit('close')"
      @contextmenu.prevent="emit('close')"
    ></div>
    <div
      ref="root"
      class="ws-context-menu fixed z-[1200] overflow-y-auto rounded-xl border border-black/[0.08] bg-white py-1 shadow-[0_12px_32px_rgba(0,0,0,0.18)] dark:border-white/[0.12] dark:bg-[#2c2c2e]"
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
          class="my-1 h-px bg-black/[0.07] dark:bg-white/[0.1]"
          role="separator"
        ></div>
        <button
          v-else
          type="button"
          role="menuitem"
          :data-menu-index="index"
          :disabled="item.disabled"
          :aria-disabled="item.disabled"
          class="flex min-h-[38px] w-full items-center px-3 text-left text-[13px] transition-colors duration-150 focus:bg-black/[0.05] focus:outline-none disabled:cursor-default disabled:opacity-35 dark:focus:bg-white/[0.07]"
          :title="item.label"
          :class="item.danger
            ? 'text-[#FF3B30] hover:bg-[#FF3B30]/[0.08] focus:bg-[#FF3B30]/[0.08]'
            : 'text-[#1d1d1f] hover:bg-black/[0.04] dark:text-[#f5f5f7] dark:hover:bg-white/[0.06]'"
          @mouseenter="active = index"
          @click="choose(item)"
        ><span class="min-w-0 truncate">{{ item.label }}</span></button>
      </template>
    </div>
  </Teleport>
</template>
