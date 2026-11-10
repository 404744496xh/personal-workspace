<script setup lang="ts">
import { computed } from 'vue'
import { cn } from '../../lib/utils'

defineOptions({ inheritAttrs: false })

const model = defineModel<string>({ default: '' })
const props = defineProps<{ class?: string }>()

const classes = computed(() => cn(
  'w-full resize-y rounded-xl border border-black/[0.08] bg-white/80 px-3.5 py-3 text-[14px] leading-relaxed text-[#1d1d1f] outline-none transition-[border-color,box-shadow,background-color] duration-200 ease-[var(--ws-ease)] placeholder:text-[#1d1d1f]/35 focus:border-[#007AFF]/50 focus:ring-[3px] focus:ring-[#007AFF]/15 disabled:opacity-40 dark:border-white/10 dark:bg-white/[0.06] dark:text-[#f5f5f7] dark:placeholder:text-[#f5f5f7]/30',
  props.class
))
</script>

<template>
  <textarea v-model="model" v-bind="$attrs" :class="classes"></textarea>
</template>
