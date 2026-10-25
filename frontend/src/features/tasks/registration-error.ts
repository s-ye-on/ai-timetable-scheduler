export function registrationError(error: unknown) {
  const { code, status } = (error || {}) as { code?: string; status?: number };
  if (code === 'RECOMMENDATION_NO_LONGER_AVAILABLE') return { manual: false, title: '현재는 이 시간에 등록할 수 없어요', message: '시간이 지났거나 최신 일정·수업·여유 시간 조건과 충돌해요. 저장된 상태를 확인한 뒤 다시 추천받아 주세요.' };
  if (code === 'GOOGLE_CALENDAR_NOT_CONNECTED') return { manual: false, title: 'Google Calendar 연결이 필요해요', message: 'Calendar를 연결한 뒤 상태를 다시 확인해 주세요. 기존 후보도 등록 직전에 최신 조건을 다시 검증해요.' };
  if (['GOOGLE_CALENDAR_EVENT_MISMATCH', 'GOOGLE_CALENDAR_SYNC_RECOVERY_REQUIRED'].includes(code || '')) return { manual: true, title: 'Calendar 일정을 직접 확인해 주세요', message: '기존 이벤트를 안전하게 확인할 수 없어 자동 등록·복구를 멈췄어요. Google Calendar에서 실제 일정을 확인해 주세요.' };
  if (['GOOGLE_CALENDAR_FREE_BUSY_FAILED', 'GOOGLE_OAUTH_TOKEN_REFRESH_FAILED', 'GOOGLE_OAUTH_CONFIGURATION_INVALID'].includes(code || '')) return { manual: false, title: 'Google Calendar 일정을 확인하지 못했어요', message: '일정이 비어 있다는 뜻이 아니에요. 연결과 저장된 상태를 확인한 뒤 다시 진행해 주세요.' };
  if (status === 400 || status === 409) return { manual: false, title: '추천 후보의 최신 상태를 확인해 주세요', message: '현재 상태에서는 요청을 완료하지 못했어요. 저장된 상태를 확인하고 유효한 후보로 진행해 주세요.' };
  if (status === 403 || status === 404) return { manual: true, title: '추천 후보에 접근할 수 없어요', message: '저장한 할 일 목록에서 현재 사용자의 일정을 다시 확인해 주세요.' };
  return { manual: false, title: '등록 결과를 확인해야 해요', message: '요청을 자동으로 다시 보내지 않았어요. 서버의 Task와 추천 상태를 조회한 뒤 안전하게 진행해 주세요.' };
}
