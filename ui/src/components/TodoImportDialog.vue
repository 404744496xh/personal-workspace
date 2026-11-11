<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError, createProject, createTodo, updateProject } from '../api'
import type { Project } from '../types'
import { messageOf } from '../utils'
import { parseImport } from '../todoImport'
import Modal from './ui/Modal.vue'
import Button from './ui/Button.vue'
import Textarea from './ui/Textarea.vue'

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
const autoCreate = ref(true)
const importing = ref(false)
const finished = ref(false)
const progress = ref({ done: 0, total: 0 })
const createdProjects = ref(0)
const results = ref(new Map<number, string>())

const preview = computed(() => parseImport(text.value, {
  projects: props.projects,
  activeProject: props.activeProject,
  activeModule: props.activeModule,
  autoCreateProjects: autoCreate.value,
  openTitles: props.openTitles
}))
const validRows = computed(() => preview.value.rows.filter((row) => !row.error))
const errorRows = computed(() => preview.value.rows.filter((row) => row.error))
const canImport = computed(() =>
  !importing.value && !finished.value && !preview.value.error && validRows.value.length > 0)
const summary = computed(() => {
  const ok = [...results.value.values()].filter((value) => value === 'ok').length
  return { ok, failed: validRows.value.length - ok }
})
const contextHint = computed(() => {
  if (props.activeProject === null) return ''
  if (props.activeProject === '') return '当前筛选为未分类，项目列为空时导入到未分类。'
  const title = props.projects.find((project) => project.name === props.activeProject)?.title
  const moduleHint = props.activeModule ? `、模块列为空时归入「${props.activeModule}」` : ''
  return `当前筛选为项目「${title ?? ''}」，项目列为空时归入该项目${moduleHint}。`
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

async function runImport(): Promise<void> {
  if (!canImport.value) return
  importing.value = true
  results.value = new Map()
  const rows = validRows.value
  progress.value = { done: 0, total: rows.length }
  createdProjects.value = 0

  const working = props.projects.map((project) => ({
    name: project.name,
    title: project.title,
    modules: [...project.modules]
  }))
  const projectNames = new Map<string, string>()

  for (const title of preview.value.newProjects) {
    const targets = rows.filter((row) => row.newProject && row.project === title)
    const modules = distinct(targets.map((row) => row.module))
    try {
      const created = await createProject({ title, modules })
      projectNames.set(title, created.name)
      working.push({ name: created.name, title: created.title, modules: [...created.modules] })
      createdProjects.value += 1
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        importing.value = false
        emit('unauthorized')
        return
      }
      failRows(targets, `项目创建失败：${messageOf(err)}`)
    }
  }

  const moduleTargets = new Map<string, typeof rows>()
  for (const row of rows) {
    if (row.newProject || !row.newModule || !row.projectName) continue
    const list = moduleTargets.get(row.projectName) ?? []
    list.push(row)
    moduleTargets.set(row.projectName, list)
  }
  for (const [name, targets] of moduleTargets) {
    const project = working.find((item) => item.name === name)
    if (!project) {
      failRows(targets, '项目不存在')
      continue
    }
    const modules = distinct(targets.map((row) => row.module))
    try {
      const updated = await updateProject(name, {
        title: project.title,
        modules: [...project.modules, ...modules.filter((module) => !project.modules.includes(module))]
      })
      project.modules = [...updated.modules]
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
        const projectName = row.newProject
          ? projectNames.get(row.project) ?? null
          : row.projectName
        if (row.newProject && !projectName) throw new Error('项目创建失败')
        await createTodo({
          title: row.title,
          detail: '',
          priority: 'MEDIUM',
          project: projectName,
          module: row.module || null,
          dueDate: null,
          completed: false
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
  <Modal title="粘贴导入待办" size="wide" @close="onClose">
    <div class="flex flex-col gap-3">
      <p class="text-[12px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
        从 Excel 复制 3 列（项目、模块、待办）直接粘贴，单元格留空即可；只粘贴一列时全部按待办标题处理。
        列数或分隔符有歧义时不导入。{{ contextHint }}
      </p>
      <Textarea
        v-model="text"
        class="ws-import-text min-h-[150px] font-mono text-[13px]"
        placeholder="项目	模块	待办"
        aria-label="粘贴待办数据"
        :disabled="importing || finished"
      />
      <label class="flex w-fit cursor-pointer items-center gap-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
        <input
          v-model="autoCreate"
          class="h-4 w-4 accent-[#007AFF]"
          type="checkbox"
          :disabled="importing || finished"
        />
        自动创建不存在的项目
      </label>

      <div
        v-if="preview.error"
        class="ws-import-error rounded-xl border border-[#FF3B30]/30 bg-[#FF3B30]/[0.06] px-3 py-2 text-[12px] leading-relaxed text-[#FF3B30]"
      >{{ preview.error }}</div>

      <template v-else-if="preview.rows.length">
        <div class="ws-import-summary flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          <span>分隔符：<b class="text-[#1d1d1f] dark:text-[#f5f5f7]">{{ preview.delimiter }}</b></span>
          <span>有效 <b class="text-[#1d1d1f] dark:text-[#f5f5f7]">{{ validRows.length }}</b> 行</span>
          <span v-if="errorRows.length">问题 <b class="text-[#FF3B30]">{{ errorRows.length }}</b> 行</span>
          <span v-if="preview.skippedHeader">已跳过表头</span>
          <span v-if="preview.newProjects.length">新建项目 {{ preview.newProjects.length }} 个</span>
        </div>
        <p
          v-for="title in preview.duplicateProjectTitles"
          :key="title"
          class="ws-import-warn text-[12px] text-[#FF9F0A]"
        >项目「{{ title }}」存在多个同名项目，将匹配第一个。</p>
        <p v-if="preview.duplicateOpenCount" class="ws-import-warn text-[12px] text-[#FF9F0A]">
          其中 {{ preview.duplicateOpenCount }} 条与现有未完成待办同名，将照常创建。
        </p>
        <div class="ws-import-table max-h-[280px] overflow-y-auto rounded-xl border border-black/[0.06] dark:border-white/[0.08]">
          <table class="w-full border-collapse text-left text-[12px]">
            <thead class="sticky top-0 bg-white/90 backdrop-blur dark:bg-[#1c1c1e]/90">
              <tr class="text-[#6e6e73] dark:text-[#98989d]">
                <th class="px-3 py-2 font-medium">行</th>
                <th class="px-3 py-2 font-medium">项目</th>
                <th class="px-3 py-2 font-medium">模块</th>
                <th class="px-3 py-2 font-medium">待办</th>
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
                  <span v-if="row.newProject" class="ml-1 text-[10px] text-[#FF9F0A]">新建</span>
                </td>
                <td class="max-w-[120px] truncate px-3 py-1.5" :title="row.module">{{ row.module || '—' }}</td>
                <td class="max-w-[260px] truncate px-3 py-1.5" :title="row.title">{{ row.title || '—' }}</td>
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

      <p v-else-if="text.trim()" class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">没有可导入的行。</p>
      <p v-else class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">粘贴后在这里预览。</p>

      <p v-if="importing" class="ws-import-progress text-[12px] text-[#6e6e73] dark:text-[#98989d]">
        正在导入 {{ progress.done }} / {{ progress.total }}
      </p>
      <p v-if="finished" class="ws-import-result text-[13px] font-medium">
        成功 {{ summary.ok }} 条<span v-if="summary.failed">，失败 {{ summary.failed }} 条</span><span
          v-if="createdProjects"
        >，新建项目 {{ createdProjects }} 个</span>
      </p>

      <div class="flex justify-end gap-2 pt-1">
        <Button variant="ghost" :disabled="importing" @click="emit('close')">关闭</Button>
        <Button variant="primary" class="ws-import-submit" :disabled="!canImport" @click="runImport">
          导入 {{ validRows.length }} 条
        </Button>
      </div>
    </div>
  </Modal>
</template>
