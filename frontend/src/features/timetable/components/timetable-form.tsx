import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { COLOR_LABELS, DAY_LABELS, TIMETABLE_COLORS, TIMETABLE_DAYS } from '@/features/timetable/timetable.types';
import type { TimetableDay, TimetableDraft, TimetableFieldErrors, TimetableSlot } from '@/features/timetable/timetable.types';
import { displayTime, timeMinutes, validTime, timetableFailure } from '@/features/timetable/api/timetable.api';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

interface TimetableFormProps {
  slot?: TimetableSlot; slots: TimetableSlot[]; open: boolean; focus: boolean; disabled: boolean;
  onCancel: () => void; onSave: (draft: TimetableDraft, id?: number) => Promise<void>;
}
export function TimetableForm({ slot, slots, open, focus, disabled, onCancel, onSave }: TimetableFormProps) {
  const [draft, setDraft] = useState<TimetableDraft>(() => slot ? { ...slot } : { subjectName: '', dayOfWeek: 'MONDAY', startTime: '09:00', endTime: '10:30', location: '', color: 'BLUE' });
  const [errors, setErrors] = useState<TimetableFieldErrors>({});
  const [message, setMessage] = useState('');
  const [pending, setPending] = useState(false);
  const submitted = useRef(false);
  const section = useRef<HTMLElement>(null);
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => { if (focus) { section.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }); form.current?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true }); } }, [focus]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || submitted.current) return;
    const next: TimetableFieldErrors = {};
    if (!draft.subjectName.trim()) next.subjectName = '과목명을 입력해 주세요.';
    else if (draft.subjectName.length > 100) next.subjectName = '과목명은 100자 이하로 입력해 주세요.';
    if ((draft.location || '').length > 100) next.location = '장소는 100자 이하로 입력해 주세요.';
    if (!validTime(draft.startTime)) next.startTime = '시작 시각을 입력해 주세요.';
    if (!validTime(draft.endTime)) next.endTime = '종료 시각을 입력해 주세요.';
    if (!next.startTime && !next.endTime) {
      if (timeMinutes(draft.startTime) >= timeMinutes(draft.endTime)) next.endTime = '종료 시간을 시작 시간보다 늦게 설정해 주세요.';
      else if (slots.some((item) => item.id !== slot?.id && item.dayOfWeek === draft.dayOfWeek
        && timeMinutes(draft.startTime) < timeMinutes(item.endTime) && timeMinutes(draft.endTime) > timeMinutes(item.startTime))) {
        next.startTime = '이미 등록한 수업과 시간이 겹쳐요. 요일이나 시간을 바꿔 주세요.';
      }
    }
    setErrors(next); setMessage('');
    if (Object.keys(next).length) {
      form.current?.querySelector<HTMLElement>(`[name="${Object.keys(next)[0]}"]`)?.focus(); return;
    }
    submitted.current = true; setPending(true);
    try {
      const { subjectName, dayOfWeek, startTime, endTime, location, color } = draft;
      await onSave({ subjectName: subjectName.trim(), dayOfWeek, startTime, endTime, location: location?.trim() || null, color }, slot?.id);
    } catch (error) {
      const failure = timetableFailure(error); setMessage(failure.message);
      setErrors(Object.fromEntries(Object.entries(failure.fields).filter(([field]) => ['subjectName', 'dayOfWeek', 'startTime', 'endTime', 'location', 'color'].includes(field))));
    } finally { submitted.current = false; setPending(false); }
  }
  const busy = disabled || pending;
  function error(field: keyof TimetableDraft) { return errors[field] && <p className="field-error" id={`class-error-${field}`}>{errors[field]}</p>; }
  const timeInput = (field: 'startTime' | 'endTime', label: string) => <div className="field"><label htmlFor={`class-${field}`}>{label} <span className="muted">(필수)</span></label>
    <input id={`class-${field}`} name={field} type={displayTime(draft[field]).length > 5 ? 'text' : 'time'} inputMode="numeric" step="60" required value={displayTime(draft[field])} disabled={busy}
      onChange={(event) => setDraft({ ...draft, [field]: event.target.value })} aria-invalid={Boolean(errors[field])} aria-describedby={errors[field] ? `class-error-${field}` : undefined} />{error(field)}</div>;
  return <section ref={section} id="class-editor" className={`card card-pad course-editor${open ? ' is-open' : ''}`} aria-labelledby="class-form-title">
    <div className="setup-title"><div className="section-kicker">MY CLASS, MY WEEK</div><div className="od-row"><h2 className="od-fill" id="class-form-title">{slot ? '수업 수정하기' : '새 수업 추가하기'}</h2>
      <button className="icon-btn course-editor-close" type="button" onClick={onCancel} disabled={busy} aria-label="수업 폼 닫기"><TimetableIcon name="close" /></button></div>
      <p>{slot ? '수정한 내용은 저장하면 시간표에 반영돼요.' : '수업을 하나씩 추가해 나만의 한 주를 채워요.'}</p></div>
    <form ref={form} noValidate onSubmit={submit} aria-busy={pending}>
      {(message || Object.keys(errors).length > 0) && <div className="notice error" role="alert"><div><strong>{message || '입력 내용을 확인해 주세요'}</strong>
        <ul>{Object.entries(errors).map(([field, text]) => <li key={field}><a href={`#class-${field}`} onClick={(event) => { event.preventDefault(); form.current?.querySelector<HTMLElement>(`[name="${field}"]`)?.focus(); }}>{text}</a></li>)}</ul></div></div>}
      <div className="form-grid course-form-grid">
        <div className="field full"><label htmlFor="class-subjectName">수업명 <span className="muted">(필수)</span></label><input id="class-subjectName" name="subjectName" required maxLength={100} placeholder="예: 자료구조" value={draft.subjectName} disabled={busy}
          onChange={(event) => setDraft({ ...draft, subjectName: event.target.value })} aria-invalid={Boolean(errors.subjectName)} aria-describedby={errors.subjectName ? 'class-error-subjectName' : undefined} />{error('subjectName')}</div>
        <div className="field full"><label htmlFor="class-dayOfWeek">요일</label><select id="class-dayOfWeek" name="dayOfWeek" value={draft.dayOfWeek} disabled={busy} onChange={(event) => setDraft({ ...draft, dayOfWeek: event.target.value as TimetableDay })}>{TIMETABLE_DAYS.map((day) => <option key={day} value={day}>{DAY_LABELS[day]}요일</option>)}</select>{error('dayOfWeek')}</div>
        {timeInput('startTime', '시작 시각')}{timeInput('endTime', '종료 시각')}
        <div className="field full"><label htmlFor="class-location">장소 <span className="muted">(선택)</span></label><input id="class-location" name="location" maxLength={100} placeholder="예: 공학관 302" value={draft.location || ''} disabled={busy} onChange={(event) => setDraft({ ...draft, location: event.target.value })} aria-invalid={Boolean(errors.location)} aria-describedby={errors.location ? 'class-error-location' : undefined} />{error('location')}</div>
        <fieldset className="full" disabled={busy}><legend>시간표 색상</legend><div className="color-options">{TIMETABLE_COLORS.map((color) => <label key={color} className={`color-option color-${color.toLowerCase()}`}><input type="radio" name="color" value={color} aria-label={COLOR_LABELS[color]} checked={draft.color === color} onChange={() => setDraft({ ...draft, color })} /><TimetableIcon name="check" /></label>)}</div>
          <p className="field-help">수업별로 고른 색상이 저장돼요.<br />같은 과목도 수업마다 다르게 지정할 수 있어요.</p>{error('color')}</fieldset>
        <div className="course-form-actions full"><button className="btn btn-primary" type="submit" disabled={busy}><TimetableIcon name={slot ? 'check' : 'plus'} />{pending ? '저장 중…' : slot ? '수정 내용 저장' : '수업 추가하기'}</button>
          {slot && <button className="btn btn-secondary" type="button" disabled={busy} onClick={onCancel}>수정 취소</button>}</div>
      </div>
    </form>
  </section>;
}
