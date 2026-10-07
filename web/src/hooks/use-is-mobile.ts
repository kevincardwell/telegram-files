import { useSyncExternalStore } from "react";

// Complement of Tailwind's `md` breakpoint (min-width: 768px). One MediaQueryList for the whole
// app; its change event only fires when the breakpoint is crossed, not on every resize.
let mediaQuery: MediaQueryList | undefined;
const getMediaQuery = () =>
  (mediaQuery ??= window.matchMedia("not all and (min-width: 768px)"));

const subscribe = (onChange: () => void) => {
  const query = getMediaQuery();
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
};

const useIsMobile = (): boolean =>
  useSyncExternalStore(
    subscribe,
    () => getMediaQuery().matches,
    () => false,
  );

export default useIsMobile;
