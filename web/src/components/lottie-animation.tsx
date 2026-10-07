"use client";

import dynamic from "next/dynamic";
import useSWRImmutable from "swr/immutable";

const Lottie = dynamic(() => import("lottie-react"), { ssr: false });

const fetchJson = (url: string) =>
  fetch(url).then((res) => res.json() as Promise<unknown>);

/** Plays a Lottie animation from /public, fetched at runtime instead of bundled into the JS. */
export default function LottieAnimation({
  src,
  className,
}: {
  src: string;
  className?: string;
}) {
  // Decorative: no retry, and override the global SWR error toast.
  const { data } = useSWRImmutable(src, fetchJson, {
    shouldRetryOnError: false,
    onError: () => undefined,
  });
  if (!data) return <div className={className} />;
  return <Lottie className={className} animationData={data} loop={true} />;
}
