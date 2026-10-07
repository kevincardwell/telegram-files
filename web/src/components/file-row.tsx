import { cn } from "@/lib/utils";
import { type RowHeight } from "@/components/table-row-height-switch";
import { Checkbox } from "@/components/ui/checkbox";
import React, { memo, type ReactNode, useState } from "react";
import { type TelegramFile } from "@/lib/types";
import prettyBytes from "pretty-bytes";
import FileStatus from "@/components/file-status";
import FileExtra from "@/components/file-extra";
import FileControl from "@/components/file-control";
import { type Column } from "./table-column-filter";
import { useFileSpeed } from "@/hooks/use-file-speed";
import { Progress } from "@/components/ui/progress";
import FileImage from "./file-image";
import FileTags from "@/components/file-tags";
import { Badge } from "@/components/ui/badge";
import { TooltipWrapper } from "@/components/ui/tooltip";

export type FileRowProperties = {
  rowHeight: RowHeight;
  dynamicClass: {
    content: string;
    contentCell: string;
  };
  columns: Column[];
};

// Every prop must be referentially stable (primitives, memoised objects, stable callbacks taking
// the file as an argument) so memo() can skip rows that did not change.
type FileRowProps = {
  index: number;
  start: number;
  size: number;
  ref?: React.Ref<HTMLDivElement>;
  file: TelegramFile;
  checked: boolean;
  updateField: (
    uniqueId: string,
    patch: Partial<TelegramFile>,
  ) => Promise<void>;
  properties: FileRowProperties;
  onSelect: (fileId: number) => void;
  onFileClick: (file: TelegramFile) => void;
};

function FileRow({
  index,
  start,
  size,
  ref,
  file,
  checked,
  updateField,
  properties,
  onSelect,
  onFileClick,
}: FileRowProps) {
  const { rowHeight, dynamicClass, columns } = properties;
  const { downloadProgress, downloadSpeed } = useFileSpeed(file);
  const [hovered, setHovered] = useState(false);

  const columnRenders: Record<string, ReactNode> = {
    content: (
      <div
        className="relative flex cursor-pointer items-center justify-center gap-2"
        onClick={() => onFileClick(file)}
      >
        {file.reactionCount > 0 && (
          <TooltipWrapper content="Reaction Count">
            <Badge className="absolute -left-1 -top-1.5 z-10 flex h-6 w-6 items-center justify-center overflow-hidden rounded-full bg-blue-500 text-sm hover:bg-blue-600">
              {file.reactionCount}
            </Badge>
          </TooltipWrapper>
        )}
        <FileImage file={file} className={dynamicClass.content} />
      </div>
    ),
    type: (
      <div className="flex flex-col items-center">
        <span className="text-sm capitalize">{file.type}</span>
        {process.env.NODE_ENV === "development" && (
          <div className="flex flex-col items-center text-xs">
            <span className="text-xs">{file.id}</span>
            <span className="text-xs">{file.messageId}</span>
          </div>
        )}
      </div>
    ),
    size: <span className="text-sm">{prettyBytes(file.size)}</span>,
    status: <FileStatus file={file} />,
    tags: (
      <FileTags
        file={file}
        onTagsUpdate={(tags) =>
          updateField(file.uniqueId, { tags: tags.join(",") })
        }
      />
    ),
    extra: <FileExtra file={file} rowHeight={rowHeight} />,
    actions: (
      <FileControl
        file={file}
        downloadSpeed={downloadSpeed}
        hovered={hovered}
      />
    ),
  };

  return (
    <div
      data-index={index}
      className="absolute left-0 top-0 flex w-full flex-col items-center border-b"
      style={{ height: `${size}px`, transform: `translateY(${start}px)` }}
      ref={ref}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div className="flex w-full flex-1 items-center hover:bg-accent">
        <div className="w-[30px] text-center">
          <Checkbox checked={checked} onCheckedChange={() => onSelect(file.id)} />
        </div>
        {columns.map((col) =>
          col.isVisible ? (
            <div
              key={col.id}
              className={cn(
                col.className ?? "",
                col.id === "content" ? dynamicClass.contentCell : "",
              )}
            >
              {columnRenders[col.id]}
            </div>
          ) : null,
        )}
      </div>
      {downloadProgress > 0 && downloadProgress !== 100 && (
        <div className="flex w-full items-end justify-between gap-2">
          <Progress
            value={downloadProgress}
            className="flex-1 rounded-none md:w-32"
          />
        </div>
      )}
    </div>
  );
}

export default memo(FileRow);
