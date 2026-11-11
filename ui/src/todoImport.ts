import type { Project } from './types'
import { parseDateTimeInput } from './datetime'

export const MAX_IMPORT_ROWS = 500
export const MAX_PROJECT_MODULES = 20
export const MAX_MODULE_LENGTH = 32
export const MAX_TITLE_LENGTH = 200
export const MAX_REPORTER_LENGTH = 64
export const MAX_DETAIL_LENGTH = 5000

export interface ParsedRow {
  line: number
  project: string
  module: string
  title: string
  detail: string
  createdAt: string | null
  reporter: string
}

export interface ImportRow extends ParsedRow {
  projectName: string | null
  newModule: boolean
  error: string
}

export interface ImportPreview {
  error: string
  delimiter: string
  skippedHeader: boolean
  rows: ImportRow[]
  duplicateProjectTitles: string[]
  duplicateOpenCount: number
  ignoredProjectTitles: string[]
}

export interface ResolveOptions {
  projects: Project[]
  activeProject: string | null
  activeModule: string | null
  openTitles: string[]
}

// Excel 单元格内的换行会被双引号包住，不能按物理行切批，否则会把一个单元格劈成两条
export function splitForAiChunks(source: string, maxRows: number): string[] {
  const text = source.replace(/\r\n?/g, '\n')
  const rows: string[] = []
  let start = 0
  let quoted = false
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index]
    if (char === '"') {
      if (quoted && text[index + 1] === '"') index += 1
      else quoted = !quoted
      continue
    }
    if (char === '\n' && !quoted) {
      const row = text.slice(start, index)
      if (row.trim()) rows.push(row)
      start = index + 1
    }
  }
  const tail = text.slice(start)
  if (tail.trim()) rows.push(tail)

  const chunks: string[] = []
  for (let index = 0; index < rows.length; index += maxRows) {
    chunks.push(rows.slice(index, index + maxRows).join('\n'))
  }
  return chunks.length ? chunks : [text]
}

interface Delimiter {
  label: string
  strong: boolean
  match: (source: string, index: number) => number
}

function charDelimiter(label: string, chars: string[], strong: boolean): Delimiter {
  return {
    label,
    strong,
    match: (source, index) => (chars.includes(source[index]) ? 1 : 0)
  }
}

// 逗号 / 分号 / 顿号在正文里常见，放在最后；只有没有任何强分隔符可用时才采用
const DELIMITERS: Delimiter[] = [
  charDelimiter('制表符', ['\t'], true),
  charDelimiter('竖线', ['|'], true),
  charDelimiter('全角竖线', ['｜'], true),
  {
    label: '连续空格',
    strong: true,
    match: (source, index) => {
      if (source[index] !== ' ' || source[index + 1] !== ' ') return 0
      let end = index + 1
      while (source[end] === ' ') end += 1
      return end - index
    }
  },
  charDelimiter('中文逗号', ['，'], false),
  charDelimiter('顿号', ['、'], false),
  charDelimiter('中文分号', ['；'], false),
  charDelimiter('逗号', [','], false),
  charDelimiter('分号', [';'], false)
]

function tokenize(text: string, match: (source: string, index: number) => number): string[][] | null {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  let index = 0
  while (index < text.length) {
    const char = text[index]
    if (quoted) {
      if (char === '"') {
        if (text[index + 1] === '"') {
          field += '"'
          index += 2
          continue
        }
        quoted = false
        index += 1
        continue
      }
      field += char
      index += 1
      continue
    }
    if (char === '"' && field === '') {
      quoted = true
      index += 1
      continue
    }
    if (char === '\n') {
      row.push(field)
      rows.push(row)
      row = []
      field = ''
      index += 1
      continue
    }
    const width = match(text, index)
    if (width > 0) {
      row.push(field)
      field = ''
      index += width
      continue
    }
    field += char
    index += 1
  }
  if (quoted) return null
  row.push(field)
  rows.push(row)
  return rows
}

function isBlankRow(cells: string[] | undefined): boolean {
  return !cells || cells.every((cell) => cell.trim() === '')
}

type ColumnRole = 'project' | 'module' | 'title' | 'createdAt' | 'reporter'

const HEADER_ALIASES: Record<string, ColumnRole> = {
  项目: 'project',
  project: 'project',
  模块: 'module',
  module: 'module',
  待办: 'title',
  待办标题: 'title',
  标题: 'title',
  todo: 'title',
  title: 'title',
  创建时间: 'createdAt',
  创建日期: 'createdAt',
  创建于: 'createdAt',
  createdat: 'createdAt',
  created: 'createdAt',
  姓名: 'reporter',
  提出人: 'reporter',
  反馈人: 'reporter',
  提单人: 'reporter',
  提交人: 'reporter',
  报告人: 'reporter',
  谁提的: 'reporter',
  提出者: 'reporter',
  reporter: 'reporter'
}

