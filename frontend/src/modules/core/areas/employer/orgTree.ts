import type { UUID } from '@/shared/api';
import type { OrgUnit } from './api';

export interface OrgTreeNode {
  unit: OrgUnit;
  children: OrgTreeNode[];
}

const bySortThenCode = (a: OrgTreeNode, b: OrgTreeNode) =>
  a.unit.sortOrder - b.unit.sortOrder || a.unit.code.localeCompare(b.unit.code);

/**
 * Builds the forest from the flat as-of list (`GET /core/org-units`, path order). A unit whose parent is not in the
 * list (a scoped user sees subtrees without their ancestors) becomes a root.
 */
export function buildOrgTree(units: OrgUnit[]): OrgTreeNode[] {
  const nodes = new Map<UUID, OrgTreeNode>(units.map((u) => [u.id, { unit: u, children: [] }]));
  const roots: OrgTreeNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.unit.parentId ? nodes.get(node.unit.parentId) : undefined;
    if (parent && parent !== node) parent.children.push(node);
    else roots.push(node);
  }
  const sort = (list: OrgTreeNode[]) => {
    list.sort(bySortThenCode);
    list.forEach((n) => sort(n.children));
  };
  sort(roots);
  return roots;
}

/** Ids of the unit and everything under it (to keep them out of a "new parent" picker). */
export function subtreeIds(units: OrgUnit[], rootId: UUID): Set<UUID> {
  const children = new Map<UUID, UUID[]>();
  for (const u of units) {
    if (!u.parentId) continue;
    const list = children.get(u.parentId) ?? [];
    list.push(u.id);
    children.set(u.parentId, list);
  }
  const out = new Set<UUID>();
  const stack = [rootId];
  while (stack.length) {
    const id = stack.pop() as UUID;
    if (out.has(id)) continue;
    out.add(id);
    stack.push(...(children.get(id) ?? []));
  }
  return out;
}

export const orgUnitLabel = (u: Pick<OrgUnit, 'code' | 'nameVi' | 'nameEn'>, language: string) => {
  const name = language.startsWith('en') ? u.nameEn || u.nameVi : u.nameVi || u.nameEn;
  return name ? `${u.code} – ${name}` : u.code;
};
