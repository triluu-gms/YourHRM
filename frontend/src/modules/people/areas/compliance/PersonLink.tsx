import { Link } from '@tanstack/react-router';
import type { UUID } from '@/shared/api';
import { shortId } from '../../common';
import { usePersonLink } from './queries';

/**
 * Person name linking to an employee profile (PIT-primary employment first, else the first one). `name` avoids the
 * person lookup when the row already carries it; employments are still fetched to build the link.
 */
export function PersonLink({ personId, name }: { personId: UUID; name?: string | null }) {
  const q = usePersonLink(personId);
  const label = name ?? q.data?.person.fullName ?? shortId(personId);
  const employments = q.data?.employments ?? [];
  const target = employments.find((e) => e.pitPrimary) ?? employments[0];
  if (!target) return <>{label}</>;
  return (
    <Link to="/people/employees/$employmentId" params={{ employmentId: target.id }}>
      {label}
    </Link>
  );
}
