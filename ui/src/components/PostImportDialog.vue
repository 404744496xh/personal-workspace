<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { Note } from '../types'
import { importNoteFromPost } from '../api'
import { fetchPostContent, listMyPosts, postPhaseLabel } from '../articles'
import type { PostSummary } from '../articles'
import { formatDateTime, messageOf } from '../utils'
import Button from './ui/Button.vue'
import Input from './ui/Input.vue'
import Modal from './ui/Modal.vue'

const props = defineProps<{ linkedPosts: string[] }>()

const emit = defineEmits<{
  close: []
  imported: [note: Note, updated: boolean]
}>()

const posts = ref<PostSummary[]>([])
const keyword = ref('')
const loading = ref(false)
const loadingMore = ref(false)
const importing = ref('')
const error = ref('')
const hasNext = ref(false)
const page = ref(1)
let searchTimer: number | undefined

const username = document.getElementById('workspace-app')?.dataset.username?.trim() ?? ''

async function load(nextPage = 1, append = false): Promise<void> {
  if (append) loadingMore.value = true
  else {
    loading.value = true
    posts.value = []
  }
  error.value = ''
  try {
    const result = await listMyPosts({
      username,
      keyword: keyword.value,
      page: nextPage,
      size: 50
    })
    posts.value = append ? [...posts.value, ...result.posts] : result.posts
    page.value = nextPage
    hasNext.value = result.hasNext
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function scheduleSearch(): void {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    void load(1, false)
  }, 300)
}

function clearSearch(): void {
  window.clearTimeout(searchTimer)
  keyword.value = ''
  void load(1, false)
}

function phaseClass(post: PostSummary): string {
  if (post.phase === 'PUBLISHED' || (!post.phase && post.publish)) {
    return 'bg-[#34C759]/[0.12] text-[#248A3D] dark:bg-[#30D158]/[0.16] dark:text-[#4CD964]'
  }
  if (post.phase === 'PENDING_APPROVAL') {
    return 'bg-[#FF9500]/[0.12] text-[#C93400] dark:bg-[#FF9F0A]/[0.16] dark:text-[#FFB340]'
  }
  if (post.phase === 'FAILED') {
    return 'bg-[#FF3B30]/[0.10] text-[#D70015] dark:bg-[#FF453A]/[0.16] dark:text-[#FF6961]'
  }
  return 'bg-black/[0.05] text-[#1d1d1f]/65 dark:bg-white/[0.10] dark:text-[#f5f5f7]/70'
}

async function importPost(post: PostSummary): Promise<void> {
  if (importing.value) return
  importing.value = post.name
  error.value = ''
  try {
    const updated = props.linkedPosts.includes(post.name)
    const content = await fetchPostContent(post.name)
    const text = (content.raw ?? '').trim() || (content.content ?? '').trim()
    if (!text) {
      error.value = '这篇文章没有正文，不能导入'
      return
    }
    if (text.length > 200000) {
      error.value = '文章正文超过 20 万字，不能导入'
      return
    }
    const note = await importNoteFromPost(post.name, {
      title: post.title.slice(0, 200),
      content: text,
      tags: [],
      project: null,
      module: null,
      todos: []
    })
    emit('imported', note, updated)
    emit('close')
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    importing.value = ''
  }
}

onMounted(() => {
  void load()
})

onUnmounted(() => {
  window.clearTimeout(searchTimer)
})
</script>

