import { useCallback, useEffect, useRef, useState } from 'react';
import type { Preference, PreferenceValues } from '@/features/preferences/preference.types';
import { PreferenceApi, preferenceDisplayTime, preferenceErrors, preferenceFailure } from '@/features/preferences/api/preference.api';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

const periods = [ ['ANYTIME', '상관없음', ''], ['MORNING', '오전', '09:00–12:00'], ['LUNCH', '점심', '12:00–14:00'],
  ['AFTERNOON', '오후', '14:00–18:00'], ['EVENING', '저녁', '18:00–22:00'] ];
const densities = [['COMPACT', '촘촘하게'], ['BALANCED', '균형 있게'], ['RELAXED', '여유롭게']];
const timings = [['ASAP', '가능한 빨리'], ['BALANCED', '균형 있게'], ['NEAR_DEADLINE', '마감에 가깝게']];

export function PreferenceForm({ api, manage, onSaved, onBusyChange }: {
  api: PreferenceApi; manage: boolean; onSaved: () => void; onBusyChange: (busy: boolean) => void;
}) {
  const [saved, setSaved] = useState<Preference | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [pending, setPending] = useState(false);
  const [epoch, setEpoch] = useState(0);
  const active = useRef(false);
  const generation = useRef(0);
  const mutation = useRef(false);
  const load = useCallback(async () => {
    const version = ++generation.current; setLoading(true);
    try {
      const result = await api.read();
      if (active.current && version === generation.current) { setSaved(result); setError(''); setUncertain(false); setEpoch((v) => v + 1); }
    } catch (failure) { if (active.current && version === generation.current) setError(preferenceFailure(failure).message); }
    finally { if (active.current && version === generation.current) setLoading(false); }
  }, [api]);
  useEffect(() => { active.current = true; void load(); return () => { active.current = false; generation.current++; }; }, [load]);
  useEffect(() => { onBusyChange(pending); return () => onBusyChange(false); }, [pending, onBusyChange]);

  async function update(values?: PreferenceValues): Promise<void> {
    if (mutation.current) return;
    mutation.current = true; setPending(true); setMessage('');
    try {
      const result = await (values ? api.save(values) : api.reset());
      if (!active.current) return;
      generation.current++; setSaved(result); setError(''); setUncertain(false); setEpoch((v) => v + 1);
      if (values) onSaved(); else setMessage('기본값으로 복원했어요.');
    } catch (failure) {
      if (active.current) { if (preferenceFailure(failure).uncertain) setUncertain(true); throw failure; }
    } finally { mutation.current = false; if (active.current) setPending(false); }
  }
  return <section className="card preferences-card" aria-labelledby="preference-title">
    <div className="setup-title"><div className="section-kicker">MAKE ROOM FOR YOUR RHYTHM</div><h2 id="preference-title">일정 추천 기준을 정해볼까요?</h2>
      <p>평소 편한 시간대와 일정 스타일을 설정해 주세요. 기본값으로 시작해도 괜찮아요.</p></div>
    {loading && <p role="status" className="notice">선호 설정을 불러오고 있어요.</p>}
    {error && <div role="alert" className="notice error"><div><strong>선호 설정을 불러오지 못했어요</strong><p>{error}</p><p>기본값으로 대체하거나 저장하지 않았어요.</p></div></div>}
    {uncertain && <div className="notice warn" role="alert">저장 결과를 확인하지 못했어요. 다시 저장하기 전에 서버의 선호 설정을 확인해 주세요.</div>}
    {(error || uncertain) && <button type="button" className="btn btn-secondary" disabled={loading || pending} onClick={() => { void load(); }}>저장된 선호 다시 불러오기</button>}
    {message && <p role="status" className="notice success">기본값으로 복원했어요.</p>}
    {saved && <PreferenceFields key={epoch} initial={saved} manage={manage} disabled={loading || pending || uncertain || Boolean(error)} pending={pending}
      onSave={update} onReset={() => update()} />}
  </section>;
}

