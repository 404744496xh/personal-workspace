// 与站点前台一致：theme-hao 对 mermaid 注入的配置与配色（同 post-workbench）
const CONFIG = {
  flowchart: {
    curve: 'basis',
    padding: 20,
    nodeSpacing: 55,
    rankSpacing: 65,
    diagramPadding: 16,
    useMaxWidth: true
  },
  sequence: {
    diagramMarginX: 24,
    diagramMarginY: 16,
    actorMargin: 64,
    width: 170,
    height: 48,
    boxMargin: 12,
    boxTextMargin: 8,
    noteMargin: 14,
    messageMargin: 42,
    mirrorActors: true,
    useMaxWidth: true,
    wrap: true
  },
  gantt: {
    barHeight: 26,
    barGap: 8,
    topPadding: 56,
    leftPadding: 110,
    gridLineStartPadding: 40,
    fontSize: 13,
    sectionFontSize: 13,
    numberSectionStyles: 4,
    useMaxWidth: true
  }
}

function isDark(): boolean {
  return document.getElementById('workspace-app')?.getAttribute('data-ws-theme') === 'dark'
}

function themeConfig(): { theme: string; themeVariables: Record<string, unknown> } {
  const fontFamily =
    "'Noto Sans SC','PingFang SC','Hiragino Sans GB','Microsoft YaHei',system-ui,sans-serif"
  if (isDark()) {
    return {
      theme: 'base',
      themeVariables: {
        darkMode: true,
        background: 'transparent',
        fontFamily,
        fontSize: '14px',
        primaryColor: 'rgba(99,102,241,.22)',
        primaryBorderColor: '#818cf8',
        primaryTextColor: '#c7d2fe',
        secondaryColor: 'rgba(56,189,248,.18)',
        secondaryBorderColor: '#38bdf8',
        secondaryTextColor: '#bae6fd',
        tertiaryColor: 'rgba(52,211,153,.16)',
        tertiaryBorderColor: '#34d399',
        tertiaryTextColor: '#a7f3d0',
        lineColor: '#64748b',
        textColor: '#cbd5e1',
        titleColor: '#f1f5f9',
        edgeLabelBackground: 'rgba(22,28,42,.92)',
        clusterBkg: 'rgba(99,102,241,.08)',
        clusterBorder: 'rgba(129,140,248,.45)',
        actorBkg: 'rgba(99,102,241,.22)',
        actorBorder: '#818cf8',
        actorTextColor: '#c7d2fe',
        actorLineColor: '#475569',
        signalColor: '#94a3b8',
        signalTextColor: '#e2e8f0',
        labelBoxBkgColor: 'rgba(99,102,241,.22)',
        labelBoxBorderColor: '#818cf8',
        labelTextColor: '#c7d2fe',
        loopTextColor: '#e2e8f0',
        noteBkgColor: 'rgba(250,204,21,.13)',
        noteBorderColor: '#facc15',
        noteTextColor: '#fde68a',
        activationBkgColor: 'rgba(129,140,248,.35)',
        activationBorderColor: '#818cf8',
        sequenceNumberColor: '#0f172a',
        taskBkgColor: 'rgba(129,140,248,.45)',
        taskBorderColor: '#818cf8',
        taskTextColor: '#eef2ff',
        taskTextDarkColor: '#eef2ff',
        taskTextLightColor: '#eef2ff',
        activeTaskBkgColor: 'rgba(129,140,248,.75)',
        activeTaskBorderColor: '#6366f1',
        doneTaskBkgColor: 'rgba(52,211,153,.45)',
        doneTaskBorderColor: '#34d399',
        critBkgColor: 'rgba(248,113,113,.45)',
        critBorderColor: '#f87171',
        sectionBkgColor: 'rgba(148,163,184,.07)',
        altSectionBkgColor: 'rgba(148,163,184,.13)',
        sectionColor: '#94a3b8',
        gridColor: '#334155',
        todayLineColor: '#fb7185',
        dividerColor: '#475569',
        classText: '#e2e8f0'
      }
    }
  }
  return {
    theme: 'base',
    themeVariables: {
      darkMode: false,
      background: 'transparent',
      fontFamily,
      fontSize: '14px',
      primaryColor: '#eef2ff',
      primaryBorderColor: '#a5b4fc',
      primaryTextColor: '#3730a3',
      secondaryColor: '#e0f2fe',
      secondaryBorderColor: '#7dd3fc',
      secondaryTextColor: '#075985',
      tertiaryColor: '#d1fae5',
      tertiaryBorderColor: '#6ee7b7',
      tertiaryTextColor: '#065f46',
      lineColor: '#94a3b8',
      textColor: '#334155',
      titleColor: '#0f172a',
      edgeLabelBackground: 'rgba(255,255,255,.92)',
      clusterBkg: 'rgba(99,102,241,.05)',
      clusterBorder: '#c7d2fe',
      actorBkg: '#eef2ff',
      actorBorder: '#a5b4fc',
      actorTextColor: '#3730a3',
      actorLineColor: '#cbd5e1',
      signalColor: '#475569',
      signalTextColor: '#334155',
      labelBoxBkgColor: '#eef2ff',
      labelBoxBorderColor: '#a5b4fc',
      labelTextColor: '#3730a3',
      loopTextColor: '#334155',
      noteBkgColor: '#fff9c3',
      noteBorderColor: '#f6c34d',
      noteTextColor: '#713f12',
      activationBkgColor: '#c7d2fe',
      activationBorderColor: '#818cf8',
      sequenceNumberColor: '#ffffff',
      taskBkgColor: '#c7d2fe',
      taskBorderColor: '#818cf8',
      taskTextColor: '#312e81',
      taskTextDarkColor: '#312e81',
      taskTextLightColor: '#312e81',
      activeTaskBkgColor: '#a5b4fc',
      activeTaskBorderColor: '#6366f1',
      doneTaskBkgColor: '#bbf7d0',
      doneTaskBorderColor: '#4ade80',
      critBkgColor: '#fecaca',
      critBorderColor: '#f87171',
      sectionBkgColor: '#f8fafc',
      altSectionBkgColor: '#f1f5f9',
      sectionColor: '#475569',
      gridColor: '#e2e8f0',
      todayLineColor: '#ec4899',
      dividerColor: '#cbd5e1',
      classText: '#334155'
    }
  }
}

interface MermaidGlobal {
  initialize(options?: Record<string, unknown>): void
  __wsThemed?: boolean
}

function wrap(mermaid: MermaidGlobal): MermaidGlobal {
  if (mermaid.__wsThemed) return mermaid
  const initialize = mermaid.initialize.bind(mermaid)
  mermaid.initialize = (options: Record<string, unknown> = {}) =>
    initialize({ ...options, ...CONFIG, ...themeConfig(), startOnLoad: false })
  mermaid.__wsThemed = true
  return mermaid
}

let trapped = false

// Vditor 首次遇到 mermaid 代码块才懒加载脚本，且渲染前会自己调用 initialize
// 覆盖外部配置；用 setter 在脚本落地时立刻包一层，保证第一次渲染就用站点配色。
export function applyMermaidTheme(): void {
  const scope = window as unknown as { mermaid?: MermaidGlobal }
  if (scope.mermaid) {
    wrap(scope.mermaid)
    return
  }
  if (trapped) return
  trapped = true
  let value: MermaidGlobal | undefined
  Object.defineProperty(window, 'mermaid', {
    configurable: true,
    enumerable: true,
    get: () => value,
    set(next: MermaidGlobal) {
      value = wrap(next)
    }
  })
}