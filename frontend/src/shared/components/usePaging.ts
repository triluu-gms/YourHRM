import { useCallback, useState } from 'react';

/** Local 0-based paging state for server-side lists. */
export function usePaging(initialSize = 20) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(initialSize);
  const onChange = useCallback((p: number, s: number) => {
    setPage(s !== size ? 0 : p);
    setSize(s);
  }, [size]);
  const reset = useCallback(() => setPage(0), []);
  return { page, size, onChange, reset };
}
