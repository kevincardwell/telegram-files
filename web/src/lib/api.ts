import { env } from "@/env";

export function getApiUrl(): string {
  const url = env.NEXT_PUBLIC_API_URL;
  if (url.startsWith("http")) {
    return url;
  }
  if (typeof window === "undefined") {
    return url;
  }
  return `${window.location.protocol}//${window.location.host}${url}`;
}

export function getWsUrl(): string {
  const url = env.NEXT_PUBLIC_WS_URL;
  if (url.startsWith("ws")) {
    return url;
  }
  if (typeof window === "undefined") {
    return url;
  }
  return `${window.location.protocol === "https:" ? "wss" : "ws"}://${
    window.location.host
  }${url}`;
}

/* eslint-disable */
export async function request<T = any>(
  api: string,
  requestInit?: RequestInit,
): Promise<T> {
  const defaultHeaders = {
    "Content-Type": "application/json",
  };

  const response = await fetch(`${getApiUrl()}${api}`, {
    credentials: "include",
    ...requestInit,
    // After the spread, so a caller's requestInit.headers can't drop the defaults.
    headers: {
      ...defaultHeaders,
      ...requestInit?.headers,
    },
  });
  const responseText = await response.text();
  let data;
  if (responseText) {
    try {
      data = JSON.parse(responseText);
    } catch (e) {
      throw new RequestParsedError(responseText);
    }
  }
  // Check status before the empty-body early return: an empty error response must reject, not
  // resolve to undefined for callers to crash on.
  if (!response.ok) {
    throw new Error(
      data?.error ?? `Request failed with status ${response.status}`,
    );
  }

  return data as T;
}

export class RequestParsedError extends Error {
  responseText: string;

  constructor(responseText: string) {
    super("Parse JSON Error");
    this.responseText = responseText;
  }
}

export function localStorageProvider() {
  const map = new Map<string, any>(
    JSON.parse(localStorage.getItem("telegram-files") ?? "[]"),
  );

  window.addEventListener("beforeunload", () => {
    const appCache = JSON.stringify(Array.from(map.entries()));
    localStorage.setItem("telegram-files", appCache);
  });

  return map;
}

export async function POST(api: string, data?: any): Promise<any> {
  return await request(api, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export type TelegramApiArg = {
  data: any;
  method: string;
};

export async function telegramApi(
  api: string,
  {
    arg,
  }: {
    arg: TelegramApiArg;
  },
): Promise<any> {
  return await request(`${api}/${arg.method}`, {
    method: "POST",
    body: arg.data ? JSON.stringify(arg.data) : undefined,
  });
}
