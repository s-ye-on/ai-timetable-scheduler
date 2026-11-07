import { useEffect, useRef } from 'react';

export interface ServiceScreenshot { src: string; title: string; alt: string }

export function ServiceImageDialog({ screenshot, onClose }: { screenshot: ServiceScreenshot; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const node = dialog.current!;
    const previousFocus = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    if (typeof node.showModal === 'function') node.showModal();
    else node.setAttribute('open', '');
    return () => {
      if (typeof node.close === 'function') node.close();
      document.body.style.overflow = previousOverflow;
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);

  return <dialog ref={dialog} aria-labelledby="image-dialog-title"
    onCancel={(event) => { event.preventDefault(); onClose(); }}
    onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="dialog-heading"><div><h2 id="image-dialog-title">{screenshot.title}</h2>
      <p>원본 화면입니다. 작은 화면에서는 좌우로 이동해 확인하세요.</p></div>
      <button className="close-button" type="button" aria-label="화면 확대 닫기" onClick={onClose}>×</button></div>
    <div className="image-scroll"><img src={screenshot.src} alt={screenshot.alt} /></div>
  </dialog>;
}
