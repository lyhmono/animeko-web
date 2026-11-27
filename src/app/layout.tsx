import type { Metadata, Viewport } from 'next';
import { ThemeProvider } from 'next-themes';
import './globals.css';
import { BootGate } from '@/components/BootGate';
import { PageLayout } from '@/components/PageLayout';

export const metadata: Metadata = {
  title: { default: 'Animeko Web · 自托管追番', template: '%s · Animeko Web' },
  description: '自托管的动漫追番应用：聚合搜索、弹幕、外挂字幕、外部播放器与观看进度同步。',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: '#0b0e11',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body>
        <ThemeProvider attribute="class" defaultTheme="dark" enableSystem={false} disableTransitionOnChange>
          <BootGate>
            <PageLayout>{children}</PageLayout>
          </BootGate>
        </ThemeProvider>
      </body>
    </html>
  );
}
