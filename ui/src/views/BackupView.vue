<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, exportBackup, importBackup } from '../api'
import type { BackupCounts, BackupData } from '../types'
import { formatDateTime, messageOf } from '../utils'
import { useToast } from '../lib/toast'
import Button from '../components/ui/Button.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Toast from '../components/Toast.vue'

const emit = defineEmits<{ unauthorized: [] }>()

const { toast, show } = useToast()
const fileInput = ref<HTMLInputElement | null>(null)
const exporting = ref(false)
const importing = ref(false)
const pending = ref<BackupData | null>(null)
const fileName = ref('')
const localError = ref('')
const replaceConfirm = ref(false)
const result = ref<BackupCounts | null>(null)

const MAX_FILE_BYTES = 8 * 1024 * 1024

function handleError(err: unknown): void {
  if (err instanceof ApiError && err.status === 401) {
    emit('unauthorized')
    return
  }
  show(messageOf(err))
}

function stamp(): string {
  const now = new Date()
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-`
    + `${pad(now.getHours())}${pad(now.getMinutes())}`
}

async function runExport(): Promise<void> {
  exporting.value = true
  try {
    const data = await exportBackup()
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `personal-workspace-backup-${stamp()}.json`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
    show('备份已导出')
  } catch (err) {
    handleError(err)
  } finally {
    exporting.value = false
  }
}

function pickFile(): void {
  fileInput.value?.click()
}

function normalizeBackup(value: unknown): BackupData {
  if (typeof value !== 'object' || value === null) {
    throw new Error('备份文件格式不正确')
  }
  const data = value as Partial<BackupData>
  if (data.version !== '1') {
    throw new Error('备份文件版本不支持')
  }
  return {
    version: '1',
    exportedAt: data.exportedAt ?? null,
    todos: Array.isArray(data.todos) ? data.todos : [],
    projects: Array.isArray(data.projects) ? data.projects : [],
    commands: Array.isArray(data.commands) ? data.commands : [],
    notes: Array.isArray(data.notes) ? data.notes : []
  }
}

async function onFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > MAX_FILE_BYTES) {
    localError.value = '备份文件不能超过 8MB'
    pending.value = null
    return
  }
  try {
    const data = normalizeBackup(JSON.parse(await file.text()))
    pending.value = data
    fileName.value = file.name
    localError.value = ''
    result.value = null
  } catch (err) {
    pending.value = null
    fileName.value = ''
    localError.value = err instanceof Error ? err.message : '备份文件格式不正确'
  }
}

function countOf(items: unknown[] | null | undefined): number {
  return Array.isArray(items) ? items.length : 0
}

async function runImport(replace: boolean): Promise<void> {
  if (!pending.value) return
  importing.value = true
  localError.value = ''
  try {
    result.value = await importBackup(pending.value, replace)
    show(replace ? '已覆盖导入' : '已合并导入')
    pending.value = null
    fileName.value = ''
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    localError.value = messageOf(err)
  } finally {
    importing.value = false
  }
}

function confirmReplace(): void {
  replaceConfirm.value = false
  void runImport(true)
}
</script>

<template>
  <div class="ws-view">
    <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <section
        class="ws-backup-card rounded-[18px] border border-white/20 bg-white/[0.72] p-6 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
      >
        <h3 class="text-[16px] font-semibold tracking-[-0.01em]">导出备份</h3>
        <p class="mt-1 text-[13px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
          导出待办、项目、命令与笔记为 JSON。只包含文本与链接，图片仅保留 URL，不包含二进制文件。
        </p>
        <Button class="mt-4" variant="primary" :disabled="exporting" @click="runExport">
          {{ exporting ? '导出中…' : '导出 JSON' }}
        </Button>
      </section>

      <section
        class="ws-backup-card rounded-[18px] border border-white/20 bg-white/[0.72] p-6 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
      >
        <h3 class="text-[16px] font-semibold tracking-[-0.01em]">导入备份</h3>
        <p class="mt-1 text-[13px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
          选择一个由本插件导出的 JSON。合并导入会保留现有数据；覆盖导入会先清空当前账号的全部数据。
        </p>
        <input
          ref="fileInput"
          class="hidden"
          type="file"
          accept="application/json,.json"
          @change="onFile"
        />
        <Button class="mt-4" :disabled="importing" @click="pickFile">选择备份文件</Button>

        <div v-if="pending" class="mt-4 rounded-xl bg-black/[0.04] p-4 dark:bg-white/[0.05]">
          <p class="truncate text-[13px] font-medium" :title="fileName">{{ fileName }}</p>
          <p class="mt-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
            待办 {{ countOf(pending.todos) }} · 项目 {{ countOf(pending.projects) }}
            · 命令 {{ countOf(pending.commands) }} · 笔记 {{ countOf(pending.notes) }}
          </p>
          <p v-if="pending.exportedAt" class="mt-0.5 text-[11px] text-[#6e6e73] dark:text-[#98989d]">
            导出时间 {{ formatDateTime(pending.exportedAt) }}
          </p>
          <div class="mt-3 flex flex-wrap gap-2">
            <Button
              variant="primary"
              :disabled="importing"
              @click="runImport(false)"
            >{{ importing ? '导入中…' : '合并导入' }}</Button>
            <Button
              variant="danger"
              :disabled="importing"
              @click="replaceConfirm = true"
            >覆盖导入</Button>
          </div>
        </div>

        <p
          v-if="localError"
          class="ws-form-error mt-3 rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
          role="alert"
        >{{ localError }}</p>
      </section>
    </div>

    <section
      v-if="result"
      class="ws-backup-result mt-4 rounded-[18px] border border-[#34C759]/25 bg-[#34C759]/[0.08] px-6 py-4 text-[13px] text-[#1d1d1f] dark:text-[#f5f5f7]"
    >
      导入完成：待办 {{ result.todos }}、项目 {{ result.projects }}、命令 {{ result.commands }}、
      笔记 {{ result.notes }}。
    </section>

    <section
      class="ws-backup-card mt-4 rounded-[18px] border border-white/20 bg-white/[0.72] p-6 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <h3 class="text-[15px] font-semibold">备份内容</h3>
      <ul class="mt-2 space-y-1.5 text-[13px] text-[#6e6e73] dark:text-[#98989d]">
        <li>待办：标题、详情、优先级、项目/模块、截止日期、完成时间</li>
        <li>项目：名称、模块、进度、备注、关联链接</li>
        <li>命令：名称、分类、命令内容、备注</li>
        <li>笔记：标题、Markdown 正文、关联项目/模块/待办；图片仅保留 URL</li>
      </ul>
    </section>

    <ConfirmDialog
      v-if="replaceConfirm"
      title="覆盖导入"
      message="这将先删除当前账号的所有待办、项目、命令和笔记，再导入备份内容。确定继续？"
      confirm-text="覆盖导入"
      @confirm="confirmReplace"
      @close="replaceConfirm = false"
    />

    <Toast :message="toast" />
  </div>
</template>
