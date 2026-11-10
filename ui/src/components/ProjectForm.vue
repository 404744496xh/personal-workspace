<script setup lang="ts">
import { ref } from 'vue'
import type { Project, ProjectPayload } from '../types'
import Modal from './ui/Modal.vue'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Textarea from './ui/Textarea.vue'
import ModuleEditor from './ModuleEditor.vue'

const props = defineProps<{
  project: Project | null
  saving: boolean
}>()

const emit = defineEmits<{
  save: [payload: ProjectPayload]
  renamed: [from: string, to: string]
  close: []
}>()

const title = ref(props.project?.title ?? '')
const modules = ref<string[]>(props.project ? [...props.project.modules] : [])
const progress = ref(props.project?.progress ?? 0)
const note = ref(props.project?.note ?? '')
const linksText = ref((props.project?.links ?? []).join('\n'))
const editor = ref<InstanceType<typeof ModuleEditor> | null>(null)
const localError = ref('')

function close(): void {
  if (!props.saving) emit('close')
}

function onModuleRename(from: string, to: string): void {
  if (props.project) emit('renamed', from, to)
}

function submit(): void {
  if (!(editor.value?.commit() ?? true)) return
  const titleValue = title.value.trim()
  if (!titleValue) {
    localError.value = '请输入项目名称'
    return
  }
  const progressValue = Math.max(0, Math.min(100, Number(progress.value) || 0))
  const links = linksText.value
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean)
  if (links.length > 20) {
    localError.value = '关联链接最多 20 个'
    return
  }
  if (links.some((item) => item.length > 2000)) {
    localError.value = '单个链接不能超过 2000 个字符'
    return
  }
  localError.value = ''
  emit('save', {
    title: titleValue,
    modules: [...modules.value],
    progress: progressValue,
    note: note.value.trim() || null,
    links,
    tags: props.project?.tags ?? []
  })
}
</script>

<template>
  <Modal :title="project ? '编辑项目' : '新建项目'" @close="close">
    <form class="ws-form flex flex-col gap-4" @submit.prevent="submit">
      <label class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">项目名称</span>
        <Input v-model="title" autofocus maxlength="200" autocomplete="off" placeholder="如：个人工作台" />
      </label>

      <div class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">模块</span>
        <ModuleEditor ref="editor" v-model="modules" @rename="onModuleRename" />
      </div>

      <label class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">进度</span>
        <div class="flex items-center gap-3">
          <input
            v-model.number="progress"
            type="range"
            min="0"
            max="100"
            class="h-11 flex-1 accent-[#007AFF]"
          />
          <input
            v-model.number="progress"
            type="number"
            min="0"
            max="100"
            class="h-11 w-20 rounded-xl border border-black/[0.08] bg-white/80 px-3 text-[14px] tabular-nums outline-none transition-[border-color,box-shadow] duration-200 ease-[var(--ws-ease)] focus:border-[#007AFF]/50 focus:ring-[3px] focus:ring-[#007AFF]/15 dark:border-white/10 dark:bg-white/[0.06]"
          />
          <span class="text-[13px] text-[#6e6e73] dark:text-[#98989d]">%</span>
        </div>
      </label>

      <label class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">备注</span>
        <Textarea v-model="note" maxlength="2000" rows="3" placeholder="项目目标、当前阶段或风险"></Textarea>
      </label>

      <label class="ws-field flex flex-col gap-1.5">
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">关联链接</span>
        <Textarea
          v-model="linksText"
          rows="3"
          placeholder="每行一个链接，如 https://example.com/docs"
        ></Textarea>
        <span class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">每行一个，最多 20 个。</span>
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
  </Modal>
</template>
