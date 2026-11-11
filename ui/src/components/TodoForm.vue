<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { Priority, Project, Todo, TodoPayload } from '../types'
import { formatDateTime } from '../utils'
import { localDateTimeToIso, toDateTimeLocal } from '../datetime'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Select from './ui/Select.vue'
import Textarea from './ui/Textarea.vue'

const props = defineProps<{
  todo: Todo | null
  projects: Project[]
  saving: boolean
  defaultProject?: string
  defaultModule?: string
}>()

const emit = defineEmits<{
  submit: [payload: TodoPayload]
  close: []
}>()

const title = ref(props.todo?.title ?? '')
const detail = ref(props.todo?.detail ?? '')
const priority = ref<Priority>(props.todo?.priority ?? 'MEDIUM')
const project = ref(props.todo?.project ?? props.defaultProject ?? '')
const module = ref(props.todo?.module ?? props.defaultModule ?? '')
const dueDate = ref(props.todo?.dueDate ?? '')
const reporter = ref(props.todo?.reporter ?? '')
const createdAt = ref(toDateTimeLocal(props.todo?.createdAt)
  || toDateTimeLocal(new Date().toISOString()))
const completed = ref(props.todo?.completed ?? false)
const localError = ref('')

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

watch(project, () => {
  if (module.value && !projectModules.value.includes(module.value)) {
    module.value = ''
  }
})

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && !props.saving) {
    emit('close')
  }
}

function close(): void {
  if (!props.saving) emit('close')
}

function submit(): void {
  const titleValue = title.value.trim()
  if (!titleValue) {
    localError.value = '请输入标题'
    return
  }
  if (titleValue.length > 200) {
    localError.value = '标题不能超过 200 个字符'
    return
  }
  if (dueDate.value && !/^\d{4}-\d{2}-\d{2}$/.test(dueDate.value)) {
    localError.value = '截止日期格式应为 YYYY-MM-DD'
    return
  }
  const createdAtIso = localDateTimeToIso(createdAt.value)
  if (!createdAtIso) {
    localError.value = '请选择有效的创建时间'
    return
  }
  localError.value = ''
  emit('submit', {
    title: titleValue,
    detail: detail.value,
    priority: priority.value,
    project: project.value || null,
    module: module.value || null,
    reporter: reporter.value.trim() || null,
    dueDate: dueDate.value || null,
    completed: completed.value,
    createdAt: createdAtIso
  })
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div
    class="ws-modal-overlay fixed inset-0 z-[1000] flex items-center justify-center bg-black/40 p-4 backdrop-blur-[2px]"
    @click.self="close"
  >
    <div
      class="ws-modal max-h-[calc(100vh-32px)] w-full max-w-[520px] overflow-y-auto rounded-[18px] border border-white/30 bg-white/[0.92] p-6 shadow-[0_24px_80px_rgba(0,0,0,0.18)] backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/[0.92]"
      role="dialog"
      aria-modal="true"
      aria-labelledby="ws-todo-form-title"
    >
      <header class="ws-modal-header mb-5 flex items-center justify-between">
        <h2 id="ws-todo-form-title" class="text-[17px] font-semibold tracking-[-0.01em]">
          {{ todo ? '编辑待办' : '新建待办' }}
        </h2>
        <Button variant="ghost" size="sm" :disabled="saving" @click="close">关闭</Button>
      </header>

      <form class="ws-form flex flex-col gap-4" @submit.prevent="submit">
        <label class="ws-field flex flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">标题</span>
          <Input
            v-model="title"
            autofocus
            maxlength="200"
            autocomplete="off"
            placeholder="要做什么"
          />
        </label>

        <label class="ws-field flex flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">详情</span>
          <Textarea
            v-model="detail"
            maxlength="5000"
            rows="4"
            placeholder="补充说明、验收标准或上下文"
          ></Textarea>
        </label>

        <div class="ws-field-row grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label class="ws-field flex min-w-0 flex-col gap-1.5">
            <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">项目</span>
            <Select v-model="project" class="w-full">
              <option value="">未分类</option>
              <option v-for="item in projects" :key="item.name" :value="item.name">
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
              list="ws-module-options"
              placeholder="未分模块，可直接输入新模块"
            />
            <datalist id="ws-module-options">
              <option v-for="item in moduleOptions" :key="item" :value="item"></option>
            </datalist>
          </label>
        </div>
        <p class="ws-hint -mt-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          模块可直接输入新模块，保存后自动加入项目。
        </p>

        <div class="ws-field-row grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label class="ws-field flex min-w-0 flex-col gap-1.5">
            <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">优先级</span>
            <Select v-model="priority" class="w-full">
              <option value="HIGH">高</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
            </Select>
          </label>
          <label class="ws-field flex min-w-0 flex-col gap-1.5">
            <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">截止日期</span>
            <input
              v-model="dueDate"
              class="h-11 w-full rounded-xl border border-black/[0.08] bg-white/80 px-3.5 text-[14px] text-[#1d1d1f] outline-none transition-[border-color,box-shadow,background-color] duration-200 ease-[var(--ws-ease)] focus:border-[#007AFF]/50 focus:ring-[3px] focus:ring-[#007AFF]/15 dark:border-white/10 dark:bg-white/[0.06] dark:text-[#f5f5f7] dark:[color-scheme:dark]"
              type="date"
            />
          </label>
        </div>

        <div class="ws-field-row grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label class="ws-field flex min-w-0 flex-col gap-1.5">
            <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">姓名</span>
            <Input
              v-model.trim="reporter"
              maxlength="64"
              autocomplete="off"
              placeholder="提出人姓名，例如 张三"
            />
          </label>
          <label class="ws-field flex min-w-0 flex-col gap-1.5">
            <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">创建时间</span>
            <input
              v-model="createdAt"
              class="ws-created-input h-11 w-full rounded-xl border border-black/[0.08] bg-white/80 px-3.5 text-[14px] text-[#1d1d1f] outline-none transition-[border-color,box-shadow,background-color] duration-200 ease-[var(--ws-ease)] focus:border-[#007AFF]/50 focus:ring-[3px] focus:ring-[#007AFF]/15 dark:border-white/10 dark:bg-white/[0.06] dark:text-[#f5f5f7] dark:[color-scheme:dark]"
              type="datetime-local"
            />
          </label>
        </div>

        <label
          class="ws-check-row flex min-h-11 items-center gap-3 text-[13px] text-[#6e6e73] dark:text-[#98989d]"
        >
          <input v-model="completed" type="checkbox" class="ws-check h-5 w-5 accent-[#007AFF]" />
          <span>已完成</span>
          <span v-if="todo?.completedAt" class="ml-auto text-[12px] tabular-nums">
            完成于 {{ formatDateTime(todo.completedAt) }}
          </span>
        </label>

        <p
          v-if="localError"
          class="ws-form-error rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
          role="alert"
        >{{ localError }}</p>

        <footer class="ws-modal-footer mt-1 flex justify-end gap-2">
          <Button type="button" variant="ghost" :disabled="saving" @click="close">取消</Button>
          <Button type="submit" variant="primary" :disabled="saving">
            {{ saving ? '保存中…' : '保存' }}
          </Button>
        </footer>
      </form>
    </div>
  </div>
</template>
