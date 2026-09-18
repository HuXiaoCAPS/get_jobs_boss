'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'

/**
 * 「过滤规则（JD）」区块 —— 按**一条规则**为单位增删改，不再直接暴露文件文本。
 *
 * 规则跟着配置走（换配置后本区块自动换成那一份），与配置页顶部那个「保存」互不影响：
 * 这里有自己的「保存」，写的是规则；那边写的是搜索条件/招呼语/AI/通知。
 *
 * 为什么改成结构化表单：规则文件是给机器判定用的，用户要表达的是
 * 「哪一类词、命中几条、拒绝还是放行」，而不是手写某种文件格式。
 * 每条规则有自己的备注字段（note），原来靠注释承载的说明不再需要塞进文件。
 *
 * 为什么增删走弹窗而不是平铺一行行的表单：配置页本来就很长，
 * 平铺会让"列表"和"编辑器"混在一起、越改越乱；弹窗只在需要时出现。
 *
 * 生效时机：保存后不需要重启 —— JdRuleFilter 在每次投递任务开始时读取，
 * 下一次点「开始投递」读到的就是新规则。
 */

type Rule = {
  action: string
  name: string
  threshold: number
  note: string
  words: string[]
}

type RuleInfo = {
  /** file / classpath / missing / unreadable */
  source: string
  profile?: string
  rules: Rule[]
  counts: Record<string, number>
  warnings: string[]
}

type Draft = { action: string; name: string; threshold: string; note: string; words: string }

const INPUT = 'w-full border border-gray-300 bg-white px-2 py-1 text-sm outline-none focus:border-gray-800'
const BTN = 'border border-gray-300 px-3 py-1 text-sm hover:border-gray-800'

const ACTIONS: { value: string; label: string; hint: string }[] = [
  { value: 'reject', label: 'reject —— 命中即拒', hint: '命中（命中词数 ≥ 阈值）就跳过这个岗位，不投递' },
  { value: 'require', label: 'require —— 必须命中', hint: '没达到阈值就跳过；用来表达"必须是这个方向"' },
  { value: 'warn', label: 'warn —— 仅提示', hint: '只记一条提示（写进数据页的过滤备注），照常投递' },
]

const ACTION_COLOR: Record<string, string> = {
  reject: 'text-red-700',
  require: 'text-blue-700',
  warn: 'text-gray-500',
}

const BLANK: Draft = { action: 'reject', name: '', threshold: '1', note: '', words: '' }

