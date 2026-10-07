import "@/styles/globals.css";
import type { Metadata } from "next";
import { Inter } from "next/font/google";
import React, { Suspense } from "react";
import { Toaster } from "@/components/ui/toaster";
import { SWRProvider } from "@/components/swr-provider";
import { SettingsProvider } from "@/hooks/use-settings";
import { WebSocketProvider } from "@/hooks/use-websocket";
import { env } from "@/env";
import { TelegramAccountProvider } from "@/hooks/use-telegram-account";
import { ThemeProvider } from "@/components/theme-provider";
import { LocalStorageProvider } from "@/hooks/use-local-storage";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Telegram Files",
  description: "Manage your files on Telegram",
};

function AppSkeleton() {
  return (
    <div className="container mx-auto animate-pulse px-4 py-6" aria-busy="true">
      <div className="mb-6 h-[74px] rounded-xl border bg-muted/50" />
      <div className="h-[calc(100vh-13rem)] rounded-md border bg-muted/30" />
    </div>
  );
}

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <link
          rel="icon"
          type="image/png"
          href="/favicon-96x96.png"
          sizes="96x96"
        />
        <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
        <link rel="shortcut icon" href="/favicon.ico" />
        <link
          rel="apple-touch-icon"
          sizes="180x180"
          href="/apple-touch-icon.png"
        />
        <meta name="apple-mobile-web-app-title" content="TeleFiles" />
        <link rel="manifest" href="/site.webmanifest" />
        {env.NEXT_PUBLIC_SCAN && (
          <script
            src="https://unpkg.com/react-scan/dist/auto.global.js"
            async
          />
        )}
      </head>
      <body className={inter.className}>
        <LocalStorageProvider>
          <ThemeProvider
            attribute="class"
            defaultTheme="light"
            disableTransitionOnChange
          >
            <SWRProvider>
              {/* The providers read useSearchParams, which bails out of static rendering up
                  to the nearest Suspense boundary; without one the exported HTML is empty. */}
              <Suspense fallback={<AppSkeleton />}>
                <WebSocketProvider>
                  <SettingsProvider>
                    <TelegramAccountProvider>{children}</TelegramAccountProvider>
                  </SettingsProvider>
                </WebSocketProvider>
              </Suspense>
            </SWRProvider>
            <Toaster />
          </ThemeProvider>
        </LocalStorageProvider>
      </body>
    </html>
  );
}
