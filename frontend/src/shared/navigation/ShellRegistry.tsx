import { createContext, useContext, type ComponentType, type ReactNode } from 'react';
import type { MenuEntry } from './menu';

/**
 * What a module plugs into the shell (ADR-0013 §3 "shell registry"): menu entries and header widgets
 * (notification bell, task counter…). The app composes one registry from all modules; shared code only
 * reads it, so `shared` never imports a module.
 */
export interface ShellRegistry {
  menu: MenuEntry[];
  headerWidgets: ComponentType[];
  /** Route of the home page after login. */
  homePath: string;
}

const ShellRegistryContext = createContext<ShellRegistry>({ menu: [], headerWidgets: [], homePath: '/' });

export function ShellRegistryProvider({ registry, children }: { registry: ShellRegistry; children: ReactNode }) {
  return <ShellRegistryContext.Provider value={registry}>{children}</ShellRegistryContext.Provider>;
}

export function useShellRegistry() {
  return useContext(ShellRegistryContext);
}

export function mergeRegistries(parts: Partial<ShellRegistry>[]): ShellRegistry {
  return {
    menu: parts.flatMap((p) => p.menu ?? []),
    headerWidgets: parts.flatMap((p) => p.headerWidgets ?? []),
    homePath: parts.find((p) => p.homePath)?.homePath ?? '/',
  };
}
