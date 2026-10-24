const INPUT_ERRORS: Record<string, string> = {
  MISSING_LLM_TITLE: '어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요.',
  MISSING_LLM_CATEGORY: '일정 내용을 분류하지 못했어요. 영화 감상·운동·독서도 요청할 수 있어요. 내용을 조금 더 구체적으로 적어 주세요.',
  MISSING_LLM_DURATION: '소요 시간을 알려주세요. 예: 공부 1시간',
  INVALID_LLM_DURATION: '소요 시간은 30분 단위로 입력해 주세요.',
  INVALID_LLM_DATE_CONDITION: '날짜와 요일 조건을 다시 확인해 주세요.',
  INVALID_LLM_DATE_RANGE: '허용 기간의 시작일과 종료일을 확인해 주세요.',
  INVALID_LLM_PARSE_RESULT: '날짜, 시간대, 소요 시간을 조금 더 명확하게 입력해 주세요.',
  INVALID_LLM_PARSE_REQUEST: '일정으로 분석할 내용을 입력해 주세요.',
  LLM_CLARIFICATION_REQUIRED: '계획할 활동이나 날짜·시간 조건을 확인해 주세요. 원하는 활동, 오전·오후와 꼭 지켜야 할 조건을 명확하게 입력해 주세요.',
  UNSUPPORTED_LLM_START_SECONDS: '초 단위 시작 시간은 지원하지 않아요. 분 단위로 입력해 주세요.',
  UNSUPPORTED_LLM_RELATIVE_START_RANGE: '최소 시작 시각 조건은 지원하지 않아요. 원하는 시작 시각을 지정해 주세요.',
  UNSUPPORTED_LLM_RECURRENCE: '반복 일정은 현재 지원하지 않아요. 한 번의 일정으로 요청해 주세요.',
};
export function plannerError(error: unknown, kind: 'analysis' | 'generation') {
  const { code, status } = (error || {}) as { code?: string; status?: number };
  if (code && Object.hasOwn(INPUT_ERRORS, code)) return { title: '입력 내용을 확인해 주세요', message: INPUT_ERRORS[code], uncertain: false };
  if (code === 'OPENAI_API_FAILED') return { title: 'AI 분석 서비스에 연결하지 못했어요', message: '잠시 후 다시 시도해 주세요. 이전 분석 결과로 추천하지 않아요.', uncertain: false };
  if (code && ['GOOGLE_CALENDAR_FREE_BUSY_FAILED', 'GOOGLE_OAUTH_TOKEN_REFRESH_FAILED', 'GOOGLE_OAUTH_CONFIGURATION_INVALID'].includes(code)) {
    return { title: 'Google Calendar 일정을 확인하지 못했어요', message: '일정이 비어 있다는 뜻이 아니에요. 연결 상태를 확인한 뒤 다시 추천해 주세요.', uncertain: false };
  }
  if (status === 401) return { title: '로그인이 만료되었어요', message: '다시 로그인한 뒤 저장한 할 일을 확인해 주세요.', uncertain: false };
  if (status === 403 || status === 404) return { title: '할 일에 접근할 수 없어요', message: '저장한 할 일 목록을 다시 확인해 주세요.', uncertain: false };
  if (status === 400) return { title: '요청 조건을 확인해 주세요', message: kind === 'analysis' ? '입력은 500자 이내로 작성하고 날짜·시간·소요 시간을 확인해 주세요.' : '현재 상태에서 추천할 수 없는 할 일이에요. 작업 상태를 다시 확인해 주세요.', uncertain: false };
  if (status === 409) return { title: '현재 작업 상태를 다시 확인해 주세요', message: '다른 변경 요청이 처리 중일 수 있어요. 최신 상태를 조회한 뒤 시도해 주세요.', uncertain: false };
  return { title: '요청 결과를 확인하지 못했어요', message: kind === 'analysis'
    ? '자동으로 다시 저장하지 않았어요. 저장한 할 일 목록을 먼저 확인한 뒤 새 분석을 요청해 주세요.'
    : '자동으로 다시 추천하지 않았어요. 저장된 후보를 다시 조회한 뒤 새 추천을 요청해 주세요.', uncertain: true };
}
