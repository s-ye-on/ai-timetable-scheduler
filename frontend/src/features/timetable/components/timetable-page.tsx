import { useMemo } from 'react';
import type { TimetableRequestClient } from '@/features/timetable/timetable.types';
import { TimetableApi } from '@/features/timetable/api/timetable.api';
import { TimetableWorkspace } from '@/features/timetable/components/timetable-workspace';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

export function TimetablePage({ client }: { client: TimetableRequestClient }) {
  const api = useMemo(() => new TimetableApi(client), [client]);
  return <div className="timetable-workspace course-page">
    <a className="return-link" href="/#home"><TimetableIcon name="back" />홈으로 돌아가기</a>
    <div className="page-heading"><div><div className="section-kicker">MY WEEK, AT A GLANCE</div><h1>내 시간표 관리</h1><p>수업이 바뀌어도 괜찮아요. 나의 한 주를 편하게 관리해요.</p></div></div>
    <TimetableWorkspace api={api} />
  </div>;
}
