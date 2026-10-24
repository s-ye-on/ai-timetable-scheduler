export function validLocalDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}
export function validLocalDateTime(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(value) && validLocalDate(value.slice(0, 10));
}
export function localDateTimeKey(value: string): string {
  const [seconds = '00', fraction = ''] = (value.split(':')[2] || '00').split('.');
  return value.slice(0, 16) + seconds + fraction.padEnd(9, '0');
}
export const taskDateLabel = (date: string) => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date(`${date.slice(0, 10)}T00:00:00+09:00`));
export const taskTimeLabel = (value: string) => value.slice(11).length > 5 ? value.slice(11).replace(/:00(?:\.0+)?$/, '') : value.slice(11);
export const seoulToday = (now: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
