'use client'

import { useEffect, useState } from 'react'
import { useTheme } from 'next-themes'
import { DEFAULT_PREFS, loadPrefs, savePrefs, type Density, type Prefs } from '@/lib/prefs'

/**
 * 外观页：主题 + 少量显示项。
 *
 * 这里的东西只影响"这台浏览器怎么显示"（localStorage），
 * 不会写进 config/boss.yaml，也不会同步给后端。
 */

const PAGE_SIZES = [20, 50, 100]

export default function AppearancePage() {
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [prefs, setPrefs] = useState<Prefs>(DEFAULT_PREFS)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    setMounted(true)
    setPrefs(loadPrefs())
  }, [])

  function update(next: Partial<Prefs>) {
    const merged = { ...prefs, ...next }
    setPrefs(merged)
    // 即时生效：每页条数/密度这类偏好没必要再点一次"保存"
    savePrefs(merged)
    setSaved(true)
    window.setTimeout(() => setSaved(false), 1500)
  }

  return (
    <div className="max-w-3xl text-sm">
      <h1 className="mb-1 text-lg font-semibold">外观</h1>
      <p className="mb-5 text-xs text-gray-500">
        这里的设置只保存在当前浏览器（localStorage），不影响配置文件与投递行为。
        {saved && <span className="ml-2 text-green-700">已保存</span>}
      </p>

      <Section title="主题">
        <Row label="配色" hint="浅色 / 深色二选一（原来是站点里的渐变主题）">
          <div className="flex items-center gap-2 py-1">
            {[
              { value: 'light', label: '浅色' },
              { value: 'dark', label: '深色' },
            ].map((t) => {
              const active = mounted && theme === t.value
              return (
                <button
                  key={t.value}
                  type="button"
                  onClick={() => setTheme(t.value)}
                  className={`border px-3 py-1 ${
                    active ? 'border-gray-800 bg-gray-900 text-white' : 'border-gray-300 hover:border-gray-800'
                  }`}
                >
                  {t.label}
                </button>
              )
            })}
          </div>
        </Row>
      </Section>

      <Section title="数据显示">
        <Row label="每页条数" hint="数据页每页显示多少条投递记录">
          <div className="flex items-center gap-2 py-1">
            {PAGE_SIZES.map((n) => {
              const active = prefs.pageSize === n
              return (
                <button
                  key={n}
                  type="button"
                  onClick={() => update({ pageSize: n })}
                  className={`border px-3 py-1 ${
                    active ? 'border-gray-800 bg-gray-900 text-white' : 'border-gray-300 hover:border-gray-800'
                  }`}
                >
                  {n}
                </button>
              )
            })}
          </div>
        </Row>

        <Row label="表格密度">
          <div className="flex items-center gap-2 py-1">
            {(
              [
                { value: 'comfortable', label: '舒适' },
                { value: 'compact', label: '紧凑' },
              ] as { value: Density; label: string }[]
            ).map((d) => {
              const active = prefs.density === d.value
              return (
                <button
                  key={d.value}
                  type="button"
                  onClick={() => update({ density: d.value })}
                  className={`border px-3 py-1 ${
                    active ? 'border-gray-800 bg-gray-900 text-white' : 'border-gray-300 hover:border-gray-800'
                  }`}
                >
                  {d.label}
                </button>
              )
            })}
          </div>
        </Row>

        <Row label="恢复默认">
          <button
            type="button"
            onClick={() => update({ ...DEFAULT_PREFS })}
            className="border border-gray-300 px-3 py-1 hover:border-gray-800"
          >
            每页 {DEFAULT_PREFS.pageSize} 条 / 舒适
          </button>
        </Row>
      </Section>
    </div>
  )
}

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
