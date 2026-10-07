"use client";

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import useWebSocket, { ReadyState } from "react-use-websocket";
import {
  type TelegramError,
  type WebSocketMessage,
  WebSocketMessageType,
} from "@/lib/websocket-types";
import { toast } from "./use-toast";
import { getWsUrl } from "@/lib/api";
import { useSearchParams } from "next/navigation";
import { useSWRConfig } from "swr";
import { dispatchMessage } from "@/lib/ws-store";

const WS_URL = `${getWsUrl()}`;

// Only connection-level state lives in this context; it changes rarely. Message streams are read
// through the store hooks in lib/ws-store (useFileProgress, useAccountDownloadSpeed,
// useWebSocketMessage).
interface WebsocketContextType {
  sendMessage: (message: WebSocketMessage) => void;
  connectionStatus: string;
  isReady: boolean;
  reconnect: () => void;
  telegramConnectionState: string | null;
}

const WebSocketContext = createContext<WebsocketContextType | undefined>(
  undefined,
);

interface WebSocketProviderProps {
  children: ReactNode;
}

export const WebSocketProvider: React.FC<WebSocketProviderProps> = ({
  children,
}) => {
  const searchParams = useSearchParams();
  const { mutate } = useSWRConfig();

  const [reconnectNonce, setReconnectNonce] = useState(0);
  const [telegramConnectionState, setTelegramConnectionState] = useState<
    string | null
  >(null);

  const { sendMessage, readyState } = useWebSocket(
    `${WS_URL}?telegramId=${searchParams.get("id") ?? ""}&_r=${reconnectNonce}`,
    {
      // Keep retrying (essentially) forever with exponential backoff capped at 30s, and also
      // retry on error events — so a transient outage recovers on its own instead of giving up.
      shouldReconnect: () => true,
      reconnectAttempts: 999,
      reconnectInterval: (attemptNumber) =>
        Math.min(1000 * 2 ** attemptNumber, 30000),
      retryOnError: true,
      // Never let react-use-websocket store lastMessage: it does so with flushSync, which would
      // re-render this provider (and every consumer) synchronously on each progress tick.
      filter: () => false,
      onMessage: (event: MessageEvent) => {
        let payload: WebSocketMessage;
        try {
          payload = JSON.parse(event.data as string) as WebSocketMessage;
        } catch (error) {
          console.error("Failed to parse WebSocket message:", error);
          return;
        }
        switch (payload.type) {
          case WebSocketMessageType.AUTHORIZATION:
            void mutate("/telegrams");
            break;
          case WebSocketMessageType.CONNECTION:
            setTelegramConnectionState(
              (payload.data as { state?: string })?.state ?? null,
            );
            break;
          case WebSocketMessageType.ERROR:
            toast({
              variant: "error",
              description: (payload.data as TelegramError).message,
            });
            break;
        }
        dispatchMessage(payload);
      },
    },
  );

  // Force a fresh connection (resets the backoff/attempt counter); used by the manual
  // "reconnect" affordance and when the network/focus comes back.
  const reconnect = useCallback(() => {
    setReconnectNonce((n) => n + 1);
  }, []);

  useEffect(() => {
    const maybeReconnect = () => {
      if (
        readyState !== ReadyState.OPEN &&
        readyState !== ReadyState.CONNECTING
      ) {
        setReconnectNonce((n) => n + 1);
      }
    };
    window.addEventListener("online", maybeReconnect);
    window.addEventListener("focus", maybeReconnect);
    return () => {
      window.removeEventListener("online", maybeReconnect);
      window.removeEventListener("focus", maybeReconnect);
    };
  }, [readyState]);

  const connectionStatus = {
    [ReadyState.CONNECTING]: "Connecting",
    [ReadyState.OPEN]: "Open",
    [ReadyState.CLOSING]: "Closing",
    [ReadyState.CLOSED]: "Closed",
    [ReadyState.UNINSTANTIATED]: "Uninstantiated",
  }[readyState];

  const isReady = readyState === ReadyState.OPEN;

  const sendWebSocketMessage = useCallback(
    (message: WebSocketMessage) => {
      if (isReady) {
        sendMessage(JSON.stringify(message));
      }
    },
    [isReady, sendMessage],
  );

  const value = useMemo(
    () => ({
      sendMessage: sendWebSocketMessage,
      connectionStatus,
      isReady,
      reconnect,
      telegramConnectionState,
    }),
    [
      sendWebSocketMessage,
      connectionStatus,
      isReady,
      reconnect,
      telegramConnectionState,
    ],
  );

  return (
    <WebSocketContext.Provider value={value}>
      {children}
    </WebSocketContext.Provider>
  );
};

export function useWebsocket() {
  const context = useContext(WebSocketContext);
  if (context === undefined) {
    throw new Error(
      "useTelegramWebSocket must be used within a WebSocketProvider",
    );
  }
  return context;
}
