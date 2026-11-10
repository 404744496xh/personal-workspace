<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { createProject, deleteProject, renameModule, updateProject } from '../api'
import type { Project, ProjectPayload } from '../types'
import { messageOf } from '../utils'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import ModuleEditor from './ModuleEditor.vue'

const props = defineProps<{
  projects: Project[]
}>()

const emit = defineEmits<{
  close: []
  changed: []
}>()

const editing = ref<Project | null>(null)
const title = ref('')
const moduleDraft = ref<string[]>([])
const editor = ref<InstanceType<typeof ModuleEditor> | null>(null)
const busy = ref(false)
const error = ref('')
const pendingDelete = ref<Project | null>(null)

function resetForm(): void {
  editing.value = null
  title.value = ''
  moduleDraft.value = []
  error.value = ''
}

function startEdit(project: Project): void {
  editing.value = project
  title.value = project.title
  moduleDraft.value = [...project.modules]
  error.value = ''
}

function submit(): void {
  if (!(editor.value?.commit() ?? true)) return
  const titleValue = title.value.trim()
  if (!titleValue) {
    error.value = '请输入项目名称'
    return
  }
  if (titleValue.length > 200) {
    error.value = '项目名称不能超过 200 个字符'
    return
  }
  const modules = [...moduleDraft.value]
  if (modules.length > 20) {
    error.value = '模块最多 20 个'
    return
  }
  if (modules.some((item) => item.length > 32)) {
    error.value = '单个模块不能超过 32 个字符'
    return
  }
  const payload: ProjectPayload = { title: titleValue, modules }
  busy.value = true
  const request = editing.value
    ? updateProject(editing.value.name, payload)
    : createProject(payload)
  request
    .then(() => {
      resetForm()
      emit('changed')
    })
    .catch((err) => {
      error.value = messageOf(err)
    })
    .finally(() => {
      busy.value = false
    })
}

async function removeConfirmed(): Promise<void> {
  const project = pendingDelete.value
  if (!project) return
  busy.value = true
  try {
    await deleteProject(project.name)
    pendingDelete.value = null
    if (editing.value?.name === project.name) {
      resetForm()
    }
    error.value = ''
    emit('changed')
  } catch (err) {
    pendingDelete.value = null
    error.value = messageOf(err)
  } finally {
    busy.value = false
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && !busy.value) emit('close')
}

function onModuleRename(from: string, to: string): void {
  const project = editing.value
  if (!project) return
  busy.value = true
  renameModule(project.name, from, to)
    .then(() => emit('changed'))
    .catch((err) => {
      error.value = messageOf(err)
    })
    .finally(() => {
      busy.value = false
    })
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
      class="ws-modal ws-project-modal max-h-[calc(100vh-32px)] w-full max-w-[560px] overflow-y-auto rounded-[18px] border border-white/30 bg-white/[0.92] p-6 shadow-[0_24px_80px_rgba(0,0,0,0.18)] backdrop-blur-xl saturate-[180%] dark:border-white/10 dark:bg-[#1c1c1e]/[0.92]"
      role="dialog"
      aria-modal="true"
      aria-labelledby="ws-project-title"
    >
      <header class="ws-modal-header mb-5 flex items-center justify-between">
        <h2 id="ws-project-title" class="text-[17px] font-semibold tracking-[-0.01em]">项目管理</h2>
        <Button variant="ghost" size="sm" :disabled="busy" @click="emit('close')">关闭</Button>
      </header>

      <form class="ws-form flex flex-col gap-4" @submit.prevent="submit">
        <label class="ws-field flex flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">项目名称</span>
          <Input v-model="title" maxlength="200" autocomplete="off" placeholder="如：个人工作台" />
        </label>
        <div class="ws-field flex flex-col gap-1.5">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">模块</span>
          <ModuleEditor ref="editor" v-model="moduleDraft" @rename="onModuleRename" />
        </div>
        <p class="ws-hint -mt-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          最多 20 个模块，单个不超过 32 个字符；配置好后在待办表单里选择。
        </p>
        <p
          v-if="error"
          class="ws-form-error rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
          role="alert"
        >{{ error }}</p>
        <footer class="ws-modal-footer flex justify-end gap-2">
          <Button v-if="editing" type="button" variant="ghost" :disabled="busy" @click="resetForm">
            取消编辑
          </Button>
          <Button type="submit" variant="primary" :disabled="busy">
            {{ editing ? '保存修改' : '新建项目' }}
          </Button>
        </footer>
      </form>

      <ul
        class="ws-project-list mt-6 flex flex-col gap-1 border-t border-black/[0.06] pt-4 dark:border-white/[0.08]"
      >
        <li
          v-for="project in props.projects"
          :key="project.name"
          class="ws-project-item flex items-start justify-between gap-3 rounded-xl px-3 py-2.5 transition-colors duration-200 ease-[var(--ws-ease)]"
          :class="editing?.name === project.name
            ? 'bg-[#007AFF]/[0.07]'
            : 'hover:bg-black/[0.03] dark:hover:bg-white/[0.04]'"
        >
          <div class="ws-project-info flex min-w-0 flex-col gap-1.5">
            <strong class="truncate text-[14px] font-medium">{{ project.title }}</strong>
            <div v-if="project.modules.length" class="ws-chips flex flex-wrap gap-1.5">
              <span
                v-for="item in project.modules"
                :key="item"
                class="ws-chip rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#1d1d1f]/70 dark:bg-white/[0.08] dark:text-[#f5f5f7]/70"
              >{{ item }}</span>
            </div>
            <p v-else class="ws-hint text-[12px] text-[#6e6e73] dark:text-[#98989d]">还没有模块</p>
          </div>
          <div class="ws-project-actions flex shrink-0 items-center">
            <Button variant="ghost" size="sm" :disabled="busy" @click="startEdit(project)">编辑</Button>
            <Button
              variant="ghost"
              size="sm"
              class="text-[#FF3B30] hover:bg-[#FF3B30]/[0.08] hover:text-[#FF3B30] dark:text-[#FF453A] dark:hover:text-[#FF453A]"
              :disabled="busy"
              @click="pendingDelete = project"
            >删除</Button>
          </div>
        </li>
        <li
          v-if="!props.projects.length"
          class="ws-project-empty py-6 text-center text-[13px] text-[#6e6e73] dark:text-[#98989d]"
        >
          还没有项目，先在上面创建一个。
        </li>
      </ul>
    </div>

    <ConfirmDialog
      v-if="pendingDelete"
      title="删除项目"
      :message="`确定删除「${pendingDelete.title}」？项目下仍有待办时无法删除。`"
      confirm-text="删除"
      @confirm="removeConfirmed"
      @close="pendingDelete = null"
    />
  </div>
</template>
