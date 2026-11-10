<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ApiError,
  createCommand,
  deleteCommand,
  listCommands,
  updateCommand
} from '../api'
import type { QuickCmd, QuickCmdPayload } from '../types'
import { messageOf } from '../utils'
import { takeSearchTarget } from '../searchTarget'
import { useToast } from '../lib/toast'
import Button from '../components/ui/Button.vue'
import Input from '../components/ui/Input.vue'
import Select from '../components/ui/Select.vue'
import CommandForm from '../components/CommandForm.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Toast from '../components/Toast.vue'
import { toolsCollapsed } from '../collapse'

const emit = defineEmits<{ unauthorized: [] }>()

const commands = ref<QuickCmd[]>([])
const keyword = ref(takeSearchTarget())
const categoryFilter = ref('')
const loading = ref(true)
const error = ref('')

const formOpen = ref(false)
const editing = ref<QuickCmd | null>(null)
const saving = ref(false)
const pendingDelete = ref<QuickCmd | null>(null)
const { toast, show } = useToast()

const categories = computed(() => {
  const values = new Set<string>()
  for (const command of commands.value) {
    if (command.category) values.add(command.category)
  }
  return [...values].sort((left, right) => left.localeCompare(right, 'zh-CN'))
})

const filteredCommands = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  return commands.value.filter((command) => {
    if (categoryFilter.value && command.category !== categoryFilter.value) return false
    if (!needle) return true
    return [command.title, command.command, command.note ?? '', command.category ?? '']
      .some((value) => value.toLowerCase().includes(needle))
  })
})

function handleError(err: unknown): void {
  if (err instanceof ApiError && err.status === 401) {
    emit('unauthorized')
    return
  }
  show(messageOf(err))
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    commands.value = await listCommands()
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      emit('unauthorized')
      return
    }
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  formOpen.value = true
}

function openEdit(command: QuickCmd): void {
  editing.value = command
  formOpen.value = true
}

function closeForm(): void {
  if (saving.value) return
  formOpen.value = false
  editing.value = null
}

async function save(payload: QuickCmdPayload): Promise<void> {
  saving.value = true
  try {
    if (editing.value) {
      const updated = await updateCommand(editing.value.name, payload)
      const index = commands.value.findIndex((item) => item.name === updated.name)
      if (index >= 0) commands.value[index] = updated
      show('已保存')
    } else {
      const created = await createCommand(payload)
      commands.value = [created, ...commands.value]
      show('已创建命令')
    }
    formOpen.value = false
    editing.value = null
  } catch (err) {
    handleError(err)
  } finally {
    saving.value = false
  }
}

async function copy(command: QuickCmd): Promise<void> {
  try {
    await navigator.clipboard.writeText(command.command)
    show('已复制命令')
  } catch {
    const area = document.createElement('textarea')
    area.value = command.command
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    document.execCommand('copy')
    document.body.removeChild(area)
    show('已复制命令')
  }
}

