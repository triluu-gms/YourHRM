import type { ReactNode } from 'react';
import { usePermission } from './me';

/** Renders children only when the user holds one of the permissions (buttons, menu entries, columns). */
export function PermissionGate({
  anyOf,
  children,
  fallback = null,
}: {
  anyOf: string | string[];
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const codes = Array.isArray(anyOf) ? anyOf : [anyOf];
  const allowed = usePermission(...codes);
  return <>{allowed ? children : fallback}</>;
}
