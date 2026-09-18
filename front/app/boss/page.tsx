'use client'

import { useEffect, useRef, useState } from 'react'
import { API_BASE } from '@/lib/api'
import JdRulesSection from '@/app/components/JdRulesSection'

/**
 * 配置页（单页极简版）
 *
 * 这个副本只做 Boss 一个平台，所以把原来分散的三个页面合并到这一页：
 *   - Boss 搜索 / 投递配置（/api/boss/config）
 *   - AI 提示词 + 我的资料（/api/ai/config）
 *   - 企业微信通知（/api/config 的 HOOK_URL / BOT_IS_SEND）
 *
 * 设计约定：
 *   - 每项一行：左边标签、右边控件；无圆角、无阴影、无渐变
 *   - 配置以 config/ 下的配置文件为权威来源，保存时后端会写文件再同步回库
 *   - 这页很长，所以配置文件的四个操作放在**右上角固定按钮**（另存为 / 载入 / 选择 / 保存），
 *     不用滚到底部再滚回来；另存为与选择各配一个弹窗
 *   - **前端不暴露"文件"这一层**：不出现 .yaml 后缀、不显示文件路径，
 *     用户面对的是「一份配置」，换配置就是换那一份
 *   - API 地址 / API Key / 模型**不在这里改**（只允许编辑配置文件），
 *     因为网页端是明文输入框，容易泄露；改了文件后启动/投递前会自动同步到库
 *   - 城市是逗号分隔的输入框：能表达多城市（深圳,广州），留空 = 不限
 *   - 过滤规则由 <JdRulesSection /> 独立编辑与保存，跟随当前配置，
 *     与这里的「保存」互不影响
 */

type Option = { name: string; code: string }
type Blacklist = { id: number; type: string; value: string }
type Dict = Record<string, string[]>
type Form = Record<string, unknown>
/** 一份配置：名字 + 是否当前生效 + 有没有配过滤规则 */
type Profile = { name: string; active: boolean; hasRules: boolean }

const INPUT =
  'w-full border border-gray-300 bg-white px-2 py-1 text-sm outline-none focus:border-gray-800'
const BTN = 'border border-gray-300 px-3 py-1 text-sm hover:border-gray-800'

/** "[本科,大专]" 或 "本科,大专" → ["本科","大专"] */
function parseList(raw?: unknown): string[] {
  if (raw == null) return []
  let s = String(raw).trim()
  if (s.startsWith('[') && s.endsWith(']')) s = s.slice(1, -1)
  return s
    .split(/[,，]/)
    .map((v) => v.trim())
    .filter(Boolean)
}

/** ["本科","大专"] → "[本科,大专]"（后端约定：多选存中文名括号列表） */
function toBracket(list: string[]): string {
  return list.length ? `[${list.join(',')}]` : ''
}

/** 配置名 → 给人看的名字：去掉 .yaml 后缀（前端不暴露文件这一层） */
function displayName(name: string): string {
  return (name || '').replace(/\.yaml$/i, '')
}