async function removeConfirmed(): Promise<void> {
  const command = pendingDelete.value
  if (!command) return
  pendingDelete.value = null
  try {
    await deleteCommand(command.name)
    commands.value = commands.value.filter((item) => item.name !== command.name)
    show('已删除命令')
  } catch (err) {
    handleError(err)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="ws-view">
    <template v-if="!toolsCollapsed">
    <div class="ws-toolbar mb-4 flex flex-wrap items-center gap-2">
      <div class="relative min-w-[180px] flex-1 sm:max-w-[340px]">
        <svg
          class="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-black/30 dark:text-white/30"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.5" />
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
        <Input v-model="keyword" class="ws-search pl-10" placeholder="搜索命令、备注或分类" />
      </div>
      <Select v-if="categories.length" v-model="categoryFilter" class="w-[130px]" aria-label="命令分类">
        <option value="">全部分类</option>
        <option v-for="item in categories" :key="item" :value="item">{{ item }}</option>
      </Select>
      <Button variant="primary" class="ml-auto" @click="openCreate">
        <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        新建命令
      </Button>
    </div>
    </template>

    <section
      v-if="error"
      class="ws-state ws-state-error flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.72] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
    >
      <p class="ws-state-title text-[15px] font-semibold">加载失败</p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">{{ error }}</p>
      <Button class="mt-2" @click="load">重试</Button>
    </section>

    <div v-else-if="loading" class="ws-skeleton flex flex-col gap-3" aria-label="正在加载">
      <div
        v-for="index in 3"
        :key="index"
        class="ws-skeleton-row h-36 animate-pulse rounded-[18px] bg-black/[0.05] dark:bg-white/[0.06]"
      ></div>
    </div>

    <section
      v-else-if="!filteredCommands.length"
      class="ws-state ws-empty flex flex-col items-center gap-2 rounded-[18px] border border-white/20 bg-white/[0.6] px-6 py-12 text-center backdrop-blur-xl dark:border-white/[0.08] dark:bg-[#1c1c1e]/50"
    >
      <p class="ws-state-title text-[15px] font-semibold">
        {{ commands.length ? '没有匹配的命令' : '还没有命令' }}
      </p>
      <p class="ws-muted text-[13px] text-[#6e6e73] dark:text-[#98989d]">
        {{ commands.length ? '换个关键词或分类试试' : '把常用命令存起来，需要时一键复制' }}
      </p>
      <Button v-if="!commands.length" class="mt-2" variant="primary" @click="openCreate">
        新建命令
      </Button>
    </section>

    <div v-else class="flex flex-col gap-4">
      <article
        v-for="command in filteredCommands"
        :key="command.name"
        class="ws-command-card group rounded-[18px] border border-white/20 bg-white/[0.72] p-5 shadow-[0_4px_24px_rgba(0,0,0,0.08)] backdrop-blur-xl saturate-[180%] dark:border-white/[0.08] dark:bg-[#1c1c1e]/70"
      >
        <header class="mb-2 flex items-start justify-between gap-2">
          <div class="min-w-0">
            <div class="flex items-center gap-2">
              <h3 class="truncate text-[15px] font-semibold">{{ command.title }}</h3>
              <span
                v-if="command.category"
                class="shrink-0 rounded-full bg-black/[0.05] px-2 py-0.5 text-[11px] text-[#6e6e73] dark:bg-white/[0.08] dark:text-[#98989d]"
              >{{ command.category }}</span>
            </div>
            <p v-if="command.note" class="mt-0.5 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
              {{ command.note }}
            </p>
          </div>
          <div class="flex shrink-0 items-center opacity-100 transition-opacity lg:opacity-0 lg:group-hover:opacity-100">
            <Button variant="ghost" size="icon" aria-label="编辑" title="编辑" @click="openEdit(command)">
              <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M10.8 2.6a1.4 1.4 0 012 2L5 12.4l-2.6.6.6-2.6 7.8-7.8z"
                  stroke="currentColor"
                  stroke-width="1.4"
                  stroke-linejoin="round"
                />
              </svg>
            </Button>
            <Button
              variant="ghost"
              size="icon"
              class="text-[#FF3B30]/75 hover:text-[#FF3B30]"
              aria-label="删除"
              title="删除"
              @click="pendingDelete = command"
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
          </div>
        </header>

        <div class="relative rounded-xl bg-black/[0.04] px-3 py-2.5 dark:bg-white/[0.05]">
          <pre class="ws-command-text whitespace-pre-wrap break-words pr-12 font-mono text-[12.5px] leading-relaxed text-[#1d1d1f] dark:text-[#f5f5f7]">{{ command.command }}</pre>
          <Button
            variant="secondary"
            size="sm"
            class="absolute right-2 top-2"
            aria-label="复制命令"
            @click="copy(command)"
          >
            <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" stroke="currentColor" stroke-width="1.3" />
              <path d="M10.5 5.5v-1a1 1 0 00-1-1h-6a1 1 0 00-1 1v6a1 1 0 001 1h1" stroke="currentColor" stroke-width="1.3" />
            </svg>
            复制
          </Button>
        </div>
      </article>
    </div>

    <CommandForm
      v-if="formOpen"
      :command="editing"
      :categories="categories"
      :saving="saving"
      @save="save"
      @close="closeForm"
    />

    <ConfirmDialog
      v-if="pendingDelete"
      title="删除命令"
      :message="`确定删除「${pendingDelete.title}」？删除后不可恢复。`"
      confirm-text="删除"
      @confirm="removeConfirmed"
      @close="pendingDelete = null"
    />

    <Toast :message="toast" />
  </div>
</template>
