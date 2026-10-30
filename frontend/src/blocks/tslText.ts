/** A number as TSL writes it. JavaScript prints 0.0000001 as "1e-7" and
 *  1e21 as "1e+21"; TSL has no exponents, so those are spelled out. */
export function num(v: unknown): string {
  const n = Number(v)
  const s = String(n)
  const m = /^(-?)(\d+)(?:\.(\d+))?e([+-]\d+)$/.exec(s)
  if (!m) return s
  const [, sign, int, frac = '', exp] = m
  const digits = int + frac
  const point = int.length + Number(exp) // where the decimal point goes
  if (point <= 0) return `${sign}0.${'0'.repeat(-point)}${digits}`
  if (point >= digits.length) return sign + digits + '0'.repeat(point - digits.length)
  return `${sign}${digits.slice(0, point)}.${digits.slice(point)}`
}
