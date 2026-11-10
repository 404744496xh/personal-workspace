// 跨页面缓存：切换标签时先用上次数据秒开，再后台静默刷新
const values = new Map<string, unknown>()

export function readCache<T>(key: string): T | undefined {
  return values.get(key) as T | undefined
}

export function writeCache<T>(key: string, value: T): void {
  values.set(key, value)
}
