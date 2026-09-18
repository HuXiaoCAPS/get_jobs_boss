/**
 * 前端显示偏好（「外观」页里的设置）。
 *
 * 只存这台浏览器上的显示偏好（localStorage），不进配置文件、不进数据库 ——
 * 后端那套（搜索条件 / AI / 通知）走 config/boss.yaml，别混在一起。
 */

export type Density = 'comfortable' | 'compact'

export type Prefs = {
  /** 数据页每页条数 */
  pageSize: number
  /** 表格密度 */
  density: Density
}

export const DEFAULT_PREFS: Prefs = {
  pageSize: 20,
  density: 'comfortable',
}

const KEY = 'getjobs.prefs'

export function loadPrefs(): Prefs {
  if (typeof window === 'undefined') return DEFAULT_PREFS
  try {
    const raw = window.localStorage.getItem(KEY)
    if (!raw) return DEFAULT_PREFS
    const parsed = JSON.parse(raw) as Partial<Prefs>
    return {
      pageSize:
        typeof parsed.pageSize === 'number' && parsed.pageSize > 0
          ? parsed.pageSize
          : DEFAULT_PREFS.pageSize,
      density: parsed.density === 'compact' ? 'compact' : 'comfortable',
    }
  } catch {
    return DEFAULT_PREFS
  }
}

export function savePrefs(prefs: Prefs): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(KEY, JSON.stringify(prefs))
  } catch {
    /* 隐私模式下写不了，忽略即可 */
  }
}

/** 当前表格密度对应的单元格内边距 */
export function cellPadding(density: Density): string {
  return density === 'compact' ? 'px-2 py-1' : 'px-2 py-2'
}