function headerRoles(cells: string[]): ColumnRole[] | null {
  const roles = cells.map((cell) =>
    HEADER_ALIASES[cell.trim().normalize('NFKC').toLowerCase()])
  if (roles.some((role) => !role)) return null
  if (new Set(roles).size !== roles.length) return null
  return roles as ColumnRole[]
}

function emptyPreview(): ImportPreview {
  return {
    error: '',
    delimiter: '',
    skippedHeader: false,
    rows: [],
    duplicateProjectTitles: [],
    duplicateOpenCount: 0,
    ignoredProjectTitles: []
  }
}

export function parseImport(source: string, options: ResolveOptions): ImportPreview {
  const preview = emptyPreview()
  const text = source.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
  if (!text.trim()) return preview

  const attempted: { delimiter: Delimiter; rows: string[][] }[] = []
  const strong: { delimiter: Delimiter; width: number }[] = []
  const weak: { delimiter: Delimiter; width: number }[] = []
  for (const delimiter of DELIMITERS) {
    const tokenized = tokenize(text, delimiter.match)
    if (!tokenized) continue
    const rows = tokenized.filter((row) => !isBlankRow(row))
    attempted.push({ delimiter, rows })
    const widths = new Set(rows.map((row) => row.length))
    if (widths.size !== 1) continue
    const width = [...widths][0]
    if (width < 2) continue
    ;(delimiter.strong ? strong : weak).push({ delimiter, width })
  }

  let chosen: Delimiter | null = null
  let width = 1
  let rows: string[][] | null = null
  if (strong.length > 1) {
    return {
      ...preview,
      error: `检测到多种分隔符（${strong.map((item) => item.delimiter.label).join('、')}），无法确定列；请从 Excel 直接复制，统一用制表符分隔`
    }
  }
  if (strong.length === 1) {
    chosen = strong[0].delimiter
    width = strong[0].width
  } else if (weak.length > 1) {
    return {
      ...preview,
      error: `无法确定分隔符（${weak.map((item) => item.delimiter.label).join('、')} 都能切分），请改用制表符分隔`
    }
  } else if (weak.length === 1) {
    chosen = weak[0].delimiter
    width = weak[0].width
  } else {
    const splittable = attempted.filter((item) => item.rows.some((row) => row.length >= 2))
    if (splittable.length) {
      const offending = splittable[0]
      const lines = offending.rows
        .map((row, index) => ({ index: index + 1, width: row.length }))
        .filter((entry) => entry.width >= 2)
        .slice(0, 8)
        .map((entry) => entry.index)
      return {
        ...preview,
        error: `含「${offending.delimiter.label}」的行列数不一致（第 ${lines.join('、')} 行），无法判断列；请从 Excel 直接复制，统一用制表符分隔`
      }
    }
    rows = tokenize(text, () => 0)
    if (!rows) return { ...preview, error: '引号不成对，无法解析；请检查是否漏了引号' }
    rows = rows.filter((row) => !isBlankRow(row)).map((row) => [row[0] ?? ''])
    width = 1
  }

  if (chosen) {
    rows = attempted.find((item) => item.delimiter === chosen)?.rows ?? null
    if (!rows) return { ...preview, error: '解析失败，请重新粘贴' }
    if (width < 1 || width > 5) {
      return {
        ...preview,
        error: `识别为 ${width} 列，仅支持 1 列（只有待办）、2 列（项目、待办或模块、待办）、3 列（项目、模块、待办）、4 列（项目、模块、待办、创建时间）或 5 列（项目、模块、待办、创建时间、姓名）；其他列数不导入`
      }
    }
  }

  if (!rows) return { ...preview, error: '解析失败，请重新粘贴' }
  let skippedHeader = false
  let roles: ColumnRole[] | null = null
  if (rows.length) {
    const header = headerRoles(rows[0])
    if (header && !header.includes('title')) {
      return {
        ...preview,
        delimiter: chosen?.label ?? '单列',
        error: '表头里缺少待办列；请补上待办列，或按「项目、模块、待办」粘贴'
      }
    }
    if (header) {
      roles = header
      rows = rows.slice(1)
      skippedHeader = true
    }
  }
  if (!roles) {
    // 没有表头时：两列按当前筛选判断，三列以上按固定顺序
    if (width === 1) roles = ['title']
    else if (width === 2) roles = options.activeProject ? ['module', 'title'] : ['project', 'title']
    else if (width === 3) roles = ['project', 'module', 'title']
    else if (width === 4) roles = ['project', 'module', 'title', 'createdAt']
    else roles = ['project', 'module', 'title', 'createdAt', 'reporter']
  }
  if (!rows.length) return { ...preview, delimiter: chosen?.label ?? '单列', skippedHeader }
  if (rows.length > MAX_IMPORT_ROWS) {
    return { ...preview, error: `一次最多导入 ${MAX_IMPORT_ROWS} 行，当前 ${rows.length} 行` }
  }

  const parsed: ParsedRow[] = rows.map((cells, index) => {
    const line = index + 1 + (skippedHeader ? 1 : 0)
    const values: Partial<Record<ColumnRole, string>> = {}
    roles?.forEach((role, roleIndex) => {
      values[role] = (cells[roleIndex] ?? '').trim()
    })
    return {
      line,
      project: values.project ?? '',
      module: values.module ?? '',
      title: values.title ?? '',
      detail: '',
      createdAt: values.createdAt || null,
      reporter: values.reporter ?? ''
    }
  })

  return resolveRows(parsed, options, chosen?.label ?? '单列', skippedHeader)
}

