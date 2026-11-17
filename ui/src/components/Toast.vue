<script setup lang="ts">
defineProps<{ message: string; actionLabel?: string }>()
defineEmits<{ action: [] }>()
</script>

<template>
  <div
    v-if="message"
    class="ws-toast fixed bottom-6 left-1/2 z-[1100] flex -translate-x-1/2 items-center gap-2 rounded-full border border-white/20 bg-[#1d1d1f]/85 py-2 pl-4 pr-2 text-[12px] font-medium text-white shadow-[0_8px_32px_rgba(0,0,0,0.24)] backdrop-blur-xl dark:bg-white/90 dark:text-[#1d1d1f]"
    role="status"
  >
    <span>{{ message }}</span>
    <button
      v-if="actionLabel"
      type="button"
      class="ws-toast-action shrink-0 rounded-full px-2.5 py-1 text-[12px] font-semibold text-[#64B5FF] transition-colors duration-150 hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#64B5FF]/40 dark:text-[#007AFF] dark:hover:bg-black/[0.06]"
      @click="$emit('action')"
    >{{ actionLabel }}</button>
  </div>
</template>
