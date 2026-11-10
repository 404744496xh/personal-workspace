<script setup lang="ts">
import Input from './ui/Input.vue'

withDefaults(defineProps<{
  active?: boolean
  modelValue?: string
  placeholder?: string
  searchLabel?: string
}>(), {
  active: false,
  modelValue: '',
  placeholder: '搜索',
  searchLabel: '搜索'
})
defineEmits<{ expand: []; 'update:modelValue': [value: string] }>()
</script>

<template>
  <div class="ws-tools-toggle-row mb-2 flex flex-wrap items-center gap-2">
    <button
      type="button"
      class="ws-tools-toggle relative inline-flex min-h-9 items-center gap-1.5 rounded-full border border-black/[0.08] bg-white/[0.6] px-3 text-[12px] font-medium text-[#6e6e73] transition-colors duration-150 hover:text-[#1d1d1f] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:border-white/10 dark:bg-white/[0.05] dark:text-[#98989d] dark:hover:text-[#f5f5f7]"
      aria-label="显示筛选与操作"
      title="显示筛选与操作"
      @click="$emit('expand')"
    >
      <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M3 3.5h10L9.5 8v4.2l-3-1.5V8L3 3.5z"
          stroke="currentColor"
          stroke-width="1.3"
          stroke-linejoin="round"
        />
      </svg>
      显示筛选与操作
      <span
        v-if="active"
        class="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-[#007AFF] dark:bg-[#0A84FF]"
        aria-hidden="true"
      ></span>
    </button>
    <div class="relative w-full max-w-[420px] flex-1">
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
        :model-value="modelValue"
        class="ws-search pl-10"
        type="search"
        :placeholder="placeholder"
        :aria-label="searchLabel"
        @update:model-value="$emit('update:modelValue', $event)"
      />
    </div>
  </div>
</template>
