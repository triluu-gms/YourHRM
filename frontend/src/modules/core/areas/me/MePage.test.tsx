import { describe, expect, it } from 'vitest';
import { renderPage, screen } from '@/test/render';
import { MePage } from './MePage';

describe('MePage', () => {
  it('shows the user, roles and permissions from /me', async () => {
    renderPage(<MePage />);
    expect(await screen.findByText('admin')).toBeInTheDocument();
    expect(screen.getByText('TENANT_OWNER')).toBeInTheDocument();
    expect(screen.getByText('core.user.view')).toBeInTheDocument();
  });
});
