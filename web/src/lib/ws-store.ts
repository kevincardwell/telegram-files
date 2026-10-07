import {
  useCallback,
  useEffect,
  useEffectEvent,
  useSyncExternalStore,
} from "react";
import type { TDFile } from "@/lib/types";
import {
  type WebSocketMessage,
  WebSocketMessageType,
} from "@/lib/websocket-types";

// High-frequency WebSocket data lives here, outside React state, so a progress tick re-renders
// only the components subscribed to that file instead of the whole page.

export type FileProgress = {
  progress: number;
  speed: number;
  downloadedSize: number;
  timestamp: number;
};

// FILE_UPDATE events are throttled server-side per account, so with several concurrent downloads a
// single file can go a few seconds between updates. Only report 0 B/s after a longer silence.
const SPEED_DECAY_MS = 5000;

const fileProgress = new Map<string, FileProgress>();
const fileListeners = new Map<string, Set<() => void>>();
const decayTimers = new Map<string, ReturnType<typeof setTimeout>>();

let account = { speed: 0, downloadedSize: 0, timestamp: 0 };
const accountListeners = new Set<() => void>();

const messageListeners = new Set<(message: WebSocketMessage) => void>();

function setFileProgress(uniqueId: string, next: FileProgress | undefined) {
  if (next) fileProgress.set(uniqueId, next);
  else fileProgress.delete(uniqueId);
  fileListeners.get(uniqueId)?.forEach((listener) => listener());
}

function onFileUpdate(file: TDFile, timestamp: number) {
  const uniqueId = file.remote?.uniqueId;
  if (!uniqueId || !file.local) return;
  const size = file.size === 0 ? file.expectedSize : file.size;
  const downloadedSize = file.local.downloadedSize;
  const prev = fileProgress.get(uniqueId);
  if (
    prev &&
    (timestamp <= prev.timestamp || downloadedSize <= prev.downloadedSize)
  ) {
    return;
  }
  const speed = prev
    ? (downloadedSize - prev.downloadedSize) /
      ((timestamp - prev.timestamp) / 1000)
    : 0;
  const progress = size > 0 ? Math.min((downloadedSize / size) * 100, 100) : 0;
  setFileProgress(uniqueId, {
    progress: Math.max(progress, prev?.progress ?? 0),
    speed,
    downloadedSize,
    timestamp,
  });

  clearTimeout(decayTimers.get(uniqueId));
  decayTimers.set(
    uniqueId,
    setTimeout(() => {
      decayTimers.delete(uniqueId);
      const current = fileProgress.get(uniqueId);
      if (current && current.speed !== 0) {
        setFileProgress(uniqueId, { ...current, speed: 0 });
      }
    }, SPEED_DECAY_MS),
  );
}

function setAccount(next: typeof account) {
  const changed = next.speed !== account.speed;
  account = next;
  if (changed) accountListeners.forEach((listener) => listener());
}

function onAccountDownload(
  data: { downloadedSize: number; totalCount: number },
  timestamp: number,
) {
  const { downloadedSize, totalCount } = data;
  if (totalCount === 0) {
    setAccount({ speed: 0, downloadedSize: 0, timestamp: 0 });
    return;
  }
  const timeDiff = (timestamp - account.timestamp) / 1000;
  if (
    account.timestamp === 0 ||
    timeDiff <= 0 ||
    downloadedSize <= account.downloadedSize
  ) {
    setAccount({ speed: account.speed, downloadedSize, timestamp });
    return;
  }
  setAccount({
    speed: (downloadedSize - account.downloadedSize) / timeDiff,
    downloadedSize,
    timestamp,
  });
}

export function dispatchMessage(message: WebSocketMessage) {
  switch (message.type) {
    case WebSocketMessageType.FILE_UPDATE:
      onFileUpdate((message.data as { file: TDFile }).file, message.timestamp);
      return;
    case WebSocketMessageType.FILE_DOWNLOAD:
      onAccountDownload(
        message.data as { downloadedSize: number; totalCount: number },
        message.timestamp,
      );
      return;
    case WebSocketMessageType.FILE_STATUS: {
      // Any status transition starts a fresh progress session (cancel/restart must not keep the
      // old high-water mark).
      const { uniqueId } = message.data as { uniqueId?: string };
      if (uniqueId && fileProgress.has(uniqueId)) {
        setFileProgress(uniqueId, undefined);
      }
      break;
    }
  }
  messageListeners.forEach((listener) => listener(message));
}

export function mockFileProgress(uniqueId: string) {
  const prev = fileProgress.get(uniqueId);
  setFileProgress(uniqueId, {
    progress: Math.min((prev?.progress ?? 0) + Math.random() * 10, 100),
    speed: Math.random() * 1024 * 1024 * 10,
    downloadedSize: 0,
    timestamp: 0,
  });
}

export function useFileProgress(uniqueId: string): FileProgress | undefined {
  const subscribe = useCallback(
    (listener: () => void) => {
      let listeners = fileListeners.get(uniqueId);
      if (!listeners) fileListeners.set(uniqueId, (listeners = new Set()));
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
        if (listeners.size === 0) fileListeners.delete(uniqueId);
      };
    },
    [uniqueId],
  );
  return useSyncExternalStore(
    subscribe,
    () => fileProgress.get(uniqueId),
    () => undefined,
  );
}

const subscribeAccount = (listener: () => void) => {
  accountListeners.add(listener);
  return () => accountListeners.delete(listener);
};

export function useAccountDownloadSpeed() {
  return useSyncExternalStore(
    subscribeAccount,
    () => account.speed,
    () => 0,
  );
}

/** Low-frequency messages (status, auth, method results, errors). FILE_UPDATE / FILE_DOWNLOAD
 * ticks are not delivered here; read them with useFileProgress / useAccountDownloadSpeed. */
export function useWebSocketMessage(
  handler: (message: WebSocketMessage) => void,
) {
  const onMessage = useEffectEvent(handler);
  useEffect(() => {
    const listener = (message: WebSocketMessage) => onMessage(message);
    messageListeners.add(listener);
    return () => {
      messageListeners.delete(listener);
    };
  }, []);
}
