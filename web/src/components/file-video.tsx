import { type TelegramFile } from "@/lib/types";
import React, { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { AnimatePresence, motion } from "framer-motion";
import {
  Loader2,
  Maximize,
  Minimize,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  VideoOff,
  Volume2,
  VolumeX,
} from "lucide-react";
import { getApiUrl } from "@/lib/api";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { cn } from "@/lib/utils";
import useIsMobile from "@/hooks/use-is-mobile";
import * as SliderPrimitive from "@radix-ui/react-slider";
import { SafeBottomWrapper } from "@/components/safe-bottom-wrapper";

// 检测浏览器是否支持特定视频格式
const checkVideoSupport = (mimeType: string): "probably" | "maybe" | "" => {
  const video = document.createElement("video");
  return video.canPlayType(mimeType);
};

// Browser compatibility limited formats
const BROWSER_LIMITED_FORMATS: Record<string, string> = {
  "video/quicktime": "QuickTime format is recommended for Safari browser",
  "video/mp2t":
    "MPEG-TS format is not supported by browsers, please download and use VLC player",
  "video/x-matroska": "MKV format has limited support in some browsers",
};

// 获取 MIME 类型
const getMimeType = (file: TelegramFile): string => {
  // 优先使用顶层的 mimeType
  if (file.mimeType) {
    return file.mimeType;
  }

  // 如果 extra 存在且包含 mimeType (即 VideoExtra 类型)
  if (file.extra && "mimeType" in file.extra) {
    return file.extra.mimeType;
  }

  // 默认返回 video/mp4
  return "video/mp4";
};

const VideoErrorFallback = ({
  className = "",
  message = "Video loading failed!",
}) => (
  <div
    className={`flex flex-col items-center justify-center rounded bg-gray-100 p-4 ${className}`}
  >
    <VideoOff className="mb-2 h-8 w-8 text-gray-400" />
    <p className="text-sm text-gray-500">{message}</p>
  </div>
);

const Slider = React.forwardRef<
  React.ComponentRef<typeof SliderPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SliderPrimitive.Root> & {
    isMobile?: boolean;
  }
>(({ className, isMobile = false, ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn(
      "relative flex w-full touch-none select-none items-center",
      isMobile ? "py-4" : "",
      className,
    )}
    {...props}
  >
    <SliderPrimitive.Track
      className={cn(
        "relative w-full grow overflow-hidden rounded-full bg-gray-600/40",
        isMobile ? "h-2" : "h-1.5",
      )}
    >
      <SliderPrimitive.Range className="absolute h-full bg-white" />
    </SliderPrimitive.Track>
    <SliderPrimitive.Thumb
      className={cn(
        "block rounded-full border-4 border-white bg-background shadow transition-all",
        isMobile ? "h-6 w-6 active:scale-110" : "h-4 w-4",
        "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring",
        "disabled:pointer-events-none disabled:opacity-50",
      )}
    />
  </SliderPrimitive.Root>
));

Slider.displayName = "Slider";

const DesktopControls = ({
  isPlaying,
  currentTime,
  duration,
  volume,
  isMuted,
  isFullscreen,
  playbackRate,
  onPlayPause,
  onVolumeChange,
  onMuteToggle,
  onFullscreenToggle,
  onPlaybackRateChange,
  onSeek,
  progressBarRef,
  onProgressBarHover,
  onProgressBarLeave,
  showPreview,
  previewTime,
  previewPos,
  canvasRef,
}: {
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  isMuted: boolean;
  isFullscreen: boolean;
  playbackRate: number;
  onPlayPause: () => void;
  onVolumeChange: (volume: number) => void;
  onMuteToggle: () => void;
  onFullscreenToggle: () => void;
  onPlaybackRateChange: (rate: number) => void;
  onSeek: (time: number) => void;
  progressBarRef: React.RefObject<HTMLDivElement | null>;
  onProgressBarHover: (e: React.MouseEvent) => void;
  onProgressBarLeave: () => void;
  showPreview: boolean;
  previewTime: number;
  previewPos: number;
  canvasRef: React.RefObject<HTMLCanvasElement | null>;
}) => {
  const playbackRates = [0.5, 0.75, 1, 1.25, 1.5, 2];
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  return (
    <div className="space-y-4">
      <div
        ref={progressBarRef}
        className="relative"
        onMouseMove={onProgressBarHover}
        onMouseLeave={onProgressBarLeave}
      >
        <Slider
          value={[currentTime]}
          max={duration}
          step={0.1}
          className="w-full cursor-pointer"
          onValueChange={(value) => value[0] && onSeek(value[0])}
        />
        {showPreview && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            className="absolute bottom-full mb-4 overflow-hidden bg-black"
            style={{ left: `${previewPos}px`, transform: "translateX(-50%)" }}
          >
            <div className="flex aspect-video w-48 items-center justify-center bg-black">
              <canvas
                ref={canvasRef}
                className="h-full w-full object-contain"
              />
            </div>
            <div className="bg-black/80 px-2 py-1 text-center text-sm text-white">
              {formatTime(previewTime)}
            </div>
          </motion.div>
        )}
      </div>

      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
          onClick={onPlayPause}
        >
          {isPlaying ? (
            <Pause className="h-6 w-6" />
          ) : (
            <Play className="h-6 w-6" />
          )}
        </Button>

        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            className="text-white hover:bg-white/20"
            onClick={onMuteToggle}
          >
            {isMuted ? (
              <VolumeX className="h-6 w-6" />
            ) : (
              <Volume2 className="h-6 w-6" />
            )}
          </Button>
          <Slider
            value={[volume * 100]}
            max={100}
            className="w-24"
            onValueChange={(value) => onVolumeChange(value[0]! / 100)}
          />
        </div>

        <div className="text-sm text-white">
          {formatTime(currentTime)} / {formatTime(duration)}
        </div>

        <div className="ml-auto flex items-center gap-2">
          <Popover>
            <PopoverTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                className="text-white hover:bg-white/20"
              >
                {playbackRate}x
              </Button>
            </PopoverTrigger>
            <PopoverContent className="w-18 p-0" modal={true} side="top">
              <div className="flex flex-col">
                {playbackRates.map((rate) => (
                  <Button
                    key={rate}
                    variant="ghost"
                    className={cn(
                      "justify-start rounded-none",
                      rate === playbackRate && "bg-accent",
                    )}
                    onClick={() => onPlaybackRateChange(rate)}
                  >
                    {rate}x
                  </Button>
                ))}
              </div>
            </PopoverContent>
          </Popover>

          <Button
            variant="ghost"
            size="icon"
            className="text-white hover:bg-white/20"
            onClick={onFullscreenToggle}
          >
            {isFullscreen ? (
              <Minimize className="h-6 w-6" />
            ) : (
              <Maximize className="h-6 w-6" />
            )}
          </Button>
        </div>
      </div>
    </div>
  );
};

