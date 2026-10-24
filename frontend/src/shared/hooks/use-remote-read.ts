import { useCallback, useEffect, useState } from 'react';

/** 조회별 생명주기를 분리하고 새 요청 중에는 이전 요청의 데이터를 노출하지 않는다. */
export function useRemoteRead<T>(request: () => Promise<T>) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ request: typeof request; attempt: number; data: T | null; error: unknown; loading: boolean } | null>(null);
  useEffect(() => {
    let active = true;
    void request().then((data) => { if (active) setResult({ request, attempt, data, error: null, loading: false }); })
      .catch((error: unknown) => { if (active) setResult({ request, attempt, data: null, error, loading: false }); });
    return () => { active = false; };
  }, [request, attempt]);
  const current = result?.request === request && result.attempt === attempt ? result : null;
  const refresh = useCallback(() => setAttempt((value) => value + 1), []);
  return { data: current?.data ?? null, error: current?.error ?? null, loading: current?.loading ?? true, refresh, attempt };
}
