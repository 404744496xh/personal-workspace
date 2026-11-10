<script setup lang="ts">
import { ref } from 'vue'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'

const modules = defineModel<string[]>({ default: () => [] })
const emit = defineEmits<{ rename: [from: string, to: string] }>()
const draft = ref('')
const error = ref('')
const editingIndex = ref<number | null>(null)
const editValue = ref('')

function add(): boolean {
  const names = draft.value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
  for (const name of names) {
    if (name.length > 32) {
      error.value = '单个模块不能超过 32 个字符'
      return false
    }
    if (modules.value.includes(name)) continue
    if (modules.value.length >= 20) {
      error.value = '模块最多 20 个'
      return false
    }
    modules.value.push(name)
  }
  draft.value = ''
  error.value = ''
  return true
}

function remove(name: string): void {
  modules.value = modules.value.filter((item) => item !== name)
  error.value = ''
}

function startEdit(index: number, item: string): void {
  editingIndex.value = index
  editValue.value = item
  error.value = ''
}

function cancelEdit(): void {
  editingIndex.value = null
  editValue.value = ''
}

function commitEdit(): boolean {
  const index = editingIndex.value
  if (index === null) return true
  const original = modules.value[index]
  const value = editValue.value.trim()
  if (!value) {
    error.value = '模块名称不能为空'
    return false
  }
  if (value.length > 32) {
    error.value = '单个模块不能超过 32 个字符'
    return false
  }
  if (value === original) {
    cancelEdit()
    error.value = ''
    return true
  }
  if (modules.value.some((item, i) => i !== index && item === value)) {
    error.value = '已存在同名模块'
    return false
  }
  modules.value.splice(index, 1, value)
  emit('rename', original, value)
  cancelEdit()
  error.value = ''
  return true
}

function commit(): boolean {
  if (editingIndex.value !== null && !commitEdit()) return false
  return add()
}

defineExpose({ commit })
</script>

<template>
  <div class="ws-module-editor flex flex-col gap-2">
    <div v-if="modules.length" class="ws-chips flex flex-wrap gap-2">
      <span
        v-for="(item, index) in modules"
        :key="index"
        class="ws-chip group inline-flex min-h-7 items-center gap-1 rounded-full bg-black/[0.05] px-2.5 text-[12px] text-[#1d1d1f]/80 dark:bg-white/[0.08] dark:text-[#f5f5f7]/80"
      >
        <input
          v-if="editingIndex === index"
          v-model="editValue"
          class="ws-chip-input h-7 w-32 rounded-full border border-[#007AFF]/50 bg-white px-2.5 text-[12px] text-[#1d1d1f] outline-none ring-[3px] ring-[#007AFF]/15 dark:bg-[#2c2c2e] dark:text-[#f5f5f7]"
          type="text"
          maxlength="32"
          autofocus
          :aria-label="`重命名模块 ${item}`"
          @keydown.enter.prevent="commitEdit"
          @keydown.esc.prevent="cancelEdit"
          @blur="commitEdit"
        />
        <template v-else>
          <button
            type="button"
            class="ws-chip-label inline-flex items-center gap-1 rounded-full outline-none transition-colors duration-150 ease-[var(--ws-ease)] hover:text-[#007AFF] focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/25 dark:hover:text-[#64B5FF]"
            :title="`点击重命名 ${item}`"
            @click="startEdit(index, item)"
          >
            {{ item }}
            <svg
              class="h-3 w-3 opacity-0 transition-opacity duration-150 group-hover:opacity-50"
              viewBox="0 0 16 16"
              fill="none"
              aria-hidden="true"
            >
              <path
                d="M10.8 2.6a1.4 1.4 0 012 2L5 12.4l-2.6.6.6-2.6 7.8-7.8z"
                stroke="currentColor"
                stroke-width="1.4"
                stroke-linejoin="round"
              />
            </svg>
          </button>
          <button
            type="button"
            class="ws-chip-remove -mr-1 flex h-6 w-6 items-center justify-center rounded-full text-black/35 transition-colors duration-150 ease-[var(--ws-ease)] hover:bg-black/[0.06] hover:text-[#FF3B30] dark:text-white/35 dark:hover:bg-white/[0.08]"
            :aria-label="`移除模块 ${item}`"
            @click="remove(item)"
          >
            <svg class="h-3 w-3" viewBox="0 0 12 12" fill="none" aria-hidden="true">
              <path d="M3 3l6 6M9 3l-6 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
            </svg>
          </button>
        </template>
      </span>
    </div>
    <div class="ws-module-add flex gap-2">
      <Input
        v-model="draft"
        class="flex-1"
        maxlength="32"
        placeholder="输入模块名，回车添加"
        @keydown.enter.prevent="add"
      />
      <Button type="button" variant="secondary" @click="add">添加</Button>
    </div>
    <p v-if="error" class="ws-form-error text-[12px] text-[#FF3B30]">{{ error }}</p>
  </div>
</template>