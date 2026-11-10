<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import Button from './ui/Button.vue'

defineProps<{
  title: string
  message: string
  confirmText: string
}>()

const emit = defineEmits<{
  confirm: []
  close: []
}>()

const cancelButton = ref<InstanceType<typeof Button> | null>(null)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
  cancelButton.value?.$el?.focus()
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div
    class="ws-modal-overlay fixed inset-0 z-[1000] flex items-center justify-center bg-black/40 p-4 backdrop-blur-[2px]"
    @click.self="emit('close')"
  >
    <div
      class="ws-modal ws-modal-compact w-full max-w-[360px] rounded-[18px] border border-white/30 bg-white/[0.92] p-6 shadow-[0_24px_80px_rgba(0,0,0,0.18)] backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/[0.92]"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="ws-confirm-title"
      aria-describedby="ws-confirm-message"
    >
      <header class="ws-modal-header flex items-center justify-between">
        <h2 id="ws-confirm-title" class="text-[17px] font-semibold tracking-[-0.01em]">
          {{ title }}
        </h2>
      </header>
      <p
        id="ws-confirm-message"
        class="ws-confirm-message mt-3 text-[13px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]"
      >{{ message }}</p>
      <footer class="ws-modal-footer mt-6 flex justify-end gap-2">
        <Button ref="cancelButton" variant="ghost" @click="emit('close')">取消</Button>
        <Button variant="danger" @click="emit('confirm')">{{ confirmText }}</Button>
      </footer>
    </div>
  </div>
</template>
