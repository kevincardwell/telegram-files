"use client";

import {
  ThemeProvider as NextThemesProvider,
  type ThemeProviderProps,
} from "next-themes";

// No "mounted" gate: next-themes sets the theme class before paint itself (with
// suppressHydrationWarning on <html>), and returning null until mount made the static export
// render an empty <body>.
export function ThemeProvider({ children, ...props }: ThemeProviderProps) {
  return <NextThemesProvider {...props}>{children}</NextThemesProvider>;
}
