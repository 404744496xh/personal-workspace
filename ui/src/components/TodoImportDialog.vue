<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { aiImport, ApiError, createTodo, updateProject } from '../api'
import type { AiImportRow, Project } from '../types'
import { formatDateTime, messageOf } from '../utils'
import { toDateTimeLocal } from '../datetime'
import { parseImport, resolveRows, splitForAiChunks } from '../todoImport'
import Modal from './ui/Modal.vue'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Select from './ui/Select.vue'
import Textarea from './ui/Textarea.vue'

interface EditRow {
  project: string
  module: string
  title: string
  detail: string
  createdAtLocal: string
  reporter: string
}

const props = defineProps<{
  projects: Project[]
  activeProject: string | null
  activeModule: string | null
  openTitles: string[]
}>()

const emit = defineEmits<{
  close: []
  done: []
  unauthorized: []
}>()

const text = ref('')
const importing = ref(false)
const finished = ref(false)
const progress = ref({ done: 0, total: 0 })
const results = ref(new Map<number, string>())
const aiBusy = ref(false)
const aiError = ref('')
const aiProgress = ref({ done: 0, total: 1 })
const aiIgnoredProjects = ref<string[]>([])
const AI_CHUNK_LINES = 40
const AI_TARGET_AUTO = '__auto__'
const targetProject = ref(
  props.activeProject === null
    ? AI_TARGET_AUTO
    : props.activeProject === ''
      ? ''
      : props.projects.some((project) => project.name === props.activeProject)
        ? props.activeProject
        : AI_TARGET_AUTO
)
const editing = ref(false)
const editRows = ref<EditRow[]>([])
const helpOpen = ref(false)
const copiedHint = ref('')

const templateText = [
  '项目\t模块\t待办\t创建时间',
  '讲师库管理\t讲师库\t新增内部讲师\t2026-11-10 16:46',
  '个人工作台\t前端\t调整归档页分类树\t2026-11-09 09:30',
  '\t\t买咖啡豆\t'
].join('\n')

async function copyTemplate(): Promise<void> {
  try {
    await navigator.clipboard.writeText(templateText)
    copiedHint.value = '已复制'
  } catch {
    copiedHint.value = '复制失败'
  }
  window.setTimeout(() => { copiedHint.value = '' }, 1800)
}

const resolveOptions = (aiImport = false) => ({
  projects: props.projects,
  activeProject: targetProject.value === AI_TARGET_AUTO ? null : targetProject.value,
  activeModule: props.activeModule,
  openTitles: props.openTitles,
  aiImport
})

const preview = computed(() => parseImport(text.value, resolveOptions()))
const resolvedEdit = computed(() => resolveRows(
  editRows.value.map((row, index) => ({
    line: index + 1,
    project: row.project.trim(),
    module: row.module.trim(),
    title: row.title.trim(),
    detail: row.detail.trim(),
    createdAt: row.createdAtLocal || null,
    reporter: row.reporter.trim()
  })),
  resolveOptions(true)
))
const activePreview = computed(() => editing.value ? resolvedEdit.value : preview.value)
const showCreatedColumn = computed(() => activePreview.value.rows.some((row) => row.createdAt))
const showReporterColumn = computed(() => activePreview.value.rows.some((row) => row.reporter))
const validRows = computed(() => activePreview.value.rows.filter((row) => !row.error))
const errorRows = computed(() => activePreview.value.rows.filter((row) => row.error))
const canImport = computed(() => !importing.value && !finished.value && !aiBusy.value
  && (editing.value || !preview.value.error) && validRows.value.length > 0)
const summary = computed(() => {
  const ok = [...results.value.values()].filter((value) => value === 'ok').length
  return { ok, failed: validRows.value.length - ok }
})
const progressPercent = computed(() => {
  const source = aiBusy.value ? aiProgress.value : progress.value
  return source.total ? Math.round((source.done / source.total) * 100) : 0
})
const ignoredProjectTitles = computed(() =>
  editing.value ? aiIgnoredProjects.value : preview.value.ignoredProjectTitles)
