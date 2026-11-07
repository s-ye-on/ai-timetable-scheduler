import { useState } from 'react';
import { PublicPageLayout, PublicPageIcon } from '@/features/public-pages/components/public-page-layout';
import { ServiceImageDialog } from '@/features/public-pages/components/service-image-dialog';
import type { ServiceScreenshot } from '@/features/public-pages/components/service-image-dialog';
import inputImage from '@/features/public-pages/assets/service-input.png';
import recommendationImage from '@/features/public-pages/assets/service-recommendations.png';
import calendarImage from '@/features/public-pages/assets/service-calendar.png';

const SCREENSHOTS = {
  input: { src: inputImage, title: '실제 자연어 입력 화면', alt: "목요일 저녁 여섯시에 알고리즘 공부 두시간 잡아줘 입력 문장과 AI로 일정 분석하기 버튼이 있는 실제 화면" },
  recommendations: { src: recommendationImage, title: '실제 맞춤형 추천 후보 화면', alt: "알고리즘 공부 추천 후보 3개. 10월 29일 18시와 20시 후보는 각각 65점, 10월 30일 18시 후보는 63.6점. 원조건 일치, 시간 변경, 날짜 변경으로 구분된 실제 추천 화면" },
  calendar: { src: calendarImage, title: '실제 Google Calendar 등록 결과', alt: "Google Calendar에 등록된 알고리즘 공부. 10월 29일 목요일 오후 6시부터 8시까지" },
} satisfies Record<string, ServiceScreenshot>;

