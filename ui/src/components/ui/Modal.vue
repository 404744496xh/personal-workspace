<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { cn } from '../../lib/utils'
import Button from './Button.vue'

const props = withDefaults(defineProps<{
  title: string
  size?: 'default' | 'wide'
  class?: string
}>(), {
  size: 'default'
})

const emit = defineEmits<{ close: [] }>()

const width = computed(() => props.size === 'wide' ? 'max-w-[820px]' : 'max-w-[520px]')

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div
    class="ws-modal-overlay fixed inset-0 z-[1000] flex items-center justify-center bg-black/40 p-4 backdrop-blur-[2px]"
    @click.self="emit('close')"
  >
    <div
      :class="cn(
        'ws-modal max-h-[calc(100vh-32px)] w-full overflow-y-auto rounded-[18px] border border-white/30 bg-white/[0.92] p-6 shadow-[0_24px_80px_rgba(0,0,0,0.18)] backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/[0.92]',
        width,
        props.class
      )"
      role="dialog"
      aria-modal="true"
      :aria-label="title"
    >
      <header class="ws-modal-header mb-5 flex items-center justify-between">
        <h2 class="text-[17px] font-semibold tracking-[-0.01em]">{{ title }}</h2>
        <slot name="actions">
          <Button variant="ghost" size="sm" @click="emit('close')">关闭</Button>
        </slot>
      </header>
      <slot />
    </div>
  </div>
</template>
