import { useMemo, useSyncExternalStore } from 'react';
import type { TaskReadClient } from '@/features/tasks/task.types';

type Snapshot = { pending: boolean; requiresCheck: boolean; revision: number };
function createGate() {
  let state: Snapshot = { pending: false, requiresCheck: false, revision: 0 };
  const listeners = new Set<() => void>();
  function update(pending: boolean, requiresCheck: boolean) {
    state = { pending, requiresCheck, revision: state.revision + 1 };
    listeners.forEach((listener) => listener());
  }
  return {
    snapshot: () => state,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    begin: () => {
      if (state.pending || state.requiresCheck) return false;
      update(true, true); return true;
    },
    finish: () => update(false, true),
    checked: () => { if (!state.pending && state.requiresCheck) update(false, false); },
  };
}
// 화면 이동 중에도 진행 중인 같은 Task 요청을 잠근다. 응답·토큰·사용자 데이터는 보관하지 않는다.
const gates = new WeakMap<TaskReadClient, Map<number, ReturnType<typeof createGate>>>();
export function useRegistrationGate(client: TaskReadClient, taskId: number) {
  const gate = useMemo(() => {
    let tasks = gates.get(client);
    if (!tasks) { tasks = new Map(); gates.set(client, tasks); }
    let current = tasks.get(taskId);
    if (!current) { current = createGate(); tasks.set(taskId, current); }
    return current;
  }, [client, taskId]);
  const state = useSyncExternalStore(gate.subscribe, gate.snapshot);
  return { ...state, begin: gate.begin, finish: gate.finish, checked: gate.checked };
}
