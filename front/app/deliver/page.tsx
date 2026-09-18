'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE } from '@/lib/api'

/**
 * 投递页：左边是平台，右边是「开始 / 停止」和本次上限。
 *
 * 页面完全按 `GET /api/platforms` 渲染 —— 平台是从后端注册表里读出来的，
 * 所以删掉某个平台的代码后这里自然就没有它；一个平台都没有时显示提示，
 * 页面本身照常工作（这就是"缺平台也能正常运行"在界面上的体现）。
 *
 * 进度走 SSE：`GET /api/platforms/{id}/stream`。
 */

type Platform = { id: string; name: string }

type PlatformStatus = {
  platform: string
  name?: string
  registered?: boolean
  isRunning?: boolean
  /** 已收到停止指令但任务还没退出 */
  stopping?: boolean
  runningForMillis?: number
  maxDeliveries?: number
  delivered?: number
  filtered?: number
  skipped?: number
  lastMessage?: string
}

type ProgressMessage = {
  platform?: string
  type?: string
  message?: string
  current?: number | null
  total?: number | null
}

const INPUT =
  'border border-gray-300 bg-white px-2 py-1 text-sm outline-none focus:border-gray-800'

export default function DeliverPage() {
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [statuses, setStatuses] = useState<Record<string, PlatformStatus>>({})
  const [selected, setSelected] = useState('')
  const [max, setMax] = useState('0')
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState('')
  const [logs, setLogs] = useState<ProgressMessage[]>([])
  const esRef = useRef<EventSource | null>(null)

  // 平台列表 + 状态
  const loadPlatforms = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/platforms`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      const list: Platform[] = data.platforms || []
      setPlatforms(list)
      setSelected((prev) => prev || (list.length ? list[0].id : ''))
      if (!list.length) {
        setErr('当前没有可用平台 —— 后端没有注册任何 JobPlatform 实现（程序仍在正常运行）')
      }
    } catch (e) {
      setErr(`加载平台失败：${(e as Error).message}（检查后端是否已启动）`)
    } finally {
      setLoading(false)
    }
  }, [])

  const loadStatuses = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/platforms/status`)
      if (!res.ok) return
      const data = await res.json()
      const map: Record<string, PlatformStatus> = {}
      for (const item of (data.platforms || []) as PlatformStatus[]) {
        map[item.platform] = item
      }
      setStatuses(map)
    } catch {
      /* 状态拉不到不影响页面本身 */
    }
  }, [])

  // 状态轮询：2 秒一次 —— 足够快看到「正在停止…」的变化，也不至于太吵
  useEffect(() => {
    void loadPlatforms()
    void loadStatuses()
    const timer = window.setInterval(() => void loadStatuses(), 2000)
    return () => window.clearInterval(timer)
  }, [loadPlatforms, loadStatuses])

  // 选中的平台变化时重连 SSE
  useEffect(() => {
    if (!selected) return
    if (esRef.current) {
      esRef.current.close()
      esRef.current = null
    }
    // 换平台就清空日志：日志是按平台分开推送的，留着上一个平台的内容
    // 会让人以为它们是同一个任务的输出（而"已连接"那行紧接着会说明现在连的是谁）
    setLogs([])
    const es = new EventSource(`${API_BASE}/api/platforms/${selected}/stream`)
    es.addEventListener('progress', (ev) => {
      try {
        const msg = JSON.parse((ev as MessageEvent).data) as ProgressMessage
        setLogs((prev) => [...prev.slice(-199), msg])
      } catch {
        /* 忽略解析失败 */
      }
    })
    es.addEventListener('connected', () => {
      setLogs((prev) => [...prev, { type: 'info', message: `已连接 ${selected} 的进度推送` }])
    })
    es.onerror = () => {
      /* EventSource 自己会重连，这里不打扰用户 */
    }
    esRef.current = es
    return () => {
      es.close()
      esRef.current = null
    }
  }, [selected])

  async function start(id: string) {
    setErr('')
    try {
      const res = await fetch(
        `${API_BASE}/api/platforms/${id}/start?max=${encodeURIComponent(max || '0')}`,
        { method: 'POST' },
      )
      const data = await res.json().catch(() => ({}))
      if (!res.ok || data.success === false) {
        setErr(`启动失败：${data.message || `HTTP ${res.status}`}`)
      } else {
        setLogs((prev) => [...prev, { type: 'info', message: data.message || '任务已启动' }])
      }
      await loadStatuses()
    } catch (e) {
      setErr(`启动失败：${(e as Error).message}`)
    }
  }

  async function stop(id: string) {
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/platforms/${id}/stop`, { method: 'POST' })
      const data = await res.json().catch(() => ({}))
      if (!res.ok || data.success === false) {
        setErr(`停止失败：${data.message || `HTTP ${res.status}`}`)
      }
      await loadStatuses()
    } catch (e) {
      setErr(`停止失败：${(e as Error).message}`)
    }
  }

  function fmtDuration(ms?: number) {
    if (!ms || ms <= 0) return '-'
    const s = Math.floor(ms / 1000)
    return `${Math.floor(s / 60)} 分 ${s % 60} 秒`
  }

  if (loading) {
    return <div className="p-6 text-sm text-gray-500">加载中…</div>
  }

  return (
    <div className="text-sm">
      <h1 className="mb-1 text-lg font-semibold">投递</h1>
      <p className="mb-5 text-xs text-gray-500">
        左侧是后端已注册的平台，右侧控制这一轮投递。「当次上限」填 0 表示不限。
      </p>

      {err && <div className="mb-4 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">{err}</div>}

      <div className="mb-4">
        <label className="mr-2 text-gray-600">当次上限</label>
        <input className={INPUT + ' w-24'} value={max} onChange={(e) => setMax(e.target.value)} />
        <span className="ml-2 text-xs text-gray-400">本轮最多投出去几个岗位（0 = 不限）</span>
      </div>

      {platforms.length === 0 ? (
        <div className="border border-gray-300 bg-gray-50 px-4 py-6 text-gray-600">
          当前没有可用平台。
          <div className="mt-1 text-xs text-gray-500">
            后端没有注册任何平台实现时程序仍会正常启动 —— 把平台代码放回来即可。
          </div>
        </div>
      ) : (
        <div className="border border-gray-200">
          {platforms.map((p) => {
            const st = statuses[p.id] || {}
            const running = Boolean(st.isRunning)
            const stopping = Boolean(st.stopping)
            const busy = running || stopping
            return (
              <div
                key={p.id}
                className={`flex flex-wrap items-center gap-4 border-b border-gray-100 px-4 py-3 last:border-b-0 ${
                  selected === p.id ? 'bg-gray-50' : ''
                }`}
                onClick={() => setSelected(p.id)}
              >
                <div className="w-48">
                  <div className="font-medium">{p.name}</div>
                  <div className={`text-xs ${stopping ? 'text-amber-700' : 'text-gray-500'}`}>
                    {p.id}
                    {stopping
                      ? ' · 正在停止…'
                      : running
                      ? ` · 运行中（${fmtDuration(st.runningForMillis)}）`
                      : ' · 空闲'}
                  </div>
                </div>

                <div className="text-xs text-gray-500">
                  投递 {st.delivered ?? 0} · 过滤 {st.filtered ?? 0} · 跳过 {st.skipped ?? 0}
                </div>

                <div className="ml-auto flex items-center gap-2">
                  {stopping && (
                    <span className="text-xs text-amber-700">
                      <span className="mr-1 inline-block animate-pulse">●</span>
                      正在停止，等当前岗位跑完…
                    </span>
                  )}
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void start(p.id)}
                    className="border border-gray-800 bg-gray-900 px-4 py-1 text-white disabled:opacity-40"
                  >
                    开始投递
                  </button>
                  <button
                    type="button"
                    disabled={!running || stopping}
                    onClick={() => void stop(p.id)}
                    className={`border px-4 py-1 disabled:opacity-40 ${
                      stopping
                        ? 'border-amber-500 bg-amber-50 text-amber-800'
                        : 'border-gray-300 hover:border-gray-800'
                    }`}
                  >
                    {stopping ? '正在停止…' : '停止'}
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {/* 进度日志 */}
      <h2 className="mb-2 mt-7 border-b-2 border-gray-800 pb-1 text-sm font-semibold">进度</h2>
      <div className="h-72 overflow-y-auto border border-gray-200 bg-gray-50 p-2 font-mono text-xs">
        {logs.length === 0 ? (
          <div className="text-gray-400">（暂无进度。点「开始投递」后这里会实时刷新）</div>
        ) : (
          logs.map((l, i) => (
            <div key={i} className="whitespace-pre-wrap">
              <span className="text-gray-400">[{l.type || 'info'}]</span>{' '}
              {l.message}
              {l.current != null && l.total != null ? (
                <span className="text-gray-400"> ({l.current}/{l.total})</span>
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  )
}
