import fs from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'

const BASE = (process.env.WORKSPACE_BASE_URL ?? 'http://127.0.0.1:8093').replace(/\/+$/, '')
const TOKEN_FILE = process.env.WORKSPACE_TOKEN_FILE?.trim()
const TOKEN = process.env.WORKSPACE_TOKEN?.trim()
  || (TOKEN_FILE ? fs.readFileSync(TOKEN_FILE, 'utf8').trim() : '')
if (!TOKEN) throw new Error('WORKSPACE_TOKEN or WORKSPACE_TOKEN_FILE is required')

const SUFFIX = Date.now().toString(36)
const PROJECT = `MCP测试项目${SUFFIX}`
const MODULE = 'MCP模块'
const TODO = `MCP测试待办${SUFFIX}`
const NOTE = `MCP测试笔记${SUFFIX}`
let todoName = null
let noteName = null
let projectName = null

function assert(condition, message) {
  if (!condition) throw new Error(`ASSERT FAILED: ${message}`)
}

const transport = new StdioClientTransport({
  command: process.execPath,
  args: ['dist/index.js'],
  env: {
    WORKSPACE_BASE_URL: BASE,
    ...(process.env.WORKSPACE_TOKEN
      ? { WORKSPACE_TOKEN: process.env.WORKSPACE_TOKEN }
      : { WORKSPACE_TOKEN_FILE: TOKEN_FILE })
  },
  stderr: 'inherit'
})
const client = new Client({ name: 'workspace-mcp-smoke', version: '0.0.0' })
await client.connect(transport)

async function call(name, args) {
  const result = await client.callTool({ name, arguments: args })
  const text = (result.content ?? [])
    .map((item) => (item.type === 'text' ? item.text : ''))
    .join('')
  if (result.isError) throw new Error(`${name} failed: ${text}`)
  return JSON.parse(text)
}

const api = (path, init = {}) => fetch(`${BASE}/apis/api.workspace.halo.run/v1alpha1${path}`, {
  ...init,
  headers: {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${TOKEN}`,
    ...(init.headers ?? {})
  }
})

try {
  const tools = await client.listTools()
  const names = tools.tools.map((tool) => tool.name).sort()
  assert(names.includes('list_todos') && names.includes('create_todo') && names.includes('update_todo'),
    `todo tools registered: ${names.join(', ')}`)
  assert(names.includes('list_notes') && names.includes('create_note') && names.includes('update_note'),
    'note tools registered')
  console.log(`PASS tools: ${names.join(', ')}`)

  const project = await call('create_project', { title: PROJECT, status: 'TEST' })
  projectName = project.created.name
  assert(project.created.status === 'TEST', 'project created with TEST status')

  const todo = await call('create_todo', {
    title: TODO, project: PROJECT, module: MODULE, priority: 'HIGH'
  })
  todoName = todo.created.name
  assert(todo.created.projectStatus === 'TEST', `todo snapshots TEST, got ${todo.created.projectStatus}`)
  assert(todo.created.projectTitle === PROJECT, 'todo resolves project title')

  const projects = await call('list_projects', { keyword: PROJECT })
  assert(projects.items[0].modules.includes(MODULE), 'new module is added to the project automatically')
  console.log('PASS create project, todo and auto-add module')

  const advanced = await call('update_project', { project: PROJECT, status: 'RELEASED' })
  assert(advanced.updated.status === 'RELEASED', 'project advanced to RELEASED')

  const listed = await call('list_todos', { project: PROJECT, status: 'ALL' })
  const listedTodo = listed.items.find((item) => item.name === todoName)
  assert(listedTodo, 'list_todos filters by project title')
  assert(listedTodo.projectStatus === 'TEST', 'row keeps creation-time snapshot after project advanced')

  const detail = await call('get_todo', { name: todoName })
  assert(detail.projectStatus === 'TEST' && detail.projectTitle === PROJECT, 'get_todo returns snapshot')
  console.log('PASS project status snapshot survives project changes')

  const completed = await call('update_todo', { name: todoName, completed: true })
  assert(completed.updated.completed === true, 'todo can be completed')
  const dismissed = await call('update_todo', { name: todoName, dismissed: true, completed: false })
  assert(dismissed.updated.dismissed === true, 'todo can be dismissed')
  const restored = await call('update_todo', { name: todoName, dismissed: false, pinned: true })
  assert(restored.updated.dismissed === false && restored.updated.pinned === true,
    'todo can be restored and pinned')
  console.log('PASS todo state updates')

  const note = await call('create_note', {
    title: NOTE, content: '# MCP 测试\n\n正文内容', project: PROJECT, module: MODULE, todos: [todoName]
  })
  noteName = note.created.name
  assert(note.created.project === projectName, 'note links to the project')

  const notes = await call('list_notes', { keyword: NOTE })
  assert(notes.items.some((item) => item.name === noteName), 'list_notes finds the new note')

  const fullNote = await call('get_note', { name: noteName })
  assert((fullNote.content ?? '').includes('正文内容'), 'get_note returns full Markdown')

  const updatedNote = await call('update_note', { name: noteName, summary: 'MCP 摘要' })
  assert(updatedNote.updated.summary === 'MCP 摘要', 'update_note patches the summary')
  console.log('PASS note create, search, read and update')

  const search = await call('search', { q: TODO })
  assert(search.hits.some((hit) => hit.name === todoName), 'search finds the test todo')

  const commands = await call('list_commands', { limit: 5 })
  assert(typeof commands.total === 'number', 'list_commands returns a total')
  console.log('PASS search and commands')

  console.log('ALL WORKSPACE MCP SMOKE TESTS PASSED')
} finally {
  try {
    if (noteName) {
      await api(`/notes/${noteName}?confirm=true`, { method: 'DELETE' })
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
    if (todoName) {
      await api(`/todos/${todoName}?confirm=true`, { method: 'DELETE' })
      await new Promise((resolve) => setTimeout(resolve, 1200))
    }
    if (projectName) {
      let status = 0
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const res = await api(`/projects/${projectName}?confirm=true`, { method: 'DELETE' })
        status = res.status
        if (status === 204 || status === 200) break
        await new Promise((resolve) => setTimeout(resolve, 800))
      }
      if (status !== 204 && status !== 200) console.error('cleanup: project delete returned', status)
    }
  } catch (error) {
    console.error('cleanup failed:', error instanceof Error ? error.message : error)
  }
  await client.close()
}
