import { useCallback, useEffect, useMemo, useState } from "react";
import {
  type DownloadStatus,
  type FileFilter,
  type TelegramFile,
  type Thumbnail,
  type TransferStatus,
} from "@/lib/types";
import useSWRInfinite from "swr/infinite";
import { WebSocketMessageType } from "@/lib/websocket-types";
import { useLocalStorage } from "@/hooks/use-local-storage";
import { useDebounce } from "use-debounce";
import { useWebSocketMessage } from "@/lib/ws-store";

const DEFAULT_FILTERS: FileFilter = {
  search: "",
  type: "media",
  downloadStatus: undefined,
  transferStatus: undefined,
  offline: false,
  tags: [],
};

const PAGE_SIZE = 50;
/** Start fetching the next page when the last rendered row is this close to the end. */
export const PREFETCH_ROWS = 20;

type FileResponse = {
  files: TelegramFile[];
  count: number;
  nextFromMessageId: number;
};

type FileStatusOverride = {
  fileId: number;
  downloadStatus: DownloadStatus;
  localPath?: string;
  completionDate?: number;
  downloadedSize: number;
  transferStatus?: TransferStatus;
  thumbnailFile?: Thumbnail;
  removed?: boolean;
};

// Merged rows are cached per (server file, override) pair so a status event only creates new
// objects for the file it is about; every other row keeps its identity and memoised rows skip
// re-rendering.
const mergedRows = new WeakMap<
  FileStatusOverride,
  { file: TelegramFile; merged: TelegramFile }
>();

function patchThumbnail(
  pages: FileResponse[],
  thumbnailUniqueId: string,
  thumbnailFile: Thumbnail,
): FileResponse[] {
  let changed = false;
  const patched = pages.map((page) => {
    if (!page.files.some((f) => f.thumbnailUniqueId === thumbnailUniqueId)) {
      return page;
    }
    changed = true;
    return {
      ...page,
      files: page.files.map((f) =>
        f.thumbnailUniqueId === thumbnailUniqueId ? { ...f, thumbnailFile } : f,
      ),
    };
  });
  return changed ? patched : pages;
}

function mergeOverride(
  file: TelegramFile,
  override: FileStatusOverride | undefined,
): TelegramFile {
  if (!override) return file;
  const hit = mergedRows.get(override);
  if (hit?.file === file) return hit.merged;
  const merged = {
    ...file,
    id: override.fileId ?? file.id,
    downloadStatus: override.downloadStatus ?? file.downloadStatus,
    localPath: override.localPath ?? file.localPath,
    completionDate: override.completionDate ?? file.completionDate,
    downloadedSize: override.downloadedSize ?? file.downloadedSize,
    transferStatus: override.transferStatus ?? file.transferStatus,
    thumbnailFile: override.thumbnailFile ?? file.thumbnailFile,
  };
  mergedRows.set(override, { file, merged });
  return merged;
}

