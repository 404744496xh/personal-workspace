<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { cn } from '../../lib/utils'

interface Option {
  value: string
  label: string
}

defineOptions({ inheritAttrs: false })

const model = defineModel<string>({ default: '' })
const props = defineProps<{
  options: Option[]
  placeholder?: string
  ariaLabel?: string
  disabled?: boolean
  label?: string
  active?: boolean
  class?: string
}>()

const root = ref<HTMLElement | null>(null)
const input = ref<HTMLInputElement | null>(null)
const list = ref<HTMLElement | null>(null)
const open = ref(false)
const query = ref('')
const active = ref(0)

const selected = computed(() => props.options.find((item) => item.value === model.value))
const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return props.options
  return props.options.filter((item) => item.label.toLowerCase().includes(needle))
})

const rootClasses = computed(() => cn(
  'relative inline-flex h-11 items-center rounded-full border transition-[border-color,box-shadow,background-color] duration-200 ease-[var(--ws-ease)] focus-within:ring-[3px] focus-within:ring-[#007AFF]/15',
  props.active
    ? 'border-[#007AFF]/45 bg-[#007AFF]/[0.07] text-[#007AFF] focus-within:border-[#007AFF]/50 dark:border-[#0A84FF]/45 dark:bg-[#0A84FF]/[0.12] dark:text-[#64B5FF]'
    : 'border-black/[0.08] bg-white/80 text-[#1d1d1f] hover:border-black/[0.14] focus-within:border-[#007AFF]/50 dark:border-white/10 dark:bg-white/[0.06] dark:text-[#f5f5f7] dark:hover:border-white/20',
  props.label ? 'pl-3.5' : '',
  props.disabled ? 'opacity-40' : '',
  props.class
))

const inputClasses = computed(() => cn(
  'h-full min-w-0 border-0 bg-transparent text-[13px] text-inherit outline-none placeholder:text-current placeholder:opacity-55',
  props.label ? 'w-auto flex-1 pl-0 pr-8' : 'w-full pl-4 pr-9',
  props.disabled ? 'cursor-default' : ''
))

const chevronClasses = computed(() => cn(
  'pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2',
  props.active ? 'text-current opacity-70' : 'text-black/30 dark:text-white/30'
))

function openList(): void {
  if (props.disabled) return
  open.value = true
  query.value = ''
  active.value = Math.max(0, filtered.value.findIndex((item) => item.value === model.value))
}

function closeList(): void {
  open.value = false
  query.value = ''
}

function choose(option: Option): void {
  model.value = option.value
  closeList()
  input.value?.blur()
}

function onInput(event: Event): void {
  query.value = (event.target as HTMLInputElement).value
  open.value = true
  active.value = 0
}

function scrollActiveIntoView(): void {
  void nextTick(() => {
    list.value?.querySelector<HTMLElement>(`[data-index="${active.value}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  })
}

function move(step: number): void {
  if (!open.value) {
    openList()
    return
  }
  const total = filtered.value.length
  if (!total) return
  active.value = (active.value + step + total) % total
  scrollActiveIntoView()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    move(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    move(-1)
  } else if (event.key === 'Enter') {
    if (!open.value) return
    event.preventDefault()
    const option = filtered.value[active.value]
    if (option) choose(option)
  } else if (event.key === 'Escape') {
    if (!open.value) return
    event.stopPropagation()
    closeList()
  } else if (event.key === 'Tab') {
    closeList()
  }
}

function onDocumentMousedown(event: MouseEvent): void {
  if (!root.value?.contains(event.target as Node)) closeList()
}

onMounted(() => document.addEventListener('mousedown', onDocumentMousedown))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocumentMousedown))
</script>

<template>
  <span ref="root" :class="rootClasses">
    <span
      v-if="label"
      class="shrink-0 cursor-text select-none pr-1.5 text-[11px] font-medium leading-none opacity-50"
      @mousedown.prevent="input?.focus()"
    >{{ label }}</span>
    <input
      ref="input"
      type="text"
      role="combobox"
      autocomplete="off"
      :aria-label="ariaLabel"
      :aria-expanded="open"
      aria-autocomplete="list"
      :disabled="disabled"
      :class="inputClasses"
      :value="open ? query : (selected?.label ?? '')"
      :placeholder="selected?.label ?? placeholder ?? ''"
      @focus="openList"
      @input="onInput"
      @keydown="onKeydown"
    />
    <svg :class="chevronClasses" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path
        d="M6 8l4 4 4-4"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <div
      v-if="open"
      ref="list"
      role="listbox"
      class="absolute left-0 top-full z-40 mt-1 max-h-64 min-w-full overflow-y-auto rounded-xl border border-black/[0.08] bg-white py-1 shadow-[0_12px_32px_rgba(0,0,0,0.18)] dark:border-white/[0.12] dark:bg-[#2c2c2e]"
      :aria-label="ariaLabel"
    >
      <button
        v-for="(item, index) in filtered"
        :key="item.value || '__all__'"
        type="button"
        role="option"
        :aria-selected="item.value === model"
        :data-index="index"
        class="block w-full truncate px-3 py-2 text-left text-[12px] text-[#1d1d1f] transition-colors duration-150 dark:text-[#f5f5f7]"
        :class="index === active ? 'bg-black/[0.05] dark:bg-white/[0.08]' : ''"
        @mousedown.prevent="choose(item)"
        @mouseenter="active = index"
      >{{ item.label }}</button>
      <p
        v-if="!filtered.length"
        class="px-3 py-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
      >没有匹配项</p>
    </div>
  </span>
</template>
