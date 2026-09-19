'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useEffect, useState } from 'react'
import { useTheme } from 'next-themes'
import { API_BASE } from '@/lib/api'

/**
 * 顶部工具栏（取代原来的左侧栏）。
 *
 * 这个副本只有一个平台、页面也只有三块：配置 / 数据 / 外观，
 * 横向三个按钮就够了，不用再让侧栏吃掉 256px 的宽度。
 * 样式刻意不用渐变/阴影：白底 + 一条下边框（原来是 blue→indigo→purple 渐变）。
 */

const TABS = [
  { href: '/deliver', label: '投递' },
  { href: '/boss', label: '配置' },
  { href: '/data', label: '数据' },
  { href: '/appearance', label: '外观' },
]

export default function Toolbar() {
  const pathname = usePathname()
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [health, setHealth] = useState<'up' | 'degraded' | 'down' | 'unknown'>('unknown')

  useEffect(() => {
    setMounted(true)
  }, [])

  useEffect(() => {
    let timer: NodeJS.Timeout | null = null
    let checking = false

    const check = async () => {
      if (checking) return
      checking = true
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 3000)
      try {
        // 先试自定义健康接口，404 再退回 Spring Boot Actuator
        let res = await fetch(`${API_BASE}/api/health`, { signal: controller.signal })
        if (res.status === 404) {
          res = await fetch(`${API_BASE}/actuator/health`, { signal: controller.signal })
        }
        if (!res.ok) throw new Error(`status ${res.status}`)
        const data = await res.json()
        const raw = (data.status || data.state || '').toString().toUpperCase()
        setHealth(raw === 'UP' || raw === 'HEALTHY' ? 'up' : raw === 'DEGRADED' ? 'degraded' : 'down')
      } catch {
        setHealth('unknown')
      } finally {
        clearTimeout(timeout)
        checking = false
      }
    }

    void check()
    timer = setInterval(check, 30000)
    return () => {
      if (timer) clearInterval(timer)
    }
  }, [])

  const healthText =
    health === 'up' ? '服务正常' : health === 'degraded' ? '服务降级' : health === 'down' ? '服务异常' : '未连接'
  const healthColor =
    health === 'up' ? 'bg-green-500' : health === 'degraded' ? 'bg-yellow-500' : health === 'down' ? 'bg-red-500' : 'bg-gray-400'

  return (
    <header className="sticky top-0 z-50 border-b border-gray-200 bg-white dark:border-gray-800 dark:bg-blacksection">
      <div className="mx-auto flex w-full max-w-[1600px] items-center gap-6 px-6 py-3">
        <Link href="/boss" className="flex items-center gap-2 text-base font-semibold">
          <span>🍀</span>
          <span>Get Jobs</span>
        </Link>

        <nav className="flex items-center gap-1">
          {TABS.map((t) => {
            const active = pathname === t.href || (t.href === '/boss' && pathname === '/')
            return (
              <Link
                key={t.href}
                href={t.href}
                className={`border px-3 py-1 text-sm ${
                  active
                    ? 'border-gray-800 bg-gray-900 text-white'
                    : 'border-transparent text-gray-600 hover:border-gray-300 hover:text-gray-900 dark:text-gray-300 dark:hover:border-gray-600 dark:hover:text-gray-100'
                }`}
              >
                {t.label}
              </Link>
            )
          })}
        </nav>

        <div className="ml-auto flex items-center gap-4 text-xs text-gray-500">
          <span className="flex items-center gap-1.5">
            <span className={`h-2 w-2 rounded-full ${healthColor}`} />
            {healthText}
          </span>
          {mounted && (
            <button
              type="button"
              onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
              className="border border-gray-300 px-2 py-1 hover:border-gray-800 dark:border-gray-700 dark:hover:border-gray-400"
            >
              {theme === 'dark' ? '浅色' : '深色'}
            </button>
          )}
        </div>
      </div>
    </header>
  )
}
