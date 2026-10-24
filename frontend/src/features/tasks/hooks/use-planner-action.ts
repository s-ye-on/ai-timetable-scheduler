import { useEffect, useRef, useState } from 'react';

/** 중복 클릭과 폐기된 화면의 응답을 차단한다. 쓰기 요청을 자동 재전송하지 않는다. */
export function usePlannerAction<T>() {
  const mounted = useRef(false); const locked = useRef(false); const revision = useRef(0);
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<{ data: T | null; error: unknown } | null>(null);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; revision.current += 1; }; }, []);
  function invalidate() { revision.current += 1; setResult(null); }
  async function run(request: () => Promise<T>, onSuccess?: (value: T) => void) {
    if (locked.current) return;
    locked.current = true; const current = ++revision.current; setPending(true); setResult(null);
    try {
      const value = await request();
      if (mounted.current && current === revision.current) { setResult({ data: value, error: null }); onSuccess?.(value); }
    } catch (error: unknown) {
      if (mounted.current && current === revision.current) setResult({ data: null, error });
    } finally {
      locked.current = false;
      if (mounted.current) setPending(false);
    }
  }
  return { pending, data: result?.data ?? null, error: result?.error ?? null, run, invalidate };
}
