import type { ReactNode } from 'react';
import { useEffect, useRef } from 'react';
import { PublicPageLayout } from '@/features/public-pages/components/public-page-layout';
import { PRIVACY_POLICY_MARKDOWN } from '@/features/public-pages/privacy-policy.content';

const [introduction, ...sections] = PRIVACY_POLICY_MARKDOWN.trim().split(/\n(?=## )/);
const policySections = sections.map((section) => {
  const [, number, title, body] = section.match(/^## (\d+)\. (.+)\n([\s\S]*)$/)!;
  return { id: `policy-${number}`, number: number.padStart(2, '0'), title, body: body.trim() };
});

function InlineText({ text }: { text: string }): ReactNode {
  return text.split(/(\*\*.+?\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\)|\[확정 필요\])/g).map((part, index) => {
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={index}><InlineText text={part.slice(2, -2)} /></strong>;
    if (part.startsWith('`') && part.endsWith('`')) return <code key={index}>{part.slice(1, -1)}</code>;
    if (part === '[확정 필요]') return <span className="pending" key={index}>{part}</span>;
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link && /^(https:\/\/|mailto:|\/(?!\/)|#)/.test(link[2])) return <a key={index} href={link[2]}>{link[1]}</a>;
    return part;
  });
}

/** 정책에 쓰는 문법만 React 요소로 렌더링한다. 원시 HTML과 스크립트는 실행하지 않는다. */
function PolicyBody({ source }: { source: string }) {
  return source.split(/\n\s*\n/).map((block, index) => {
    if (block.startsWith('|')) {
      const rows = block.split('\n').filter((line) => !/^\|[\s|:-]+\|$/.test(line))
        .map((line) => line.replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim()));
      return <div className="data-list" key={index}><table className="policy-table">
        <thead><tr>{rows[0].map((cell, column) => <th scope="col" key={column}><InlineText text={cell} /></th>)}</tr></thead>
        <tbody>{rows.slice(1).map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, column) => <td key={column}><InlineText text={cell} /></td>)}</tr>)}</tbody>
      </table></div>;
    }
    if (block.startsWith('- ')) return <ul key={index}>{block.split('\n').map((line, item) => <li key={item}><InlineText text={line.replace(/^- /, '')} /></li>)}</ul>;
    return <p key={index}><InlineText text={block} /></p>;
  });
}

function PolicyContents({ mobile = false }: { mobile?: boolean }) {
  const details = useRef<HTMLDetailsElement>(null);
  const navigation = <nav aria-label={mobile ? '모바일 문서 목차' : '문서 목차'}>{policySections.map((section) =>
    <a key={section.id} href={`#${section.id}`} onClick={() => { if (details.current) details.current.open = false; }}>
      <span>{section.number}</span> {section.title}
    </a>)}</nav>;
  return mobile ? <details className="mobile-contents" ref={details}><summary>목차 · 필요한 내용 바로 찾기</summary>{navigation}</details>
    : <aside className="contents" aria-label="문서 목차"><p>이 페이지의 내용</p>{navigation}<div className="toc-note">로그인하지 않아도<br />이 문서를 읽을 수 있습니다.</div></aside>;
}

export function PrivacyPage() {
  useEffect(() => {
    const followAnchor = () => {
      const id = window.location.hash.slice(1);
      if (id === 'main' || policySections.some((section) => section.id === id)) document.getElementById(id)?.scrollIntoView?.();
    };
    followAnchor();
    window.addEventListener('hashchange', followAnchor);
    window.addEventListener('popstate', followAnchor);
    return () => { window.removeEventListener('hashchange', followAnchor); window.removeEventListener('popstate', followAnchor); };
  }, []);

  return <PublicPageLayout privacy>
    <main id="main">
      <div className="document-intro"><div className="container">
        <a className="back-link" href="/about">← 서비스 소개로 돌아가기</a>
        <div className="document-title"><div><p className="eyebrow">Campus Planner</p><h1>개인정보처리방침</h1></div>
          <div className="effective-date"><span>시행일 <strong><time dateTime="2026-11-07">2026년 11월 7일</time></strong></span></div></div>
        <p className="document-lead">{introduction.split('\n\n').slice(1).join('\n\n')}</p>
        <div className="policy-summary"><div><span>서비스 운영 종료</span><strong>2026년 11월 16일</strong></div>
          <div><span>개인정보 폐기 완료</span><strong>2026년 11월 17일까지</strong></div>
          <div><span>Google Calendar</span><strong>선택 연동 · 미연동 추천 가능</strong></div></div>
      </div></div>
      <div className="container document-layout"><PolicyContents />
        <article className="document-body" aria-label="개인정보처리방침 본문">
          <PolicyContents mobile />
          {policySections.map((section) => <section className="policy-section" id={section.id} key={section.id} aria-labelledby={`${section.id}-title`}>
            <div className="policy-heading"><span aria-hidden="true">{section.number}</span><h2 id={`${section.id}-title`}>{section.title}</h2></div>
            <PolicyBody source={section.body} />
          </section>)}
          <div className="document-end"><span>졸업 프로젝트</span><a href="#main">맨 위로 ↑</a></div>
        </article>
      </div>
    </main>
  </PublicPageLayout>;
}
