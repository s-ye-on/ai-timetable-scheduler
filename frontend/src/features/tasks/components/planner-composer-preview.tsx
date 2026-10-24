import { AppIcon } from '@/app/components/app-icon';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

/** 디자인은 보존하되 후속 단계의 쓰기 API를 실행하는 것처럼 보이지 않게 한다. */
export function PlannerComposerPreview() {
  return <section className="hero" aria-labelledby="composer-title"><div className="hero-title-row"><div><div className="section-kicker">A LITTLE PLAN, A BETTER DAY</div>
    <h2 id="composer-title">하고 싶은 일을,<br /><em>딱 맞는 일정</em>으로.</h2><p>편하게 말해 주세요. 내 수업과 선호에 맞춰 시간을 찾아볼게요.</p></div>
    <div className="hero-symbol" aria-hidden="true"><i /><i><TimetableIcon name="check" /></i><i /></div></div>
    <div className="composer"><label htmlFor="natural-preview"><AppIcon name="planner" />어떤 일정을 계획하고 있나요?</label>
      <textarea id="natural-preview" disabled rows={2} placeholder="목요일 저녁 7시에 알고리즘 공부를 2시간 하고 싶어." aria-describedby="analysis-help" />
      <div className="composer-footer"><span id="analysis-help" className="small">React 일정 분석은 준비 중이에요.<br />현재는 기존 플래너에서 분석·추천·등록을 이용해 주세요.</span>
        <button className="btn btn-primary" type="button" disabled>AI로 일정 분석하기<TimetableIcon name="next" /></button></div></div>
    <a className="link-btn" href="/demo.html">기존 플래너에서 일정 분석하기<TimetableIcon name="next" /></a>
    <div className="hero-bottom"><TimetableIcon name="info" />내가 선택하기 전에는 일정이 확정되지 않아요</div>
  </section>;
}
