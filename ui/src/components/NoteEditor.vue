<script setup lang="ts">
import type Vditor from 'vditor'
import 'vditor/dist/index.css'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { Note, NotePayload, NoteTemplate, Project, Todo } from '../types'
import { messageOf } from '../utils'
import { uploadImage } from '../api'
import { openPostEditor } from '../articles'
import { applyMermaidTheme } from '../mermaidTheme'
import { loadVditor, VDITOR_CDN, VDITOR_CONTENT_THEME, type VditorCtor } from '../vditor'
import { useContextMenu } from '../contextMenu'
import Button from './ui/Button.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import ContextMenu from './ContextMenu.vue'
import NotePropsDialog from './NotePropsDialog.vue'

const props = defineProps<{
  note: Note | null
  projects: Project[]
  todos: Todo[]
  templates: NoteTemplate[]
  saving: boolean
  summaryBusy?: boolean
  exporting?: boolean
  persist: (payload: NotePayload) => Promise<boolean>
  defaultProject?: string
  defaultModule?: string
  defaultTodo?: string
}>()

const emit = defineEmits<{
  close: []
  manageTemplates: []
  summary: [payload: { title: string; content: string }]
  exportPost: []
}>()

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
const ready = ref(false)
const { menu: articleMenu, open: openArticleMenu } = useContextMenu()

// 大纲折叠偏好跨次打开保留；默认收起成右侧细轨，鼠标悬停/点击展开
const OUTLINE_KEY = 'personal-workspace:outline-hidden-v2'
const outlineHidden = ref(localStorage.getItem(OUTLINE_KEY) !== '0')
const outlineAvailable = ref(false)
const panelOpen = ref(false)
const outlineFlyout = ref(false)
const headings = ref<{ level: number; text: string; id: string }[]>([])
let outlineTimer: number | undefined
let flyoutTimer: number | undefined
let inlineHtmlTimer: number | undefined

function snapshotOf(payload: NotePayload): string {
  return JSON.stringify({
    title: payload.title,
    content: payload.content,
    project: payload.project ?? '',
    module: payload.module ?? '',
    todos: [...(payload.todos ?? [])].sort()
  })
}

function snapshot(): string {
  return JSON.stringify({
    title: title.value.trim(),
    content: content.value,
    project: project.value,
    module: project.value ? module.value.trim() : '',
    todos: [...selectedTodos.value].sort()
  })
}

const baseline = ref(snapshot())
const dirty = computed(() => snapshot() !== baseline.value)
const autoStatus = ref('')
let autoTimer: ReturnType<typeof setTimeout> | undefined
let editSeq = 0

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

