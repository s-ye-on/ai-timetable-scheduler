import { useCallback, useEffect, useRef, useState } from 'react';
import type { TimetableDay, TimetableDraft, TimetableSlot } from '@/features/timetable/timetable.types';
import { DAY_LABELS } from '@/features/timetable/timetable.types';
import { displayTime, sortSlots, TimetableApi, timetableFailure } from '@/features/timetable/api/timetable.api';
import { TimetableForm } from '@/features/timetable/components/timetable-form';
import { TimetableWeek, TimetableCourseCard } from '@/features/timetable/components/timetable-week';
import { TimetableDialog } from '@/features/timetable/components/timetable-dialog';

/** M3에서도 이동·온보딩 정책을 주입하지 않고 그대로 사용할 수 있는 수업 관리 영역. */
export function TimetableWorkspace({ api }: { api: TimetableApi }) {
  const [slots, setSlots] = useState<TimetableSlot[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState('');
  const [day, setDay] = useState<TimetableDay>('MONDAY');
  const [editor, setEditor] = useState<{ slot?: TimetableSlot; open: boolean; epoch: number }>({ open: false, epoch: 0 });
  const [dialog, setDialog] = useState<{ kind: 'detail' | 'delete'; slot: TimetableSlot; loading?: boolean; error?: string } | null>(null);
  const mounted = useRef(false);
  const readGeneration = useRef(0);
  const detailGeneration = useRef(0);
  const mutation = useRef(false);
  const overview = useRef<HTMLDivElement>(null);
  const focusOverview = () => overview.current?.querySelector<HTMLButtonElement>('.course-add-button')?.focus({ preventScroll: true });
  const load = useCallback(async () => {
    const generation = ++readGeneration.current;
    try {
      const result = await api.list();
      if (mounted.current && generation === readGeneration.current) { setSlots(result); setLoadError(''); setUncertain(false); }
    } catch (error) {
      if (mounted.current && generation === readGeneration.current) setLoadError(timetableFailure(error).message);
    } finally { if (mounted.current && generation === readGeneration.current) setLoading(false); }
  }, [api]);
  useEffect(() => {
    mounted.current = true; void load();
    return () => { mounted.current = false; readGeneration.current++; detailGeneration.current++; };
  }, [load]);

  function refresh() { if (!mutation.current) { setLoading(true); setMessage(''); void load(); } }
  function closeDialog() { if (!mutation.current) { detailGeneration.current++; setDialog(null); } }
  function edit(slot?: TimetableSlot) { closeDialog(); setEditor((value) => ({ slot, open: true, epoch: value.epoch + 1 })); }
  async function detail(slot: TimetableSlot) {
    const generation = ++detailGeneration.current;
    setDialog({ kind: 'detail', slot, loading: true });
    try {
      const result = await api.read(slot.id);
      if (mounted.current && generation === detailGeneration.current) setDialog({ kind: 'detail', slot: result });
    } catch (error) {
      if (mounted.current && generation === detailGeneration.current) setDialog({ kind: 'detail', slot, error: timetableFailure(error).message });
    }
  }
  async function save(draft: TimetableDraft, id?: number) {
    if (mutation.current) return;
    mutation.current = true; setPending(true); setMessage('');
    try {
      const result = await api.save(draft, id);
      if (!mounted.current) return;
      readGeneration.current++;
      setSlots((previous) => sortSlots([...(previous || []).filter((slot) => slot.id !== result.id), result]));
      setDay(result.dayOfWeek); setEditor((value) => ({ open: false, epoch: value.epoch + 1 })); setMessage(id ? '수업을 수정했어요.' : '수업을 추가했어요.');
    } catch (error) {
      if (mounted.current && timetableFailure(error).uncertain) setUncertain(true);
      throw error;
    } finally { mutation.current = false; if (mounted.current) setPending(false); }
  }
  async function remove(slot: TimetableSlot) {
    if (mutation.current) return;
    mutation.current = true; setPending(true); setMessage('');
    try {
      await api.remove(slot.id);
      if (!mounted.current) return;
      readGeneration.current++; setSlots((previous) => previous!.filter((item) => item.id !== slot.id));
      setDialog(null); if (editor.slot?.id === slot.id) setEditor((value) => ({ open: false, epoch: value.epoch + 1 })); setMessage('수업을 삭제했어요.');
    } catch (error) {
      if (mounted.current) {
        const failure = timetableFailure(error); setDialog({ kind: 'delete', slot, error: failure.message }); if (failure.uncertain) setUncertain(true);
      }
    } finally { mutation.current = false; if (mounted.current) setPending(false); }
  }
  const disabled = loading || pending || Boolean(loadError) || uncertain;
  const callbacks = { disabled, onAdd: () => edit(), onDetail: (slot: TimetableSlot) => { void detail(slot); }, onEdit: edit,
    onDelete: (slot: TimetableSlot) => { detailGeneration.current++; setDialog({ kind: 'delete', slot }); } };
  return <div className="timetable-workspace">
    <div className="timetable-status"><span className="badge neutral">{slots === null ? '수업 조회 중' : `등록된 수업 ${slots.length}개`}</span>
      <button className="btn btn-secondary btn-sm" type="button" onClick={refresh} disabled={loading || pending}>수업 목록 새로고침</button></div>
    {message && <p className="notice success" role="status">{message}</p>}
    {loading && <p className="notice" role="status">수업을 불러오고 있어요.</p>}
    {loadError && <div className="notice error" role="alert"><div><strong>수업을 불러오지 못했어요</strong><p>{loadError}</p>{slots && <p>아래는 마지막으로 확인한 수업이에요. 다시 조회한 후 변경해 주세요.</p>}<button className="btn btn-secondary btn-sm" onClick={refresh} disabled={loading}>다시 조회하기</button></div></div>}
    {uncertain && <div className="notice warn" role="alert"><div><strong>저장·삭제 결과를 확인하지 못했어요.</strong><p>중복 요청을 보내기 전에 수업 목록을 새로고침해 현재 상태를 확인해 주세요.</p><button className="btn btn-secondary btn-sm" onClick={refresh} disabled={loading || pending}>현재 수업 다시 확인</button></div></div>}
    {slots !== null && <div className="course-workspace">
      <div className="course-overview" ref={overview}><TimetableWeek {...callbacks} slots={slots} day={day} onDay={setDay} /></div>
      <TimetableForm key={`${editor.slot?.id || 'new'}:${editor.epoch}`} slot={editor.slot} slots={slots} open={editor.open} focus={editor.open} disabled={disabled}
        onCancel={() => { setEditor((value) => ({ open: false, epoch: value.epoch + 1 })); focusOverview(); }} onSave={save} />
      <section className="card card-pad course-catalog" aria-label="전체 등록된 수업"><div className="card-head"><div><h2>등록된 수업</h2><p className="small muted">전체 {slots.length}개 · 월요일부터 일요일, 시작 시각 순서</p></div></div>
        {slots.length ? <ul className="course-catalog-list">{slots.map((slot) => <TimetableCourseCard {...callbacks} key={slot.id} slot={slot} showDay />)}</ul> : <div className="empty"><h3>첫 수업을 등록해 보세요</h3><p>시간표가 없어도 추천은 이용할 수 있어요.</p><button className="btn btn-secondary" onClick={() => edit()} disabled={disabled}>수업 추가하기</button></div>}
      </section>
    </div>}
    {dialog && <TimetableDialog title={dialog.kind === 'delete' ? '이 수업을 삭제할까요?' : dialog.slot.subjectName} busy={pending} onClose={closeDialog} fallbackFocus={focusOverview}>
      {dialog.loading ? <p role="status">수업 정보를 확인하고 있어요.</p> : dialog.error ? <p className="notice error" role="alert">{dialog.error}</p> : <>
        {dialog.kind === 'detail' ? <dl className="condition-grid"><div><dt>요일</dt><dd>{DAY_LABELS[dialog.slot.dayOfWeek]}요일</dd></div><div><dt>시간</dt><dd>{displayTime(dialog.slot.startTime)}–{displayTime(dialog.slot.endTime)}</dd></div><div className="full"><dt>장소</dt><dd>{dialog.slot.location || '등록하지 않았어요'}</dd></div></dl>
          : <p className="dialog-copy">{dialog.slot.subjectName} · {DAY_LABELS[dialog.slot.dayOfWeek]}요일 {displayTime(dialog.slot.startTime)}–{displayTime(dialog.slot.endTime)}<br />삭제하면 추천할 때 이 수업 시간을 피하지 않아요.</p>}
      </>}
      <div className="actions"><button className="btn btn-secondary" onClick={closeDialog} disabled={pending}>{dialog.kind === 'delete' ? '유지하기' : '닫기'}</button>
        {dialog.kind === 'delete' ? <button className="btn btn-danger" disabled={pending || uncertain || Boolean(dialog.error)} onClick={() => { void remove(dialog.slot); }}>{pending ? '삭제 중…' : '수업 삭제'}</button>
          : <button className="btn btn-primary" disabled={disabled || dialog.loading || Boolean(dialog.error)} onClick={() => edit(dialog.slot)}>수업 수정하기</button>}</div>
    </TimetableDialog>}
  </div>;
}
