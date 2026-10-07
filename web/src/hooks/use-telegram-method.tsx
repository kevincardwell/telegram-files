import { useCallback, useRef, useState } from "react";
import useSWRMutation from "swr/mutation";
import type { TelegramApiResult } from "@/lib/types";
import { telegramApi, type TelegramApiArg } from "@/lib/api";
import { useWebSocketMessage } from "@/lib/ws-store";

export function useTelegramMethod() {
  const pendingRequestsRef = useRef<
    Map<
      string,
      {
        resolve: (value: any) => void;
        reject: (reason?: any) => void;
      }
    >
  >(new Map());

  const lastResultRef = useRef<{
    code: string | null;
    result: unknown;
  }>({ code: null, result: null });

  const [pendingCount, setPendingCount] = useState(0); // 用 state 追踪 ref 的 size

  // A ref, not state: every file row uses this hook, and method results must not re-render them.
  useWebSocketMessage((message) => {
    if (!message.code) return;
    const { code, data } = message;
    lastResultRef.current = { code, result: data };

    const pendingRequest = pendingRequestsRef.current.get(code);
    if (pendingRequest) {
      pendingRequest.resolve(data);
      pendingRequestsRef.current.delete(code);
      setPendingCount(pendingRequestsRef.current.size);
    }
  });

  const { trigger, isMutating } = useSWRMutation<
    TelegramApiResult,
    Error,
    string,
    TelegramApiArg
  >("/telegram/api", telegramApi);

  const executeMethod = useCallback(
    async (arg: TelegramApiArg): Promise<any> => {
      const result = await trigger(arg);
      const { code } = result;

      if (lastResultRef.current.code === code) {
        return lastResultRef.current.result;
      }

      return new Promise((resolve, reject) => {
        const timeoutId = setTimeout(() => {
          pendingRequestsRef.current.delete(code);
          setPendingCount(pendingRequestsRef.current.size); // 更新 state
          reject(new Error(`Request timeout for code: ${code}`));
        }, 30000);

        pendingRequestsRef.current.set(code, {
          resolve: (value) => {
            clearTimeout(timeoutId);
            pendingRequestsRef.current.delete(code);
            setPendingCount(pendingRequestsRef.current.size); // 更新 state
            resolve(value);
          },
          reject: (reason) => {
            clearTimeout(timeoutId);
            pendingRequestsRef.current.delete(code);
            setPendingCount(pendingRequestsRef.current.size); // 更新 state
            reject(reason instanceof Error ? reason : new Error(String(reason)));
          },
        });

        setPendingCount(pendingRequestsRef.current.size); // 更新 state
      });
    },
    [trigger],
  );

  const isMethodExecuting = isMutating || pendingCount > 0;

  return {
    executeMethod,
    triggerMethod: trigger,
    isMethodExecuting,
    pendingRequestsCount: pendingCount,
  };
}