export function AboutPage() {
  const [expanded, setExpanded] = useState<ServiceScreenshot | null>(null);
  return <PublicPageLayout>
    <main id="main">
<section className="hero" id="top" aria-labelledby="hero-title">
 <div className="container hero-grid">
  <div className="hero-copy">
   <p className="eyebrow"><span></span>수업과 일상 사이, 나에게 맞는 시간</p>
   <h1 id="hero-title">복잡한 일정 고민,<br /><span>Campus Planner</span>와 함께</h1>
   <p className="subtitle">자연어 기반 개인 맞춤형 일정 추천 및<br className="desktop-break" /> Google Calendar 연동 시스템</p>
   <p className="hero-description">하고 싶은 일을 문장으로 입력하세요.<br />수업 시간표와 기존 일정, 선호 조건을 고려한<br className="wide-break" /> 여러 시간대를 비교하고 직접 선택할 수 있어요.</p>
   <div className="hero-action"><a className="button" href="/login">일정 추천 시작하기<PublicPageIcon name="arrow" /></a></div>
   <p className="cta-note"><PublicPageIcon name="check" />Google Calendar 연결 없이도 분석·추천 가능</p>
  </div>
  <div className="service-visual" aria-label="실제 Campus Planner 서비스 화면">
   <div className="screen-top"><span className="status-dot"></span><span>Campus Planner</span><span className="screen-label">실제 서비스 화면</span></div>
   <div className="input-preview">
    <div className="visual-caption"><span className="tiny-step">01</span><span>자연어로 할 일 입력</span></div>
    <button className="image-button" onClick={() => setExpanded(SCREENSHOTS.input)} aria-label="자연어 입력 화면 확대"><img src={SCREENSHOTS.input.src} width="2270" height="453" alt="목요일 저녁 여섯시에 알고리즘 공부 두시간 잡아줘 입력 문장과 AI로 일정 분석하기 버튼이 있는 실제 화면" /></button>
   </div>
   <div className="recommendation-preview">
    <div className="visual-caption"><span className="tiny-step">02</span><span>추천 후보를 비교하고 선택</span><button className="expand-button" onClick={() => setExpanded(SCREENSHOTS.recommendations)} aria-label="추천 후보 화면 확대"><PublicPageIcon name="expand" /></button></div>
    <button className="image-button" onClick={() => setExpanded(SCREENSHOTS.recommendations)} aria-label="추천 후보 3개 화면 확대"><img src={SCREENSHOTS.recommendations.src} width="2130" height="1568" alt="알고리즘 공부 추천 후보 3개. 10월 29일 18시와 20시 후보는 각각 65점, 10월 30일 18시 후보는 63.6점. 원조건 일치, 시간 변경, 날짜 변경으로 구분된 실제 추천 화면" /></button>
   </div>
   <div className="visual-foot"><PublicPageIcon name="check" /><span>최종 시간은 사용자가 직접 결정해요.</span></div>
  </div>
 </div>
</section>
<section className="features section" id="features" aria-labelledby="features-title">
 <div className="container">
  <div className="section-heading"><div><p className="eyebrow">내 일정에 맞춘 네 가지 기능</p><h2 id="features-title">입력은 간편하게, 선택은 내 뜻대로.</h2></div><p>단순히 빈 시간이 아닌,<br />내 조건에 맞는 시간을 찾아보세요.</p></div>
  <div className="feature-grid">
   <article><span className="feature-number">01</span><h3>자연어로 간편하게<br />일정 입력</h3><p>일상적인 문장에서 날짜·시간·소요 시간 등의 조건을 분석해요.</p></article>
   <article><span className="feature-number">02</span><h3>시간표·선호를 반영한<br />맞춤형 추천</h3><p>수업과 기존 일정, 선호 조건을 고려해 적합한 시간대를 제안해요.</p></article>
   <article><span className="feature-number">03</span><h3>여러 추천 시간대를<br />비교하고 선택</h3><p>최대 3개 후보의 적합도와 추천 이유를 확인하고 직접 선택해요.</p></article>
   <article><span className="feature-number">04</span><h3>필요할 때 선택하는<br />Google Calendar 연동</h3><p>연동 후, 선택한 일정을 등록 버튼으로 Calendar에 보낼 수 있어요.</p></article>
  </div>
 </div>
</section>
<section className="flow section" id="how-it-works" aria-labelledby="flow-title">
 <div className="container">
  <div className="section-heading"><div><p className="eyebrow">이렇게 이용해요</p><h2 id="flow-title">한 문장에서, 나에게 맞는 일정까지.</h2></div><p>AI가 일정 조건을 분석하고,<br />시간표와 선호를 고려해 알맞은 시간을 찾아줘요.</p></div>
  <ol className="flow-list">
   <li><span className="flow-number">1</span><h3>자연어 일정 입력</h3><p>하고 싶은 일과<br />시간 조건을 입력해요.</p></li>
   <li><span className="flow-number">2</span><h3>AI 조건 분석</h3><p>문장에서 일정에 필요한<br />조건을 추출해요.</p></li>
   <li><span className="flow-number">3</span><h3>맞춤형 시간 추천</h3><p>시간표와 선호 조건을 고려해<br />적합한 시간을 추천해요.</p></li>
   <li><span className="flow-number">4</span><h3>사용자 선택</h3><p>추천 후보를 비교하고<br />원하는 시간을 골라요.</p></li>
   <li className="optional-step"><span className="flow-number">5</span><h3>Google Calendar 등록<span className="optional-tag">선택</span></h3><p>연동 후 등록을 요청하면<br />최종 검증을 거쳐 등록돼요.</p></li>
  </ol>
  <p className="flow-note">분석 후 추천을 요청하고, 후보를 선택한 뒤 Calendar 등록을 별도로 요청합니다.</p>
 </div>
</section>
<section className="calendar-section section" aria-labelledby="calendar-title">
 <div className="container calendar-panel">
  <div className="calendar-copy"><p className="eyebrow">Google Calendar는 선택 사항</p><h2 id="calendar-title">연결 없이도,<br />일정 추천을 시작할 수 있어요.</h2><p>일정 분석과 추천은 Calendar를 연결하지 않아도 이용할 수 있어요. 연동하면 Google Calendar의 기존 일정과의 충돌도 고려하고, 선택한 일정을 직접 등록할 수 있어요.</p></div>
  <div className="calendar-example"><span className="calendar-icon"><PublicPageIcon name="calendar-mark" /></span><h3>선택한 일정을 내 Calendar로.</h3><p>후보 선택 후, 등록 버튼으로 직접 요청해요.</p><button className="image-button calendar-image" onClick={() => setExpanded(SCREENSHOTS.calendar)} aria-label="Google Calendar 등록 결과 확대"><img src={SCREENSHOTS.calendar.src} width="1005" height="260" alt="Google Calendar에 등록된 알고리즘 공부. 10월 29일 목요일 오후 6시부터 8시까지" /></button><span className="example-caption">실제 Google Calendar 등록 화면</span></div>
 </div>
</section>
</main>
    {expanded && <ServiceImageDialog screenshot={expanded} onClose={() => setExpanded(null)} />}
  </PublicPageLayout>;
}
