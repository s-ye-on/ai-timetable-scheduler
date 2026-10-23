import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { TimetableIcon } from '@/features/timetable/components/timetable-icon';

export function TimetableDialog({ title, busy, onClose, fallbackFocus, children }: { title: string; busy?: boolean; onClose: () => void; fallbackFocus: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    const node = dialog.current!;
    if (typeof node.showModal === 'function') node.showModal();
    else node.setAttribute('open', '');
    return () => {
      if (typeof node.close === 'function') node.close();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
      else fallbackFocus();
    };
  }, []);
  return <dialog className="timetable-dialog" ref={dialog} aria-labelledby="timetable-dialog-title" onCancel={(event) => { event.preventDefault(); if (!busy) onClose(); }}>
    <div className="dialog-body"><div className="dialog-head"><h2 id="timetable-dialog-title">{title}</h2>
      <button className="icon-btn" type="button" disabled={busy} onClick={onClose} aria-label="닫기"><TimetableIcon name="close" /></button></div>{children}</div>
  </dialog>;
}
