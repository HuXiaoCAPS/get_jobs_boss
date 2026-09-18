'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { cellPadding, loadPrefs } from '@/lib/prefs'

/**
 * 数据页：投递记录（只读）+ 统计。
 *
 * <p>**页面是平台无关的**：平台列表来自 `GET /api/platforms`，数据来自
 * `GET /api/platforms/{id}/jobs` 与 `/stats`。所以这里不出现任何平台的表名或字段名 ——
 * 换平台、加平台，这一页一行都不用改。（早先它写死 `/api/boss/list` 与
 * 「来自 boss_data 表」，等于把平台能力只做在了投递链路上。）
 *
 * 刻意不做"直接展示表 + 自由写 SQL"：
 *   - 后端已按平台提供分页/筛选/统计，字段与各平台的数据模型对齐，不用再维护一套 SQL；
 *   - 自由 SQL 的注入面（哪怕只读也能 `; DROP TABLE`）、大表全扫卡死、误操作风险都不小。
 */

/** 一条岗位记录：后端 JobRecord 的形状（平台无关） */
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
  /** 投递状态（字段名不叫 deliveryStatus —— 那个是 boss_data 的列名） */
  status?: string
  /** 过滤/提示备注 */
  note?: string
  jobUrl?: string
}

type Paged = { items: JobRow[]; total: number; page: number; size: number }

/** name + 计数的通用分组项 */
type Count = { name?: string; value?: number }

/** 后端 JobStats 的形状：KPI 直接平铺，不再套一层 kpi */
type Stats = {
  total?: number
  delivered?: number
  pending?: number
  filtered?: number
  failed?: number
  /** 平均月薪（中位数 K/月），只有月薪口径的岗位参与平均 */
  avgMonthlyK?: number | null
  /** 平均日薪（中位数 元/天），只有日薪口径的岗位参与平均（实习岗基本都是这种） */
  avgDailyYuan?: number | null
  salaryBuckets?: Count[]
  dailySalaryBuckets?: Count[]
}

type Platform = { id: string; name: string }

const STATUSES = ['未投递', '已投递', '已过滤', '投递失败']

/** 薪资区间两个输入框的 placeholder —— 随口径变，免得把"元/天"填进"K"的框里 */
const SALARY_PLACEHOLDER: Record<'MONTH' | 'DAY', { min: string; max: string }> = {
  MONTH: { min: '最低K', max: '最高K' },
  DAY: { min: '最低元/天', max: '最高元/天' },
}

const INPUT = 'border border-gray-300 bg-white px-2 py-1 text-sm outline-none focus:border-gray-800'

