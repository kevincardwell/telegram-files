import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { type Proxy, type TelegramFile } from "@/lib/types";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Stable row identity: the same file (uniqueId) can appear in several messages. */
export function fileKey(file: TelegramFile) {
  return `${file.telegramId}-${file.chatId}-${file.messageId}-${file.uniqueId}`;
}

/** The file with this key from the live list, linked to its neighbours for prev/next navigation. */
export function findWithNeighbours(
  files: TelegramFile[],
  key: string | undefined,
): TelegramFile | undefined {
  if (!key) return undefined;
  const index = files.findIndex((f) => fileKey(f) === key);
  if (index === -1) return undefined;
  return { ...files[index]!, prev: files[index - 1], next: files[index + 1] };
}

export function parseProxyString(proxyString: string): Proxy | null {
  // 匹配 mtproto 格式：mtproto://server:port?secret=xxx
  const mtprotoRegex = /^mtproto:\/\/([^:]+):(\d+)(\?secret=([^&]+))?$/i;
  const mtprotoMatch = mtprotoRegex.exec(proxyString);
  if (mtprotoMatch) {
    const server = mtprotoMatch[1] ?? "";
    const port = parseInt(mtprotoMatch[2] ?? "0", 10);
    const secret = mtprotoMatch[4] ?? "";

    return {
      name: "mtproto proxy",
      server,
      port,
      username: "",
      password: "",
      secret,
      type: "mtproto",
    };
  }

  const proxyRegex =
    /^(http|socks|socks5):\/\/(([^:]+):([^@]+)@)?([^:]+):(\d+)$/i;
  const match = proxyRegex.exec(proxyString);

  if (!match) {
    return null;
  }

  let type = match[1];
  if (type === "http") {
    type = "http";
  } else {
    type = "socks5";
  }
  const username = match[3] ?? "";
  const password = match[4] ?? "";
  const server = match[5] ?? "";
  const port = parseInt(match[6] ?? "0", 10);

  return {
    name: `${type} proxy`,
    server,
    port,
    username,
    password,
    secret: "",
    type: type as "http" | "socks5",
  };
}

export function split(separator: string, str?: string): string[] {
  if (!str || str.length === 0) {
    return [];
  }
  return str.split(separator).map((item) => item.trim());
}
