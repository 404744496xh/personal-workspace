export const VDITOR_CDN = '/plugins/personal-workspace/assets/vditor'
export const VDITOR_CONTENT_THEME = `${VDITOR_CDN}/dist/css/content-theme`

export type VditorCtor = typeof import('vditor')['default']

let loading: Promise<VditorCtor> | null = null

// Vditor 用 UMD 脚本按需加载，避免把编辑器打进首屏 bundle；版本随插件资源路径更新
export function loadVditor(): Promise<VditorCtor> {
  const global = window as unknown as { Vditor?: VditorCtor }
  if (global.Vditor) return Promise.resolve(global.Vditor)
  if (loading) return loading
  loading = new Promise<VditorCtor>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = `${VDITOR_CDN}/dist/index.min.js`
    script.onload = () => {
      if (global.Vditor) {
        resolve(global.Vditor)
      } else {
        loading = null
        reject(new Error('Vditor 加载失败'))
      }
    }
    script.onerror = () => {
      loading = null
      reject(new Error('Vditor 加载失败'))
    }
    document.head.appendChild(script)
  })
  return loading
}

export async function markdownToHtml(markdown: string): Promise<string> {
  const Vditor = await loadVditor()
  return Vditor.md2html(markdown, {
    mode: 'light',
    cdn: VDITOR_CDN,
    theme: { current: 'light', path: VDITOR_CONTENT_THEME }
  })
}