const MobileControls = ({
  isPlaying,
  currentTime,
  duration,
  onPlayPause,
  onSeek,
  onSkipForward,
  onSkipBackward,
}: {
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  onPlayPause: () => void;
  onSeek: (time: number) => void;
  onSkipForward: () => void;
  onSkipBackward: () => void;
}) => {
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  return (
    <div className="space-y-6" onClick={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between px-4">
        <span className="text-sm text-white">{formatTime(currentTime)}</span>
        <span className="text-sm text-white">{formatTime(duration)}</span>
      </div>

      <div
        onTouchStart={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <Slider
          isMobile={true}
          value={[currentTime]}
          max={duration}
          step={0.1}
          className="w-full cursor-pointer"
          onValueChange={(value) => value[0] && onSeek(value[0])}
        />
      </div>

      <div className="flex items-center justify-center gap-8">
        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
          onClick={onSkipBackward}
        >
          <RotateCcw className="h-8 w-8" />
        </Button>

        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
          onClick={onPlayPause}
        >
          {isPlaying ? (
            <Pause className="h-12 w-12" />
          ) : (
            <Play className="h-12 w-12" />
          )}
        </Button>

        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
          onClick={onSkipForward}
        >
          <RotateCw className="h-8 w-8" />
        </Button>
      </div>
    </div>
  );
};

const FileVideo = ({
  file,
  onTimeUpdate,
  onVolumeChange,
  className,
}: {
  file: TelegramFile;
  onTimeUpdate?: (time: number) => void;
  onVolumeChange?: (volume: number) => void;
  className?: string;
}) => {
  const videoWidth = file.extra?.width ?? 480;
  const videoHeight = file.extra?.height ?? 270;
  const aspectRatio = videoWidth / videoHeight;
  const videoRef = useRef<HTMLVideoElement>(null);
  const previewVideoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const progressBarRef = useRef<HTMLDivElement>(null);
  const isMobile = useIsMobile();
  const [loading, setLoading] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [isMuted, setIsMuted] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [showControls, setShowControls] = useState(true);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [showPreview, setShowPreview] = useState(false);
  const [previewTime, setPreviewTime] = useState(0);
  const [previewPos, setPreviewPos] = useState(0);
  const [isPreviewReady, setIsPreviewReady] = useState(false);
  // The hover-preview <video> is a second request for the same file; mount it on first hover.
  const [previewMounted, setPreviewMounted] = useState(false);
  const [error, setError] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [formatWarning, setFormatWarning] = useState<string | null>(null);

  const url = `${getApiUrl()}/${file.telegramId}/file/${file.uniqueId}`;
  const mimeType = getMimeType(file);

  // Check format compatibility
  useEffect(() => {
    const support = checkVideoSupport(mimeType);

    if (support === "" && BROWSER_LIMITED_FORMATS[mimeType]) {
      setFormatWarning(BROWSER_LIMITED_FORMATS[mimeType]);
      console.warn(
        `Video format ${mimeType} may not be supported in current browser`,
      );
    }
  }, [mimeType]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const handleWaiting = () => setLoading(true);
    const handlePlaying = () => setLoading(false);
    const handleCanPlay = () => setLoading(false);

    video.addEventListener("waiting", handleWaiting);
    video.addEventListener("playing", handlePlaying);
    video.addEventListener("canplay", handleCanPlay);

    return () => {
      video.removeEventListener("waiting", handleWaiting);
      video.removeEventListener("playing", handlePlaying);
      video.removeEventListener("canplay", handleCanPlay);
    };
  }, []);

  const captureVideoFrame = () => {
    const previewVideo = previewVideoRef.current;
    const canvas = canvasRef.current;
    if (!previewVideo || !canvas || previewVideo.readyState < 2) return;

    const context = canvas.getContext("2d");
    if (!context) return;

    // Set canvas dimensions to match video dimensions
    canvas.width = previewVideo.videoWidth;
    canvas.height = previewVideo.videoHeight;

    // Draw the current frame
    context.drawImage(previewVideo, 0, 0, canvas.width, canvas.height);
  };

  const handleSkipForward = () => {
    if (videoRef.current) {
      videoRef.current.currentTime = Math.min(duration, currentTime + 15);
    }
  };

  const handleSkipBackward = () => {
    if (videoRef.current) {
      videoRef.current.currentTime = Math.max(0, currentTime - 15);
    }
  };

  const handleProgressBarHover = (e: React.MouseEvent) => {
    if (isMobile) return;
    setPreviewMounted(true);

    const progressBar = progressBarRef.current;
    const previewVideo = previewVideoRef.current;
    if (!progressBar || !previewVideo || !isPreviewReady) return;

    const rect = progressBar.getBoundingClientRect();
    const percent = Math.max(
      0,
      Math.min(1, (e.clientX - rect.left) / rect.width),
    );
    const previewTimeValue = percent * duration;

    setPreviewTime(previewTimeValue);
    setPreviewPos(e.clientX - rect.left);
    setShowPreview(true);
    previewVideo.currentTime = previewTimeValue;
  };

  const togglePlay = () => {
    if (videoRef.current) {
      if (!isPreviewReady) {
        return;
      }
      if (isPlaying) {
        videoRef.current.pause();
      } else {
        void videoRef.current.play();
      }
      setIsPlaying(!isPlaying);
    }
  };

  const handleSeek = (time: number) => {
    if (videoRef.current) {
      videoRef.current.currentTime = time;
      setCurrentTime(time);
    }
  };

  const handleTimeUpdate = () => {
    if (videoRef.current) {
      setCurrentTime(videoRef.current.currentTime);
      onTimeUpdate?.(videoRef.current.currentTime);
    }
  };

  const handleVolumeChange = (newVolume: number) => {
    if (videoRef.current) {
      videoRef.current.volume = newVolume;
      setVolume(newVolume);
      setIsMuted(newVolume === 0);
      onVolumeChange?.(newVolume);
    }
  };

  const handleError = (e: React.SyntheticEvent<HTMLVideoElement>) => {
    setError(true);
    const videoElement = e.currentTarget;
    console.error("Video playback error:", {
      code: videoElement.error?.code,
      message: videoElement.error?.message,
      src: videoElement.currentSrc,
      mimeType: mimeType,
    });
    // Set a user-friendly error message based on the error code
    let message = "Video loading failed";
    if (videoElement.error) {
      switch (videoElement.error.code) {
        case 1:
          message = "Playback aborted";
          break;
        case 2:
          message = "Network error";
          break;
        case 3:
          message = "Decode error";
          break;
        case 4:
          message = `Unsupported format (${mimeType})`;
          if (BROWSER_LIMITED_FORMATS[mimeType]) {
            message += ` - ${BROWSER_LIMITED_FORMATS[mimeType]}`;
          }
          break;
        default:
          message = "Unknown error";
      }
    }
    setErrorMessage(message);
  };

  const toggleMute = () => {
    if (videoRef.current) {
      const newMuted = !isMuted;
      videoRef.current.muted = newMuted;
      setIsMuted(newMuted);
      if (newMuted) {
        handleVolumeChange(0);
      } else {
        handleVolumeChange(1);
      }
    }
  };

  const toggleFullscreen = () => {
    if (!containerRef.current) return;

    if (!document.fullscreenElement) {
      void containerRef.current.requestFullscreen();
      setIsFullscreen(true);
    } else {
      void document.exitFullscreen();
      setIsFullscreen(false);
    }
  };

  const handlePlaybackRateChange = (rate: number) => {
    if (videoRef.current) {
      videoRef.current.playbackRate = rate;
      setPlaybackRate(rate);
    }
  };

  const handleEnded = () => {
    setCurrentTime(0);
    setIsPlaying(false);
  };

  if (error) {
    return (
      <VideoErrorFallback
        className="h-full min-h-[200px] w-full"
        message={errorMessage}
      />
    );
  }

  return (
    <motion.div
      ref={containerRef}
      style={{
        aspectRatio: aspectRatio,
        maxWidth: isMobile ? "100vw" : videoWidth,
        maxHeight: isMobile ? "100vh" : videoHeight,
        position: "relative",
        background: "#000",
      }}
      className={cn(
        "group relative w-full overflow-hidden bg-black",
        isMobile ? "flex h-screen w-screen items-center" : "min-w-[30rem]",
      )}
      onClick={isMobile ? () => setShowControls((prev) => !prev) : undefined}
      onMouseEnter={() => setShowControls(true)}
      onMouseLeave={() => setShowControls(false)}
    >
      {!isPreviewReady && file.thumbnailFile && (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
          <Loader2 className="h-12 w-12 animate-spin text-white" />
        </div>
      )}

      <video
        ref={videoRef}
        autoPlay={isMobile}
        onPlay={() => isMobile && !isPlaying && setIsPlaying(true)}
        onEnded={handleEnded}
        onError={handleError}
        playsInline
        className={cn("max-h-[calc(100vh-5rem)] w-full", className)}
        onTimeUpdate={handleTimeUpdate}
        onLoadedMetadata={(e) => {
          setDuration(e.currentTarget.duration);
          setIsPreviewReady(true);
        }}
      >
        <source src={url} type={mimeType} />
        Your browser does not support this video format
      </video>

      {/* Hidden video for preview */}
      {!isMobile && previewMounted && (
        <video
          ref={previewVideoRef}
          className="hidden"
          preload="metadata"
          muted
          onSeeked={captureVideoFrame}
        >
          <source src={url} type={mimeType} />
        </video>
      )}

      {loading && (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
          <Loader2 className="h-12 w-12 animate-spin text-white" />
        </div>
      )}

      {/* Format warning */}
      {formatWarning && !error && (
        <div className="absolute left-0 right-0 top-0 z-20 bg-yellow-500/90 px-4 py-2 text-center text-sm text-black">
          ⚠️ {formatWarning}
        </div>
      )}

      <AnimatePresence>
        {showControls && (
          <motion.div
            id="video-controls"
            onTouchStart={(e) => e.stopPropagation()}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
            className={cn(
              "absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-4",
              isMobile && "bg-gray-900 bg-opacity-20",
            )}
          >
            {isMobile ? (
              <SafeBottomWrapper>
                <MobileControls
                  isPlaying={isPlaying}
                  currentTime={currentTime}
                  duration={duration}
                  onPlayPause={togglePlay}
                  onSeek={handleSeek}
                  onSkipForward={handleSkipForward}
                  onSkipBackward={handleSkipBackward}
                />
              </SafeBottomWrapper>
            ) : (
              <DesktopControls
                isPlaying={isPlaying}
                currentTime={currentTime}
                duration={duration}
                volume={volume}
                isMuted={isMuted}
                isFullscreen={isFullscreen}
                playbackRate={playbackRate}
                onPlayPause={togglePlay}
                onVolumeChange={handleVolumeChange}
                onMuteToggle={toggleMute}
                onFullscreenToggle={toggleFullscreen}
                onPlaybackRateChange={handlePlaybackRateChange}
                onSeek={handleSeek}
                progressBarRef={progressBarRef}
                onProgressBarHover={handleProgressBarHover}
                onProgressBarLeave={() => setShowPreview(false)}
                showPreview={showPreview}
                previewTime={previewTime}
                previewPos={previewPos}
                canvasRef={canvasRef}
              />
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
};

export default FileVideo;