/** 词表按 , ， 、 ; ； 与换行切分 —— 手打时这几种分隔符都常见 */
function parseWords(raw: string): string[] {
  return raw
    .split(/[,，、;；\r\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

export default function JdRulesSection({ profile }: { profile?: string }) {
  const [rules, setRules] = useState<Rule[]>([])
  const [info, setInfo] = useState<RuleInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  // 弹窗：'new' = 新增，数字 = 编辑第几条，null = 关闭
  const [editing, setEditing] = useState<number | 'new' | null>(null)
  const [draft, setDraft] = useState<Draft>(BLANK)
  const [dialogErr, setDialogErr] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/boss/jd-rules`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (data?.success === false) throw new Error(data?.message || '后端拒绝返回规则')
      applyInfo(data?.data)
    } catch (e) {
      setErr(`加载过滤规则失败：${(e as Error).message}（检查后端是否已启动）`)
    } finally {
      setLoading(false)
    }
  }, [])

  // profile 变了说明配置被换过，规则也跟着换了，必须重新拉一次
  useEffect(() => {
    void load()
  }, [load, profile])

  function applyInfo(data?: RuleInfo) {
    if (!data) return
    setInfo(data)
    setRules(data.rules || [])
    setDirty(false)
  }

  async function save() {
    setSaving(true)
    setMsg('')
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/boss/jd-rules`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rules }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok || data?.success === false) {
        throw new Error(data?.message || `HTTP ${res.status}`)
      }
      applyInfo(data?.data)
      setMsg('已保存（下一次点「开始投递」即生效，不需要重启程序）')
    } catch (e) {
      setErr(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  // ---------- 弹窗 ----------

  function openAdd() {
    setDraft(BLANK)
    setDialogErr('')
    setEditing('new')
  }

  function openEdit(index: number) {
    const r = rules[index]
    setDraft({
      action: r.action,
      name: r.name,
      threshold: String(r.threshold),
      note: r.note || '',
      // 词表回填成逗号分隔，和输入框的约定一致
      words: (r.words || []).join(', '),
    })
    setDialogErr('')
    setEditing(index)
  }

  function closeDialog() {
    setEditing(null)
    setDialogErr('')
  }

  function applyDraft() {
    const words = parseWords(draft.words)
    if (words.length === 0) {
      setDialogErr('词表不能为空 —— 至少要有一个词，否则这条规则无法判定')
      return
    }
    const parsed = parseInt(draft.threshold, 10)
    const threshold = Number.isFinite(parsed) && parsed >= 1 ? parsed : 1
    const rule: Rule = {
      action: draft.action,
      name: draft.name.trim() || draft.action,
      threshold,
      note: draft.note.trim(),
      words,
    }
    setRules((prev) => {
      if (editing === 'new') return [...prev, rule]
      if (typeof editing === 'number') return prev.map((r, i) => (i === editing ? rule : r))
      return prev
    })
    setDirty(true)
    closeDialog()
  }

  function removeRule(index: number) {
    if (!confirm(`删除规则「${rules[index]?.name}」？`)) return
    setRules((prev) => prev.filter((_, i) => i !== index))
    setDirty(true)
  }

  if (loading) {
    return <div className="mb-7 text-sm text-gray-500">过滤规则加载中…</div>
  }

  const owner = info?.profile || profile || ''
  const counts = info?.counts || {}
  const warnings = info?.warnings || []
  const draftWordCount = parseWords(draft.words).length

  return (
    <section className="mb-7">
      <div className="mb-2 flex items-end justify-between gap-4 border-b-2 border-gray-800 pb-1">
        <h2 className="text-sm font-semibold">
          过滤规则（JD）
          {owner && <span className="ml-2 text-xs font-normal text-gray-500">跟随配置：{owner}</span>}
        </h2>
        <div className="flex shrink-0 items-center gap-2 text-xs">
          {dirty && <span className="text-orange-600">● 有未保存的改动</span>}
          <span className="text-gray-500">
            共 {rules.length} 条（reject {counts.reject || 0} / require {counts.require || 0} / warn{' '}
            {counts.warn || 0}）
          </span>
          <button className={BTN} onClick={openAdd}>
            新增规则
          </button>
          <button
            className="border border-gray-800 bg-gray-900 px-3 py-1 text-white disabled:opacity-50"
            disabled={saving}
            onClick={() => void save()}
          >
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>

      <p className="mb-2 text-xs text-gray-500">
        按「一类词 + 命中条件」写规则：<code>reject</code> 命中就跳过、<code>require</code> 必须命中、
        <code>warn</code> 只记提示。保存后下一次点「开始投递」生效，不需重启。
        <span className="ml-1">这一段的「保存」与页面顶部那个「保存」互不影响。</span>
      </p>

      {err && <div className="mb-3 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">{err}</div>}
      {msg && <div className="mb-3 border-l-2 border-green-600 bg-green-50 px-3 py-2 text-green-700">{msg}</div>}

      {info?.source === 'unreadable' && (
        <div className="mb-3 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">
          规则读取失败（文件可能正被别的程序占用，或格式有问题）—— 下面显示的规则为空，请稍后重试。
        </div>
      )}

      {warnings.length > 0 && (
        <div className="mb-3 border-l-2 border-orange-500 bg-orange-50 px-3 py-2 text-orange-800">
          <div className="mb-1 font-semibold">语法告警（这些规则不会生效）</div>
          <ul className="list-disc pl-5">
            {warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      {rules.length === 0 ? (
        <div className="border border-dashed border-gray-300 px-3 py-4 text-center text-gray-500">
          还没有任何规则 —— 此时不会过滤任何岗位。点右上角「新增规则」开始。
        </div>
      ) : (
        <table className="w-full">
          <thead>
            <tr className="border-b border-gray-300 text-left text-xs text-gray-500">
              <th className="w-24 py-1 font-normal">动作</th>
              <th className="w-36 py-1 font-normal">名称</th>
              <th className="w-14 py-1 font-normal">阈值</th>
              <th className="w-14 py-1 font-normal">词数</th>
              <th className="w-56 py-1 font-normal">备注</th>
              <th className="py-1 font-normal">词</th>
              <th className="w-20 py-1 font-normal">操作</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r, i) => (
              <tr key={i} className="border-b border-gray-100 align-top">
                <td className="py-1">
                  <span className={ACTION_COLOR[r.action] || 'text-gray-500'}>{r.action}</span>
                </td>
                <td className="py-1">{r.name}</td>
                <td className="py-1">{r.threshold}</td>
                <td className="py-1">{r.words.length}</td>
                <td className="py-1 text-xs text-gray-500">{r.note}</td>
                <td className="py-1 text-xs text-gray-600">
                  {r.words.slice(0, 8).join('、')}
                  {r.words.length > 8 && <span className="text-gray-400"> …（共 {r.words.length} 个）</span>}
                </td>
                <td className="py-1 whitespace-nowrap">
                  <button className="text-gray-700 hover:underline" onClick={() => openEdit(i)}>
                    编辑
                  </button>
                  <button className="ml-2 text-red-600 hover:underline" onClick={() => removeRule(i)}>
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* ---------- 弹窗：新增 / 编辑规则 ---------- */}
      {editing !== null && (
        <Modal title={editing === 'new' ? '新增规则' : '编辑规则'} onClose={closeDialog}>
          <div className="space-y-3">
            <label className="block">
              <span className="mb-1 block text-xs text-gray-500">动作</span>
              <select
                className={INPUT}
                value={draft.action}
                onChange={(e) => setDraft({ ...draft, action: e.target.value })}
              >
                {ACTIONS.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
              <span className="mt-1 block text-xs text-gray-400">
                {ACTIONS.find((a) => a.value === draft.action)?.hint}
              </span>
            </label>

            <div className="flex gap-3">
              <label className="flex-1">
                <span className="mb-1 block text-xs text-gray-500">名称</span>
                <input
                  className={INPUT}
                  placeholder="例如：大数据组件"
                  value={draft.name}
                  onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                />
              </label>
              <label className="w-24">
                <span className="mb-1 block text-xs text-gray-500">阈值</span>
                <input
                  className={INPUT}
                  type="number"
                  min={1}
                  value={draft.threshold}
                  onChange={(e) => setDraft({ ...draft, threshold: e.target.value })}
                />
              </label>
            </div>

            <label className="block">
              <span className="mb-1 block text-xs text-gray-500">备注（只给自己看，可选）</span>
              <input
                className={INPUT}
                placeholder="为什么加这条规则，例如：一个组件都不提的直接淘汰"
                value={draft.note}
                onChange={(e) => setDraft({ ...draft, note: e.target.value })}
              />
            </label>

            <label className="block">
              <span className="mb-1 block text-xs text-gray-500">词表（逗号分隔，也认顿号/分号/换行）</span>
              <textarea
                className={INPUT}
                rows={6}
                placeholder="Hadoop, Hive, Spark, Flink, Doris"
                value={draft.words}
                onChange={(e) => setDraft({ ...draft, words: e.target.value })}
              />
              <span className="mt-1 block text-xs text-gray-400">
                已解析出 {draftWordCount} 个词
                {draftWordCount > 0 && draftWordCount < parseInt(draft.threshold || '1', 10) && (
                  <span className="ml-1 text-orange-600">
                    —— 比阈值 {draft.threshold} 还少，这条规则永远不可能命中
                  </span>
                )}
              </span>
            </label>

            {dialogErr && <div className="border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">{dialogErr}</div>}

            <div className="flex justify-end gap-2 pt-1">
              <button className={BTN} onClick={closeDialog}>
                取消
              </button>
              <button className="border border-gray-800 bg-gray-900 px-4 py-1 text-white" onClick={applyDraft}>
                {editing === 'new' ? '加入' : '保存修改'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  )
}

/** 极简弹窗：点遮罩或按 Esc 关闭 */
function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/30 pt-16" onClick={onClose}>
      <div className="w-[32rem] border border-gray-800 bg-white p-4" onClick={(e) => e.stopPropagation()}>
        <h3 className="mb-3 border-b border-gray-300 pb-1 text-sm font-semibold">{title}</h3>
        {children}
      </div>
    </div>
  )
}
