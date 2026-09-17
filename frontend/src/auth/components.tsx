import { useId, useState, type ReactNode } from 'react';

/* ------------------------------------------------------------- icons */

export function EyeIcon({ off = false }: { off?: boolean }) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z" />
      <circle cx="12" cy="12" r="3" />
      {off && <path d="M4 20L20 4" />}
    </svg>
  );
}

export function CheckIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}

export function AlertIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.5" />
      <path d="M12 16.4v.01" />
    </svg>
  );
}

export function ClockIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5V12l3 2" />
    </svg>
  );
}

/** The TSB mark: three candles, the last one in the accent colour. */
export function TsbMark({ size = 30 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 30 30"
      fill="none"
      role="img"
      aria-label="TSB"
    >
      <path
        d="M6 4v22M15 7v18M24 3v20"
        stroke="var(--text)"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
      <rect x="3" y="9" width="6" height="10" fill="var(--text)" />
      <rect
        x="12"
        y="11"
        width="6"
        height="8"
        fill="var(--bg)"
        stroke="var(--text)"
        strokeWidth="1.6"
      />
      <rect x="21" y="6" width="6" height="11" fill="var(--accent)" />
    </svg>
  );
}

/* ----------------------------------------------------------- banners */

export function Banner({
  kind = 'error',
  children,
}: {
  kind?: 'error' | 'warn';
  children: ReactNode;
}) {
  return (
    <div className={`tsb-auth__banner tsb-auth__banner--${kind}`} role="alert">
      {kind === 'warn' ? <ClockIcon /> : <AlertIcon />}
      <div>{children}</div>
    </div>
  );
}

/* ----------------------------------------------------- password field */

export function PasswordField({
  id,
  label,
  value,
  onChange,
  onBlur,
  placeholder,
  invalid = false,
  error,
  autoComplete = 'current-password',
  disabled = false,
  trailing,
  children,
}: {
  id?: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  onBlur?: () => void;
  placeholder?: string;
  invalid?: boolean;
  error?: string | null;
  autoComplete?: 'current-password' | 'new-password';
  disabled?: boolean;
  /** Rendered on the label row, right-aligned (e.g. a "Forgot?" link). */
  trailing?: ReactNode;
  /** Rendered under the input (e.g. the strength meter). */
  children?: ReactNode;
}) {
  const generated = useId();
  const inputId = id ?? generated;
  const errId = `${inputId}-err`;
  const [shown, setShown] = useState(false);

  return (
    <div className="tsb-auth__field">
      {trailing ? (
        <div className="tsb-auth__labelrow">
          <label className="tsb-auth__label" htmlFor={inputId}>
            {label}
          </label>
          {trailing}
        </div>
      ) : (
        <label className="tsb-auth__label" htmlFor={inputId}>
          {label}
        </label>
      )}

      <div className="tsb-auth__inputwrap">
        <input
          id={inputId}
          className={
            'tsb-auth__input tsb-auth__input--adorned' +
            (invalid ? ' tsb-auth__input--invalid' : '')
          }
          type={shown ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
          placeholder={placeholder}
          autoComplete={autoComplete}
          disabled={disabled}
          aria-invalid={invalid || undefined}
          aria-describedby={error ? errId : undefined}
        />
        <button
          type="button"
          className="tsb-auth__reveal"
          onClick={() => setShown((s) => !s)}
          aria-label={shown ? 'Hide password' : 'Show password'}
          aria-pressed={shown}
          disabled={disabled}
        >
          <EyeIcon off={shown} />
        </button>
      </div>

      {children}
      {error && (
        <p className="tsb-auth__fielderr" id={errId}>
          {error}
        </p>
      )}
    </div>
  );
}

/* ------------------------------------------------------ strength meter */

export function StrengthMeter({
  score,
  label,
}: {
  score: 0 | 1 | 2 | 3 | 4;
  label: string;
}) {
  if (score === 0) return null;
  const fill =
    score === 1 ? 'weak' : score === 2 ? 'fair' : 'on';

  return (
    <div className="tsb-auth__strength">
      <div
        className="tsb-auth__bars"
        role="meter"
        aria-valuenow={score}
        aria-valuemin={1}
        aria-valuemax={4}
        aria-label="Password strength"
        aria-valuetext={label}
      >
        {[1, 2, 3, 4].map((i) => (
          <div
            key={i}
            className={
              'tsb-auth__bar' + (i <= score ? ` tsb-auth__bar--${fill}` : '')
            }
          />
        ))}
      </div>
      <span className="tsb-auth__strengthlabel">{label}</span>
    </div>
  );
}

/* ------------------------------------------------------------- footer */

export function LegalNote() {
  return (
    <p className="tsb-auth__legal">
      By continuing you agree to the <a href="/terms">Terms</a> and{' '}
      <a href="/privacy">Privacy Policy</a>.
      <br />
      Virtual money only. TSB never places a real order.
    </p>
  );
}
