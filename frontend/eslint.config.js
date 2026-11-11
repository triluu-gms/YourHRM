// @ts-check
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import boundaries from 'eslint-plugin-boundaries';
import globals from 'globals';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { ecmaVersion: 2022, globals: { ...globals.browser, ...globals.node } },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh, boundaries },
    settings: {
      'import/resolver': { typescript: { alwaysTryTypes: true, project: './tsconfig.app.json' } },
      'boundaries/elements': [
        { type: 'app', pattern: 'src/app/**' },
        { type: 'shared', pattern: 'src/shared/**' },
        { type: 'test', pattern: 'src/test/**' },
        { type: 'module', pattern: 'src/modules/*/**', capture: ['module'] },
      ],
      'boundaries/ignore': ['**/*.test.{ts,tsx}'],
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      // Module boundaries (ADR-0013 §3): a domain module never imports another domain module; shared never
      // imports a module; only app wires everything together.
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            {
              from: { element: { type: 'app' } },
              allow: [{ to: { element: { type: ['app', 'shared', 'module', 'test'] } } }],
            },
            { from: { element: { type: 'shared' } }, allow: [{ to: { element: { type: 'shared' } } }] },
            {
              from: { element: { type: 'test' } },
              allow: [{ to: { element: { type: ['app', 'shared', 'test', 'module'] } } }],
            },
            {
              from: { element: { type: 'module' } },
              allow: [
                { to: { element: { type: ['shared', 'test'] } } },
                { to: { element: { type: 'module', captured: { module: '{{from.captured.module}}' } } } },
              ],
            },
          ],
        },
      ],
    },
  },
);