export default function DataPage() {
  const [pageSize, setPageSize] = useState(20)
  const [density, setDensity] = useState<'comfortable' | 'compact'>('comfortable')

  // 平台：列表 + 当前选中（页面完全按平台渲染，与投递页保持一致的思路）
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [selected, setSelected] = useState('')

  const [statuses, setStatuses] = useState<string[]>([])
  const [location, setLocation] = useState('')
  const [keyword, setKeyword] = useState('')
  const [minK, setMinK] = useState('')
  const [maxK, setMaxK] = useState('')
  /** 薪资区间口径：MONTH = K/月（默认），DAY = 元/天（实习岗多按这个报价） */
  const [salaryUnit, setSalaryUnit] = useState<'MONTH' | 'DAY'>('MONTH')

  const [page, setPage] = useState(1)
  const [data, setData] = useState<Paged>({ items: [], total: 0, page: 1, size: 20 })
  const [stats, setStats] = useState<Stats>({})
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState('')
  /** 手动触发重查用的计数器（点「查询」「重置」时 +1）。
   *  不能只靠 setPage(1)：页码本来就是 1 时它不变化，effect 不会重跑。 */
  const [refreshKey, setRefreshKey] = useState(0)

  // 外观页设置的每页条数/密度
  useEffect(() => {
    const p = loadPrefs()
    setPageSize(p.pageSize)
    setDensity(p.density)
  }, [])

  // 平台列表：有多个时用户可以切换；一个都没有时页面给出提示而不是报错
  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch(`${API_BASE}/api/platforms`)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const body = await res.json()
        const list: Platform[] = body.platforms || []
        setPlatforms(list)
        setSelected((prev) => prev || (list.length ? list[0].id : ''))
        if (!list.length) {
          setLoading(false)
          setErr('当前没有可用平台 —— 后端没有注册任何 JobPlatform 实现（程序仍在正常运行）')
        }
      } catch (e) {
        setLoading(false)
        setErr(`加载平台列表失败：${(e as Error).message}（检查后端是否已启动）`)
      }
    })()
  }, [])

  const buildQuery = useCallback(
    (extra: Record<string, string | number>) => {
      const q = new URLSearchParams()
      if (statuses.length) q.set('statuses', statuses.join(','))
      if (location.trim()) q.set('location', location.trim())
      if (keyword.trim()) q.set('keyword', keyword.trim())
      if (minK.trim()) q.set('minK', minK.trim())
      if (maxK.trim()) q.set('maxK', maxK.trim())
      // 只在填了区间时才带口径：没填区间时它没有意义，带上反而让 URL 变脏
      if (minK.trim() || maxK.trim()) q.set('salaryUnit', salaryUnit)
      for (const [k, v] of Object.entries(extra)) q.set(k, String(v))
      return q.toString()
    },
    [statuses, location, keyword, minK, maxK, salaryUnit],
  )

  const load = useCallback(
    async (targetPage: number, size: number) => {
      if (!selected) return
      setLoading(true)
      setErr('')
      try {
        const [listRes, statsRes] = await Promise.all([
          fetch(`${API_BASE}/api/platforms/${selected}/jobs?${buildQuery({ page: targetPage, size })}`),
          fetch(`${API_BASE}/api/platforms/${selected}/stats?${buildQuery({})}`),
        ])

        // 501 = 该平台没实现数据浏览。这跟"没有数据"是两回事，必须分开告诉用户。
        if (listRes.status === 501) {
          setData({ items: [], total: 0, page: 1, size: size })
          setStats({})
          setErr(`平台「${selected}」暂不支持查看数据（没实现该数据能力）`)
          return
        }
        if (!listRes.ok) throw new Error(`列表 HTTP ${listRes.status}`)

        const listBody = await listRes.json()
        const listData = (listBody.data || {}) as Paged
        setData({
          items: listData.items || [],
          total: listData.total || 0,
          page: listData.page || targetPage,
          size: listData.size || size,
        })

        if (statsRes.ok) {
          const statsBody = await statsRes.json()
          setStats((statsBody.data || {}) as Stats)
        }
      } catch (e) {
        setErr(`加载数据失败：${(e as Error).message}（检查后端是否已启动）`)
      } finally {
        setLoading(false)
      }
    },
    [buildQuery, selected],
  )

  // 平台 / 页码 / 每页条数 / 手动重查 —— 任一变化都重新加载。
  // 换平台时页码由 select 的 onChange 一起归 1，两个 state 同批更新，只会触发一次请求。
  useEffect(() => {
    if (!selected) return
    void load(page, pageSize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, page, pageSize, refreshKey])

  function applyFilters() {
    setPage(1)
    setRefreshKey((k) => k + 1)
  }

  function resetFilters() {
    setStatuses([])
    setLocation('')
    setKeyword('')
    setMinK('')
    setMaxK('')
    setSalaryUnit('MONTH')
    setPage(1)
    setRefreshKey((k) => k + 1)
  }

  function toggleStatus(s: string) {
    setStatuses((prev) => (prev.includes(s) ? prev.filter((v) => v !== s) : [...prev, s]))
  }

  const totalPages = Math.max(1, Math.ceil((data.total || 0) / (pageSize || 20)))
  const pad = cellPadding(density)
  const current = platforms.find((p) => p.id === selected)

  return (
    <div className="text-sm">
      <div className="mb-1 flex flex-wrap items-baseline justify-between gap-3">
        <h1 className="text-lg font-semibold">数据</h1>
        {/* 平台选择：只有注册了多个平台时才有意义，但始终显示 —— 这样"这一页是平台无关的"一眼可见 */}
        {platforms.length > 0 && (
          <div className="flex items-center gap-2 text-xs">
            <span className="text-gray-500">平台</span>
            <select
              className={INPUT}
              value={selected}
              onChange={(e) => {
                setSelected(e.target.value)
                // 归到第 1 页：换了平台还停在第 3 页，多半是空页
                setPage(1)
              }}
            >
              {platforms.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      <p className="mb-5 text-xs text-gray-500">
        来自平台「{current?.name || selected || '—'}」的投递记录（只读）。筛选条件与统计口径一致；
        展示偏好在外观页里改。
      </p>

      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-7">
        <Kpi label="全部" value={stats.total} />
        <Kpi label="已投递" value={stats.delivered} />
        <Kpi label="未投递" value={stats.pending} />
        <Kpi label="已过滤" value={stats.filtered} />
        <Kpi label="投递失败" value={stats.failed} />
        <Kpi label="平均月薪(K)" value={stats.avgMonthlyK ?? undefined} />
        <Kpi label="平均日薪(元/天)" value={stats.avgDailyYuan ?? undefined} />
      </div>

      <p className="mb-4 text-xs text-gray-500">
        平均月薪与平均日薪<b>分开统计</b>：实习岗多按「元/天」报价，折算成月薪（约 3~5K）与正职月薪
        （10~40K）不是一回事，混在一起平均两个数字都会失真。所以按岗位自己的计价口径分别汇总，
        哪个口径没有数据就显示「-」。薪资区间按下方选的口径比对（月薪 K / 日薪 元/天），
        两种口径<b>不会互相匹配</b> —— 拿日薪折出的月薪去和正职比会得出错误结论。
      </p>

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
          <input className={INPUT + ' w-20'} placeholder={SALARY_PLACEHOLDER[salaryUnit].min} value={minK} onChange={(e) => setMinK(e.target.value)} />
          <input className={INPUT + ' w-20'} placeholder={SALARY_PLACEHOLDER[salaryUnit].max} value={maxK} onChange={(e) => setMaxK(e.target.value)} />
          {/* 口径必须能切：实习岗按「元/天」报价，只按月薪筛的话它们全被排除，
              搜「日薪 150 以上」也就无从下手 */}
          <select
            className={INPUT}
            value={salaryUnit}
            onChange={(e) => setSalaryUnit(e.target.value as 'MONTH' | 'DAY')}
            title="薪资区间的单位：这两个框里的数字按它解释"
          >
            <option value="MONTH">月薪 (K)</option>
            <option value="DAY">日薪 (元/天)</option>
          </select>
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
                    <StatusTag status={row.status} />
                  </Td>
                  <Td pad={pad}>
                    <span className="text-xs text-gray-500">{row.note || ''}</span>
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
