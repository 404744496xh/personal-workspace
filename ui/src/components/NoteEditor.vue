<script setup lang="ts">
import Vditor from 'vditor'
import 'vditor/dist/index.css'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import type { Note, NotePayload, Project, Todo } from '../types'
import { messageOf } from '../utils'
import { uploadImage } from '../api'
import Button from './ui/Button.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import NotePropsDialog from './NotePropsDialog.vue'

const props = defineProps<{
  note: Note | null
  projects: Project[]
  todos: Todo[]
  saving: boolean
  defaultProject?: string
  defaultModule?: string
  defaultTodo?: string
}>()

const emit = defineEmits<{
  save: [payload: NotePayload]
  close: []
}>()

const CDN = '/plugins/personal-workspace/assets/vditor'
const CONTENT_THEME_PATH = `${CDN}/dist/css/content-theme`

const title = ref(props.note?.title ?? '')
const content = ref(props.note?.content ?? '')
const project = ref(props.note?.project ?? props.defaultProject ?? '')
const module = ref(
  props.note
    ? props.note.module ?? ''
    : props.defaultProject
      ? props.defaultModule ?? ''
      : ''
)
const selectedTodos = ref<Set<string>>(
  new Set(props.note?.todos ?? (props.defaultTodo ? [props.defaultTodo] : []))
)

const editorEl = ref<HTMLDivElement>()
const vditor = shallowRef<Vditor>()
const propsOpen = ref(false)
const confirmDiscard = ref(false)
const localError = ref('')
const uploading = ref(0)

function snapshot(): string {
  return JSON.stringify({
    title: title.value,
    content: content.value,
    project: project.value,
    module: module.value,
    todos: [...selectedTodos.value].sort()
  })
}

const baseline = ref(snapshot())
const dirty = computed(() => snapshot() !== baseline.value)

const projectTitle = computed(
  () => props.projects.find((item) => item.name === project.value)?.title ?? ''
)
const propsSummary = computed(() => {
  const parts: string[] = []
  if (project.value) parts.push(projectTitle.value || '原项目已删除')
  if (module.value) parts.push(module.value)
  if (selectedTodos.value.size) parts.push(`待办 ${selectedTodos.value.size}`)
  return parts
})

function isDark(): boolean {
  return document.getElementById('workspace-app')?.getAttribute('data-ws-theme') === 'dark'
}

interface MermaidApi {
  initialize(options: Record<string, unknown>): void
  render(id: string, text: string): Promise<{ svg: string }>
}

// Vditor 只在首次渲染 Mermaid 时读取主题；切换深浅色后按当前主题重绘已渲染的图表
async function rerenderDiagrams(): Promise<void> {
  const root = editorEl.value
  const mermaid = (window as unknown as { mermaid?: MermaidApi }).mermaid
  if (!root || !mermaid) return
  const dark = isDark()
  mermaid.initialize({
    securityLevel: 'loose',
    altFontFamily: 'sans-serif',
    fontFamily: 'sans-serif',
    startOnLoad: false,
    theme: dark ? 'dark' : 'default',
    flowchart: { htmlLabels: true, useMaxWidth: true },
    sequence: {
      useMaxWidth: true,
      diagramMarginX: 8,
      diagramMarginY: 8,
      boxMargin: 8,
      showSequenceNumbers: true
    },
    gantt: { leftPadding: 75, rightPadding: 20 }
  })
  const codes = root.querySelectorAll<HTMLElement>('pre.vditor-ir__marker--pre > code.language-mermaid')
  for (const code of codes) {
    const preview = code
      .closest('[data-type="code-block"]')
      ?.querySelector<HTMLElement>('pre.vditor-ir__preview > .language-mermaid')
    if (!preview || preview.getAttribute('data-processed') !== 'true') continue
    const source = code.textContent?.trim()
    if (!source) continue
    try {
      const { svg } = await mermaid.render(`mermaid${Math.random().toString(36).slice(2)}`, source)
      preview.innerHTML = svg
    } catch {
      // 保留 Vditor 原有的错误提示
    }
  }
}

function applyTheme(): void {
  const dark = isDark()
  vditor.value?.setTheme(
    dark ? 'dark' : 'classic',
    dark ? 'dark' : 'light',
    dark ? 'github-dark' : 'github',
    CONTENT_THEME_PATH
  )
  void rerenderDiagrams()
}

function focusEditor(): void {
  vditor.value?.focus()
}

async function handleUpload(files: File[]): Promise<string | null> {
  for (const file of files) {
    uploading.value += 1
    try {
      const { url } = await uploadImage(file)
      const label = file.name.replace(/[[\]]/g, '')
      vditor.value?.insertValue(`![${label}](${url})\n`)
    } catch (err) {
      return messageOf(err)
    } finally {
      uploading.value -= 1
    }
  }
  return null
}

function requestClose(): void {
  if (props.saving) return
  // Vditor 的 input 回调有约 400ms 防抖，先同步取值再判断是否有改动
  content.value = vditor.value?.getValue() ?? content.value
  if (dirty.value) {
    confirmDiscard.value = true
    return
  }
  emit('close')
}

function discardAndClose(): void {
  confirmDiscard.value = false
  emit('close')
}

