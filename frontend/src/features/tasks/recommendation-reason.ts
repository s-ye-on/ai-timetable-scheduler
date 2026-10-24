/** 초기 버전이 저장한 점수 전용 문구만 가린다. API 원본과 추천 근거는 변경하지 않는다. */
export function visibleRecommendationReason(reason: string | null): string {
  return (reason || '').split(/[,，]/).map((part) => part.trim())
    .filter((part) => part && !/^(?:원점수|내부\s*점수|점수)\s*[:：]?\s*-?\d+(?:\.\d+)?\s*점?$/.test(part)).join(', ');
}
