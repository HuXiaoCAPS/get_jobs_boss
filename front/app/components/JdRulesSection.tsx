'use client'

import { useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'

/**
 * 「过滤规则（JD）」区块 —— 把项目根目录的 jd-rules.txt 当**文本**编辑。
 *
 * 为什么不做结构化卡片：规则文件里的注释本身就是语法文档（三种动作的含义、
 * 判定顺序、哪些词容易误伤全都写在注释里）。卡片一存盘就会把注释洗掉，
 * 所以这里只做「原文进、原文出」，另附后端解析结果做校验回显。
 *
 * 与「保存」按钮的关系：这个区块有自己独立的保存按钮，不参与配置页顶部那个
 * 「保存」（那个写的是 config/boss.yaml）。两边互不影响。
 *
 * 生效时机：保存后不需要重启 —— JdRuleFilter 在每次投递任务开始时 reload()，
 * 下一次点「开始投递」读到的就是新规则。
 *
 * 样式与配置页保持一致（等宽输入、无圆角、每项一行）；这里自带一份最小的
 * Section/Row，因为配置页里那两个组件是私有实现，不对外导出。
 */

type JdRule = {
  action: string
  name: string
  threshold: number
  count: number
  words: string[]
  describe: string
}

type JdInfo = {
  text: string
  path: string
  source: string
  rules: JdRule[]
  counts: Record<string, number>
  warnings: string[]
}

const INPUT = 'w-full border border-gray-300 bg-white px-2 py-1 text-sm outline-none focus:border-gray-800'

const ACTION_LABEL: Record<string, string> = {
  reject: '命中即拒',
  require: '必须命中',
  warn: '仅提示',
}

/** 来源说明：告诉用户"现在展示的内容是从哪读来的" */
function sourceHint(source: string, path: string): string {
  switch (source) {
    case 'file':
      return `读自工作目录文件：${path}`
    case 'classpath':
      return `工作目录没有这个文件，现在读的是打包资源里的副本；保存后会写到工作目录 ${path}`
    case 'missing':
      return `文件还不存在（路径 ${path}）—— 内容改好后点「保存并校验」即创建`
    case 'unreadable':
      return `文件读取失败（可能正被别的程序占用），下面显示的规则为空，请稍后重试`
    default:
      return path
  }
}

export default function JdRulesSection() {
  const [text, setText] = useState('')
  const [info, setInfo] = useState<JdInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function load() {
    setLoading(true)
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/boss/jd-rules`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      apply(data?.data)
      setDirty(false)
    } catch (e) {
      setErr(`加载过滤规则失败：${(e as Error).message}（检查后端是否已启动）`)
    } finally {
      setLoading(false)
    }
  }

  async function save() {
    setSaving(true)
    setMsg('')
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/boss/jd-rules`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (data?.success === false) throw new Error(data?.message || '后端拒绝保存')
      apply(data?.data)
      setDirty(false)
      setMsg('已保存 → jd-rules.txt（下一次点「开始投递」即生效，不需要重启程序）')
    } catch (e) {
      setErr(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  function apply(data?: JdInfo) {
    if (!data) return
    setInfo(data)
    setText(data.text || '')
  }

  if (loading) {
    return <div className="mb-7 text-sm text-gray-500">过滤规则加载中…</div>
  }

  const counts = info?.counts || {}
  const rules = info?.rules || []
  const warnings = info?.warnings || []
  const total = rules.length

  return (
    <section className="mb-7">
      <h2 className="mb-2 border-b-2 border-gray-800 pb-1 text-sm font-semibold">过滤规则（JD）</h2>
      <p className="mb-1 text-xs text-gray-500">
        直接编辑项目根目录的 <code>jd-rules.txt</code>，<code>#</code>{' '}
        开头是注释（也是这份语法的说明，建议留着）。规则头写{' '}
        <code>[动作] 名称 阈值</code>，紧跟一行一个关键词；动作为 <code>reject</code>（命中即拒）/
        <code>require</code>（必须命中）/ <code>warn</code>（只记提示）。保存后下一次点「开始投递」生效，
        不需重启。这一段的「保存并校验」与页面顶部那个「保存」互不影响。
      </p>

      {err && <div className="mb-3 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">{err}</div>}
      {msg && <div className="mb-3 border-l-2 border-green-600 bg-green-50 px-3 py-2 text-green-700">{msg}</div>}

      <div className="border-b border-gray-200 py-2.5">
        <div className="mb-1 flex items-center gap-3">
          <span className="text-xs text-gray-500">{sourceHint(info?.source || '', info?.path || '')}</span>
          {dirty && <span className="shrink-0 text-xs text-orange-600">● 有未保存的改动</span>}
        </div>
        <textarea
          className={INPUT + ' font-mono'}
          rows={22}
          spellCheck={false}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setDirty(true)
          }}
        />
        <div className="mt-2 flex items-center gap-3">
          <button
            className="border border-gray-800 bg-gray-900 px-4 py-1 text-white disabled:opacity-50"
            disabled={saving}
            onClick={() => void save()}
          >
            {saving ? '保存中…' : '保存并校验'}
          </button>
          <button className="border border-gray-300 px-4 py-1" onClick={() => void load()}>
            放弃改动并重新加载
          </button>
        </div>
      </div>

      <div className="pt-3">
        <div className="mb-2 text-xs text-gray-500">
          解析结果：共 {total} 组（reject {counts.reject || 0} / require {counts.require || 0} / warn{' '}
          {counts.warn || 0}）
        </div>

        {warnings.length > 0 && (
          <div className="mb-3 border-l-2 border-orange-500 bg-orange-50 px-3 py-2 text-orange-800">
            <div className="mb-1 font-semibold">语法告警（这些行/组不会生效）</div>
            <ul className="list-disc pl-5">
              {warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </div>
        )}

        {total === 0 ? (
          <div className="py-1 text-gray-500">(没有任何规则 —— 此时不会过滤任何岗位)</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-gray-300 text-left text-xs text-gray-500">
                <th className="w-24 py-1 font-normal">动作</th>
                <th className="w-40 py-1 font-normal">名称</th>
                <th className="w-20 py-1 font-normal">阈值</th>
                <th className="w-16 py-1 font-normal">词数</th>
                <th className="py-1 font-normal">词</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r, i) => (
                <tr key={i} className="border-b border-gray-100 align-top">
                  <td className="py-1">
                    <span className={r.action === 'reject' ? 'text-red-700' : r.action === 'require' ? 'text-blue-700' : 'text-gray-500'}>
                      {r.action}
                    </span>
                    <span className="ml-1 text-xs text-gray-400">{ACTION_LABEL[r.action] || ''}</span>
                  </td>
                  <td className="py-1">{r.name}</td>
                  <td className="py-1">{r.threshold}</td>
                  <td className="py-1">{r.count}</td>
                  <td className="py-1 text-xs text-gray-600">{r.words.join('、')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  )
}
