import type { RecommendationRead } from '@/features/tasks/task.types';
import { taskDateLabel, taskTimeLabel } from '@/features/tasks/task-time';
import { visibleRecommendationReason } from '@/features/tasks/recommendation-reason';

export const MATCH_LABELS = { EXACT: '원조건 일치', TIME_CHANGED: '시간 변경', DATE_CHANGED: '날짜 변경', DATE_AND_TIME_CHANGED: '날짜·시간 변경', GENERAL: '원조건 없음' };
const CHANGES = { TIME_CHANGED: '요청한 시간 조건을 변경한 대안이에요.', DATE_CHANGED: '요청한 날짜 조건을 변경한 대안이에요.', DATE_AND_TIME_CHANGED: '요청한 날짜와 시간을 변경했어요.' };
export function recommendationFit(score: number) { return Math.round(score / 140 * 1000) / 10; }

export function RecommendationCandidates({ items }: { items: RecommendationRead[] }) {
  return <div className="candidate-list">{[...items].sort((a, b) => a.rank - b.rank || a.id - b.id).map((item) => {
    const alternative = item.matchType && Object.hasOwn(CHANGES, item.matchType);
    return <article className="candidate" key={item.id} aria-label={`추천 후보 ${item.rank}`}>
      <div className="rank-num" aria-label={`순위 ${item.rank}`}>{item.rank}</div><div>
        <div className="candidate-meta"><span className="badge neutral">후보 {item.rank} · 저장된 후보 · 최신 검증 전</span><span className="badge neutral">{item.matchType ? MATCH_LABELS[item.matchType] : '이전 후보 · 유형 정보 없음'}</span></div>
        <p className="candidate-date">{taskDateLabel(item.recommendedStartAt)}</p>
        <p className="candidate-time">{taskTimeLabel(item.recommendedStartAt)}–{taskTimeLabel(item.recommendedEndAt)}</p>
        {item.recommendedStartAt.slice(0, 10) !== item.recommendedEndAt.slice(0, 10) && <p>{taskDateLabel(item.recommendedEndAt)} 종료</p>}
        {item.matchType === 'EXACT' && <p className="candidate-match">정확히 일치하는 시간대가 존재해요!</p>}
        {alternative && <div className="candidate-change"><strong>다른 대안도 있어요</strong><p>{CHANGES[item.matchType as keyof typeof CHANGES]}</p></div>}
        {item.matchType === 'GENERAL' && <p className="candidate-match">특정 원조건 없이 현재 조건과 선호를 반영한 추천이에요.</p>}
        {visibleRecommendationReason(item.reason) && <p className="candidate-reason">{visibleRecommendationReason(item.reason)}</p>}
      </div><div className="candidate-side"><div className="od-stat" aria-label={`추천 적합도 ${recommendationFit(item.score)}점`}><strong className="fit-value">{recommendationFit(item.score)}<small>점</small></strong><span className="fit-caption">추천 적합도</span></div></div>
    </article>;
  })}</div>;
}
