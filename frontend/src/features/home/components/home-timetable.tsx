import { useCallback, useState } from 'react';
import { TimetableApi, displayTime } from '@/features/timetable/api/timetable.api';
import { TimetableWeek } from '@/features/timetable/components/timetable-week';
import { TimetableDialog } from '@/features/timetable/components/timetable-dialog';
import { DAY_LABELS, TIMETABLE_DAYS } from '@/features/timetable/timetable.types';
import type { TimetableDay } from '@/features/timetable/timetable.types';
import { navigateApp } from '@/app/app-routing';
import { useRemoteRead } from '@/shared/hooks/use-remote-read';
import { RemoteReadNotice } from '@/shared/components/remote-read-notice';

export function HomeTimetable({ api, now }: { api: TimetableApi; now: Date }) {
  const request = useCallback(() => api.list(), [api]);
  const query = useRemoteRead(request);
  const [selectedDay, setSelectedDay] = useState<TimetableDay | null>(null);
  const [detailId, setDetailId] = useState<number | null>(null);
  const today = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', weekday: 'long' }).format(now).toUpperCase() as TimetableDay;
  return <section className="timetable-workspace home-timetable" aria-label="홈 수업 시간표">
    <RemoteReadNotice {...query} label="수업을" onRetry={query.refresh} />
    {query.data && <TimetableWeek slots={query.data} day={selectedDay || today} onDay={setSelectedDay} referenceNow={now} readOnly disabled={false}
      onAdd={() => navigateApp('/#timetable')} onDetail={(slot) => setDetailId(slot.id)} onEdit={() => {}} onDelete={() => {}} />}
    {detailId !== null && <HomeClassDetail key={detailId} api={api} id={detailId} onClose={() => setDetailId(null)} />}
  </section>;
}
function HomeClassDetail({ api, id, onClose }: { api: TimetableApi; id: number; onClose: () => void }) {
  const request = useCallback(() => api.read(id), [api, id]); const detail = useRemoteRead(request);
  const slot = detail.data;
  return <TimetableDialog title={slot?.subjectName || '수업 상세'} busy={false} onClose={onClose} fallbackFocus={() => document.getElementById('main')?.focus()}>
    <RemoteReadNotice {...detail} label="수업 상세를" onRetry={detail.refresh} />
    {slot && <dl className="condition-grid"><div><dt>요일</dt><dd>{DAY_LABELS[TIMETABLE_DAYS.find((day) => day === slot.dayOfWeek)!]}요일</dd></div>
      <div><dt>시간</dt><dd>{displayTime(slot.startTime)}–{displayTime(slot.endTime)}</dd></div><div className="full"><dt>장소</dt><dd>{slot.location || '장소 미등록'}</dd></div></dl>}
    <div className="actions"><button type="button" className="btn btn-secondary" onClick={onClose}>닫기</button><a className="btn btn-primary" href="/#timetable">시간표 관리하기</a></div>
  </TimetableDialog>;
}
