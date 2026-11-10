import { onUnmounted, ref } from 'vue'

export function useToast() {
  const toast = ref('')
  let timer: ReturnType<typeof setTimeout> | undefined

  function show(message: string): void {
    toast.value = message
    if (timer) window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      toast.value = ''
    }, 2400)
  }

  onUnmounted(() => {
    if (timer) window.clearTimeout(timer)
  })

  return { toast, show }
}
