<script setup lang="ts">
import type { Todo } from '../types'
import { formatDateTime, priorityClass, priorityLabel, todayString } from '../utils'
import Button from './ui/Button.vue'

const props = defineProps<{
  todo: Todo
  selecting: boolean
  selected: boolean
  busy: boolean
  bulkBusy: boolean
  noteBusy: boolean
  scope?: string
}>()

const emit = defineEmits<{
  select: []
  toggle: []
  edit: []
  createNote: []
  relatedNotes: []
  dismiss: []
  remove: []
  filter: []
}>()

function isOverdue(): boolean {
  return Boolean(props.todo.dueDate && !props.todo.completed && !props.todo.dismissed
    && props.todo.dueDate < todayString())
}
</script>

<template>
  <article
    class="ws-todo group flex items-start gap-1 rounded-xl px-1 transition-colors duration-200 ease-[var(--ws-ease)] hover:bg-black/[0.03] dark:hover:bg-white/[0.04]"
    :class="{
      done: todo.completed,
      dismissed: todo.dismissed,
      'bg-[#007AFF]/[0.06] dark:bg-[#0A84FF]/[0.10]': selecting && selected
    }"
  >
    <label
      v-if="selecting"
      class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center"
    >
      <input
        class="ws-select h-5 w-5 accent-[#007AFF]"
        type="checkbox"
        :checked="selected"
        :disabled="bulkBusy"
        :aria-label="`选择 ${todo.title}`"
        @change="emit('select')"
      />
    </label>
    <label
      v-else
      class="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center"
    >
      <input
        class="ws-check h-5 w-5 accent-[#007AFF]"
        type="checkbox"
        :checked="todo.completed"
        :disabled="busy || bulkBusy"
        :aria-label="`标记 ${todo.title} 完成`"
        @change="emit('toggle')"
      />
    </label>
    <div
      class="ws-todo-body min-w-0 flex-1 cursor-pointer py-2.5"
      @click="selecting ? emit('select') : emit('edit')"
    >
      <div class="ws-todo-top flex items-baseline gap-2">
        <h4
          class="min-w-0 flex-1 truncate text-[14px]"
          :class="todo.completed || todo.dismissed
            ? 'text-[#1d1d1f]/45 dark:text-[#f5f5f7]/40'
            : 'text-[#1d1d1f] dark:text-[#f5f5f7]'"
        >{{ todo.title }}</h4>
        <span
          v-if="todo.dismissed"
          class="ws-dismissed-tag shrink-0 text-[11px] text-[#8e8e93] dark:text-[#98989d]"
        >不用处理</span>
        <span
          class="ws-prio shrink-0 text-[11px] font-medium tabular-nums"
          :class="priorityClass(todo.priority)"
        >{{ priorityLabel(todo.priority) }}</span>
      </div>
      <p
        v-if="todo.detail"
        class="ws-todo-detail line-clamp-2 text-[12px] text-[#6e6e73] dark:text-[#98989d]"
      >{{ todo.detail }}</p>
      <div
        v-if="scope || todo.reporter || todo.dueDate || todo.completedAt || todo.createdAt"
        class="ws-meta mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] tabular-nums text-[#6e6e73] dark:text-[#98989d]"
      >
        <button
          v-if="scope"
          type="button"
          class="ws-todo-scope text-left transition-colors duration-150 hover:text-[#007AFF] hover:underline hover:underline-offset-2 dark:hover:text-[#64B5FF]"
          title="按该项目和模块筛选"
          @click.stop="emit('filter')"
        >{{ scope }}</button>
        <span v-if="todo.reporter" class="ws-reporter">姓名 {{ todo.reporter }}</span>
        <span
          v-if="todo.dueDate"
          class="ws-due"
          :class="isOverdue() ? 'text-[#FF3B30]' : ''"
        >{{ todo.dueDate }}</span>
        <span v-if="todo.completedAt" class="ws-completed-at">
          完成于 {{ formatDateTime(todo.completedAt) }}
        </span>
        <span v-if="todo.createdAt" class="ws-created-at">
          创建于 {{ formatDateTime(todo.createdAt) }}
        </span>
      </div>
    </div>
    <div
      class="ws-todo-actions flex shrink-0 items-center self-center transition-opacity duration-200 ease-[var(--ws-ease)] lg:opacity-0 lg:group-hover:opacity-100 lg:group-focus-within:opacity-100"
    >
      <Button
        variant="ghost"
        size="icon"
        :aria-label="`为 ${todo.title} 新建笔记`"
        title="新建笔记"
        @click="emit('createNote')"
      >
        <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M3.5 2.5h7a1 1 0 011 1v9a1 1 0 01-1 1h-7a1 1 0 01-1-1v-9a1 1 0 011-1z"
            stroke="currentColor"
            stroke-width="1.4"
          />
          <path d="M7 5.5v5M4.5 8h5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        </svg>
      </Button>
      <Button
        variant="ghost"
        size="icon"
        :disabled="noteBusy"
        :aria-label="`查看 ${todo.title} 的关联笔记`"
        title="查看关联笔记"
        @click="emit('relatedNotes')"
      >
        <svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M3.5 2.5h7a1 1 0 011 1v9a1 1 0 01-1 1h-7a1 1 0 01-1-1v-9a1 1 0 011-1z"
            stroke="currentColor"
            stroke-width="1.4"
          />
          <path d="M5.5 5h4M5.5 7.5h4M5.5 10h2.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        </svg>
      </Button>
      <Button
        variant="ghost"
        size="icon"
        :disabled="busy"
        :aria-label="todo.dismissed
          ? `恢复 ${todo.title} 到待办`
          : `将 ${todo.title} 标记为不用处理`"
        :title="todo.dismissed ? '恢复到待办' : '不用处理'"
        @click="emit('dismiss')"
      >
        <svg v-if="todo.dismissed" class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M3.5 7a4.8 4.8 0 118.2 3.4M3.5 3.5V7h3.5"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <svg v-else class="h-4 w-4" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path
            d="M2.5 4h11v2.2h-11zM3.7 6.2V13h8.6V6.2M6.3 9h3.4"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
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
        @click="emit('remove')"
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
  </article>
</template>
