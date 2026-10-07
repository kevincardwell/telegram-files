import { LoaderPinwheel } from "lucide-react";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { PREFETCH_ROWS, useFiles } from "@/hooks/use-files";
import { useWindowVirtualizer } from "@tanstack/react-virtual";
import { FileCard } from "@/components/mobile/file-card";
import FileDrawer from "@/components/mobile/file-drawer";
import type { TelegramFile } from "@/lib/types";
import { fileKey, findWithNeighbours } from "@/lib/utils";
import FileFilters from "@/components/file-filters";
import DraggableElement from "@/components/ui/draggable-element";
import { useLocalStorage } from "@/hooks/use-local-storage";
import FileNotFount from "@/components/file-not-found";
import { MobileFileTagsDrawer } from "@/components/file-tags";

interface FileListProps {
  accountId: string;
  chatId: string;
  link?: string;
}

export default function FileList({ accountId, chatId, link }: FileListProps) {
  const useFilesProps = useFiles(accountId, chatId, undefined, link);
  const [viewFile, setViewFile] = useState<TelegramFile>();
  const [currentTagsFile, setCurrentTagsFile] = useState<
    TelegramFile | undefined
  >();
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [isTagsDrawerOpen, setIsTagsDrawerOpen] = useState(false);
  const [layout] = useLocalStorage<"detailed" | "gallery">(
    "telegramFileLayout",
    "detailed",
  );

  const {
    filters,
    updateField,
    handleFilterChange,
    clearFilters,
    isLoading,
    size,
    files,
    hasMore,
    handleLoadMore,
  } = useFilesProps;

  const handleFileClick = useCallback((file: TelegramFile) => {
    setViewFile(file);
    setIsDrawerOpen(true);
  }, []);

  const handleFileTagsClick = useCallback((file: TelegramFile) => {
    setCurrentTagsFile(file);
    setIsTagsDrawerOpen(true);
  }, []);

  const rowVirtual = useWindowVirtualizer({
    count: hasMore ? files.length + 1 : files.length,
    estimateSize: (index) => {
      const file = files[index];
      if (!file) {
        return 90;
      }
      if (layout === "detailed") {
        return 90;
      }
      return !file.thumbnail ? 116 : 340;
    },
    overscan: 8,
    scrollMargin: 0,
    gap: 10,
  });

  useEffect(() => {
    rowVirtual.measure();
  }, [layout, rowVirtual]);

  useEffect(() => {
    const [lastItem] = [...rowVirtual.getVirtualItems()].reverse();
    if (!lastItem) {
      return;
    }

    if (
      lastItem.index >= files.length - 1 - PREFETCH_ROWS &&
      hasMore &&
      !isLoading
    ) {
      void handleLoadMore();
    }
  }, [files.length, handleLoadMore, hasMore, isLoading, rowVirtual]);

  // Read the viewed file from the live list each render; if it drops out of the list while the
  // drawer is open (e.g. a status filter), keep showing the last snapshot instead of closing.
  const currentViewFile = useMemo(
    () =>
      findWithNeighbours(files, viewFile && fileKey(viewFile)) ??
      (isDrawerOpen ? viewFile : undefined),
    [files, viewFile, isDrawerOpen],
  );

  return (
    <div className="space-y-4">
      {!link && (
        <DraggableElement>
          <FileFilters
            telegramId={accountId}
            chatId={chatId}
            filters={filters}
            onFiltersChange={handleFilterChange}
            clearFilters={clearFilters}
          />
        </DraggableElement>
      )}
      {currentViewFile && (
        <FileDrawer
          open={isDrawerOpen}
          onOpenChange={setIsDrawerOpen}
          file={currentViewFile}
          onFileChange={setViewFile}
          onFileTagsClick={(file) => {
            setCurrentTagsFile(file);
            setIsTagsDrawerOpen(true);
          }}
          {...useFilesProps}
        />
      )}
      {currentTagsFile && (
        <MobileFileTagsDrawer
          file={currentTagsFile}
          onTagsUpdate={(tags) => {
            void updateField(currentTagsFile.uniqueId, {
              tags: tags.join(","),
            });
          }}
          open={isTagsDrawerOpen}
          onOpenChange={setIsTagsDrawerOpen}
        />
      )}
      <div
        style={{
          height: `${rowVirtual.getTotalSize()}px`,
          width: "100%",
          position: "relative",
        }}
      >
        {size === 1 && isLoading && (
          <div className="fixed left-0 top-0 flex h-full w-full items-center justify-center">
            <LoaderPinwheel
              className="h-8 w-8 animate-spin"
              style={{ strokeWidth: "0.8px" }}
            />
          </div>
        )}
        {!isLoading && files.length === 0 && <FileNotFount />}
        {files.length !== 0 &&
          rowVirtual.getVirtualItems().map((virtualRow) => {
            const isLoaderRow = virtualRow.index > files.length - 1;
            const file = files[virtualRow.index]!;
            if (isLoaderRow) {
              return (
                <div
                  className="absolute left-0 top-0 flex w-full items-center justify-center"
                  style={{
                    height: `${virtualRow.size}px`,
                    transform: `translateY(${virtualRow.start}px)`,
                  }}
                  key="loader"
                >
                  {hasMore ? (
                    <LoaderPinwheel
                      className="h-8 w-8 animate-spin"
                      style={{ strokeWidth: "0.8px" }}
                    />
                  ) : (
                    <p className="text-muted-foreground">No more files</p>
                  )}
                </div>
              );
            }
            return (
              <FileCard
                key={fileKey(file)}
                index={virtualRow.index}
                start={virtualRow.start}
                size={virtualRow.size}
                ref={rowVirtual.measureElement}
                file={file}
                onFileClick={handleFileClick}
                onFileTagsClick={handleFileTagsClick}
                layout={layout}
              />
            );
          })}
      </div>
    </div>
  );
}
