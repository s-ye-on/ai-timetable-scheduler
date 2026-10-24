export function RemoteReadNotice({ loading, error, label, onRetry }: {
  loading: boolean; error: unknown; label: string; onRetry: () => void;
}) {
  if (loading) return <p className="notice" role="status">{label} 불러오고 있어요.</p>;
  if (!error) return null;
  const status = (error as { status?: number }).status;
  const code = (error as { code?: string }).code;
  const message = status === 404 || code === 'NOT_FOUND_TASK' ? '항목을 찾을 수 없거나 접근할 수 없어요. 목록을 다시 확인해 주세요.'
    : status === 403 ? '접근 권한을 확인해 주세요.' : status === 401 ? '로그인이 만료되었어요. 다시 로그인해 주세요.'
    : '서버 응답을 확인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.';
  return <div className="notice error" role="alert"><div><strong>{label} 불러오지 못했어요</strong><p>{message}</p>
    <button type="button" className="btn btn-secondary btn-sm" onClick={onRetry}>다시 조회하기</button></div></div>;
}
