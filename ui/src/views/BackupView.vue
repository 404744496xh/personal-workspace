<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, backupZipUrl, importBackupZip } from '../api'
import type { BackupCounts } from '../types'
import { messageOf } from '../utils'
import { useToast } from '../lib/toast'
import Button from '../components/ui/Button.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Toast from '../components/Toast.vue'

const emit = defineEmits<{ unauthorized: [] }>()

const { toast, show } = useToast()
const zipInput = ref<HTMLInputElement | null>(null)
const result = ref<BackupCounts | null>(null)
const zipFile = ref<File | null>(null)
const zipFileName = ref('')
const zipError = ref('')
const zipImporting = ref(false)
const zipProgress = ref(0)
const zipReplaceConfirm = ref(false)

const MAX_ZIP_BYTES = 512 * 1024 * 1024

function stamp(): string {
  const now = new Date()
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-`
    + `${pad(now.getHours())}${pad(now.getMinutes())}`
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function runZipExport(): void {
  const anchor = document.createElement('a')
  anchor.href = backupZipUrl()
  anchor.download = `personal-workspace-backup-${stamp()}.zip`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  show('完整备份已开始下载')
}

function pickZipFile(): void {
  zipInput.value?.click()
}

function onZipFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    zipError.value = '请选择 ZIP 备份文件'
    zipFile.value = null
    return
  }
  if (file.size > MAX_ZIP_BYTES) {
    zipError.value = '备份文件不能超过 512MB'
    zipFile.value = null
    return
  }
  zipFile.value = file
  zipFileName.value = file.name
  zipError.value = ''
  zipProgress.value = 0
  result.value = null
}

async function runZipImport(replace: boolean): Promise<void> {
  if (!zipFile.value) return
  zipImporting.value = true
  zipError.value = ''
  zipProgress.value = 0
  try {
    result.value = await importBackupZip(zipFile.value, replace, (percent) => {
      zipProgress.value = percent
    })
    show(replace ? '已覆盖导入完整备份' : '已合并导入完整备份')
    zipFile.value = null
    zipFileName.value = ''
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    zipError.value = messageOf(err)
  } finally {
    zipImporting.value = false
  }
}

function confirmZipReplace(): void {
  zipReplaceConfirm.value = false
  void runZipImport(true)
}
</script>

<template>
  <div class="ws-view">
    <section
      class="ws-backup-card rounded-[18px] border border-white/20 bg-white/[0.72] p-6 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div class="min-w-0">
          <h3 class="text-[16px] font-semibold tracking-[-0.01em]">完整备份</h3>
          <p class="mt-1 max-w-[760px] text-[13px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
            ZIP 包含待办、项目、命令、笔记，以及笔记正文引用的图片附件。导入时会重新上传图片并改写地址，适合完整迁移；支持合并或覆盖导入，单包最大 512MB。
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <Button variant="primary" @click="runZipExport">导出 ZIP</Button>
          <Button :disabled="zipImporting" @click="pickZipFile">导入 ZIP</Button>
        </div>
      </div>
      <input
        ref="zipInput"
        class="hidden"
        type="file"
        accept=".zip,application/zip"
        @change="onZipFile"
      />

      <div v-if="zipFile" class="mt-4 rounded-xl bg-black/[0.04] p-4 dark:bg-white/[0.05]">
        <p class="truncate text-[13px] font-medium" :title="zipFileName">{{ zipFileName }}</p>
        <p class="mt-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          {{ formatSize(zipFile.size) }}
        </p>
        <div v-if="zipImporting" class="mt-3">
          <div class="h-1.5 overflow-hidden rounded-full bg-black/[0.08] dark:bg-white/[0.12]">
            <div
              class="h-full rounded-full bg-[#007AFF] transition-[width] duration-200"
              :style="{ width: `${zipProgress}%` }"
            ></div>
          </div>
          <p class="mt-1.5 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
            {{ zipProgress >= 100 ? '上传完成，正在写入…' : `上传中 ${zipProgress}%` }}
          </p>
        </div>
        <div v-else class="mt-3 flex flex-wrap gap-2">
          <Button variant="primary" @click="runZipImport(false)">合并导入</Button>
          <Button variant="danger" @click="zipReplaceConfirm = true">覆盖导入</Button>
        </div>
      </div>

      <p
        v-if="zipError"
        class="ws-form-error mt-3 rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
        role="alert"
      >{{ zipError }}</p>
    </section>

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
        <li>项目：名称、模块、状态、备注、关联链接</li>
        <li>命令：名称、分类、命令内容、备注</li>
        <li>笔记：标题、Markdown 正文、关联项目/模块/待办/文章</li>
        <li>笔记引用的图片附件会一并打包</li>
      </ul>
    </section>

    <ConfirmDialog
      v-if="zipReplaceConfirm"
      title="覆盖导入完整备份"
      message="这将先删除当前账号的所有待办、项目、命令和笔记，再导入 ZIP 内容。确定继续？"
      confirm-text="覆盖导入"
      @confirm="confirmZipReplace"
      @close="zipReplaceConfirm = false"
    />

    <Toast :message="toast" />
  </div>
</template>
