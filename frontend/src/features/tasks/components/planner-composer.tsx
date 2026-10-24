import { useMemo, useState } from 'react';
import { AppIcon } from '@/app/components/app-icon';
import { navigateApp } from '@/app/app-routing';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';
import type { TaskRead, TaskReadClient } from '@/features/tasks/task.types';
import { TaskPlanningApi } from '@/features/tasks/api/task-planning.api';
import { usePlannerAction } from '@/features/tasks/hooks/use-planner-action';
import { plannerError } from '@/features/tasks/planner-error';

export function PlannerComposer({ client }: { client: TaskReadClient }) {
  const api = useMemo(() => new TaskPlanningApi(client), [client]);
  const [message, setMessage] = useState(''); const [validation, setValidation] = useState('');
  const action = usePlannerAction<TaskRead>(); const failure = action.error ? plannerError(action.error, 'analysis') : null;
  function change(value: string) { setMessage(value); setValidation(''); action.invalidate(); }
  function analyze(event: React.FormEvent) {
    event.preventDefault();
    if (!message.trim() || message.length > 500) { setValidation(message.trim() ? '500자 이내로 입력해 주세요.' : '분석할 일정 내용을 입력해 주세요.'); return; }
    void action.run(() => api.analyze(message), (task) => navigateApp(`/#planner?taskId=${task.id}`));
  }
  return <section className="hero" aria-labelledby="composer-title"><div className="hero-title-row"><div><div className="section-kicker">A LITTLE PLAN, A BETTER DAY</div>
    <h2 id="composer-title">하고 싶은 일을,<br /><em>딱 맞는 일정</em>으로.</h2><p>편하게 말해 주세요. 내 수업과 선호에 맞춰 시간을 찾아볼게요.</p></div>
    <div className="hero-symbol" aria-hidden="true"><i /><i><TimetableIcon name="check" /></i><i /></div></div>
    <form className="composer" onSubmit={analyze} aria-busy={action.pending}><label htmlFor="natural-message"><AppIcon name="planner" />어떤 일정을 계획하고 있나요?</label>
      <textarea id="natural-message" rows={2} value={message} onChange={(event) => change(event.target.value)} placeholder="목요일 저녁 7시에 알고리즘 공부를 2시간 하고 싶어." aria-invalid={!!validation} aria-describedby="analysis-help analysis-validation" />
      <div className="composer-footer"><span id="analysis-help" className="small">{message.length}/500자 · AI가 조건을 분석하고 할 일을 저장해요.<br />추천은 분석 결과를 확인한 후 별도로 생성해요.</span>
        <button className="btn btn-primary" type="submit" disabled={action.pending}>{action.pending ? '분석하고 저장하는 중…' : 'AI로 일정 분석하기'}<TimetableIcon name="next" /></button></div>
      <p id="analysis-validation" className="field-error" role={validation ? 'alert' : undefined}>{validation}</p>
    </form>
    {action.pending && <p role="status">AI 분석·할 일 저장 중이에요. 입력을 바꾸면 이전 응답으로 화면을 이동하지 않아요.</p>}
    {failure && <div className="notice error" role="alert"><div><strong>{failure.title}</strong><p>{failure.message}</p>{failure.uncertain && <a className="link-btn" href="/#planner">저장한 할 일 확인하기</a>}</div></div>}
    <div className="hero-bottom"><TimetableIcon name="info" />내가 선택하기 전에는 일정이 확정되지 않아요</div>
  </section>;
}
