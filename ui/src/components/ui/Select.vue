<script setup lang="ts">
import { computed } from 'vue'
import { cn } from '../../lib/utils'

defineOptions({ inheritAttrs: false })

const model = defineModel<string>({ default: '' })
const props = defineProps<{ class?: string }>()

const classes = computed(() => cn(
  'h-11 w-full appearance-none rounded-full border border-black/[0.08] bg-white/80 pl-4 pr-9 text-[13px] text-[#1d1d1f] outline-none transition-[border-color,box-shadow,background-color] duration-200 ease-[var(--ws-ease)] focus:border-[#007AFF]/50 focus:ring-[3px] focus:ring-[#007AFF]/15 disabled:opacity-40 dark:border-white/10 dark:bg-white/[0.06] dark:text-[#f5f5f7]',
  'rounded-full'
))
</script>

<template>
  <span :class="cn('relative inline-flex', props.class)">
    <select v-model="model" v-bind="$attrs" :class="classes">
      <slot />
    </select>
    <svg
      class="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-black/30 dark:text-white/30"
      viewBox="0 0 20 20"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M6 8l4 4 4-4"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
  </span>
</template>