// 深浅色切换后 mermaid 配色要重算；配置与配色由 applyMermaidTheme 的包装注入
async function rerenderDiagrams(): Promise<void> {
  const root = editorEl.value
  const mermaid = (window as unknown as { mermaid?: MermaidApi }).mermaid
  if (!root || !mermaid) return
  mermaid.initialize({})
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

// Vditor 渲染大纲时会重写标题 id，因此必须在渲染之后读取标题列表
function collectHeadings(): void {
  const instance = vditor.value
  if (!instance) return
  instance.vditor.outline.render(instance.vditor)
  const items = instance.vditor.outline.element.querySelectorAll<HTMLElement>('[data-target-id]')
  const list: { level: number; text: string; id: string }[] = []
  items.forEach((item) => {
    const id = item.getAttribute('data-target-id') || ''
    const heading = document.getElementById(id)
    if (!heading) return
    list.push({
      level: Math.min(4, Math.max(1, Number(heading.tagName.charAt(1)) || 1)),
      text: (item.textContent || '').trim(),
      id
    })
  })
  headings.value = list
  outlineAvailable.value = list.length > 0
}

function applyOutline(): void {
  const instance = vditor.value
  if (!instance) return
  instance.vditor.outline.toggle(
    instance.vditor,
    !outlineHidden.value && outlineAvailable.value,
    false
  )
  // 窄屏下 Vditor 会拒绝展开面板，此时一律用折叠轨道
  panelOpen.value = instance.vditor.outline.element.style.display !== 'none'
}

function syncOutline(): void {
  collectHeadings()
  applyOutline()
}

function scrollToHeading(id: string): void {
  const heading = document.getElementById(id)
  // Vditor 只设置 .vditor-ir 的 scrollTop，而真正的滚动容器是内部的 .vditor-reset
  const scroller = editorEl.value?.querySelector<HTMLElement>('.vditor-ir .vditor-reset')
  if (!heading || !scroller) return
  const delta = heading.getBoundingClientRect().top - scroller.getBoundingClientRect().top
  scroller.scrollTo({ top: scroller.scrollTop + delta, behavior: 'smooth' })
}

function onOutlineClick(event: MouseEvent): void {
  const item = (event.target as HTMLElement).closest?.('[data-target-id]')
  const id = item?.getAttribute('data-target-id')
  if (!id) return
  event.preventDefault()
  event.stopPropagation()
  scrollToHeading(id)
}

function setOutlineHidden(hidden: boolean): void {
  outlineHidden.value = hidden
  if (hidden) hideFlyout()
}

function showFlyout(): void {
  window.clearTimeout(flyoutTimer)
  outlineFlyout.value = true
}

function hideFlyout(): void {
  window.clearTimeout(flyoutTimer)
  flyoutTimer = window.setTimeout(() => {
    outlineFlyout.value = false
  }, 160)
}

function jump(index: number): void {
  const heading = headings.value[index]
  if (heading) scrollToHeading(heading.id)
}

// lute 在 IR 模式下只给 html-block 生成预览，行内的空元素（<img> 等）会被压成
// 0 尺寸的 marker，编辑器里什么都不显示。这里补一个 data-render 预览节点：
// lute 回读 Markdown 时会整棵跳过带 data-render 的元素，因此不影响源码。
const INLINE_TAGS: Record<string, string[]> = {
  img: ['src', 'alt', 'title', 'width', 'height', 'loading'],
  hr: [],
  br: [],
  wbr: []
}

function isSafeUrl(value: string): boolean {
  const url = value.trim().toLowerCase()
  if (url.startsWith('data:')) return url.startsWith('data:image/')
  return !/^(javascript|vbscript|file):/.test(url)
}

function renderInlineHtml(): void {
  const root = editorEl.value?.querySelector<HTMLElement>('.vditor-ir')
  if (!root) return
  const selection = window.getSelection()
  root.querySelectorAll<HTMLElement>('[data-type="html-inline"]').forEach((node) => {
    if (node.querySelector('.vditor-ir__preview')) return
    // 正在编辑的节点不插入，避免抢走光标
    if (selection?.anchorNode && node.contains(selection.anchorNode)) return
    const source = node.querySelector('code')?.textContent?.trim() ?? ''
    const matched = /^<([a-zA-Z][\w-]*)\b([^>]*)\/?>$/.exec(source)
    if (!matched) return
    const tag = matched[1].toLowerCase()
    const allowed = INLINE_TAGS[tag]
    if (!allowed) return
    const element = document.createElement(tag)
    const attrs = /([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g
    let attr: RegExpExecArray | null
    while ((attr = attrs.exec(matched[2]))) {
      const name = attr[1].toLowerCase()
      const value = attr[3] ?? attr[2] ?? ''
      if (!allowed.includes(name)) continue
      if (name === 'src' && !isSafeUrl(value)) return
      element.setAttribute(name, value)
    }
    const preview = document.createElement('span')
    preview.className = 'vditor-ir__preview'
    preview.setAttribute('data-render', '1')
    preview.setAttribute('contenteditable', 'false')
    preview.appendChild(element)
    node.appendChild(preview)
  })
}

function scheduleInlineHtml(): void {
  window.clearTimeout(inlineHtmlTimer)
  inlineHtmlTimer = window.setTimeout(renderInlineHtml, 0)
}

function scheduleOutline(): void {
  window.clearTimeout(outlineTimer)
  outlineTimer = window.setTimeout(syncOutline, 350)
}

function applyTheme(): void {
  const dark = isDark()
  vditor.value?.setTheme(
    dark ? 'dark' : 'classic',
    dark ? 'dark' : 'light',
    dark ? 'github-dark' : 'github',
    VDITOR_CONTENT_THEME
  )
  void rerenderDiagrams()
}

function focusEditor(): void {
  vditor.value?.focus()
}

function useTemplate(template: string): void {
  content.value = template
  autoStatus.value = ''
  vditor.value?.setValue(template)
  vditor.value?.focus()
  scheduleInlineHtml()
  scheduleOutline()
}

async function handleUpload(files: File[]): Promise<string | null> {
  for (const file of files) {
    uploading.value += 1
    try {
      // 站点附件对 SVG 的 MIME 支持不一致，且内联 SVG 有脚本风险，统一栅格化成 PNG
      let upload = file
      if (file.type === 'image/svg+xml' || /\.svg$/i.test(file.name)) {
        const png = await rasterizeSvg(await file.text(), file.name || 'pasted.svg')
        if (!png) return 'SVG 图片解析失败，请导出为 PNG 后重试'
        upload = png
      }
      const { url } = await uploadImage(upload)
      const label = upload.name.replace(/[[\]]/g, '')
      vditor.value?.insertValue(`![${label}](${url})\n`)
    } catch (err) {
      return messageOf(err)
    } finally {
      uploading.value -= 1
    }
  }
  return null
}

async function rasterizeSvg(markup: string, name: string): Promise<File | null> {
  const openTag = /<svg\b[^>]*>/i.exec(markup)?.[0]
  if (!openTag) return null
  const viewBox = /viewBox="([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)"/i.exec(openTag)
  const width = Number(viewBox?.[3]) || 800
  const height = Number(viewBox?.[4]) || 600
  let attrs = ''
  if (!/\swidth\s*=/.test(openTag)) attrs += ` width="${width}"`
  if (!/\sheight\s*=/.test(openTag)) attrs += ` height="${height}"`
  const sized = attrs ? markup.replace(openTag, openTag.replace('<svg', `<svg${attrs}`)) : markup
  const blob = new Blob([sized], { type: 'image/svg+xml' })
  const objectUrl = URL.createObjectURL(blob)
  try {
    const image = await new Promise<HTMLImageElement>((resolve, reject) => {
      const element = new Image()
      element.onload = () => resolve(element)
      element.onerror = () => reject(new Error('svg load failed'))
      element.src = objectUrl
    })
    const sourceWidth = image.naturalWidth || width
    const sourceHeight = image.naturalHeight || height
    const scale = Math.min(2, Math.max(1, 1400 / Math.max(sourceWidth, sourceHeight)))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(sourceWidth * scale)
    canvas.height = Math.round(sourceHeight * scale)
    canvas.getContext('2d')?.drawImage(image, 0, 0, canvas.width, canvas.height)
    const png = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
    if (!png) return null
    return new File([png], name.replace(/\.svg$/i, '') + '.png', { type: 'image/png' })
  } catch {
    return null
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

async function dataUriFile(src: string, index: number): Promise<File | null> {
  try {
    const blob = await (await fetch(src)).blob()
    const ext = blob.type === 'image/svg+xml'
      ? 'svg' : (blob.type.split('/')[1] || 'png')
    return new File([blob], `pasted-${index + 1}.${ext}`, { type: blob.type })
  } catch {
    return null
  }
}

// 富文本来源的 SVG 可能以 data URI 或内联标记出现，Vditor 的 HTML 分支会把它丢成 ![]()
function dataImageUris(html: string): string[] {
  if (!html) return []
  const doc = new DOMParser().parseFromString(html, 'text/html')
  return [...doc.querySelectorAll('img[src]')]
    .map((img) => img.getAttribute('src') ?? '')
    .filter((src) => src.startsWith('data:image/'))
}

function inlineSvgMarkup(html: string): string | null {
  if (!html) return null
  const svg = new DOMParser().parseFromString(html, 'text/html').querySelector('svg')
  return svg ? svg.outerHTML : null
}

// Vditor 粘贴时只要剪贴板带 HTML 就优先走 HTML 分支、忽略图片文件，
// 富文本来源（网页复制图片、部分截图工具）会因此插入 ![]() 或失效的外链。
// 判定必须同步完成，否则 Vditor 的内联监听会先一步把 HTML 插进编辑器。
async function onEditorPaste(event: ClipboardEvent): Promise<void> {
  const data = event.clipboardData
  if (!data) return
  const files = [...data.files].filter(
    (file) => file.type.startsWith('image/') || /\.svg$/i.test(file.name))
  let uris: string[] = []
  let markup: string | null = null
  if (!files.length) {
    uris = dataImageUris(data.getData('text/html'))
    if (!uris.length) {
      markup = inlineSvgMarkup(data.getData('text/html'))
      const plain = data.getData('text/plain').trim()
      if (!markup && /^<svg[\s>]/i.test(plain)) markup = plain
    }
    if (!uris.length && !markup) return
  }
  event.preventDefault()
  event.stopPropagation()
  let targets = files
  if (!targets.length) {
    if (markup) {
      targets = [new File([markup], 'pasted.svg', { type: 'image/svg+xml' })]
    } else {
      const loaded = await Promise.all(uris.map((src, index) => dataUriFile(src, index)))
      targets = loaded.filter((file): file is File => file !== null)
    }
  }
  if (!targets.length) {
    vditor.value?.tip('剪贴板图片解析失败')
    return
  }
  const error = await handleUpload(targets)
  if (error) vditor.value?.tip(error)
}

interface EditorPayload extends NotePayload {
  title: string
}

function buildPayload(): EditorPayload | null {
  // Vditor 的 input 回调有约 400ms 防抖，落盘前先同步取值
  if (ready.value) content.value = vditor.value?.getValue() ?? content.value
  const titleValue = title.value.trim()
  if (!titleValue) return null
  return {
    title: titleValue,
    content: content.value,
    tags: props.note?.tags ?? [],
    project: project.value || null,
    module: project.value ? module.value.trim() || null : null,
    todos: [...selectedTodos.value]
  }
}

function markAutoSaved(): void {
  autoStatus.value = `已自动保存 ${new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit'
  })}`
}

function scheduleAutoSave(): void {
  if (!ready.value) return
  editSeq += 1
  autoStatus.value = ''
  window.clearTimeout(autoTimer)
  autoTimer = window.setTimeout(() => {
    void autoSave(editSeq)
  }, 900)
}

async function autoSave(seq: number): Promise<void> {
  window.clearTimeout(autoTimer)
  if (!ready.value || props.saving || !dirty.value) return
  const payload = buildPayload()
  if (!payload) return
  const savedSnapshot = snapshotOf(payload)
  if (await props.persist(payload)) {
    baseline.value = savedSnapshot
    markAutoSaved()
    // 保存期间又有新输入时，再排一次；失败则等用户下次输入，避免无休止重试
    if (editSeq !== seq && dirty.value) scheduleAutoSave()
  }
}

async function requestClose(): Promise<void> {
  if (props.saving) return
  window.clearTimeout(autoTimer)
  if (ready.value) content.value = vditor.value?.getValue() ?? content.value
  if (!dirty.value) {
    emit('close')
    return
  }
  const payload = buildPayload()
  if (!payload) {
    confirmDiscard.value = true
    return
  }
  if (await props.persist(payload)) emit('close')
}

function discardAndClose(): void {
  confirmDiscard.value = false
  emit('close')
}

async function submit(): Promise<void> {
  if (props.saving || !ready.value) return
  if (!title.value.trim()) {
    localError.value = '请输入笔记标题'
    return
  }
  localError.value = ''
  window.clearTimeout(autoTimer)
  const payload = buildPayload()
  if (!payload) return
  if (await props.persist(payload)) emit('close')
}

async function exportPost(): Promise<void> {
  if (props.saving || props.exporting || !ready.value) return
  if (!title.value.trim()) {
    localError.value = '请输入笔记标题'
    return
  }
  localError.value = ''
  window.clearTimeout(autoTimer)
  const payload = buildPayload()
  if (!payload) return
  const savedSnapshot = snapshotOf(payload)
  if (await props.persist(payload)) {
    baseline.value = savedSnapshot
    markAutoSaved()
    emit('exportPost')
  }
}

function openLinkedPost(): void {
  if (props.note?.post) openPostEditor(props.note.post)
}

function onArticleClick(event: MouseEvent): void {
  if (props.saving || props.exporting || !ready.value) return
  if (!props.note?.post) {
    void exportPost()
    return
  }
  const trigger = event.currentTarget as HTMLElement | null
  const rect = trigger?.getBoundingClientRect()
  const anchor = rect
    ? new MouseEvent('click', {
        clientX: rect.left,
        clientY: rect.bottom + 6,
        bubbles: true,
        cancelable: true
      })
    : event
  openArticleMenu(anchor, [
    {
      label: '更新文章草稿',
      icon: 'link',
      disabled: props.exporting,
      onSelect: () => void exportPost()
    },
    { label: '打开文章', icon: 'open', onSelect: openLinkedPost }
  ])
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

onMounted(async () => {
  if (!editorEl.value) return
  applyMermaidTheme()
  const dark = isDark()
  let VditorCtor: VditorCtor
  try {
    VditorCtor = await loadVditor()
  } catch (error) {
    localError.value = messageOf(error)
    return
  }
  if (!editorEl.value) return
  vditor.value = new VditorCtor(editorEl.value, {
    cdn: VDITOR_CDN,
    mode: 'ir',
    value: content.value,
    height: '100%',
    placeholder: '开始记录…',
    lang: 'zh_CN',
    icon: 'ant',
    theme: dark ? 'dark' : 'classic',
    cache: { enable: false },
    toolbar: [],
    outline: { enable: true, position: 'right' },
    preview: {
      maxWidth: 800,
      theme: {
        current: dark ? 'dark' : 'light',
        path: VDITOR_CONTENT_THEME
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
      scheduleInlineHtml()
      scheduleOutline()
    },
    after: () => {
      ready.value = true
      const instance = vditor.value
      if (instance) {
        syncOutline()
        scheduleInlineHtml()
        instance.vditor.outline.element.addEventListener('click', onOutlineClick, true)
      }
      vditor.value?.focus()
    }
  })

  window.addEventListener('keydown', onWindowKeydown, true)
  editorEl.value?.addEventListener('paste', onEditorPaste, true)
  const root = document.getElementById('workspace-app')
  if (root) {
    themeObserver = new MutationObserver(applyTheme)
    themeObserver.observe(root, { attributes: true, attributeFilter: ['data-ws-theme'] })
  }
})

watch(outlineHidden, (hidden) => {
  localStorage.setItem(OUTLINE_KEY, hidden ? '1' : '0')
  applyOutline()
})

watch([title, content, project, module, selectedTodos], scheduleAutoSave, { deep: true })
watch(ready, (value) => {
  if (value) scheduleAutoSave()
})

onBeforeUnmount(() => {
  window.clearTimeout(autoTimer)
  window.clearTimeout(outlineTimer)
  window.clearTimeout(flyoutTimer)
  window.clearTimeout(inlineHtmlTimer)
  window.removeEventListener('keydown', onWindowKeydown, true)
  editorEl.value?.removeEventListener('paste', onEditorPaste, true)
  themeObserver?.disconnect()
  vditor.value?.destroy()
  vditor.value = undefined
})
</script>

<template>
  <div
    class="ws-note-editor flex min-h-[520px] flex-col overflow-hidden rounded-[18px] border border-white/20 bg-white/[0.82] shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/80"
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
        <span v-else-if="autoStatus" class="text-[12px] text-[#6e6e73] dark:text-[#98989d]" role="status">
          {{ autoStatus }}
        </span>
        <Button
          variant="ghost"
          size="sm"
          class="ws-note-article"
          :disabled="saving || exporting || uploading > 0 || !ready"
          :aria-label="note?.post ? '文章操作' : '存为文章草稿'"
          :title="note?.post ? '文章操作' : '存为文章草稿'"
          @click="onArticleClick"
        >
          {{ exporting ? '存草稿中…' : note?.post ? '文章' : '存为文章草稿' }}
          <svg
            v-if="note?.post && !exporting"
            class="h-3 w-3 opacity-45"
            viewBox="0 0 16 16"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M4 6l4 4 4-4"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </Button>
        <Button
          variant="ghost"
          size="sm"
          class="ws-note-summary"
          :disabled="summaryBusy || !content.trim()"
          @click="emit('summary', { title: title.trim(), content })"
        >{{ summaryBusy ? '摘要生成中…' : 'AI 摘要' }}</Button>
        <Button
          variant="primary"
          size="sm"
          :disabled="saving || uploading > 0 || !ready"
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

    <div
      v-if="ready && !note && !content.trim()"
      class="ws-note-templates mx-6 mb-2 flex flex-wrap items-center gap-2 rounded-xl bg-black/[0.03] px-3 py-2 dark:bg-white/[0.05]"
    >
      <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">从模板开始</span>
      <Button
        v-for="item in templates"
        :key="item.name"
        variant="secondary"
        size="sm"
        class="ws-note-template"
        @click="useTemplate(item.content)"
      >{{ item.name }}</Button>
      <Button
        variant="ghost"
        size="sm"
        class="ws-note-templates-manage ml-auto"
        @click="emit('manageTemplates')"
      >{{ templates.length ? '管理' : '新建模板' }}</Button>
    </div>

    <div class="relative min-h-0 flex-1">
      <div ref="editorEl" class="h-full"></div>

      <template v-if="outlineAvailable">
        <button
          v-if="!outlineHidden && panelOpen"
          class="ws-outline-arrow"
          title="折叠大纲"
          aria-label="折叠大纲"
          @click="setOutlineHidden(true)"
        >
          ›
        </button>

        <div
          v-else
          class="ws-outline-rail"
          @mouseenter="showFlyout"
          @mouseleave="hideFlyout"
        >
          <button
            class="ws-outline-rail__expand"
            title="展开大纲"
            aria-label="展开大纲"
            @click="setOutlineHidden(false)"
          >
            ‹
          </button>
          <button
            v-for="(heading, index) in headings.slice(0, 14)"
            :key="`dash-${index}`"
            class="ws-outline-dash"
            :class="`is-h${heading.level}`"
            :title="heading.text"
            @click="jump(index)"
          ></button>

          <div v-if="outlineFlyout" class="ws-outline-flyout">
            <div class="ws-outline-flyout__head">大纲</div>
            <button
              v-for="(heading, index) in headings"
              :key="`item-${index}`"
              class="ws-outline-flyout__item"
              :class="`is-h${heading.level}`"
              @click="jump(index)"
            >
              {{ heading.text }}
            </button>
          </div>
        </div>
      </template>
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
    title="放弃新笔记"
    message="新笔记还没有标题，无法自动保存，确定放弃？"
    confirm-text="放弃"
    @confirm="discardAndClose"
    @close="confirmDiscard = false"
  />

  <ContextMenu
    v-if="articleMenu.open"
    :x="articleMenu.x"
    :y="articleMenu.y"
    :items="articleMenu.items"
    @close="articleMenu.open = false"
  />
</template>