function PreferenceFields({ initial, manage, disabled, pending, onSave, onReset }: {
  initial: Preference; manage: boolean; disabled: boolean; pending: boolean;
  onSave: (values: PreferenceValues) => Promise<void>; onReset: () => Promise<void>;
}) {
  const [values, setValues] = useState(initial);
  const [gap, setGap] = useState(String(initial.minimumGapMinutes));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  function change<K extends keyof PreferenceValues>(key: K, value: PreferenceValues[K]) { setValues((previous) => ({ ...previous, [key]: value })); }
  const inputError = (name: string) => errors[name] ? <p className="field-error" id={`error-${name}`}>{errors[name]}</p> : null;
  function choices(field: 'preferredTimeRange' | 'scheduleDensity' | 'deadlineTiming', options: string[][]) {
    return <div className="choice-grid">{options.map(([value, label, hint]) => <label className="choice" key={value}>
      <input type="radio" name={field} value={value} checked={values[field] === value} onChange={() => change(field, value as PreferenceValues[typeof field])} />
      <span className="od-field"><span>{label}</span>{hint && <small>{hint}</small>}</span></label>)}</div>;
  }
  async function reset() { setError(''); try { await onReset(); } catch (failure) { setError(preferenceFailure(failure).message); } }
  return <form noValidate onSubmit={async (event) => {
    event.preventDefault(); if (disabled) return;
    const { preferredTimeRange, scheduleStartTime, scheduleEndTime, scheduleDensity, deadlineTiming } = values;
    const draft = { preferredTimeRange, scheduleStartTime, scheduleEndTime, scheduleDensity, deadlineTiming, minimumGapMinutes: gap.trim() ? Number(gap) : NaN };
    const invalid = preferenceErrors(draft); setErrors(invalid); setError('');
    if (Object.keys(invalid).length) { document.getElementById(`pref-${Object.keys(invalid)[0]}`)?.focus(); return; }
    try { await onSave(draft); } catch (failure) { const details = preferenceFailure(failure); setError(details.message); setErrors(details.fields); }
  }}>
    {error && <p role="alert" className="notice error">{error}</p>}
    <fieldset disabled={disabled} className="preference-fields"><legend className="sr-only">선호 설정 입력</legend>
      <fieldset className="pref-section"><legend>어떤 시간대가 편하신가요?</legend>{choices('preferredTimeRange', periods)}</fieldset>
      <section className="pref-section" aria-labelledby="availability-title"><h3 id="availability-title">하루 가용 시간과 일정 사이 여유</h3><div className="form-grid">
        {(['scheduleStartTime', 'scheduleEndTime'] as const).map((key) => <div className="field" key={key}><label htmlFor={`pref-${key}`}>{key === 'scheduleStartTime' ? '가용 시작 시간' : '가용 종료 시간'}</label>
          <input id={`pref-${key}`} type={preferenceDisplayTime(values[key]).length > 5 ? 'text' : 'time'} step="60" value={preferenceDisplayTime(values[key])}
            onChange={(event) => change(key, event.target.value)} aria-invalid={Boolean(errors[key])} aria-describedby={errors[key] ? `error-${key}` : undefined} />{inputError(key)}</div>)}
        <div className="field full"><label htmlFor="pref-minimumGapMinutes">일정 사이 최소 여유 시간</label><input id="pref-minimumGapMinutes" type="number" min="0" step="5" value={gap}
          onChange={(event) => setGap(event.target.value)} aria-invalid={Boolean(errors.minimumGapMinutes)} aria-describedby="gap-help error-minimumGapMinutes" />
          <p id="gap-help" className="field-help">0 이상, 5분 단위로 입력해 주세요.</p>{inputError('minimumGapMinutes')}</div>
      </div></section>
      <fieldset className="pref-section"><legend>일정을 배치하는 방식</legend>{choices('scheduleDensity', densities)}</fieldset>
      <fieldset className="pref-section"><legend>마감이 있는 일은 언제 할까요?</legend>{choices('deadlineTiming', timings)}</fieldset>
    </fieldset>
    <div className="divider" /><div className="actions between"><button className="btn btn-text" type="button" disabled={disabled} onClick={() => { void reset(); }}>기본값으로 초기화</button>
      <button className="btn btn-primary" type="submit" disabled={disabled}>{pending ? '저장 중…' : manage ? '선호 저장하고 돌아가기' : '선호 저장하고 다음'}<TimetableIcon name="next" /></button></div>
  </form>;
}