const lockedProject = computed(() => {
  if (targetProject.value === AI_TARGET_AUTO) return null
  if (targetProject.value === '') return ''
  return props.projects.find((project) => project.name === targetProject.value)?.title.trim() ?? ''
})
const contextHint = computed(() => {
  if (targetProject.value === AI_TARGET_AUTO) return ''
  if (targetProject.value === '') return '已选择「未分类」：忽略粘贴里的项目列，全部导入为未分类。'
  const title = props.projects.find((project) => project.name === targetProject.value)?.title
  const moduleHint = props.activeModule && targetProject.value === props.activeProject
    ? `、模块列为空时归入「${props.activeModule}」`
    : ''
  return `已选择项目「${title ?? ''}」：忽略粘贴里的项目列，全部导入到该项目${moduleHint}。`
})
const projectTitles = computed(() => props.projects.map((project) => project.title))

watch(text, () => {
  aiIgnoredProjects.value = []
  if (!editing.value) return
  editing.value = false
  aiError.value = ''
})

watch(targetProject, (value) => {
  if (!editing.value || value === AI_TARGET_AUTO) return
  const title = value === ''
    ? ''
    : props.projects.find((project) => project.name === value)?.title.trim() ?? ''
  for (const row of editRows.value) {
    row.project = title
    if (!title) row.module = ''
  }
  aiIgnoredProjects.value = []
})

function displayProject(row: { project: string }): string {
  return row.project || '未分类'
}

function statusOf(line: number, error: string): string {
  if (error) return error
  const result = results.value.get(line)
  if (result) return result === 'ok' ? '已完成' : result
  return finished.value ? '' : '待导入'
}

function distinct(values: string[]): string[] {
  return [...new Set(values.filter(Boolean))]
}

function failRows(rows: typeof validRows.value, message: string): void {
  for (const row of rows) results.value.set(row.line, message)
  results.value = new Map(results.value)
}

function onClose(): void {
  if (!importing.value) emit('close')
}

function editError(index: number): string {
  return resolvedEdit.value.rows[index]?.error ?? ''
}

function moduleOptions(row: EditRow): string[] {
  const project = props.projects.find((item) => item.title === row.project)
  const modules = project?.modules ?? []
  return row.module && !modules.includes(row.module) ? [...modules, row.module] : [...modules]
}

function onProjectChange(row: EditRow): void {
  if (!moduleOptions(row).includes(row.module)) row.module = ''
}

function removeEditRow(index: number): void {
  editRows.value.splice(index, 1)
}

async function requestAiChunk(chunk: string): Promise<AiImportRow[]> {
  try {
    return (await aiImport(chunk)).rows
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) throw err
    // 网关超时或上游抖动时自动重试这一批，避免前面的批次白跑
    return (await aiImport(chunk)).rows
  }
}

