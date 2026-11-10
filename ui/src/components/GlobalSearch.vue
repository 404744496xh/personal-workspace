<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiError, searchWorkspace } from '../api'
import type { SearchHit, SearchType } from '../types'
import { messageOf } from '../utils'

const emit = defineEmits<{
  select: [type: SearchType, title: string]
  unauthorized: []
}>()

const TYPE_LABELS: Record<SearchType, string> = {
  todo: '待办',
  project: '项目',
  command: '命令',
  note: '笔记'
}
const TYPE_ORDER: SearchType[] = ['todo', 'project', 'command', 'note']

const keyword = ref('')
const open = ref(false)
const loading = ref(false)
const error = ref('')
const hits = ref<SearchHit[]>([])
const root = ref<HTMLElement | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
let requestSeq = 0

const groups = computed(() => TYPE_ORDER
  .map((type) => ({
    type,
    label: TYPE_LABELS[type],
    items: hits.value.filter((hit) => hit.type === type)
  }))
  .filter((group) => group.items.length))

async function run(): Promise<void> {
  const value = keyword.value.trim()
  if (!value) {
    hits.value = []
    open.value = false
    return
  }
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const response = await searchWorkspace(value)
    if (seq !== requestSeq) return
    hits.value = response.hits
    open.value = true
  } catch (err) {
    if (seq !== requestSeq) return
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    error.value = messageOf(err)
    open.value = true
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

watch(keyword, () => {
  if (timer) window.clearTimeout(timer)
  timer = window.setTimeout(run, 250)
})

function choose(hit: SearchHit): void {
  emit('select', hit.type, hit.title)
  keyword.value = ''
  hits.value = []
  open.value = false
}

function onInputKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    open.value = false
  }
}

function onDocumentMouseDown(event: MouseEvent): void {
  if (root.value && !root.value.contains(event.target as Node)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('mousedown', onDocumentMouseDown))
onUnmounted(() => {
  document.removeEventListener('mousedown', onDocumentMouseDown)
  if (timer) window.clearTimeout(timer)
})
</script>

<template>
  <div ref="root" class="ws-global-search relative w-full max-w-[320px]">
    <svg
      class="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-black/30 dark:text-white/30"
      viewBox="0 0 16 16"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.5" />
      <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
    </svg>
    <input
      v-model="keyword"
      class="h-11 w-full rounded-full border border-black/[0.08] bg-white/80 pl-10 pr-4 text-[13px] text-[#1d1d1f] outline-none transition-[border-color,box-shadow,background-color] duration-200 ease-[var(--ws-ease)] placeholder:text-[#1d1d1f]/35 focus:border-[#007AFF]/50 focus:ring-[3px] focus:ring-[#007AFF]/15 dark:border-white/10 dark:bg-white/[0.06] dark:text-[#f5f5f7] dark:placeholder:text-[#f5f5f7]/30"
      type="search"
      placeholder="全局搜索"
      aria-label="全局搜索"
      autocomplete="off"
      @focus="open = hits.length > 0"
      @keydown="onInputKeydown"
    />

    <div
      v-if="open"
      class="ws-search-panel absolute right-0 top-full z-[900] mt-2 max-h-[420px] w-[min(360px,90vw)] overflow-y-auto rounded-[18px] border border-white/20 bg-white/[0.92] p-2 shadow-[0_24px_80px_rgba(0,0,0,0.18)] backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/[0.92]"
    >
      <p v-if="loading" class="px-3 py-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]">搜索中…</p>
      <p v-else-if="error" class="px-3 py-2 text-[12px] text-[#FF3B30]">{{ error }}</p>
      <p
        v-else-if="!groups.length"
        class="px-3 py-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
      >没有匹配结果</p>
      <template v-else>
        <section v-for="group in groups" :key="group.type" class="mb-1 last:mb-0">
          <p class="px-3 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-[0.08em] text-[#6e6e73]/80 dark:text-[#98989d]/80">
            {{ group.label }}
          </p>
          <button
            v-for="hit in group.items"
            :key="`${hit.type}-${hit.name}`"
            type="button"
            class="flex min-h-11 w-full items-center justify-between gap-3 rounded-xl px-3 text-left transition-colors duration-150 ease-[var(--ws-ease)] hover:bg-black/[0.05] dark:hover:bg-white/[0.08]"
            @click="choose(hit)"
          >
            <span class="truncate text-[13px]">{{ hit.title }}</span>
            <span class="shrink-0 truncate text-[11px] text-[#6e6e73] dark:text-[#98989d]">
              {{ hit.meta }}
            </span>
          </button>
        </section>
      </template>
    </div>
  </div>
</template>