export function resolveRows(parsed: ParsedRow[], options: ResolveOptions, delimiter = 'AI 识别',
  skippedHeader = false): ImportPreview {
  const preview: ImportPreview = {
    ...emptyPreview(),
    delimiter,
    skippedHeader
  }
  const projectsByTitle = new Map<string, Project[]>()
  for (const project of options.projects) {
    const key = project.title.trim()
    const list = projectsByTitle.get(key) ?? []
    list.push(project)
    projectsByTitle.set(key, list)
  }
  const openTitles = new Set(options.openTitles.map((title) => title.trim()))
  const moduleTrackers = new Map<string, Set<string>>()

  for (const row of parsed) {
    let error = ''
    let projectTitle = row.project
    let projectName: string | null = null
    let projectDropped = false
    let createdAt: string | null = null
    const reporter = row.reporter.trim()
    const detail = row.detail.trim()

    if (!row.title) error = '缺少待办标题'
    else if (row.title.length > MAX_TITLE_LENGTH) error = `待办标题超过 ${MAX_TITLE_LENGTH} 个字符`
    if (!error && detail.length > MAX_DETAIL_LENGTH) error = `详情超过 ${MAX_DETAIL_LENGTH} 个字符`
    if (!error && reporter.length > MAX_REPORTER_LENGTH) error = `姓名超过 ${MAX_REPORTER_LENGTH} 个字符`
    if (!error && row.createdAt) {
      const parsedAt = parseDateTimeInput(row.createdAt)
      if (parsedAt.error) error = parsedAt.error
      else createdAt = parsedAt.iso
    }

    if (!error) {
      if (options.activeProject !== null) {
        // 项目来自当前筛选（人工选择），粘贴里的项目列不参与判断
        if (options.activeProject === '') {
          projectTitle = ''
          projectName = null
        } else {
          projectName = options.activeProject
          projectTitle = options.projects.find((project) => project.name === projectName)?.title.trim() ?? ''
        }
      } else if (!projectTitle) {
        // 没有筛选且项目列为空：未分类
      } else {
        const matches = projectsByTitle.get(projectTitle) ?? []
        if (matches.length) {
          projectName = matches[0].name
          if (matches.length > 1 && !preview.duplicateProjectTitles.includes(projectTitle)) {
            preview.duplicateProjectTitles.push(projectTitle)
          }
        } else {
          // 导入不创建项目：对不上的项目名直接丢弃，按未分类处理
          if (!preview.ignoredProjectTitles.includes(projectTitle)) {
            preview.ignoredProjectTitles.push(projectTitle)
          }
          projectTitle = ''
          projectDropped = true
        }
      }
    }

    let module = row.module
    if (projectDropped || options.activeProject === '') module = ''
    if (!error && !module && options.activeModule !== null && projectName !== null
      && projectName === options.activeProject) {
      module = options.activeModule
    }
    if (!error && module) {
      if (!projectTitle) error = '模块需要项目（项目列不能为空）'
      else if (module.length > MAX_MODULE_LENGTH) error = `模块名称超过 ${MAX_MODULE_LENGTH} 个字符`
    }

    let newModule = false
    if (!error && module && projectName) {
      const existing = options.projects.find((project) => project.name === projectName)?.modules ?? []
      const tracker = moduleTrackers.get(projectName) ?? new Set(existing)
      moduleTrackers.set(projectName, tracker)
      if (!tracker.has(module)) {
        if (tracker.size >= MAX_PROJECT_MODULES) {
          error = `项目「${projectTitle}」最多 ${MAX_PROJECT_MODULES} 个模块`
        } else {
          tracker.add(module)
          newModule = true
        }
      }
    }

    if (!error && openTitles.has(row.title)) preview.duplicateOpenCount += 1

    preview.rows.push({
      line: row.line,
      project: projectTitle,
      module,
      title: row.title,
      detail,
      createdAt,
      reporter,
      projectName,
      newModule,
      error
    })
  }
  return preview
}
