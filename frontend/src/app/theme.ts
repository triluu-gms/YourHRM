import type { ThemeConfig } from 'antd';

/** YourHRM design tokens on top of Ant Design 5 (ADR-0013 decision 2): compact, data-dense admin UI. */
export const yhrmTheme: ThemeConfig = {
  cssVar: true,
  token: {
    colorPrimary: '#1f6feb',
    colorInfo: '#1f6feb',
    colorSuccess: '#1a7f37',
    colorWarning: '#bf8700',
    colorError: '#cf222e',
    colorTextBase: '#1f2328',
    colorBgLayout: '#f6f8fa',
    borderRadius: 6,
    fontFamily:
      "'Inter', 'Segoe UI', system-ui, -apple-system, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif",
    fontSize: 14,
  },
  components: {
    Layout: { headerBg: '#ffffff', siderBg: '#0d1b2a', headerHeight: 56, headerPadding: '0 16px' },
    Menu: {
      darkItemBg: '#0d1b2a',
      darkSubMenuItemBg: '#0a1522',
      darkItemSelectedBg: '#1f6feb',
      darkItemHoverBg: '#15283d',
    },
    Table: { cellPaddingBlock: 8, cellPaddingInline: 12, headerBg: '#f6f8fa' },
    Card: { paddingLG: 16 },
    Form: { itemMarginBottom: 16 },
  },
};
