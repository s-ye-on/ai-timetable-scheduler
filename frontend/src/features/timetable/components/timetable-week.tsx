import { useState } from 'react';
import { DAY_LABELS, TIMETABLE_DAYS } from '@/features/timetable/timetable.types';
import type { TimetableDay, TimetableSlot } from '@/features/timetable/timetable.types';
import { displayTime, timeMinutes } from '@/features/timetable/api/timetable.api';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

export function weekDates(now: Date, offset: number): string[] {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const part = (type: string) => parts.find((value) => value.type === type)!.value;
  const date = new Date(`${part('year')}-${part('month')}-${part('day')}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - (date.getUTCDay() + 6) % 7 + offset * 7);
  return TIMETABLE_DAYS.map((_, index) => new Date(date.getTime() + index * 86400000).toISOString().slice(0, 10));
}
const dateLabel = (date: string) => `${Number(date.slice(5, 7))}월 ${Number(date.slice(8))}일`;

interface TimetableWeekProps {
  slots: TimetableSlot[]; day: TimetableDay; onDay: (day: TimetableDay) => void; disabled: boolean;
  onAdd: () => void; onDetail: (slot: TimetableSlot) => void; onEdit: (slot: TimetableSlot) => void; onDelete: (slot: TimetableSlot) => void;
  readOnly?: boolean; referenceNow?: Date;
}
export function TimetableWeek(props: TimetableWeekProps) {
  const { slots, day, onDay, disabled, onAdd, onDetail } = props;
  const [offset, setOffset] = useState(0);
  const [mountedNow] = useState(() => new Date());
  const now = props.referenceNow || mountedNow;
  const today = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', weekday: 'long' }).format(now).toUpperCase();
  const dates = weekDates(now, offset);
  const weekdaySlots = slots.filter((slot) => TIMETABLE_DAYS.indexOf(slot.dayOfWeek) < 5);
  const firstHour = Math.min(8, ...weekdaySlots.map((slot) => Math.floor(timeMinutes(slot.startTime) / 60)));
  const lastHour = Math.max(22, ...weekdaySlots.map((slot) => Math.ceil(timeMinutes(slot.endTime) / 60)));
  const legends = slots.filter((slot, index) => slots.findIndex((other) => other.subjectName === slot.subjectName && other.color === slot.color) === index);
  const daily = slots.filter((slot) => slot.dayOfWeek === day);
  return <section className="card week-card course-week-card" aria-label="주간 수업 시간표">
    <div className="card-head"><div><h2 className="week-title">이번 주 수업</h2><div className="week-toolbar">
      <button className="icon-btn" type="button" onClick={() => setOffset(offset - 1)} aria-label="지난주"><TimetableIcon name="back" /></button>
      <span>{dateLabel(dates[0])} – {dateLabel(dates[4])}</span><button className="icon-btn" type="button" onClick={() => setOffset(offset + 1)} aria-label="다음 주"><TimetableIcon name="next" /></button>
    </div></div>{props.readOnly ? <a className="link-btn" href="/#timetable">수업 등록<TimetableIcon name="plus" /></a> : <button className="btn btn-soft btn-sm course-add-button" type="button" onClick={onAdd} disabled={disabled}><TimetableIcon name="plus" />수업 추가</button>}</div>
    {!props.readOnly && <div className="course-preview-note">등록한 수업 {slots.length}개 · 수업마다 색상을 지정할 수 있어요</div>}
    {legends.length > 0 && <div className="legend-row" aria-label="수업 색상 범례">{legends.map((slot) => <span key={JSON.stringify([slot.subjectName, slot.color])}><i className={`legend-dot color-${slot.color.toLowerCase()}`} />{slot.subjectName}</span>)}</div>}
    <div className="course-mobile-days"><div className="home-day-tabs" role="group" aria-label="시간표 요일 선택">{TIMETABLE_DAYS.map((value) => <button className={`home-day-tab${value === day ? ' active' : ''}`} key={value} type="button" aria-label={DAY_LABELS[value]} aria-pressed={value === day} onClick={() => onDay(value)}>{DAY_LABELS[value]}{value === today && <span className="home-day-today">오늘</span>}</button>)}</div>
      <section className="home-day-content" aria-label="선택한 요일 수업" aria-live="polite"><div className="home-day-heading"><h3>{DAY_LABELS[day]}요일 수업</h3><span className="home-class-count">{daily.length}개 수업</span></div>
        {daily.length ? <ul className="course-day-list">{daily.map((slot) => props.readOnly ? <li key={slot.id}><button type="button" className={`home-class-card color-${slot.color.toLowerCase()}`} onClick={() => onDetail(slot)}>
          <span>{displayTime(slot.startTime)}–{displayTime(slot.endTime)}</span><strong>{slot.subjectName}</strong><span>{slot.location || '장소 미등록'}</span></button></li> : <TimetableCourseCard {...props} slot={slot} key={slot.id} />)}</ul> : <div className="home-day-empty"><span className="empty-symbol"><TimetableIcon name="book" /></span><p>{slots.length ? '이날은 등록된 수업이 없어요' : '아직 등록된 수업이 없어요'}</p><span>다른 요일을 확인하거나 수업을 추가해 보세요.</span><button className="btn btn-secondary" type="button" onClick={onAdd} disabled={disabled}>수업 추가하기</button></div>}
        {props.readOnly && <div className="home-timetable-footer"><a className="btn btn-secondary" href="/#timetable">전체 시간표 보기<TimetableIcon name="next" /></a></div>}
      </section>
    </div>
    <div className="desktop-week">
      <div className="week-head"><div />{TIMETABLE_DAYS.slice(0, 5).map((value, index) => <div className={`week-day${offset === 0 && value === today ? ' today' : ''}`} key={value}><span>{DAY_LABELS[value]}</span><strong>{Number(dates[index].slice(8))}</strong></div>)}</div>
      {slots.length ? <div className="week-body" style={{ gridTemplateRows: `repeat(${(lastHour - firstHour) * 60},calc(2px / 3))` }}>
        {Array.from({ length: lastHour - firstHour }, (_, index) => <div className="week-hour" key={index}>
          <span className="time-label" style={{ gridRow: `${index * 60 + 1} / span 60` }}>{String(firstHour + index).padStart(2, '0')}:00</span>
          {Array.from({ length: 5 }, (_, dayIndex) => <div className="grid-line" key={dayIndex} aria-hidden="true" style={{ gridColumn: dayIndex + 2, gridRow: `${index * 60 + 1} / span 60` }} />)}
        </div>)}
        {weekdaySlots.map((slot) => {
          const start = Math.floor(timeMinutes(slot.startTime));
          const span = Math.max(1, Math.ceil(timeMinutes(slot.endTime)) - start);
          return <button key={slot.id} className={`class-block color-${slot.color.toLowerCase()}`} type="button" disabled={disabled} onClick={() => onDetail(slot)}
            style={{ gridColumn: TIMETABLE_DAYS.indexOf(slot.dayOfWeek) + 2, gridRow: `${start - firstHour * 60 + 1} / span ${span}` }}
            aria-label={`${slot.subjectName}, ${DAY_LABELS[slot.dayOfWeek]}요일 ${displayTime(slot.startTime)}부터 ${displayTime(slot.endTime)}, ${slot.location || '장소 미지정'}`}>
            <strong className="od-truncate">{slot.subjectName}</strong>{span >= 60 && <small>{displayTime(slot.startTime)}–{displayTime(slot.endTime)}</small>}{span >= 90 && <span className="location od-truncate">{slot.location || '장소 미지정'}</span>}
          </button>;
        })}
      </div> : <div className="empty"><span className="empty-symbol"><TimetableIcon name="book" /></span><h3>아직 등록된 수업이 없어요</h3><p>수업을 등록하면 수업 시간을 피해서 일정을 추천받을 수 있어요.</p><button className="btn btn-secondary" onClick={onAdd} disabled={disabled}>첫 수업 등록하기</button></div>}
    </div>
    <div className="week-foot"><TimetableIcon name="info" /><span>등록한 수업만 표시해요 · Google 전체 일정은 포함하지 않아요{slots.some((slot) => TIMETABLE_DAYS.indexOf(slot.dayOfWeek) >= 5) && <><br />토·일 수업은 요일별 목록과 전체 수업 목록에서 확인해 주세요.</>}</span></div>
  </section>;
}

export function TimetableCourseCard({ slot, disabled, showDay, onDetail, onEdit, onDelete }: Pick<TimetableWeekProps, 'disabled' | 'onDetail' | 'onEdit' | 'onDelete'> & { slot: TimetableSlot; showDay?: boolean }) {
  return <li className={`course-card color-${slot.color.toLowerCase()}`}><div className="od-field course-card-body">
    <span className="course-time">{showDay && `${DAY_LABELS[slot.dayOfWeek]}요일 · `}{displayTime(slot.startTime)}–{displayTime(slot.endTime)}</span>
    <button className="course-name" type="button" onClick={() => onDetail(slot)} disabled={disabled} aria-label={`${slot.subjectName} 수업 상세`}><strong>{slot.subjectName}</strong></button>
    <span className="course-place"><TimetableIcon name="pin" /><span>{slot.location || '장소 미등록'}</span></span></div>
    <div className="course-actions"><button type="button" className="btn btn-secondary btn-sm" onClick={() => onEdit(slot)} disabled={disabled} aria-label={`${slot.subjectName} 수업 수정`}>수정</button><button type="button" className="btn btn-danger btn-sm" onClick={() => onDelete(slot)} disabled={disabled} aria-label={`${slot.subjectName} 수업 삭제`}>삭제</button></div>
  </li>;
}
