<script setup lang="ts">
import { nextTick, onUnmounted, ref } from 'vue'

const props = withDefaults(defineProps<{
  create: (title: string) => Promise<boolean>
  placeholder?: string
}>(), {
  placeholder: '添加待办…'
})

const title = ref('')
const focused = ref(false)
const busy = ref(false)
const error = ref('')
const justAdded = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)
let flashTimer: ReturnType<typeof setTimeout> | undefined

async function submit(): Promise<void> {
  const value = title.value.trim()
  if (!value || busy.value) return
  if (value.length > 200) {
    error.value = '名称不能超过 200 个字符'
    return
  }
  busy.value = true
  error.value = ''
  let ok = false
  try {
    ok = await props.create(value)
  } finally {
    busy.value = false
  }
  if (!ok) return
  title.value = ''
  justAdded.value = true
  if (flashTimer) clearTimeout(flashTimer)
  flashTimer = setTimeout(() => { justAdded.value = false }, 1200)
  await nextTick()
  inputEl.value?.focus()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter') {
    event.preventDefault()
    void submit()
  } else if (event.key === 'Escape') {
    title.value = ''
    error.value = ''
    inputEl.value?.blur()
  }
}

function onBlur(): void {
  focused.value = false
  error.value = ''
}

onUnmounted(() => {
  if (flashTimer) clearTimeout(flashTimer)
})
</script>

<template>
  <div
    class="ws-quick-add"
    :class="{ 'is-active': focused || title.trim() !== '', 'is-busy': busy }"
  >
    <form class="ws-quick-add-row" @submit.prevent="submit">
      <span class="ws-quick-add-icon" aria-hidden="true">
        <svg
          v-if="busy"
          class="h-4 w-4 animate-spin"
          viewBox="0 0 16 16"
          fill="none"
        >
          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.6" opacity="0.25" />
          <path d="M14 8a6 6 0 00-6-6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <svg v-else class="h-4 w-4" viewBox="0 0 16 16" fill="none">
          <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
      </span>
      <input
        ref="inputEl"
        v-model="title"
        type="text"
        class="ws-quick-add-input"
        :placeholder="placeholder"
        :disabled="busy"
        :aria-label="placeholder"
        maxlength="200"
        autocomplete="off"
        @focus="focused = true"
        @blur="onBlur"
        @keydown="onKeydown"
      />
      <span v-if="justAdded" class="ws-quick-add-done" aria-hidden="true">
        <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none">
          <path d="M3.5 8.5l3 3 6-7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </span>
      <span v-else-if="focused && title.trim() !== ''" class="ws-quick-add-hint">回车创建</span>
    </form>
    <p v-if="error" class="ws-quick-add-error" role="alert">{{ error }}</p>
  </div>
</template>