function submit(): void {
  if (props.saving) return
  const titleValue = title.value.trim()
  if (!titleValue) {
    localError.value = '请输入笔记标题'
    return
  }
  localError.value = ''
  content.value = vditor.value?.getValue() ?? content.value
  emit('save', {
    title: titleValue,
    content: content.value,
    tags: props.note?.tags ?? [],
    project: project.value || null,
    module: project.value ? module.value.trim() || null : null,
    todos: [...selectedTodos.value]
  })
}

function applyProps(value: { project: string; module: string; todos: string[] }): void {
  project.value = value.project
  module.value = value.module
  selectedTodos.value = new Set(value.todos)
  propsOpen.value = false
}

function onWindowKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    submit()
  }
}

let themeObserver: MutationObserver | undefined

onMounted(() => {
  if (!editorEl.value) return
  const dark = isDark()
  vditor.value = new Vditor(editorEl.value, {
    cdn: CDN,
    mode: 'ir',
    value: content.value,
    height: '100%',
    placeholder: '开始记录…',
    lang: 'zh_CN',
    icon: 'ant',
    theme: dark ? 'dark' : 'classic',
    cache: { enable: false },
    outline: { enable: true, position: 'right' },
    preview: {
      maxWidth: 900,
      theme: {
        current: dark ? 'dark' : 'light',
        path: CONTENT_THEME_PATH
      },
      hljs: { style: dark ? 'github-dark' : 'github', lineNumber: false }
    },
    upload: {
      accept: 'image/*',
      // Vditor 的 handler 类型把 Promise<string|null> 拆成两个 Promise 联合，
      // 运行时两者等价：返回 null 表示成功、字符串表示错误文案。
      handler: handleUpload as unknown as (files: File[]) => string | Promise<string> | Promise<null> | null
    },
    input: (value) => {
      content.value = value
    },
    after: () => {
      vditor.value?.focus()
    }
  })

  window.addEventListener('keydown', onWindowKeydown, true)
  const root = document.getElementById('workspace-app')
  if (root) {
    themeObserver = new MutationObserver(applyTheme)
    themeObserver.observe(root, { attributes: true, attributeFilter: ['data-ws-theme'] })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown, true)
  themeObserver?.disconnect()
  vditor.value?.destroy()
  vditor.value = undefined
})
</script>

<template>
  <div
    class="ws-note-editor flex h-[calc(100vh-260px)] min-h-[520px] flex-col overflow-hidden rounded-[18px] border border-white/20 bg-white/[0.82] shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/80"
  >
    <header
      class="ws-note-editor-bar flex flex-wrap items-center gap-2 border-b border-black/[0.06] px-3 py-2 dark:border-white/[0.08]"
    >
      <Button variant="ghost" size="sm" @click="requestClose">
        <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M10 3L5.5 8l4.5 5"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        返回
      </Button>

      <button
        type="button"
        class="ws-note-props flex min-h-9 min-w-0 flex-wrap items-center gap-1.5 rounded-full px-3 text-left text-[12px] text-[#6e6e73] transition-colors duration-150 hover:bg-black/[0.04] dark:text-[#98989d] dark:hover:bg-white/[0.06]"
        title="编辑项目、模块与待办"
        aria-label="编辑笔记属性"
        @click="propsOpen = true"
      >
        <template v-if="propsSummary.length">
          <span
            v-for="(item, index) in propsSummary"
            :key="index"
            class="rounded-full bg-black/[0.05] px-2 py-0.5 font-medium dark:bg-white/[0.08]"
          >{{ item }}</span>
        </template>
        <span v-else>添加项目 / 模块 / 待办</span>
        <svg class="h-3.5 w-3.5 shrink-0 opacity-40" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M10.8 2.6a1.4 1.4 0 012 2L5 12.4l-2.6.6.6-2.6 7.8-7.8z"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linejoin="round"
          />
        </svg>
      </button>

      <div class="ml-auto flex items-center gap-2">
        <span v-if="uploading" class="text-[12px] font-medium text-[#007AFF] dark:text-[#64B5FF]" role="status">
          图片上传中…
        </span>
        <Button variant="ghost" size="sm" :disabled="saving" @click="requestClose">取消</Button>
        <Button
          variant="primary"
          size="sm"
          :disabled="saving || uploading > 0"
          @click="submit"
        >{{ saving ? '保存中…' : '保存' }}</Button>
      </div>
    </header>

    <input
      v-model="title"
      class="ws-note-editor-title w-full border-0 bg-transparent px-6 pb-2 pt-4 text-[26px] font-semibold tracking-[-0.02em] text-[#1d1d1f] outline-none placeholder:text-black/25 dark:text-[#f5f5f7] dark:placeholder:text-white/25"
      maxlength="200"
      autocomplete="off"
      placeholder="笔记标题"
      aria-label="笔记标题"
      @keydown.enter.prevent="focusEditor"
    />

    <p
      v-if="localError"
      class="mx-6 mb-2 rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
      role="alert"
    >{{ localError }}</p>

    <div class="min-h-0 flex-1">
      <div ref="editorEl" class="h-full"></div>
    </div>
  </div>

  <NotePropsDialog
    v-if="propsOpen"
    :project="project"
    :module="module"
    :todos="[...selectedTodos]"
    :projects="projects"
    :todo-list="todos"
    @apply="applyProps"
    @close="propsOpen = false"
  />

  <ConfirmDialog
    v-if="confirmDiscard"
    title="放弃修改"
    message="当前笔记有未保存的修改，确定放弃？"
    confirm-text="放弃"
    @confirm="discardAndClose"
    @close="confirmDiscard = false"
  />
</template>
