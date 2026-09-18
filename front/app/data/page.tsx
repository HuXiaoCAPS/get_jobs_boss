'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { cellPadding, loadPrefs } from '@/lib/prefs'

/**
 * 数据页：投递记录（只读）+ 统计。
 *
 * 刻意不做"直接展示表 + 自由写 SQL"：
 *   - 后端已经有现成的 /api/boss/list（分页 + 筛选）和 /api/boss/stats（统计），
 *     字段与 boss_data 天然对齐，不用再维护一套 SQL；
 *   - 自由 SQL 的注入面（哪怕只读也能 `; DROP TABLE`）、大表全扫卡死、误操作风险都不小。
 */

type JobRow = {
  id?: number
  jobName?: string
  companyName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  hrPosition?: string
  hrActiveStatus?: string
  deliveryStatus?: string
  filterNote?: string
  jobUrl?: string
}

type Paged = { items: JobRow[]; total: number; page: number; size: number }

type Stats = {
  kpi?: {
    total?: number
    delivered?: number
    pending?: number
    filtered?: number
    failed?: number
    avgMonthlyK?: number | null
  }
}

const STATUSES = ['未投递', '已投递', '已过滤', '投递失败']

const INPUT = 'border border-gray-300 bg-white px-2 py-1 text-sm outline-none focus:border-gray-800'

