import { useEffect } from "react";
import type { TelegramFile } from "@/lib/types";
import { env } from "@/env";
import { mockFileProgress, useFileProgress } from "@/lib/ws-store";

export function useFileSpeed(file: TelegramFile) {
  const live = useFileProgress(file.uniqueId);

  useEffect(() => {
    if (!env.NEXT_PUBLIC_MOCK_DATA) return;
    const interval = setInterval(() => mockFileProgress(file.uniqueId), 100);
    return () => clearInterval(interval);
  }, [file.uniqueId]);

  const fileDownloadProgress =
    file.size > 0 ? Math.min((file.downloadedSize / file.size) * 100, 100) : 0;

  let downloadProgress = 0;
  if (file.downloadStatus === "downloading") {
    downloadProgress = live?.progress || fileDownloadProgress;
  } else if (file.downloadStatus === "paused") {
    downloadProgress = fileDownloadProgress;
  } else if (file.downloadStatus === "completed") {
    downloadProgress = 100;
  }

  return {
    downloadProgress,
    downloadSpeed: live?.speed ?? 0,
  };
}
