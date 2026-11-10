<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Project, Todo } from '../types'
import Modal from './ui/Modal.vue'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Select from './ui/Select.vue'

const props = defineProps<{
  project: string
  module: string
  todos: string[]
  projects: Project[]
  todoList: Todo[]
}>()

const emit = defineEmits<{
  apply: [value: { project: string; module: string; todos: string[] }]
  close: []
}>()

const project = ref(props.project)
const module = ref(props.module)
const selectedTodos = ref<Set<string>>(new Set(props.todos))

const projectModules = computed(
  () => props.projects.find((item) => item.name === project.value)?.modules ?? []
)
const moduleOptions = computed(() => {
  const options = [...projectModules.value]
  if (module.value && !options.includes(module.value)) {
    options.push(module.value)
  }
  return options
})
const todoOptions = computed(() =>
  props.todoList.filter((todo) => {
    if (project.value && (todo.project ?? '') !== project.value) return false
    if (module.value && (todo.module ?? '') !== module.value) return false
    return true
  })
)

watch([project, module], () => {
  const allowed = new Set(todoOptions.value.map((todo) => todo.name))
  for (const name of [...selectedTodos.value]) {
    if (!allowed.has(name)) selectedTodos.value.delete(name)
  }
})

function toggleTodo(name: string): void {
  if (selectedTodos.value.has(name)) {
    selectedTodos.value.delete(name)
  } else {
    selectedTodos.value.add(name)
  }
}

function apply(): void {
  emit('apply', {
    project: project.value,
    module: project.value ? module.value.trim() : '',
    todos: [...selectedTodos.value]
  })
}
</script>

<template>
  <Modal title="笔记属性" @close="emit('close')">
    <form class="ws-form flex flex-col gap-4" @submit.prevent="apply">
      <div class="ws-field-row grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label class="ws-field flex min-w-0 flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">关联项目</span>
          <Select v-model="project" class="w-full">
            <option value="">不关联</option>
            <option v-for="item in props.projects" :key="item.name" :value="item.name">
              {{ item.title }}
            </option>
          </Select>
        </label>
        <label class="ws-field flex min-w-0 flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">模块</span>
          <Input
            v-model.trim="module"
            :disabled="!project"
            maxlength="32"
            list="ws-note-props-modules"
            placeholder="可不填"
          />
          <datalist id="ws-note-props-modules">
            <option v-for="item in moduleOptions" :key="item" :value="item"></option>
          </datalist>
        </label>
      </div>

      <div class="ws-field flex flex-col gap-1.5">
        <div class="flex items-center justify-between gap-2">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">关联待办</span>
          <span class="text-[11px] text-[#6e6e73] dark:text-[#98989d]">
            {{ selectedTodos.size ? `已选 ${selectedTodos.size} 项` : '可选' }}
          </span>
        </div>
        <div
          class="ws-todo-picker max-h-56 overflow-y-auto rounded-xl border border-black/[0.08] bg-white/70 p-1 dark:border-white/10 dark:bg-white/[0.04]"
        >
          <p
            v-if="!todoOptions.length"
            class="px-3 py-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
          >暂无可关联待办</p>
          <label
            v-for="todo in todoOptions"
            :key="todo.name"
            class="flex min-h-9 cursor-pointer items-center gap-2.5 rounded-lg px-2.5 text-[13px] hover:bg-black/[0.04] dark:hover:bg-white/[0.06]"
          >
            <input
              type="checkbox"
              class="ws-check h-4 w-4 shrink-0 accent-[#007AFF]"
              :checked="selectedTodos.has(todo.name)"
              @change="toggleTodo(todo.name)"
            />
            <span
              class="min-w-0 truncate"
              :class="todo.completed
                ? 'text-[#8e8e93] line-through dark:text-[#98989d]'
                : todo.dismissed ? 'text-[#8e8e93] dark:text-[#98989d]' : ''"
            >{{ todo.title }}{{ todo.dismissed ? '（不用处理）' : '' }}</span>
            <span
              v-if="todo.module"
              class="ml-auto shrink-0 truncate text-[11px] text-[#6e6e73] dark:text-[#98989d]"
            >{{ todo.module }}</span>
          </label>
        </div>
      </div>

      <footer class="ws-modal-footer mt-1 flex justify-end gap-2">
        <Button type="button" variant="ghost" @click="emit('close')">取消</Button>
        <Button type="submit" variant="primary">应用</Button>
      </footer>
    </form>
  </Modal>
</template>