async function runAi(): Promise<void> {
  if (aiBusy.value || importing.value || finished.value || !text.value.trim()) return
  aiBusy.value = true
  aiError.value = ''
  aiIgnoredProjects.value = []
  const chunks = splitForAiChunks(text.value, AI_CHUNK_LINES)
  aiProgress.value = { done: 0, total: chunks.length }
  try {
    const rows: AiImportRow[] = []
    for (let index = 0; index < chunks.length; index += 1) {
      rows.push(...(await requestAiChunk(chunks[index])))
      aiProgress.value = { done: index + 1, total: chunks.length }
    }
    if (!rows.length) {
      aiError.value = 'AI 没有识别出待办，请检查粘贴内容'
      return
    }
    const knownProjects = new Set(props.projects.map((project) => project.title.trim()))
    const locked = lockedProject.value
    const ignored: string[] = []
    editRows.value = rows.map((row) => {
      let project = row.project.trim()
      let module = row.module.trim()
      if (locked !== null) {
        // 已选定导入项目，忽略粘贴里的项目列；未分类时不保留模块
        project = locked
        if (!locked) module = ''
      } else if (project && !knownProjects.has(project)) {
        // 项目不导入：对不上的项目名丢弃，按未分类处理；模块留着，改选导入项目后可直接沿用
        if (!ignored.includes(project)) ignored.push(project)
        project = ''
      }
      return {
        project,
        module,
        title: row.title.trim(),
        detail: (row.detail ?? '').trim(),
        createdAtLocal: toDateTimeLocal(row.createdAt),
        reporter: (row.reporter ?? '').trim()
      }
    })
    aiIgnoredProjects.value = ignored
    editing.value = true
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    const batch = aiProgress.value.total > 1
      ? `第 ${Math.min(aiProgress.value.done + 1, aiProgress.value.total)}/${aiProgress.value.total} 批：`
      : ''
    aiError.value = `${batch}${messageOf(err)}`
  } finally {
    aiBusy.value = false
  }
}

async function runImport(): Promise<void> {
  if (!canImport.value) return
  importing.value = true
  results.value = new Map()
  const rows = validRows.value
  progress.value = { done: 0, total: rows.length }

  const moduleTargets = new Map<string, typeof rows>()
  for (const row of rows) {
    if (!row.newModule || !row.projectName) continue
    const list = moduleTargets.get(row.projectName) ?? []
    list.push(row)
    moduleTargets.set(row.projectName, list)
  }
  for (const [name, targets] of moduleTargets) {
    const project = props.projects.find((item) => item.name === name)
    if (!project) {
      failRows(targets, '项目不存在')
      continue
    }
    const modules = distinct(targets.map((row) => row.module))
    try {
      await updateProject(name, {
        title: project.title,
        modules: [...project.modules, ...modules.filter((module) => !project.modules.includes(module))]
      })
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        importing.value = false
        emit('unauthorized')
        return
      }
      failRows(targets, `模块保存失败：${messageOf(err)}`)
    }
  }

  const queue = rows.filter((row) => !results.value.has(row.line))
  progress.value.done = rows.length - queue.length
  let cursor = 0
  const runners = Math.min(4, queue.length)
  await Promise.all(Array.from({ length: runners }, async () => {
    while (cursor < queue.length) {
      const row = queue[cursor]
      cursor += 1
      try {
        await createTodo({
          title: row.title,
          detail: row.detail || '',
          priority: 'MEDIUM',
          project: row.projectName,
          module: row.module || null,
          dueDate: null,
          completed: false,
          createdAt: row.createdAt ?? null,
          reporter: row.reporter || null
        })
        results.value.set(row.line, 'ok')
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          emit('unauthorized')
          cursor = queue.length
          return
        }
        results.value.set(row.line, messageOf(err))
      } finally {
        results.value = new Map(results.value)
        progress.value.done += 1
      }
    }
  }))

  importing.value = false
  finished.value = true
  emit('done')
}
</script>