export default function DataPage() {
  const [pageSize, setPageSize] = useState(20)
  const [density, setDensity] = useState<'comfortable' | 'compact'>('comfortable')

  const [statuses, setStatuses] = useState<string[]>([])
  const [location, setLocation] = useState('')
  const [keyword, setKeyword] = useState('')
  const [minK, setMinK] = useState('')
  const [maxK, setMaxK] = useState('')

  const [page, setPage] = useState(1)
  const [data, setData] = useState<Paged>({ items: [], total: 0, page: 1, size: 20 })
  const [stats, setStats] = useState<Stats>({})
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState('')

  // 外观页设置的每页条数/密度
  useEffect(() => {
    const p = loadPrefs()
    setPageSize(p.pageSize)
    setDensity(p.density)
  }, [])

  const buildQuery = useCallback(
    (extra: Record<string, string | number>) => {
      const q = new URLSearchParams()
      if (statuses.length) q.set('statuses', statuses.join(','))
      if (location.trim()) q.set('location', location.trim())
      if (keyword.trim()) q.set('keyword', keyword.trim())
      if (minK.trim()) q.set('minK', minK.trim())
      if (maxK.trim()) q.set('maxK', maxK.trim())
      for (const [k, v] of Object.entries(extra)) q.set(k, String(v))
      return q.toString()
    },
    [statuses, location, keyword, minK, maxK],
  )

  const load = useCallback(
    async (targetPage: number, size: number) => {
      setLoading(true)
      setErr('')
      try {
        const [listRes, statsRes] = await Promise.all([
          fetch(`${API_BASE}/api/boss/list?${buildQuery({ page: targetPage, size })}`),
          fetch(`${API_BASE}/api/boss/stats?${buildQuery({})}`),
        ])
        if (!listRes.ok) throw new Error(`列表 HTTP ${listRes.status}`)
        const listData = (await listRes.json()) as Paged
        setData({
          items: listData.items || [],
          total: listData.total || 0,
          page: listData.page || targetPage,
          size: listData.size || size,
        })
        if (statsRes.ok) {
          setStats((await statsRes.json()) as Stats)
        }
      } catch (e) {
        setErr(`加载数据失败：${(e as Error).message}（检查后端是否已启动）`)
      } finally {
        setLoading(false)
      }
    },
    [buildQuery],
  )

  useEffect(() => {
    void load(page, pageSize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, pageSize])

  function applyFilters() {
    setPage(1)
    void load(1, pageSize)
  }

  function resetFilters() {
    setStatuses([])
    setLocation('')
    setKeyword('')
    setMinK('')
    setMaxK('')
    setPage(1)
    window.setTimeout(() => void load(1, pageSize), 0)
  }

  function toggleStatus(s: string) {
    setStatuses((prev) => (prev.includes(s) ? prev.filter((v) => v !== s) : [...prev, s]))
  }

  const kpi = stats.kpi || {}
  const totalPages = Math.max(1, Math.ceil((data.total || 0) / (pageSize || 20)))
  const pad = cellPadding(density)

  return (
    <div className="text-sm">
      <h1 className="mb-1 text-lg font-semibold">数据</h1>
      <p className="mb-5 text-xs text-gray-500">
        来自 <code>boss_data</code> 表（只读）。筛选条件与统计口径一致；展示偏好在外观页里改。
      </p>

      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Kpi label="全部" value={kpi.total} />
        <Kpi label="已投递" value={kpi.delivered} />
        <Kpi label="未投递" value={kpi.pending} />
        <Kpi label="已过滤" value={kpi.filtered} />
        <Kpi label="投递失败" value={kpi.failed} />
        <Kpi label="平均薪资(K)" value={kpi.avgMonthlyK ?? undefined} />
      </div>

      {/* 筛选条 */}
      <div className="mb-4 border border-gray-200 p-3">
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <span className="text-gray-500">状态</span>
          {STATUSES.map((s) => {
            const active = statuses.includes(s)
            return (
              <button
                key={s}
                type="button"
                onClick={() => toggleStatus(s)}
                className={`border px-2 py-0.5 ${
                  active ? 'border-gray-800 bg-gray-900 text-white' : 'border-gray-300 hover:border-gray-800'
                }`}
              >
                {s}
              </button>
            )
          })}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <input
            className={INPUT + ' w-40'}
            placeholder="城市（如 深圳）"
            value={location}
            onChange={(e) => setLocation(e.target.value)}
          />
          <input
            className={INPUT + ' w-48'}
            placeholder="关键词（岗位/公司）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') applyFilters()
            }}
          />
          <input className={INPUT + ' w-20'} placeholder="最低K" value={minK} onChange={(e) => setMinK(e.target.value)} />
          <input className={INPUT + ' w-20'} placeholder="最高K" value={maxK} onChange={(e) => setMaxK(e.target.value)} />
          <button className="border border-gray-800 bg-gray-900 px-3 py-1 text-white" onClick={applyFilters}>
            查询
          </button>
          <button className="border border-gray-300 px-3 py-1 hover:border-gray-800" onClick={resetFilters}>
            重置
          </button>
        </div>
      </div>

      {err && <div className="mb-4 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">{err}</div>}

      <div className="mb-2 flex items-center gap-3 text-xs text-gray-500">
        <span>
          共 {data.total} 条 · 第 {data.page} / {totalPages} 页
        </span>
        <button
          className="border border-gray-300 px-2 py-0.5 disabled:opacity-40"
          disabled={loading || page <= 1}
          onClick={() => setPage((p) => Math.max(1, p - 1))}
        >
          上一页
        </button>
        <button
          className="border border-gray-300 px-2 py-0.5 disabled:opacity-40"
          disabled={loading || page >= totalPages}
          onClick={() => setPage((p) => p + 1)}
        >
          下一页
        </button>
        {loading && <span>加载中…</span>}
      </div>

      <div className="overflow-x-auto border border-gray-200">
        <table className="w-full min-w-[1100px] text-left">
          <thead className="border-b border-gray-300 bg-gray-50">
            <tr className="text-xs text-gray-600">
              <Th pad={pad}>岗位</Th>
              <Th pad={pad}>公司</Th>
              <Th pad={pad}>薪资</Th>
              <Th pad={pad}>城市</Th>
              <Th pad={pad}>经验</Th>
              <Th pad={pad}>学历</Th>
              <Th pad={pad}>HR</Th>
              <Th pad={pad}>HR活跃</Th>
              <Th pad={pad}>状态</Th>
              <Th pad={pad}>备注</Th>
            </tr>
          </thead>
          <tbody>
            {data.items.length === 0 && !loading ? (
              <tr>
                <td className="px-2 py-4 text-gray-500" colSpan={10}>
                  (没有符合条件的记录)
                </td>
              </tr>
            ) : (
              data.items.map((row, idx) => (
                <tr key={row.id ?? idx} className="border-b border-gray-100 align-top">
                  <Td pad={pad}>
                    {row.jobUrl ? (
                      <a className="hover:underline" href={row.jobUrl} target="_blank" rel="noreferrer">
                        {row.jobName || '(无)'}
                      </a>
                    ) : (
                      row.jobName || '(无)'
                    )}
                  </Td>
                  <Td pad={pad}>{row.companyName || ''}</Td>
                  <Td pad={pad}>{row.salary || ''}</Td>
                  <Td pad={pad}>{row.location || ''}</Td>
                  <Td pad={pad}>{row.experience || ''}</Td>
                  <Td pad={pad}>{row.degree || ''}</Td>
                  <Td pad={pad}>
                    {row.hrName || ''}
                    {row.hrPosition ? <span className="text-gray-400"> · {row.hrPosition}</span> : null}
                  </Td>
                  <Td pad={pad}>{row.hrActiveStatus || ''}</Td>
                  <Td pad={pad}>
                    <StatusTag status={row.deliveryStatus} />
                  </Td>
                  <Td pad={pad}>
                    <span className="text-xs text-gray-500">{row.filterNote || ''}</span>
                  </Td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Kpi({ label, value }: { label: string; value?: number | null }) {
  return (
    <div className="border border-gray-200 px-3 py-2">
      <div className="text-xs text-gray-500">{label}</div>
      <div className="text-lg font-semibold">{value == null ? '-' : typeof value === 'number' ? Math.round(value * 100) / 100 : value}</div>
    </div>
  )
}

function StatusTag({ status }: { status?: string }) {
  const s = status || '未投递'
  const cls =
    s === '已投递'
      ? 'border-green-600 text-green-700'
      : s === '已过滤'
      ? 'border-gray-400 text-gray-600'
      : s === '投递失败'
      ? 'border-red-500 text-red-700'
      : 'border-blue-500 text-blue-700'
  return <span className={`border px-1.5 py-0.5 text-xs ${cls}`}>{s}</span>
}

function Th({ children, pad }: { children: React.ReactNode; pad: string }) {
  return <th className={`${pad} font-medium whitespace-nowrap`}>{children}</th>
}

function Td({ children, pad }: { children: React.ReactNode; pad: string }) {
  return <td className={pad}>{children}</td>
}
