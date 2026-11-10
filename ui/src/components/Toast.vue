<script setup lang="ts">
defineProps<{ message: string }>()
</script>

<template>
  <div
    v-if="message"
    class="ws-toast fixed bottom-6 left-1/2 z-[1100] -translate-x-1/2 rounded-full border border-white/20 bg-[#1d1d1f]/85 px-4 py-2 text-[12px] font-medium text-white shadow-[0_8px_32px_rgba(0,0,0,0.24)] backdrop-blur-xl dark:bg-white/90 dark:text-[#1d1d1f]"
    role="status"
  >{{ message }}</div>
</template>
