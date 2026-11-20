import { ApiError } from './api'

const CONSOLE_API = '/apis/api.console.halo.run/v1alpha1'
const CORE_API = '/apis/content.halo.run/v1alpha1'

export interface PostSummary {
  name: string
  title: string
  phase: string
  publish: boolean
  updatedAt: string | null
  excerpt: string | null
}

export interface PostContent {
  raw: string
  content: string
  rawType: string
}

interface ConsolePostItem {
  post?: {
    metadata?: { name?: string; creationTimestamp?: string }
    spec?: { title?: string; owner?: string; publish?: boolean }
    status?: { phase?: string; permalink?: string; excerpt?: string; lastModifyTime?: string }
  }
  owner?: { name?: string }
}

const PHASE_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审核',
  PUBLISHED: '已发布',
  FAILED: '发布失败'
}

export function postPhaseLabel(phase: string, publish: boolean): string {
  if (phase) return PHASE_LABELS[phase] ?? phase
  return publish ? '已发布' : '草稿'
}

export function postEditorUrl(name: string): string {
  return `/console/posts/editor?name=${encodeURIComponent(name)}`
}

export function openPostEditor(name: string): void {
  window.open(postEditorUrl(name), '_blank', 'noopener')
}

async function errorOf(response: Response): Promise<ApiError> {
  if (response.status === 401) {
    return new ApiError(401, 'UNAUTHORIZED', '请先登录')
  }
  if (response.status === 403) {
    return new ApiError(403, 'POST_PERMISSION_DENIED',
      '当前账号没有发文权限，请换有权限的账号')
  }
  let message = `请求失败（HTTP ${response.status}）`
  try {
    const body = await response.json()
    message = body?.detail ?? body?.title ?? body?.error?.message ?? message
  } catch {
    // The console may answer with a login page instead of JSON.
  }
  return new ApiError(response.status, `HTTP_${response.status}`, message)
}

async function articleRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body != null) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers
  })
  if (!response.ok) throw await errorOf(response)
  if (response.status === 204) return undefined as T
  const type = response.headers.get('content-type') ?? ''
  if (!type.includes('json')) {
    throw new ApiError(0, 'POST_API_UNAVAILABLE',
      '无法读取文章接口，请刷新页面后重试')
  }
  return response.json() as Promise<T>
}

export async function listMyPosts(options: {
  username?: string
  keyword?: string
  page?: number
  size?: number
}): Promise<{ posts: PostSummary[]; hasNext: boolean }> {
  const params = new URLSearchParams({
    page: String(options.page ?? 1),
    size: String(options.size ?? 50),
    sort: 'metadata.creationTimestamp,desc'
  })
  const keyword = options.keyword?.trim()
  if (keyword) params.set('keyword', keyword)
  const page = await articleRequest<{ hasNext?: boolean; items?: ConsolePostItem[] }>(
    `${CONSOLE_API}/posts?${params.toString()}`)
  const username = options.username?.trim()
  const posts = (page.items ?? [])
    .filter((item) => {
      const owner = item.post?.spec?.owner ?? item.owner?.name
      return !username || !owner || owner === username
    })
    .map((item) => {
      const post = item.post
      return {
        name: post?.metadata?.name ?? '',
        title: post?.spec?.title?.trim() || '未命名文章',
        phase: post?.status?.phase ?? '',
        publish: post?.spec?.publish === true,
        updatedAt: post?.status?.lastModifyTime ?? post?.metadata?.creationTimestamp ?? null,
        excerpt: post?.status?.excerpt?.trim() || null
      }
    })
    .filter((post) => post.name)
  return { posts, hasNext: page.hasNext === true }
}

export async function fetchPostContent(name: string): Promise<PostContent> {
  const encoded = encodeURIComponent(name)
  try {
    return await articleRequest<PostContent>(
      `${CONSOLE_API}/posts/${encoded}/head-content`)
  } catch (err) {
    if (!(err instanceof ApiError) || err.status !== 404) throw err
    return articleRequest<PostContent>(
      `${CONSOLE_API}/posts/${encoded}/release-content`)
  }
}

function slugify(title: string): string {
  const slug = title
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80)
  return slug || `post-${Date.now().toString(36)}`
}

async function uniqueSlug(title: string): Promise<string> {
  let slug = slugify(title)
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const selector = encodeURIComponent(`spec.slug=${slug}`)
    const result = await articleRequest<{ total?: number }>(
      `${CONSOLE_API}/posts?page=1&size=1&fieldSelector=${selector}`)
    if (!result.total) return slug
    slug = `${slugify(title)}-${Math.random().toString(36).slice(2, 6)}`
  }
  return `${slugify(title)}-${Date.now().toString(36)}`
}

export async function createPostDraft(
  title: string,
  raw: string,
  content: string
): Promise<string> {
  const slug = await uniqueSlug(title)
  const created = await articleRequest<{ metadata?: { name?: string } }>(
    `${CONSOLE_API}/posts`,
    {
      method: 'POST',
      body: JSON.stringify({
        post: {
          apiVersion: 'content.halo.run/v1alpha1',
          kind: 'Post',
          metadata: { name: crypto.randomUUID(), annotations: {} },
          spec: {
            title,
            slug,
            template: '',
            cover: '',
            deleted: false,
            publish: false,
            pinned: false,
            allowComment: true,
            visible: 'PUBLIC',
            priority: 0,
            excerpt: { autoGenerate: true, raw: '' },
            categories: [],
            tags: [],
            htmlMetas: []
          }
        },
        content: { raw, content, rawType: 'MARKDOWN' }
      })
    })
  const name = created.metadata?.name
  if (!name) throw new ApiError(0, 'POST_CREATE_FAILED', '文章已创建，但没有返回文章标识')
  return name
}

// 已有关联文章时更新正文；文章被删除时返回 missing，由调用方重新创建
export async function updatePostContent(
  name: string,
  raw: string,
  content: string
): Promise<'updated' | 'missing'> {
  const encoded = encodeURIComponent(name)
  try {
    await articleRequest(`${CONSOLE_API}/posts/${encoded}/content`, {
      method: 'PUT',
      body: JSON.stringify({ raw, content, rawType: 'MARKDOWN' })
    })
    return 'updated'
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return 'missing'
    throw err
  }
}

export async function updatePostTitle(
  name: string,
  title: string
): Promise<'updated' | 'missing'> {
  const encoded = encodeURIComponent(name)
  let lastError: unknown
  for (let attempt = 0; attempt < 3; attempt += 1) {
    let post: { spec?: Record<string, unknown> }
    try {
      post = await articleRequest(`${CORE_API}/posts/${encoded}`)
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) return 'missing'
      throw err
    }
    if (post.spec) post.spec.title = title
    try {
      await articleRequest(`${CORE_API}/posts/${encoded}`, {
        method: 'PUT',
        body: JSON.stringify(post)
      })
      return 'updated'
    } catch (err) {
      lastError = err
      if (!(err instanceof ApiError) || err.status !== 409 || attempt >= 2) throw err
      await new Promise((resolve) => window.setTimeout(resolve, 250))
    }
  }
  throw lastError
}
