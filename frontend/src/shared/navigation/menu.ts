/**
 * Menu registry (architecture-overview §11.1): each module contributes a tree of entries; the shell filters
 * them with the user's effective permissions (`/me`) and renders what is left.
 */
export interface MenuEntry {
  key: string;
  /** i18n key in the `common` namespace, e.g. `nav.users`. */
  labelKey: string;
  /** Route path; omitted for a group. */
  path?: string;
  /** Visible when the user holds ANY of these permission codes; empty = everyone. */
  permissions?: string[];
  icon?: string;
  children?: MenuEntry[];
}

export function filterMenu(entries: MenuEntry[], can: (...codes: string[]) => boolean): MenuEntry[] {
  return entries
    .map((entry) => {
      const children = entry.children ? filterMenu(entry.children, can) : undefined;
      const allowed = !entry.permissions || entry.permissions.length === 0 || can(...entry.permissions);
      if (children) return children.length > 0 ? { ...entry, children } : null;
      return allowed ? entry : null;
    })
    .filter((e): e is MenuEntry => e !== null);
}
