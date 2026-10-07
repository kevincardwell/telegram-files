import { type TelegramFile } from "@/lib/types";
import React, { useEffect } from "react";
import { Dialog, DialogOverlay, DialogPortal, DialogTitle } from "./ui/dialog";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { cn } from "@/lib/utils";
import dynamic from "next/dynamic";
import {
  ChevronLeft,
  ChevronRight,
  CircleX,
  LoaderPinwheel,
} from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { type useFiles } from "@/hooks/use-files";
import FileExtra from "@/components/file-extra";
import { Button } from "@/components/ui/button";
import useFileSwitch from "@/hooks/use-file-switch";
import FileImage from "./file-image";

const FileVideo = dynamic(() => import("@/components/file-video"));

type FileViewerProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  file: TelegramFile;
  onFileChange: (file: TelegramFile) => void;
} & ReturnType<typeof useFiles>;

export default function FileViewer({
  open,
  onOpenChange,
  onFileChange,
  file,
  hasMore,
  handleLoadMore,
  isLoading,
}: FileViewerProps) {
  const { handleNavigation, direction } = useFileSwitch({
    file,
    onFileChange,
    hasMore,
    handleLoadMore,
  });

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (file === undefined || !open) return;

      if (e.key === "ArrowLeft") {
        handleNavigation(-1);
      } else if (e.key === "ArrowRight") {
        handleNavigation(1);
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [handleNavigation, file, open]);

  const slideVariants = {
    enter: (direction: number) => ({
      x: direction > 0 ? 1000 : -1000,
      opacity: 0,
    }),
    center: {
      zIndex: 1,
      x: 0,
      opacity: 1,
    },
    exit: (direction: number) => ({
      zIndex: 0,
      x: direction < 0 ? 1000 : -1000,
      opacity: 0,
    }),
  };

  if (!file) return null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogPortal>
        <DialogOverlay>
          <>
            <div className="absolute left-0 top-0 z-[100] flex h-16 w-full items-center border-gray-700 px-4 text-white backdrop-blur">
              <FileExtra file={file} rowHeight="s" />
              <Button
                variant="ghost"
                size="icon"
                onClick={() => onOpenChange(false)}
                className="absolute right-4"
              >
                <CircleX className="h-6 w-6" />
              </Button>
            </div>
            {file.prev && (
              <div
                className="fixed bottom-0 left-0 top-0 flex h-full w-20 cursor-pointer items-center justify-center opacity-0 hover:opacity-100"
                onClick={() => handleNavigation(-1)}
              >
                <ChevronLeft className="h-10 w-10 text-white hover:text-accent" />
              </div>
            )}

            {(file.next ?? hasMore) && (
              <div
                className="fixed bottom-0 right-0 top-0 flex h-full w-20 cursor-pointer items-center justify-center opacity-0 hover:opacity-100"
                onClick={() => handleNavigation(1)}
              >
                <ChevronRight className="h-10 w-10 text-white hover:text-accent" />
              </div>
            )}
          </>
        </DialogOverlay>

        <DialogPrimitive.Content
          data-fileid={file.id}
          data-prev={file.prev?.id}
          data-next={file.next?.id}
          className={cn(
            "fixed left-[50%] top-[50%] z-50 translate-x-[-50%] translate-y-[-50%] duration-200 focus-visible:outline-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%] data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%]",
          )}
          aria-describedby={undefined}
          onInteractOutside={(e) => {
            if (e.target instanceof Element) {
              if (e.target.getAttribute("data-state")) {
                onOpenChange(false);
              }
            }
            e.preventDefault();
          }}
        >
          <div className="relative flex min-h-screen items-center justify-center">
            <AnimatePresence
              initial={false}
              custom={direction}
              mode="popLayout"
            >
              <motion.div
                key={file.id}
                custom={direction}
                variants={slideVariants}
                initial="enter"
                animate="center"
                exit="exit"
                transition={{
                  x: { type: "spring", stiffness: 300, damping: 30 },
                  opacity: { duration: 0.5 },
                }}
                className="mx-auto"
                style={{
                  maxWidth: "calc(100vw - 100px)",
                  maxHeight: "100vh",
                }}
              >
                <VisuallyHidden>
                  <DialogTitle>File Viewer</DialogTitle>
                </VisuallyHidden>
                {file.type === "video" &&
                file.downloadStatus === "completed" ? (
                  <FileVideo file={file} />
                ) : (
                  <FileImage file={file} isFullPreview />
                )}
              </motion.div>
            </AnimatePresence>
          </div>
          {isLoading && (
            <div className="fixed inset-0 z-[100] flex items-center justify-center">
              <LoaderPinwheel
                className="h-8 w-8 animate-spin"
                style={{ strokeWidth: "0.8px" }}
              />
            </div>
          )}
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  );
}
