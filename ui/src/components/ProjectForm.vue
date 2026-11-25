<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Project, ProjectPayload } from '../types'
import { DEFAULT_PROJECT_STATUS, PROJECT_STATUSES, projectStatusMeta, projectStatusOf } from '../projectStatus'
import type { ProjectStatus } from '../projectStatus'
import { formatDate, todayString } from '../utils'
import Modal from './ui/Modal.vue'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Select from './ui/Select.vue'
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

interface TimelineRow {
  key: string
  status: ProjectStatus
  date: string
  startedAt: string | null
  pending: boolean
}

let timelineSeq = 0

function newTimelineRow(status: ProjectStatus, date: string,
  startedAt: string | null = null, pending = false): TimelineRow {
  timelineSeq += 1
  return { key: `timeline-${timelineSeq}`, status, date, startedAt, pending }
}

function toTimeline(project: Project | null): TimelineRow[] {
  if (!project) return []
  const changes = (project.statusHistory ?? []).filter((change) => change?.status)
  if (!changes.length) {
    return [newTimelineRow(projectStatusOf(project), todayString())]
  }
  return changes.map((change) => newTimelineRow(
    change.status,
    formatDate(change.startedAt) || todayString(),
    change.startedAt
  ))
}

const title = ref(props.project?.title ?? '')
const modules = ref<string[]>(props.project ? [...props.project.modules] : [])
const status = ref<ProjectStatus>(
  props.project ? projectStatusOf(props.project) : DEFAULT_PROJECT_STATUS)
const timeline = ref<TimelineRow[]>(toTimeline(props.project))
const note = ref(props.project?.note ?? '')
const linksText = ref((props.project?.links ?? []).join('\n'))
const editor = ref<InstanceType<typeof ModuleEditor> | null>(null)
const localError = ref('')

watch(status, (next) => {
  if (!props.project || !timeline.value.length) return
  const rows = timeline.value
  const last = rows[rows.length - 1]
  if (last.pending) {
    const previous = rows[rows.length - 2]
    if (previous && previous.status === next) rows.pop()
    else last.status = next
    return
  }
  if (next !== last.status) {
    const today = todayString()
    rows.push(newTimelineRow(next, last.date > today ? last.date : today, null, true))
  }
})

function addStage(): void {
  const rows = timeline.value
  if (!rows.length) return
  const last = rows[rows.length - 1]
  const previous = rows[rows.length - 2]
  const fallback = PROJECT_STATUSES.map((item) => item.value)
    .find((value) => value !== previous?.status && value !== last.status) ?? last.status
  rows.splice(rows.length - 1, 0, newTimelineRow(fallback, last.date))
}

function removeStage(index: number): void {
  const rows = timeline.value
  if (index < 0 || index >= rows.length || rows.length <= 1) return
  const removingCurrent = index === rows.length - 1
  rows.splice(index, 1)
  if (removingCurrent) status.value = rows[rows.length - 1].status
}

function startedAtOf(row: TimelineRow): string {
  if (row.startedAt && formatDate(row.startedAt) === row.date) return row.startedAt
  return new Date(`${row.date}T12:00:00`).toISOString()
}

function timelineError(): string {
  const rows = timeline.value
  if (!rows.length) return '状态时间线至少保留一段'
  if (rows[rows.length - 1].status !== status.value) return '当前状态与时间线最后一段不一致'
  for (let index = 0; index < rows.length; index += 1) {
    if (!rows[index].date) return '请填写每段状态的开始日期'
    if (index === 0) continue
    if (rows[index].status === rows[index - 1].status) return '相邻阶段状态不能相同'
    if (rows[index].date < rows[index - 1].date) return '状态时间线日期需按顺序排列'
  }
  return ''
}

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
  if (props.project) {
    const error = timelineError()
    if (error) {
      localError.value = error
      return
    }
  }
  localError.value = ''
  const payload: ProjectPayload = {
    title: titleValue,
    modules: [...modules.value],
    status: status.value,
    note: note.value.trim() || null,
    links,
    tags: props.project?.tags ?? []
  }
  if (props.project) {
    payload.statusHistory = timeline.value.map((row) => ({
      status: row.status,
      startedAt: startedAtOf(row)
    }))
  }
  emit('save', payload)
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
        <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">状态</span>
        <Select v-model="status" class="ws-project-status">
          <option v-for="item in PROJECT_STATUSES" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </Select>
        <span class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">切换状态会追加一段；误操作可在下方时间线删除。</span>
      </label>

      <div v-if="project" class="ws-field flex flex-col gap-1.5">
        <div class="flex items-center justify-between gap-2">
          <span class="text-[12px] font-medium text-[#6e6e73] dark:text-[#98989d]">状态时间线</span>
          <button
            type="button"
            class="ws-timeline-add inline-flex min-h-8 items-center gap-1 rounded-full px-2 text-[12px] font-medium text-[#007AFF] transition-colors hover:bg-[#007AFF]/[0.08] focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-[#007AFF]/20 dark:text-[#64B5FF] dark:hover:bg-[#0A84FF]/[0.14]"
            @click="addStage"
          >
            <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            </svg>
            添加阶段
          </button>
        </div>
        <ol
          class="ws-timeline-editor max-h-52 space-y-1.5 overflow-y-auto rounded-[12px] border border-black/[0.05] bg-black/[0.02] p-2 dark:border-white/[0.06] dark:bg-white/[0.025]"
        >
          <li
            v-for="(row, index) in timeline"
            :key="row.key"
            class="ws-timeline-row flex items-center gap-1.5"
          >
            <span
              v-if="index === timeline.length - 1"
              class="ws-timeline-current inline-flex h-11 w-[104px] shrink-0 items-center gap-1.5 rounded-full px-3 text-[13px]"
              :class="projectStatusMeta(row.status).badge"
              title="当前状态，请在上方切换"
            >
              <i
                class="h-1.5 w-1.5 shrink-0 rounded-full"
                :class="projectStatusMeta(row.status).dot"
                aria-hidden="true"
              ></i>
              <span class="truncate">{{ projectStatusMeta(row.status).label }}</span>
            </span>
            <Select
              v-else
              v-model="row.status"
              class="w-[104px] shrink-0"
              :aria-label="`第 ${index + 1} 段状态`"
            >
              <option v-for="item in PROJECT_STATUSES" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </Select>
            <input
              v-model="row.date"
              type="date"
              class="ws-date min-w-0 flex-1"
              :aria-label="`第 ${index + 1} 段开始日期`"
            />
            <Button
              v-if="timeline.length > 1"
              type="button"
              variant="ghost"
              size="icon"
              class="ws-timeline-remove shrink-0 text-[#FF3B30]/75 hover:text-[#FF3B30]"
              :aria-label="index === timeline.length - 1
                ? '删除当前阶段并回退到上一阶段'
                : `删除第 ${index + 1} 段`"
              :title="index === timeline.length - 1
                ? '删除当前阶段并回退到上一阶段'
                : '删除该阶段'"
              @click="removeStage(index)"
            >
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M2.5 4.5h11M6.5 4.5V3h3v1.5M4 4.5l.6 8.5h6.8l.6-8.5"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </Button>
          </li>
        </ol>
        <span class="text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          调整每段开始日期或删除误操作阶段；删除最后一段会回退到上一阶段，切换状态会追加新阶段。
        </span>
      </div>

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
