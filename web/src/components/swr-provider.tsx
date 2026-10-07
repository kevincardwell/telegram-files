"use client";
import { SWRConfig } from "swr";
import React from "react";
import { useToast } from "@/hooks/use-toast";
import { request, RequestParsedError } from "@/lib/api";

export const SWRProvider = ({ children }: { children: React.ReactNode }) => {
  const { toast } = useToast();
  return (
    <SWRConfig
      value={{
        // provider: localStorageProvider,
        refreshInterval: 0,
        errorRetryCount: 1,
        fetcher: request,
        onError: (err: Error, key: string) => {
          // Server text is rendered as plain text only (tags stripped), never as HTML.
          const message =
            err instanceof RequestParsedError
              ? err.responseText
                  .replace(/<[^>]*>/g, " ")
                  .replace(/\s+/g, " ")
                  .trim()
              : err.message;

          const displayKey = key.startsWith("http")
            ? new URL(key).pathname
            : key;

          toast({
            variant: "error",
            title: "Request Failed",
            description: (
              <div className="space-y-2">
                <div className="line-clamp-2 break-all text-xs text-muted-foreground">
                  <strong className="text-foreground">Key:</strong> {displayKey}
                </div>
                <div className="line-clamp-3 text-wrap">{message}</div>
              </div>
            ),
          });
        },
      }}
    >
      {children}
    </SWRConfig>
  );
};
