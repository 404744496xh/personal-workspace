export type ProjectStatus = 'NOT_STARTED' | 'DEV' | 'TEST' | 'RELEASED' | 'ACCEPTED'

interface ProjectStatusMeta {
  value: ProjectStatus
  label: string
  dot: string
  text: string
  badge: string
}

export const PROJECT_STATUSES: ProjectStatusMeta[] = [
  {
    value: 'NOT_STARTED',
    label: '未开始',
    dot: 'bg-[#8e8e93] dark:bg-[#98989d]',
    text: 'text-[#6e6e73] dark:text-[#98989d]',
    badge: 'bg-black/[0.05] text-[#6e6e73] dark:bg-white/[0.08] dark:text-[#98989d]'
  },
  {
    value: 'DEV',
    label: '开发中',
    dot: 'bg-[#007AFF] dark:bg-[#64B5FF]',
    text: 'text-[#007AFF] dark:text-[#64B5FF]',
    badge: 'bg-[#007AFF]/[0.10] text-[#0062CC] dark:bg-[#0A84FF]/[0.16] dark:text-[#64B5FF]'
  },
  {
    value: 'TEST',
    label: '测试中',
    dot: 'bg-[#FF9500]',
    text: 'text-[#C77700] dark:text-[#FFB340]',
    badge: 'bg-[#FF9500]/[0.12] text-[#C77700] dark:bg-[#FF9F0A]/[0.16] dark:text-[#FFB340]'
  },
  {
    value: 'RELEASED',
    label: '已上线',
    dot: 'bg-[#AF52DE] dark:bg-[#BF5AF2]',
    text: 'text-[#8944AB] dark:text-[#BF5AF2]',
    badge: 'bg-[#AF52DE]/[0.12] text-[#8944AB] dark:bg-[#BF5AF2]/[0.16] dark:text-[#BF5AF2]'
  },
  {
    value: 'ACCEPTED',
    label: '已验收',
    dot: 'bg-[#34C759] dark:bg-[#30D158]',
    text: 'text-[#248A3D] dark:text-[#30D158]',
    badge: 'bg-[#34C759]/[0.12] text-[#248A3D] dark:bg-[#30D158]/[0.16] dark:text-[#30D158]'
  }
]

export const DEFAULT_PROJECT_STATUS: ProjectStatus = 'NOT_STARTED'

export function projectStatusMeta(status: ProjectStatus | null | undefined): ProjectStatusMeta {
  return PROJECT_STATUSES.find((item) => item.value === status) ?? PROJECT_STATUSES[1]
}

export function projectStatusOf(
  project: { status?: ProjectStatus | null } | null | undefined
): ProjectStatus {
  return project?.status ?? 'DEV'
}

export function todoProjectStatusOf(
  todo: { projectStatus?: ProjectStatus | null; project: string | null },
  projects: { name: string; status?: ProjectStatus | null }[]
): ProjectStatus | null {
  if (todo.projectStatus) return todo.projectStatus
  if (!todo.project) return null
  const project = projects.find((item) => item.name === todo.project)
  return project ? projectStatusOf(project) : null
}
