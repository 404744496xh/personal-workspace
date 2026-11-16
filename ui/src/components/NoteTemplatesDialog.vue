<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { NoteTemplate } from '../types'
import { saveNoteTemplates } from '../api'
import { messageOf } from '../utils'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Modal from './ui/Modal.vue'
import Textarea from './ui/Textarea.vue'

const props = defineProps<{ templates: NoteTemplate[] }>()

const emit = defineEmits<{
  close: []
  change: [templates: NoteTemplate[]]
}>()

const drafts = ref<NoteTemplate[]>(props.templates.map((template) => ({ ...template })))
const selected = ref<number | 'new'>('new')
const name = ref('')
const content = ref('')
const saving = ref(false)
const error = ref('')
const pendingDelete = ref(false)

const editingExisting = computed(() => typeof selected.value === 'number')
const deleteName = computed(() => typeof selected.value === 'number'
  ? drafts.value[selected.value]?.name ?? ''
  : '')

function load(index: number): void {
  const template = drafts.value[index]
  if (!template) return
  selected.value = index
  name.value = template.name
  content.value = template.content
  error.value = ''
  pendingDelete.value = false
}

function startNew(): void {
  selected.value = 'new'
  name.value = ''
  content.value = ''
  error.value = ''
  pendingDelete.value = false
}

function validate(): NoteTemplate | null {
  const templateName = name.value.trim()
  const templateContent = content.value.trim()
  if (!templateName) {
    error.value = '请输入模板名称'
    return null
  }
  if (templateName.length > 50) {
    error.value = '模板名称不能超过 50 个字符'
    return null
  }
  if (!templateContent) {
    error.value = '请输入模板内容'
    return null
  }
  if (templateContent.length > 8000) {
    error.value = '模板内容不能超过 8000 个字符'
    return null
  }
  return { name: templateName, content: templateContent }
}

async function persist(next: NoteTemplate[], selectIndex: number | null): Promise<void> {
  saving.value = true
  error.value = ''
  try {
    const result = await saveNoteTemplates(next)
    drafts.value = (result.templates ?? next).map((template) => ({ ...template }))
    emit('change', drafts.value)
    if (!drafts.value.length) {
      startNew()
      return
    }
    load(selectIndex == null
      ? drafts.value.length - 1
      : Math.min(selectIndex, drafts.value.length - 1))
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    saving.value = false
  }
}

async function save(): Promise<void> {
  if (saving.value) return
  const template = validate()
  if (!template) return
  const next = drafts.value.map((item) => ({ ...item }))
  if (selected.value === 'new') {
    next.push(template)
  } else {
    next[selected.value] = template
  }
  if (new Set(next.map((item) => item.name)).size !== next.length) {
    error.value = '模板名称不能重复'
    return
  }
  await persist(next, selected.value === 'new' ? null : selected.value)
}

async function remove(): Promise<void> {
  if (saving.value || selected.value === 'new') return
  const index = selected.value
  pendingDelete.value = false
  const next = drafts.value.filter((_, itemIndex) => itemIndex !== index)
  await persist(next, next.length ? Math.min(index, next.length - 1) : null)
}

onMounted(() => {
  if (drafts.value.length) load(0)
  else startNew()
})
</script>

<template>
  <Modal title="笔记模板" size="wide" @close="emit('close')">
    <div class="ws-template-manager grid gap-5 md:grid-cols-[220px_1fr]">
      <aside class="min-w-0">
        <div class="flex items-center justify-between gap-2">
          <h3 class="text-[13px] font-semibold text-[#1d1d1f] dark:text-[#f5f5f7]">已有模板</h3>
          <Button
            variant="ghost"
            size="sm"
            class="ws-template-new"
            @click="startNew"
          >新建</Button>
        </div>
        <div class="mt-2 flex flex-col gap-1">
          <button
            v-for="(item, index) in drafts"
            :key="item.name"
            type="button"
            class="ws-template-item flex min-h-10 w-full items-center rounded-lg px-3 text-left text-[13px] transition-colors duration-150"
            :class="selected === index
              ? 'bg-[#007AFF]/[0.10] font-medium text-[#007AFF] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF]'
              : 'text-[#1d1d1f] hover:bg-black/[0.04] dark:text-[#f5f5f7] dark:hover:bg-white/[0.06]'"
            @click="load(index)"
          >{{ item.name }}</button>
          <p
            v-if="!drafts.length"
            class="px-3 py-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
          >还没有模板</p>
        </div>
      </aside>

      <section class="min-w-0">
        <label class="text-[13px] font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">模板名称</label>
        <Input
          v-model="name"
          class="ws-template-name mt-1.5"
          maxlength="50"
          aria-label="模板名称"
          placeholder="例如：需求评审"
        />

        <div class="mt-4 flex items-center justify-between gap-2">
          <label class="text-[13px] font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">模板内容</label>
          <span class="text-[11px] text-[#6e6e73] dark:text-[#98989d]">Markdown · 最多 8000 字</span>
        </div>
        <Textarea
          v-model="content"
          class="ws-template-content mt-1.5 font-mono text-[13px]"
          rows="14"
          maxlength="8000"
          aria-label="模板内容"
          placeholder="新建空白笔记时会填入这段内容"
        />
        <p class="mt-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          新建空白笔记时，模板会以按钮形式显示在标题下方。
        </p>
        <p
          v-if="error"
          class="mt-2 rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
          role="alert"
        >{{ error }}</p>

        <div class="mt-4 flex items-center gap-2">
          <template v-if="pendingDelete">
            <span class="text-[12px] text-[#FF3B30]">确定删除「{{ deleteName }}」？</span>
            <Button variant="ghost" size="sm" :disabled="saving" @click="pendingDelete = false">取消</Button>
            <Button variant="danger" size="sm" class="ws-template-delete-confirm" :disabled="saving" @click="remove">
              {{ saving ? '删除中…' : '删除' }}
            </Button>
          </template>
          <template v-else>
            <Button
              v-if="editingExisting"
              variant="ghost"
              class="ws-template-delete"
              :disabled="saving"
              @click="pendingDelete = true"
            >删除</Button>
            <Button
              variant="primary"
              class="ws-template-save ml-auto"
              :disabled="saving"
              @click="save"
            >{{ saving ? '保存中…' : '保存模板' }}</Button>
          </template>
        </div>
      </section>
    </div>
  </Modal>
</template>
