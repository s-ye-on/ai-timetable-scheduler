interface AuthIconProps { name: 'calendar' | 'check' | 'arrow'; }

const PATHS = {
  calendar: 'M8 3v4m8-4v4M4 10h16M6 5h12a2 2 0 0 1 2 2v13H4V7a2 2 0 0 1 2-2Zm2 10 3 3 5-5',
  check: 'm5 12 4 4L19 6',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
};

export function AuthIcon({ name }: AuthIconProps) {
  return <svg className="od-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={PATHS[name]} /></svg>;
}