export function useFiles(
  accountId: string,
  chatId: string,
  messageThreadId?: number,
  link?: string,
) {
  const noAccountSpecified = accountId === "-1" && chatId === "-1";
  const url = noAccountSpecified
    ? "/files"
    : `/telegram/${accountId}/chat/${chatId}/files`;
  const [latestFilesStatus, setLatestFileStatus] = useState<
    Record<string, FileStatusOverride>
  >({});
  const [filters, setFilters, clearFilters] = useLocalStorage<FileFilter>(
    "telegramFileListFilter",
    { ...DEFAULT_FILTERS, offline: noAccountSpecified },
  );
  const getKey = (page: number, previousPageData: FileResponse) => {
    const params = new URLSearchParams({
      limit: PAGE_SIZE.toString(),
      ...(filters.search && {
        search: window.encodeURIComponent(filters.search),
      }),
      ...(filters.type && { type: filters.type }),
      ...(filters.downloadStatus && { downloadStatus: filters.downloadStatus }),
      ...(filters.transferStatus && { transferStatus: filters.transferStatus }),
      ...(filters.offline && { offline: "true" }),
      ...(filters.tags.length > 0 && {
        tags: filters.tags.join(","),
      }),
      ...(messageThreadId && { messageThreadId: messageThreadId.toString() }),
      ...(link && { link: window.encodeURIComponent(link) }),
      ...(filters.dateType && { dateType: filters.dateType }),
      ...(filters.dateRange && { dateRange: filters.dateRange.join(",") }),
      ...(filters.sizeRange && { sizeRange: filters.sizeRange.join(",") }),
      ...(filters.sizeUnit && { sizeUnit: filters.sizeUnit }),
      ...(filters.sort && { sort: filters.sort }),
      ...(filters.order && { order: filters.order }),
    });

    if (page === 0) {
      return `${url}?${params.toString()}`;
    }

    if (!previousPageData) {
      return null;
    }

    params.set("fromMessageId", previousPageData.nextFromMessageId.toString());
    if (filters.offline && previousPageData.files.length > 0) {
      const lastFile =
        previousPageData.files[previousPageData.files.length - 1];
      if (filters.sort === "size") {
        params.set("fromSortField", lastFile!.size.toString());
      } else if (filters.sort === "completion_date") {
        params.set("fromSortField", lastFile!.completionDate.toString());
      } else if (filters.sort === "date") {
        params.set("fromSortField", lastFile!.date.toString());
      } else if (filters.sort === "reaction_count") {
        params.set("fromSortField", lastFile!.reactionCount.toString());
      }
    }
    return `${url}?${params.toString()}`;
  };

  const {
    data: pages,
    isLoading,
    isValidating,
    size,
    setSize,
    error,
    mutate,
  } = useSWRInfinite<FileResponse, Error>(getKey, {
    revalidateFirstPage: false,
    keepPreviousData: true,
  });

  const [debounceLoading] = useDebounce(isLoading || isValidating, 500, {
    leading: true,
    maxWait: 1000,
  });

  useWebSocketMessage((message) => {
    if (message.type !== WebSocketMessageType.FILE_STATUS) {
      return;
    }
    const data = message.data as {
      fileId: number;
      uniqueId: string;
      downloadStatus: DownloadStatus;
      localPath: string;
      completionDate: number;
      downloadedSize: number;
      transferStatus?: TransferStatus;
      thumbnailFile?: Thumbnail;
      removed?: boolean;
      type?: string;
    };

    if (data.type === "thumbnail") {
      // A thumbnail finished downloading: patch it into the loaded files that reference it.
      // Backends that don't send thumbnailFile are ignored rather than refetching every page.
      const thumbnailFile = data.thumbnailFile;
      if (thumbnailFile) {
        void mutate(
          (pages) => pages && patchThumbnail(pages, data.uniqueId, thumbnailFile),
          { revalidate: false },
        );
      }
      return;
    }

    if (data.removed) {
      setLatestFileStatus((prev) => ({
        ...prev,
        [data.uniqueId]: {
          fileId: data.fileId,
          downloadStatus: "idle",
          localPath: undefined,
          completionDate: undefined,
          downloadedSize: 0,
          transferStatus: "idle",
          removed: true,
        },
      }));
      return;
    }

    setLatestFileStatus((prev) => ({
      ...prev,
      [data.uniqueId]: {
        fileId: data.fileId,
        downloadStatus:
          data.downloadStatus ?? prev[data.uniqueId]?.downloadStatus,
        localPath: data.localPath ?? prev[data.uniqueId]?.localPath,
        completionDate:
          data.completionDate ?? prev[data.uniqueId]?.completionDate,
        downloadedSize:
          data.downloadedSize ?? prev[data.uniqueId]?.downloadedSize,
        transferStatus:
          data.transferStatus ?? prev[data.uniqueId]?.transferStatus,
        thumbnailFile: data.thumbnailFile ?? prev[data.uniqueId]?.thumbnailFile,
      },
    }));
  });

  useEffect(() => {
    if (noAccountSpecified && !filters.offline) {
      setFilters((prev) => ({
        ...prev,
        offline: true,
      }));
    }
  }, [filters.offline, noAccountSpecified, setFilters]);

  const files = useMemo(() => {
    if (!pages) return [];
    const files: TelegramFile[] = [];
    pages.forEach((page) => {
      page.files.forEach((file) => {
        if (file.originalDeleted && latestFilesStatus[file.uniqueId]?.removed) {
          return;
        }
        const merged = mergeOverride(file, latestFilesStatus[file.uniqueId]);
        // Live WebSocket updates can change a row's status after it was fetched. When a status
        // filter is active, drop rows that no longer match so the filtered view stays consistent
        // (otherwise e.g. a "downloading" filter keeps showing files that just completed).
        if (
          filters.downloadStatus &&
          merged.downloadStatus !== filters.downloadStatus
        ) {
          return;
        }
        if (
          filters.transferStatus &&
          merged.transferStatus !== filters.transferStatus
        ) {
          return;
        }
        files.push(merged);
      });
    });
    return files;
  }, [
    pages,
    latestFilesStatus,
    filters.downloadStatus,
    filters.transferStatus,
  ]);

  const hasMore = useMemo(() => {
    if (!pages || pages.length === 0) return true;

    const fetchedCount = pages.reduce((acc, d) => acc + d.files.length, 0);
    const lastPage = pages[pages.length - 1];
    let hasMore = false;
    if (lastPage) {
      const count = lastPage.count;
      hasMore = count > fetchedCount && lastPage.nextFromMessageId !== 0;
    }
    return hasMore;
  }, [pages]);

  const handleLoadMore = async () => {
    if (isLoading || isValidating || !hasMore || error) return;
    await setSize(size + 1);
  };

  const handleFilterChange = async (newFilters: FileFilter) => {
    if (
      Object.keys(newFilters).every(
        (key) =>
          newFilters[key as keyof FileFilter] ===
          filters[key as keyof FileFilter],
      )
    ) {
      return;
    }
    setFilters(newFilters);
    await setSize(1);
  };

  const updateField = useCallback(
    async (uniqueId: string, patch: Partial<TelegramFile>) => {
      await mutate((pages) => {
        if (!pages) return [];

        return pages.map((page) => {
          const newFiles = page.files.map((file) =>
            file.uniqueId === uniqueId ? { ...file, ...patch } : file,
          );
          return {
            ...page,
            files: newFiles,
          };
        });
      }, false);
    },
    [mutate],
  );

  return {
    size,
    files,
    filters,
    isLoading: debounceLoading,
    updateField,
    handleFilterChange,
    clearFilters,
    handleLoadMore,
    hasMore,
  };
}