<template>
  <Modal title="粘贴导入待办" size="wide" class="max-w-[1360px]" @close="onClose">
    <template #actions>
      <div class="flex items-center gap-1">
        <Button
          variant="ghost"
          size="icon"
          class="ws-import-help"
          :aria-expanded="helpOpen"
          aria-label="导入说明与模板"
          title="导入说明与模板"
          @click="helpOpen = !helpOpen"
        >
          <span class="text-[15px] font-semibold" aria-hidden="true">?</span>
        </Button>
        <Button variant="ghost" size="sm" :disabled="importing" @click="emit('close')">关闭</Button>
      </div>
    </template>
    <div class="flex flex-col gap-3">
      <p class="text-[12px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
        从 Excel 直接复制，列顺序不限；带表头按列名对应，无表头按 1–5 列规则识别。可选下方「导入到项目」整批指定项目；按内容匹配时只匹配已有项目（不会新建），对不上的归入未分类。列数或分隔符有歧义时不导入，可直接用「AI 识别」按语义识别。{{ contextHint }}
      </p>
      <div class="flex flex-wrap items-center gap-2">
        <label class="ws-import-target-label text-[12px] text-[#6e6e73] dark:text-[#98989d]" for="ws-import-target">
          导入到项目
        </label>
        <Select
          id="ws-import-target"
          v-model="targetProject"
          class="ws-import-target w-[240px]"
          :disabled="importing || finished || aiBusy"
        >
          <option :value="AI_TARGET_AUTO">按内容匹配</option>
          <option value="">未分类</option>
          <option v-for="project in projects" :key="project.name" :value="project.name">{{ project.title }}</option>
        </Select>
        <span
          v-if="targetProject !== AI_TARGET_AUTO && targetProject !== ''"
          class="text-[11px] text-[#6e6e73] dark:text-[#98989d]"
        >AI 导入时每条待办都会带上模块，没有模块名会按语义自动起一个。</span>
      </div>
      <div
        v-if="helpOpen"
        class="ws-import-help-panel max-h-[420px] overflow-y-auto rounded-xl border border-black/[0.06] bg-black/[0.02] p-3 text-[12px] leading-relaxed text-[#3a3a3c] dark:border-white/[0.08] dark:bg-white/[0.04] dark:text-[#d1d1d6]"
      >
        <div class="font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">列规则</div>
        <ul class="mt-1 list-disc space-y-0.5 pl-4">
          <li>1 列：全部作为待办标题；选了导入项目就归入所选项目。</li>
          <li>选「导入到项目」后，粘贴 / AI 返回的项目列会被忽略，全部归入所选项目（模块列仍按内容新建或复用）；选未分类则全部进未分类。默认按内容匹配，也可以手动指定。</li>
          <li>2 列：有表头按表头；无表头时按内容匹配为「项目、待办」，选定项目后按「模块、待办」。</li>
          <li>3 列：默认「项目、模块、待办」，单元格可空。</li>
          <li>4 列：默认「项目、模块、待办、创建时间」。</li>
          <li>5 列：默认「项目、模块、待办、创建时间、姓名」。</li>
          <li>表头可显式写明列含义并调整顺序，例如「姓名、项目、待办、创建时间」；支持「姓名 / 提出人 / 反馈人 / 提单人 / 提交人 / 报告人 / 谁提的」作为姓名列。表头行会自动跳过。</li>
        </ul>
        <div class="mt-2 font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">格式说明</div>
        <ul class="mt-1 list-disc space-y-0.5 pl-4">
          <li>分隔符：制表符、竖线、连续空格、逗号、分号、顿号；从 Excel 直接复制最稳。</li>
          <li>创建时间：2026-11-10、2026/11/10、2026-11-10 16:46；留空则用导入时间。</li>
          <li>项目只匹配已有项目（不会自动创建），对不上的归入未分类；模块可以由 AI 新建，已有同名模块会复用；每个项目最多 20 个模块，一次最多 500 行。</li>
          <li>AI 导入时模块必填：模型没给模块名时会按语义自动起一个；选定导入项目后，每条待办都必须有模块。</li>
          <li>AI 识别会把功能名与问题描述合并成能独立看懂的待办标题；标题说清楚了详情就留空，详情只放标题之外的补充信息，可以适度总结提炼、分点排版，意思不变。也会按语义归并项目 / 模块，并识别创建时间、姓名与描述列，负责人、处理人不算姓名。内容较多时会自动分批识别，单批失败自动重试一次，进度显示在按钮上。</li>
        </ul>
        <div class="mt-2 flex items-center justify-between">
          <span class="font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">模板</span>
          <Button variant="ghost" size="sm" class="ws-import-copy" @click="copyTemplate">
            {{ copiedHint || '复制模板' }}
          </Button>
        </div>
        <pre class="ws-import-template mt-1 overflow-x-auto whitespace-pre rounded-lg bg-white/70 p-2 font-mono text-[11px] leading-relaxed text-[#3a3a3c] dark:bg-black/20 dark:text-[#d1d1d6]">{{ templateText }}</pre>
      </div>
      <Textarea
        v-model="text"
        class="ws-import-text min-h-[150px] font-mono text-[13px]"
        placeholder="项目	模块	待办	创建时间	姓名"
        aria-label="粘贴待办数据"
        :disabled="importing || finished || aiBusy"
      />

      <div
        v-if="aiError"
        class="ws-import-ai-error rounded-xl border border-[#FF3B30]/30 bg-[#FF3B30]/[0.06] px-3 py-2 text-[12px] leading-relaxed text-[#FF3B30]"
      >{{ aiError }}</div>

      <div
        v-if="!editing && preview.error"
        class="ws-import-error rounded-xl border border-[#FF3B30]/30 bg-[#FF3B30]/[0.06] px-3 py-2 text-[12px] leading-relaxed text-[#FF3B30]"
      >{{ preview.error }}</div>
      <p
        v-if="!editing && preview.error && text.trim()"
        class="text-[11px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]"
      >规则识别不了没关系，可以直接点下方「AI 识别」，按内容语义识别。</p>

      <template v-if="activePreview.rows.length">
        <div class="ws-import-summary flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          <span v-if="editing" class="font-medium text-[#1d1d1f] dark:text-[#f5f5f7]">AI 识别结果（可编辑）</span>
          <span v-else>分隔符：<b class="text-[#1d1d1f] dark:text-[#f5f5f7]">{{ preview.delimiter }}</b></span>
          <span>有效 <b class="text-[#1d1d1f] dark:text-[#f5f5f7]">{{ validRows.length }}</b> 行</span>
          <span v-if="errorRows.length">问题 <b class="text-[#FF3B30]">{{ errorRows.length }}</b> 行</span>
          <span v-if="!editing && preview.skippedHeader">已跳过表头</span>
          <Button v-if="editing" variant="ghost" size="sm" class="ml-auto" @click="editing = false">
            改用规则识别
          </Button>
        </div>
        <p
          v-for="title in activePreview.duplicateProjectTitles"
          :key="title"
          class="ws-import-warn text-[12px] text-[#FF9F0A]"
        >项目「{{ title }}」存在多个同名项目，将匹配第一个。</p>
        <p v-if="activePreview.duplicateOpenCount" class="ws-import-warn text-[12px] text-[#FF9F0A]">
          其中 {{ activePreview.duplicateOpenCount }} 条与现有未完成待办同名，将照常创建。
        </p>
        <p v-if="ignoredProjectTitles.length" class="ws-import-warn ws-import-ignored text-[12px] text-[#FF9F0A]">
          项目「{{ ignoredProjectTitles.join('」「') }}」不存在，已归入未分类（不会新建项目）。
        </p>

        <div
          v-if="editing"
          class="ws-import-table max-h-[320px] overflow-auto rounded-xl border border-black/[0.06] dark:border-white/[0.08]"
        >
          <table class="w-full border-collapse text-left text-[12px]">
            <thead class="sticky top-0 bg-white/90 backdrop-blur dark:bg-[#1c1c1e]/90">
              <tr class="text-[#6e6e73] dark:text-[#98989d]">
                <th class="px-3 py-2 font-medium">行</th>
                <th class="px-3 py-2 font-medium">项目</th>
                <th class="px-3 py-2 font-medium">模块</th>
                <th class="px-3 py-2 font-medium">待办</th>
                <th class="px-3 py-2 font-medium">详情</th>
                <th class="px-3 py-2 font-medium">创建时间</th>
                <th class="px-3 py-2 font-medium">姓名</th>
                <th class="px-3 py-2 font-medium">状态</th>
                <th class="px-2 py-2"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, index) in editRows"
                :key="index"
                class="ws-import-row border-t border-black/[0.04] dark:border-white/[0.06]"
              >
                <td class="px-3 py-1.5 tabular-nums text-[#6e6e73] dark:text-[#98989d]">{{ index + 1 }}</td>
                <td class="px-2 py-1.5">
                  <Select
                    v-model="row.project"
                    class="ws-import-cell w-[150px]"
                    :aria-label="`第 ${index + 1} 行项目`"
                    :disabled="importing || finished || lockedProject !== null"
                    @update:model-value="onProjectChange(row)"
                  >
                    <option value="">未分类</option>
                    <option v-for="title in projectTitles" :key="title" :value="title">{{ title }}</option>
                  </Select>
                </td>
                <td class="px-2 py-1.5">
                  <Select
                    v-model="row.module"
                    class="ws-import-cell w-[120px]"
                    :aria-label="`第 ${index + 1} 行模块`"
                    :disabled="importing || finished || !row.project"
                  >
                    <option value="">不分模块</option>
                    <option v-for="name in moduleOptions(row)" :key="name" :value="name">{{ name }}</option>
                  </Select>
                </td>
                <td class="px-2 py-1.5">
                  <Textarea
                    v-model="row.title"
                    class="ws-import-cell min-w-[260px]"
                    rows="2"
                    maxlength="200"
                    :aria-label="`第 ${index + 1} 行待办`"
                    :disabled="importing || finished"
                  />
                </td>
                <td class="px-2 py-1.5">
                  <Textarea
                    v-model="row.detail"
                    class="ws-import-cell min-w-[220px]"
                    rows="2"
                    maxlength="5000"
                    :aria-label="`第 ${index + 1} 行详情`"
                    :disabled="importing || finished"
                  />
                </td>
                <td class="px-2 py-1.5">
                  <Input
                    v-model="row.createdAtLocal"
                    class="ws-import-cell w-[166px]"
                    type="datetime-local"
                    :aria-label="`第 ${index + 1} 行创建时间`"
                    :disabled="importing || finished"
                  />
                </td>
                <td class="px-2 py-1.5">
                  <Input
                    v-model="row.reporter"
                    class="ws-import-cell w-[110px]"
                    maxlength="64"
                    :aria-label="`第 ${index + 1} 行姓名`"
                    :disabled="importing || finished"
                  />
                </td>
                <td
                  class="max-w-[160px] truncate px-3 py-1.5"
                  :class="editError(index) ? 'text-[#FF3B30]' : 'text-[#6e6e73] dark:text-[#98989d]'"
                  :title="editError(index) || statusOf(index + 1, '')"
                >{{ editError(index) || statusOf(index + 1, '') }}</td>
                <td class="px-2 py-1.5">
                  <Button
                    variant="ghost"
                    size="icon"
                    class="ws-import-remove h-8 w-8"
                    :aria-label="`删除第 ${index + 1} 行`"
                    :disabled="importing || finished"
                    @click="removeEditRow(index)"
                  >
                    <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
                    </svg>
                  </Button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div
          v-else
          class="ws-import-table max-h-[280px] overflow-y-auto rounded-xl border border-black/[0.06] dark:border-white/[0.08]"
        >
          <table class="w-full border-collapse text-left text-[12px]">
            <thead class="sticky top-0 bg-white/90 backdrop-blur dark:bg-[#1c1c1e]/90">
              <tr class="text-[#6e6e73] dark:text-[#98989d]">
                <th class="px-3 py-2 font-medium">行</th>
                <th class="px-3 py-2 font-medium">项目</th>
                <th class="px-3 py-2 font-medium">模块</th>
                <th class="px-3 py-2 font-medium">待办</th>
                <th v-if="showCreatedColumn" class="px-3 py-2 font-medium">创建时间</th>
                <th v-if="showReporterColumn" class="px-3 py-2 font-medium">姓名</th>
                <th class="px-3 py-2 font-medium">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in preview.rows"
                :key="row.line"
                class="ws-import-row border-t border-black/[0.04] dark:border-white/[0.06]"
              >
                <td class="px-3 py-1.5 tabular-nums text-[#6e6e73] dark:text-[#98989d]">{{ row.line }}</td>
                <td class="max-w-[160px] truncate px-3 py-1.5" :title="displayProject(row)">
                  {{ displayProject(row) }}
                </td>
                <td class="max-w-[120px] truncate px-3 py-1.5" :title="row.module">{{ row.module || '—' }}</td>
                <td class="max-w-[260px] truncate px-3 py-1.5" :title="row.title">{{ row.title || '—' }}</td>
                <td v-if="showCreatedColumn" class="ws-import-created whitespace-nowrap px-3 py-1.5 tabular-nums">
                  {{ formatDateTime(row.createdAt) || '—' }}
                </td>
                <td v-if="showReporterColumn" class="ws-import-reporter max-w-[120px] truncate px-3 py-1.5" :title="row.reporter">
                  {{ row.reporter || '—' }}
                </td>
                <td
                  class="max-w-[220px] truncate px-3 py-1.5"
                  :class="row.error || (results.get(row.line) && results.get(row.line) !== 'ok')
                    ? 'text-[#FF3B30]'
                    : 'text-[#6e6e73] dark:text-[#98989d]'"
                  :title="statusOf(row.line, row.error)"
                >{{ statusOf(row.line, row.error) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <p v-else-if="text.trim() && !preview.error" class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">
        没有可导入的行。
      </p>
      <p v-else-if="!text.trim()" class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">粘贴后在这里预览。</p>

      <p v-if="importing" class="ws-import-progress text-[12px] text-[#6e6e73] dark:text-[#98989d]">
        正在导入 {{ progress.done }} / {{ progress.total }}
      </p>
      <p v-if="finished" class="ws-import-result text-[13px] font-medium">
        成功 {{ summary.ok }} 条<span v-if="summary.failed">，失败 {{ summary.failed }} 条</span>
      </p>

      <div class="flex flex-wrap items-center gap-3 pt-1">
        <Button
          variant="secondary"
          class="ws-import-ai"
          :disabled="importing || finished || aiBusy || !text.trim()"
          @click="runAi"
        >
          {{ aiBusy
            ? (aiProgress.total > 1
              ? `AI 识别中 ${Math.min(aiProgress.done + 1, aiProgress.total)}/${aiProgress.total}…`
              : 'AI 识别中…')
            : 'AI 识别' }}
        </Button>
        <div
          v-if="aiBusy || importing"
          class="ws-import-bar min-w-[120px] flex-1"
          role="progressbar"
          :aria-label="aiBusy ? 'AI 识别进度' : '导入进度'"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="progressPercent"
        >
          <div class="relative h-1.5 w-full overflow-hidden rounded-full bg-black/[0.08] dark:bg-white/[0.12]">
            <div
              class="h-full rounded-full bg-[#007AFF] transition-[width] duration-300 ease-[var(--ws-ease)]"
              :style="{ width: `${progressPercent}%` }"
            ></div>
            <div class="ws-import-bar-pulse absolute inset-y-0 left-0 w-1/3 rounded-full bg-gradient-to-r from-transparent via-[#007AFF]/50 to-transparent"></div>
          </div>
        </div>
        <div class="ml-auto flex gap-2">
          <Button variant="ghost" :disabled="importing" @click="emit('close')">关闭</Button>
          <Button variant="primary" class="ws-import-submit" :disabled="!canImport" @click="runImport">
            导入 {{ validRows.length }} 条
          </Button>
        </div>
      </div>
    </div>
  </Modal>
</template>
