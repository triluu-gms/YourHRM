import { describe, expect, it } from 'vitest';
import { filterMenu, type MenuEntry } from './menu';

const menu: MenuEntry[] = [
  {
    key: 'g',
    labelKey: 'nav.access',
    children: [
      { key: 'u', labelKey: 'nav.users', path: '/u', permissions: ['core.user.view'] },
      { key: 'r', labelKey: 'nav.roles', path: '/r', permissions: ['core.role.view'] },
    ],
  },
  { key: 'open', labelKey: 'nav.home', path: '/' },
  { key: 'a', labelKey: 'nav.audit', path: '/a', permissions: ['core.audit.view'] },
];

describe('filterMenu', () => {
  it('keeps entries without permissions and those the user holds', () => {
    const out = filterMenu(menu, (...codes) => codes.includes('core.user.view'));
    expect(out.map((e) => e.key)).toEqual(['g', 'open']);
    expect(out[0]?.children?.map((e) => e.key)).toEqual(['u']);
  });

  it('drops a group whose children are all hidden', () => {
    const out = filterMenu(menu, () => false);
    expect(out.map((e) => e.key)).toEqual(['open']);
  });
});
