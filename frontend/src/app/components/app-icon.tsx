const paths = {
  brand: 'M7 4v3m10-3v3M4 10h16M6 6h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Zm2 9 3 3 5-5',
  home: 'm3 10 9-7 9 7v10H15v-6H9v6H3Z',
  planner: 'm12 3 2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4Z',
  timetable: 'M8 2v4m8-4v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM8 14h2m4 0h2m-8 4h2',
  settings: 'M4 7h16M4 17h16M8 4v6m8 4v6',
  arrow: 'm9 5 7 7-7 7',
  close: 'm6 6 12 12M6 18 18 6',
  logout: 'M9 4H4v16h5m6-12 4 4-4 4m-7-4h11',
} as const;

export function AppIcon({ name, className = '' }: { name: keyof typeof paths; className?: string }) {
  return <svg className={`od-icon ${className}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
}
