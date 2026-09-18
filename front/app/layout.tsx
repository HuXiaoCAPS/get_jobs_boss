"use client";

import type { Metadata } from "next";
import "./globals.css";
import Toolbar from "./components/Toolbar";
import { ThemeProvider } from "next-themes";

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <title>Get Jobs - 配置管理中心</title>
        <meta name="description" content="Boss 直聘投递工具的配置中心：搜索条件、AI 提示词、通知与投递数据" />
        <link
          rel="icon"
          href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><text y=%22.9em%22 font-size=%2290%22>🍀</text></svg>"
          type="image/svg+xml"
        />
      </head>
      <body suppressHydrationWarning className="bg-white text-gray-900 dark:bg-blacksection dark:text-gray-100">
        <ThemeProvider
          attribute="class"
          defaultTheme="light"
          enableSystem={false}
        >
          <div className="min-h-screen">
            <Toolbar />
            <main className="mx-auto w-full max-w-6xl px-6 py-6">{children}</main>
          </div>
        </ThemeProvider>
      </body>
    </html>
  );
}