<template>
  <Modal title="导入文章" class="max-w-[640px]" @close="emit('close')">
    <div class="flex items-center gap-2">
      <div class="relative min-w-0 flex-1">
        <svg
          class="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-black/30 dark:text-white/30"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.5" />
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
        <Input
          v-model="keyword"
          class="ws-post-search pl-10 pr-10"
          placeholder="搜索文章标题"
          aria-label="搜索文章标题"
          @input="scheduleSearch"
        />
        <button
          v-if="keyword"
          type="button"
          class="absolute right-2 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full text-black/35 transition-colors hover:bg-black/[0.05] hover:text-black/60 dark:text-white/35 dark:hover:bg-white/[0.08] dark:hover:text-white/70"
          aria-label="清除搜索"
          title="清除搜索"
          @click="clearSearch"
        >
          <svg class="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
          </svg>
        </button>
      </div>
      <span class="hidden text-[11px] text-[#6e6e73] sm:inline dark:text-[#98989d]">
        只显示你写的文章
      </span>
    </div>

    <p
      v-if="error"
      class="mt-3 rounded-lg bg-[#FF3B30]/[0.08] px-3 py-2 text-[12px] text-[#FF3B30]"
      role="alert"
    >{{ error }}</p>

    <div class="ws-post-list mt-4 max-h-[52vh] overflow-y-auto rounded-xl border border-black/[0.06] dark:border-white/[0.08]">
      <div v-if="loading" class="divide-y divide-black/[0.05] dark:divide-white/[0.06]">
        <div v-for="index in 3" :key="index" class="px-4 py-3.5">
          <div class="h-3.5 w-1/2 animate-pulse rounded bg-black/[0.06] dark:bg-white/[0.08]"></div>
          <div class="mt-2 h-3 w-4/5 animate-pulse rounded bg-black/[0.04] dark:bg-white/[0.06]"></div>
        </div>
      </div>

      <div
        v-else-if="!posts.length && !error"
        class="px-4 py-12 text-center"
      >
        <p class="text-[13px] font-medium">
          {{ keyword.trim() ? '没有匹配的文章' : '还没有文章' }}
        </p>
        <p class="mt-1 text-[12px] text-[#6e6e73] dark:text-[#98989d]">
          {{ keyword.trim() ? '换个关键词试试' : '先在 Halo 控制台写一篇，再回来导入。' }}
        </p>
      </div>

      <div v-else class="divide-y divide-black/[0.05] dark:divide-white/[0.06]">
        <div
          v-for="post in posts"
          :key="post.name"
          class="ws-post-item flex items-start gap-3 px-4 py-3 transition-colors duration-150 hover:bg-black/[0.025] dark:hover:bg-white/[0.03]"
        >
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-1.5">
              <p class="truncate text-[14px] font-medium">{{ post.title }}</p>
              <span
                class="shrink-0 rounded-full px-1.5 py-0.5 text-[10px] font-medium"
                :class="phaseClass(post)"
              >{{ postPhaseLabel(post.phase, post.publish) }}</span>
              <span
                v-if="props.linkedPosts.includes(post.name)"
                class="inline-flex shrink-0 items-center gap-0.5 rounded-full bg-black/[0.05] px-1.5 py-0.5 text-[10px] text-[#1d1d1f]/65 dark:bg-white/[0.10] dark:text-[#f5f5f7]/70"
              >
                <svg class="h-2.5 w-2.5" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                  <path d="M2.5 6.2l2.2 2.2 4.8-4.8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                已导入
              </span>
            </div>
            <p
              v-if="post.excerpt"
              class="mt-1 line-clamp-1 text-[12px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]"
            >{{ post.excerpt }}</p>
            <p v-if="post.updatedAt" class="mt-1 text-[11px] text-[#6e6e73]/80 dark:text-[#98989d]/80">
              更新于 {{ formatDateTime(post.updatedAt) }}
            </p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            class="ws-post-import shrink-0 text-[#007AFF] hover:bg-[#007AFF]/[0.08] hover:text-[#007AFF] dark:text-[#64B5FF] dark:hover:bg-[#0A84FF]/[0.16] dark:hover:text-[#64B5FF]"
            :disabled="Boolean(importing)"
            @click="importPost(post)"
          >{{ importing === post.name
            ? '导入中…'
            : props.linkedPosts.includes(post.name) ? '更新' : '导入' }}</Button>
        </div>
      </div>
    </div>

    <div v-if="hasNext" class="mt-3 flex justify-center">
      <Button
        variant="ghost"
        size="sm"
        :disabled="loadingMore"
        @click="load(page + 1, true)"
      >{{ loadingMore ? '加载中…' : '加载更多' }}</Button>
    </div>

    <p class="mt-3 text-[11px] leading-relaxed text-[#6e6e73] dark:text-[#98989d]">
      导入后仍可在笔记里编辑。已导入的文章再次导入时，只更新标题和正文。
    </p>
  </Modal>
</template>