"use client";
import React, { createContext, useContext, useState } from "react";

interface LocalStorageContextType {
  getItem: <T>(key: string, initialValue: T) => T;
  setItem: <T>(key: string, value: T | ((prev: T) => T)) => void;
  removeItem: (key: string) => void;
}

const LocalStorageContext = createContext<LocalStorageContextType | null>(null);

// Read synchronously on first render: filling the map in an effect made the first render use
// defaults, so e.g. the file list fetched page 1 twice (defaults, then stored filters).
function readLocalStorage(): Record<string, any> {
  const map: Record<string, any> = {};
  if (typeof window === "undefined") return map;
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key) {
        try {
          map[key] = JSON.parse(localStorage.getItem(key) ?? "") as unknown;
        } catch {}
      }
    }
  } catch {
    // Storage blocked (privacy mode, disabled cookies): fall back to defaults.
  }
  return map;
}

export const LocalStorageProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [storageMap, setStorageMap] =
    useState<Record<string, any>>(readLocalStorage);

  const getItem = <T,>(key: string, initialValue: T): T => {
    if (key in storageMap) {
      return storageMap[key] as T;
    }
    return initialValue;
  };

  const setItem = <T,>(key: string, valueOrUpdater: T | ((prev: T) => T)) => {
    setStorageMap((prev) => {
      const currentValue = key in prev ? (prev[key] as T) : undefined;
      const newValue =
        typeof valueOrUpdater === "function"
          ? (valueOrUpdater as (prev: T) => T)(currentValue as T)
          : valueOrUpdater;

      const newMap = { ...prev, [key]: newValue };

      try {
        if (newValue === undefined) {
          localStorage.removeItem(key);
        } else {
          localStorage.setItem(key, JSON.stringify(newValue));
        }
      } catch (error) {
        console.error("Error writing to localStorage", error);
      }

      return newMap;
    });
  };

  const removeItem = (key: string) => {
    setStorageMap((prev) => {
      const newMap = { ...prev };
      delete newMap[key];
      try {
        localStorage.removeItem(key);
      } catch {}
      return newMap;
    });
  };

  return (
    <LocalStorageContext.Provider value={{ getItem, setItem, removeItem }}>
      {children}
    </LocalStorageContext.Provider>
  );
};

export const useLocalStorage = <T,>(
  key: string,
  initialValue: T,
): [T, (valueOrUpdater: T | ((prev: T) => T)) => void, () => void] => {
  const context = useContext(LocalStorageContext);
  if (!context) {
    throw new Error(
      "useLocalStorageContext must be used within LocalStorageProvider",
    );
  }

  const value = context.getItem(key, initialValue);

  const setValue = (valueOrUpdater: T | ((prev: T) => T)) => {
    context.setItem<T>(
      key,
      typeof valueOrUpdater === "function"
        ? // Updaters see the default, not undefined, when nothing is stored yet.
          (prev: T | undefined) =>
            (valueOrUpdater as (prev: T) => T)(prev ?? initialValue)
        : valueOrUpdater,
    );
  };

  const clearValue = () => context.removeItem(key);

  return [value, setValue, clearValue];
};