export default function BossPage() {
  const [options, setOptions] = useState<Record<string, Option[]>>({})
  const [blacklist, setBlacklist] = useState<Blacklist[]>([])
  const [form, setForm] = useState<Form>({})
  const [multi, setMulti] = useState<Dict>({})

  // 城市：逗号分隔的多值输入框
  const [city, setCity] = useState('')

  // 多城市处理方式：1 = 过滤模式（搜全国 + 按岗位城市筛），0 = 逐城市轮换
  const [cityFilterMode, setCityFilterMode] = useState(0)

  // 排除的城市/省份（逗号分隔，支持全角逗号；填省份会自动展开成该省城市）
  const [cityExclude, setCityExclude] = useState('')

  // 同一家公司不重复投递：1 = 开启
  const [skipCompany, setSkipCompany] = useState(1)

  // AI 提示词 / 我的资料
  const [ai, setAi] = useState({ introduce: '', prompt: '' })

  // 通知
  const [notify, setNotify] = useState({ hookUrl: '', botIsSend: 0 })

  // 配置：全部可选项 + 当前生效的那份
  const [profiles, setProfiles] = useState<Profile[]>([])
  const [activeProfile, setActiveProfile] = useState('')
  // 当前打开的弹窗：另存为 / 选择
  const [dialog, setDialog] = useState<'saveAs' | 'pick' | null>(null)
  const [nameDraft, setNameDraft] = useState('')
  // 「载入」用的隐藏文件选择器
  const fileRef = useRef<HTMLInputElement>(null)

  // 行业选项多，默认收起
  const [showIndustry, setShowIndustry] = useState(false)

  const [loading, setLoading] = useState(true)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  const [blType, setBlType] = useState('company')
  const [blValue, setBlValue] = useState('')

  useEffect(() => {
    void load()
  }, [])

  async function load() {
    setLoading(true)
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/boss/config`)
      // 旧页面没有检查 res.ok：接口报错时表现为「下拉框全空、且没有任何提示」
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      setOptions(data.options || {})
      setBlacklist(data.blacklist || [])
      const c: Form = data.config || {}
      setForm(c)
      setCity(parseList(c.cityCode).join(','))
      const rawFilter = String(c.cityFilterMode ?? '')
      setCityFilterMode(rawFilter === '1' || rawFilter === 'true' ? 1 : 0)
      setCityExclude(parseList(c.cityExclude).join(','))
      const rawSkip = String(c.skipDeliveredCompany ?? '')
      setSkipCompany(rawSkip === '0' || rawSkip === 'false' ? 0 : 1)
      setMulti({
        experience: parseList(c.experience),
        degree: parseList(c.degree),
        salary: parseList(c.salary),
        scale: parseList(c.scale),
        stage: parseList(c.stage),
        industry: parseList(c.industry),
      })

      // AI 配置（原来单独的 AI 配置页）
      try {
        const aiRes = await fetch(`${API_BASE}/api/ai/config`)
        if (aiRes.ok) {
          const aiData = await aiRes.json()
          setAi({
            introduce: aiData?.data?.introduce || '',
            prompt: aiData?.data?.prompt || '',
          })
        }
      } catch {
        /* AI 配置读不到不影响其它项 */
      }

      // 通知配置（原来环境配置页剩下的部分）
      try {
        const cfgRes = await fetch(`${API_BASE}/api/config`)
        if (cfgRes.ok) {
          const cfgData = await cfgRes.json()
          const d: Record<string, string> = cfgData?.data || {}
          const raw = String(d.BOT_IS_SEND ?? '').trim().toLowerCase()
          setNotify({
            hookUrl: d.HOOK_URL || '',
            botIsSend: raw === '1' || raw === 'true' ? 1 : 0,
          })
        }
      } catch {
        /* 通知配置读不到不影响其它项 */
      }

      // 配置列表（有哪些可选、当前哪份生效）
      try {
        const pfRes = await fetch(`${API_BASE}/api/boss/config-files`)
        if (pfRes.ok) {
          const pfData = await pfRes.json()
          setProfiles(pfData?.data?.files || [])
          setActiveProfile(pfData?.data?.active || '')
        }
      } catch {
        /* 配置列表读不到不影响编辑当前配置 */
      }
    } catch (e) {
      setErr(`加载配置失败：${(e as Error).message}（检查后端是否已启动）`)
    } finally {
      setLoading(false)
    }
  }

  function set(key: string, value: unknown) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  /** 配置相关的接口都返回 {success, data, message}，统一处理提示并重新拉取 */
  async function profileAction(path: string, init: RequestInit, okMsg: string): Promise<boolean> {
    setMsg('')
    setErr('')
    try {
      const res = await fetch(`${API_BASE}/api/boss/config-files${path}`, init)
      const data = await res.json().catch(() => null)
      if (!res.ok || data?.success === false) {
        throw new Error(data?.message || `HTTP ${res.status}`)
      }
      setMsg(okMsg)
      await load()
      return true
    } catch (e) {
      setErr(`操作失败：${(e as Error).message}`)
      return false
    }
  }

  const jsonInit = (body: unknown): RequestInit => ({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

  // ---------- 另存为 ----------

  function openSaveAs() {
    setNameDraft(`${displayName(activeProfile)} 副本`)
    setDialog('saveAs')
  }

  async function doSaveAs() {
    const name = nameDraft.trim()
    if (!name) return
    const done = await profileAction('/save-as', jsonInit({ name }), `已另存为「${name}」并切换过去`)
    if (done) setDialog(null)
  }

  // ---------- 选择 ----------

  function pickProfile(name: string) {
    setDialog(null)
    if (name === activeProfile) return
    void profileAction('/switch', jsonInit({ name }), `已切换到「${displayName(name)}」`)
  }

  function renameProfile(name: string) {
    const input = prompt('新的配置名称', displayName(name))
    if (!input || !input.trim()) return
    const to = input.trim()
    void profileAction('/rename', jsonInit({ from: name, to }), `已重命名为「${displayName(to)}」`)
  }

  function removeProfile(name: string) {
    if (!confirm(`删除配置「${displayName(name)}」？它的过滤规则会一起删掉，不可撤销。`)) return
    void profileAction(
      `?name=${encodeURIComponent(name)}`,
      { method: 'DELETE' },
      `已删除「${displayName(name)}」`,
    )
  }

  // ---------- 载入 ----------

  function triggerImport() {
    fileRef.current?.click()
  }

  async function onFilePicked(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    // 立刻清空，否则连续选同一个文件不会再触发 change
    e.target.value = ''
    if (!file) return
    if (!confirm(`导入「${file.name}」会覆盖当前配置「${displayName(activeProfile)}」，且不可撤销。继续？`)) {
      return
    }
    let content: string
    try {
      content = await file.text()
    } catch (readErr) {
      setErr(`读取文件失败：${(readErr as Error).message}`)
      return
    }
    await profileAction('/import', jsonInit({ content }), '已导入并覆盖当前配置')
  }

  // ---------- 保存 ----------

  async function save() {
    setMsg('')
    setErr('')
    const cityText = city.trim()
    const payload = {
      ...form,
      // 城市：多值，逗号分隔。留空按「不限」提交（空城市会让投递一个岗位都搜不到）
      cityCode: cityText || '不限',
      cityFilterMode,
      // 排除的城市/省份：空字符串 = 清空排除表（后端按"空即清空"处理）
      cityExclude: toBracket(parseList(cityExclude)),
      skipDeliveredCompany: skipCompany,
      experience: toBracket(multi.experience || []),
      degree: toBracket(multi.degree || []),
      salary: toBracket(multi.salary || []),
      scale: toBracket(multi.scale || []),
      stage: toBracket(multi.stage || []),
      industry: toBracket(multi.industry || []),
    }
    try {
      const res = await fetch(`${API_BASE}/api/boss/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (!res.ok) throw new Error(`Boss 配置 HTTP ${res.status}`)

      // AI 提示词 / 我的资料 → ai 表 + 当前配置的 ai 块
      const aiRes = await fetch(`${API_BASE}/api/ai/config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ai),
      })
      if (!aiRes.ok) throw new Error(`AI 配置 HTTP ${aiRes.status}`)

      // 通知 → config 表 + 当前配置的 notify 块
      const notifyRes = await fetch(`${API_BASE}/api/config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          HOOK_URL: notify.hookUrl,
          BOT_IS_SEND: String(notify.botIsSend ?? 0),
        }),
      })
      if (!notifyRes.ok) throw new Error(`通知配置 HTTP ${notifyRes.status}`)

      setMsg('已保存（并同步回数据库）')
      await load()
    } catch (e) {
      setErr(`保存失败：${(e as Error).message}`)
    }
  }

  async function addBlacklist() {
    const value = blValue.trim()
    if (!value) return
    try {
      const res = await fetch(`${API_BASE}/api/boss/config/blacklist`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: blType, value }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setBlValue('')
      await load()
    } catch (e) {
      setErr(`添加黑名单失败：${(e as Error).message}`)
    }
  }

  async function removeBlacklist(id: number) {
    try {
      const res = await fetch(`${API_BASE}/api/boss/config/blacklist/${id}`, { method: 'DELETE' })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      await load()
    } catch (e) {
      setErr(`删除黑名单失败：${(e as Error).message}`)
    }
  }

  if (loading) {
    return <div className="p-6 text-sm text-gray-500">加载中…</div>
  }

  const industrySelected = (multi.industry || []).length
  const activeRuleMissing = profiles.find((p) => p.active)?.hasRules === false

  return (
    <div className="max-w-4xl text-sm">
      {/* 标题 + 配置操作：右侧固定在视口顶部，页面再长也不用滚到底部找按钮 */}
      <div className="mb-5 flex items-start justify-between gap-6">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold">配置</h1>
          <p className="mt-1 text-xs text-gray-500">
            保存后写入当前配置（以文件为准）。投递记录等数据在「数据」页查看。
          </p>
        </div>

        <div className="sticky top-0 z-10 flex shrink-0 items-center gap-2 bg-white py-1">
          <span className="mr-1 text-xs text-gray-500">当前：{displayName(activeProfile) || '—'}</span>
          <button className={BTN} onClick={openSaveAs}>
            另存为
          </button>
          <button className={BTN} onClick={triggerImport}>
            载入
          </button>
          <button className={BTN} onClick={() => setDialog('pick')}>
            选择
          </button>
          <button className="border border-gray-800 bg-gray-900 px-4 py-1 text-white" onClick={() => void save()}>
            保存
          </button>
          <input
            ref={fileRef}
            type="file"
            accept=".yaml,.yml,text/yaml,application/yaml"
            className="hidden"
            onChange={(e) => void onFilePicked(e)}
          />
        </div>
      </div>

      {err && <div className="mb-4 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-red-700">{err}</div>}
      {msg && <div className="mb-4 border-l-2 border-green-600 bg-green-50 px-3 py-2 text-green-700">{msg}</div>}

      <Section title="搜索条件">
        <Row label="关键词" hint="逗号分隔，例如：数据开发,数仓,数据分析">
          <input className={INPUT} value={String(form.keywords ?? '')} onChange={(e) => set('keywords', e.target.value)} />
        </Row>

        <Row label="城市" hint="逗号分隔可写多个，例如：深圳,广州；留空 = 不限（全国）">
          <input
            className={INPUT}
            list="city-options"
            placeholder="深圳,广州"
            value={city}
            onChange={(e) => setCity(e.target.value)}
          />
          <datalist id="city-options">
            {(options.city || []).map((o) => (
              <option key={o.code} value={o.name} />
            ))}
          </datalist>
        </Row>

        <Row
          label="多城市处理"
          hint="不勾选（默认）= 逐个城市各搜一轮；勾选 = 搜索直接用全国码，再按岗位自身的城市筛（Boss 的搜索一次只认一个城市，多城市只能这样绕）"
        >
          <label className="flex cursor-pointer items-center gap-1.5 py-1">
            <input
              type="checkbox"
              className="accent-gray-800"
              checked={cityFilterMode === 1}
              onChange={(e) => setCityFilterMode(e.target.checked ? 1 : 0)}
            />
            <span className="text-gray-600">{cityFilterMode === 1 ? '过滤模式（搜全国后按城市筛）' : '轮换模式（逐城市各搜一轮）'}</span>
          </label>
        </Row>

        <Row
          label="排除城市/省份"
          hint="逗号分隔（全角逗号也行）。写省份会自动展开成该省城市，例如「广东」= 广州/深圳/东莞…；留空 = 不排除"
        >
          <input
            className={INPUT}
            placeholder="广东，东莞，上海"
            value={cityExclude}
            onChange={(e) => setCityExclude(e.target.value)}
          />
        </Row>

        <Row label="工作类型">
          <select className={INPUT} value={String(form.jobType ?? '')} onChange={(e) => set('jobType', e.target.value)}>
            <option value="">(不限)</option>
            {(options.jobType || []).map((o) => (
              <option key={o.code} value={o.name}>
                {o.name}
              </option>
            ))}
          </select>
        </Row>

        <Row label="工作经验">
          <Checks opts={options.experience} value={multi.experience} onChange={(v) => setMulti({ ...multi, experience: v })} />
        </Row>

        <Row label="学历要求">
          <Checks opts={options.degree} value={multi.degree} onChange={(v) => setMulti({ ...multi, degree: v })} />
        </Row>

        <Row label="薪资范围">
          <Checks opts={options.salary} value={multi.salary} onChange={(v) => setMulti({ ...multi, salary: v })} />
        </Row>

        <Row label="公司规模">
          <Checks opts={options.scale} value={multi.scale} onChange={(v) => setMulti({ ...multi, scale: v })} />
        </Row>

        <Row label="融资阶段">
          <Checks opts={options.stage} value={multi.stage} onChange={(v) => setMulti({ ...multi, stage: v })} />
        </Row>

        <Row label="公司行业" hint="选项很多，默认收起">
          <div className="flex flex-wrap items-center gap-3 py-1">
            <button
              type="button"
              className="shrink-0 border border-gray-400 px-2 py-0.5 text-xs hover:border-gray-800"
              onClick={() => setShowIndustry((v) => !v)}
            >
              {showIndustry ? '收起' : '展开'}
            </button>
            <span className="text-xs text-gray-500">
              {industrySelected > 0 ? `已选 ${industrySelected} 项` : '未选择（= 不限）'}
            </span>
          </div>
          {showIndustry && (
            <Checks opts={options.industry} value={multi.industry} onChange={(v) => setMulti({ ...multi, industry: v })} />
          )}
        </Row>
      </Section>

      <Section title="投递行为">
        <Row label="打招呼语" hint="AI 生成失败时用它兜底；不设会导致投递失败">
          <textarea className={INPUT} rows={4} value={String(form.sayHi ?? '')} onChange={(e) => set('sayHi', e.target.value)} />
        </Row>

        <Row label="岗位间隔(秒)" hint="每投一个岗位后停顿多久，太小容易触发风控">
          <input
            type="number"
            className={INPUT}
            value={String(form.waitTime ?? '')}
            onChange={(e) => set('waitTime', e.target.value === '' ? null : Number(e.target.value))}
          />
        </Row>

        <Row label="AI 生成招呼语">
          <Bool value={form.enableAi} onChange={(v) => set('enableAi', v)} />
        </Row>

        <Row label="同公司不重复投递" hint="某家公司只要投过一次，它名下的其它岗位都跳过（含本轮）">
          <Bool value={skipCompany} onChange={setSkipCompany} />
        </Row>

        <Row label="过滤不活跃 HR">
          <Bool value={form.filterDeadHr} onChange={(v) => set('filterDeadHr', v)} />
        </Row>

        <Row label="HR 活跃阈值(天)" hint="超过这么多天没活跃就不投；0 = 关闭细粒度判定">
          <input
            type="number"
            className={INPUT}
            value={String(form.hrActiveMaxDays ?? '')}
            onChange={(e) => set('hrActiveMaxDays', e.target.value === '' ? null : Number(e.target.value))}
          />
        </Row>

        <Row label="发送图片简历" hint="需要把简历转成 resume.jpg 放到 src/main/resources/">
          <Bool value={form.sendImgResume} onChange={(v) => set('sendImgResume', v)} />
        </Row>

        <Row label="调试模式" hint="只遍历岗位、不真的投递">
          <Bool value={form.debugger} onChange={(v) => set('debugger', v)} />
        </Row>
      </Section>

      {/* 过滤规则：编辑当前配置对应的规则（与写配置的「保存」互不影响）。
          profile 变化时组件内部会重新拉取，保证换配置后显示的是新那份。 */}
      <JdRulesSection profile={activeProfile} />

      <Section title="AI 提示词与我的资料" hint="AI 生成招呼语时用它们了解你，并决定说什么（保存进当前配置的 ai 块）">
        <Row label="我的资料" hint="技能、经验、技术栈、项目经历……写得越具体，生成的话术越贴合">
          <textarea
            className={INPUT}
            rows={8}
            placeholder="例如：5 年 Java 后端，熟悉 Spring Boot、MySQL、Kafka，做过数据平台……"
            value={ai.introduce}
            onChange={(e) => setAi({ ...ai, introduce: e.target.value })}
          />
        </Row>

        <Row label="AI 提示词" hint="模板，支持 %s 占位符（由程序插入岗位/公司等信息）">
          <textarea
            className={INPUT}
            rows={8}
            placeholder="例如：你好，我对贵司的【%s】很感兴趣，%s，方便聊聊吗？"
            value={ai.prompt}
            onChange={(e) => setAi({ ...ai, prompt: e.target.value })}
          />
        </Row>
      </Section>

      <Section title="通知">
        <Row label="企业微信 Webhook" hint="群机器人地址，用于接收投递通知">
          <input
            className={INPUT}
            placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
            value={notify.hookUrl}
            onChange={(e) => setNotify({ ...notify, hookUrl: e.target.value })}
          />
        </Row>

        <Row label="发送通知">
          <Bool value={notify.botIsSend} onChange={(v) => setNotify({ ...notify, botIsSend: v })} />
        </Row>

        <Row
          label="AI 接口（只读）"
          hint="API 地址 / API Key / 模型只能改配置文件，网页端不提供输入（避免明文泄露）；改完文件后启动或投递前会自动同步到数据库"
        >
          <div className="px-2 py-1 text-xs text-gray-500">
            见当前配置的 <code>ai.base_url</code> / <code>ai.api_key</code> / <code>ai.model</code>
          </div>
        </Row>
      </Section>

      <Section title="黑名单" hint="公司 / 岗位 / HR职位，命中任一就跳过该岗位（包含匹配）">
        <div className="flex items-center gap-2 py-2">
          <select className={INPUT + ' w-28'} value={blType} onChange={(e) => setBlType(e.target.value)}>
            <option value="company">公司</option>
            <option value="job">岗位</option>
            <option value="recruiter">HR职位</option>
          </select>
          <input
            className={INPUT}
            placeholder="填关键词"
            value={blValue}
            onChange={(e) => setBlValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void addBlacklist()
            }}
          />
          <button className="shrink-0 border border-gray-800 px-3 py-1" onClick={() => void addBlacklist()}>
            添加
          </button>
        </div>
        {blacklist.length === 0 ? (
          <div className="py-2 text-gray-500">(空)</div>
        ) : (
          <table className="w-full">
            <tbody>
              {blacklist.map((b) => (
                <tr key={b.id} className="border-b border-gray-100">
                  <td className="w-20 py-1 text-gray-500">
                    {b.type === 'company' ? '公司' : b.type === 'job' ? '岗位' : 'HR职位'}
                  </td>
                  <td className="py-1">{b.value}</td>
                  <td className="w-16 py-1 text-right">
                    <button className="text-red-600 hover:underline" onClick={() => void removeBlacklist(b.id)}>
                      删除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      {/* 底部只留「重新加载」；保存已移到右上角固定区 */}
      <div className="mt-6 flex items-center gap-3">
        <button className={BTN + ' px-5 py-1.5'} onClick={() => void load()}>
          重新加载
        </button>
        {activeRuleMissing && (
          <span className="text-xs text-gray-400">当前配置还没有过滤规则 —— 在下面的「过滤规则」里加几条即可</span>
        )}
      </div>

      {/* ---------- 弹窗：另存为 ---------- */}
      {dialog === 'saveAs' && (
        <Modal title="另存为" onClose={() => setDialog(null)}>
          <p className="mb-2 text-xs text-gray-500">
            以当前配置「{displayName(activeProfile)}」为模板复制一份，并切换过去。
          </p>
          <input
            className={INPUT}
            autoFocus
            placeholder="例如：数据开发"
            value={nameDraft}
            onChange={(e) => setNameDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void doSaveAs()
            }}
          />
          <div className="mt-3 flex justify-end gap-2">
            <button className={BTN} onClick={() => setDialog(null)}>
              取消
            </button>
            <button className="border border-gray-800 bg-gray-900 px-4 py-1 text-white" onClick={() => void doSaveAs()}>
              确定
            </button>
          </div>
        </Modal>
      )}

      {/* ---------- 弹窗：选择 ---------- */}
      {dialog === 'pick' && (
        <Modal title="选择配置" onClose={() => setDialog(null)}>
          <p className="mb-2 text-xs text-gray-500">
            点一行即切换（立即生效，不用重启）。切换后整页配置与过滤规则都换成它的。
          </p>
          <div className="max-h-72 overflow-y-auto border border-gray-200">
            {profiles.length === 0 && <div className="px-2 py-2 text-gray-500">(没有可选的配置)</div>}
            {profiles.map((p) => (
              <div
                key={p.name}
                className={
                  'flex items-center gap-2 border-b border-gray-100 px-2 py-1.5 last:border-b-0 ' +
                  (p.active ? 'bg-gray-50' : '')
                }
              >
                <button className="flex-1 text-left hover:underline" onClick={() => pickProfile(p.name)}>
                  {displayName(p.name)}
                  {p.active && <span className="ml-2 text-xs text-green-700">● 当前</span>}
                  {!p.hasRules && <span className="ml-2 text-xs text-gray-400">无过滤规则</span>}
                </button>
                <button className="shrink-0 text-xs text-gray-600 hover:underline" onClick={() => renameProfile(p.name)}>
                  改名
                </button>
                {!p.active && (
                  <button className="shrink-0 text-xs text-red-600 hover:underline" onClick={() => removeProfile(p.name)}>
                    删除
                  </button>
                )}
              </div>
            ))}
          </div>
          <div className="mt-3 flex justify-end">
            <button className={BTN} onClick={() => setDialog(null)}>
              关闭
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

/* ---------------- 极简展示组件：无圆角、无阴影 ---------------- */

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="mb-7">
      <h2 className="mb-2 border-b-2 border-gray-800 pb-1 text-sm font-semibold">{title}</h2>
      {hint && <p className="mb-1 text-xs text-gray-500">{hint}</p>}
      {children}
    </section>
  )
}

function Row({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-4 border-b border-gray-200 py-2.5">
      <div className="w-32 shrink-0 pt-1 text-gray-600">{label}</div>
      <div className="flex-1">
        {children}
        {hint && <div className="mt-1 text-xs text-gray-400">{hint}</div>}
      </div>
    </div>
  )
}

/** 多选：一排复选框（比下拉多选直观，也不需要任何圆角样式） */
function Checks({ opts, value, onChange }: { opts?: Option[]; value?: string[]; onChange: (v: string[]) => void }) {
  const selected = value || []
  if (!opts || opts.length === 0) {
    return <div className="px-2 py-1 text-gray-400">(无可用选项)</div>
  }
  return (
    <div className="flex flex-wrap gap-x-5 gap-y-1 py-1">
      {opts.map((o) => (
        <label key={o.code + o.name} className="flex cursor-pointer items-center gap-1.5">
          <input
            type="checkbox"
            className="accent-gray-800"
            checked={selected.includes(o.name)}
            onChange={(e) => onChange(e.target.checked ? [...selected, o.name] : selected.filter((v) => v !== o.name))}
          />
          <span>{o.name}</span>
        </label>
      ))}
    </div>
  )
}

/** 布尔：复选框（后端用 1/0 存） */
function Bool({ value, onChange }: { value: unknown; onChange: (v: number) => void }) {
  const on = value === 1 || value === true || value === '1'
  return (
    <label className="flex cursor-pointer items-center gap-1.5 py-1">
      <input type="checkbox" className="accent-gray-800" checked={on} onChange={(e) => onChange(e.target.checked ? 1 : 0)} />
      <span className="text-gray-600">{on ? '开启' : '关闭'}</span>
    </label>
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
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/30 pt-24" onClick={onClose}>
      <div className="w-[26rem] border border-gray-800 bg-white p-4" onClick={(e) => e.stopPropagation()}>
        <h3 className="mb-3 border-b border-gray-300 pb-1 text-sm font-semibold">{title}</h3>
        {children}
      </div>
    </div>
  )
}
