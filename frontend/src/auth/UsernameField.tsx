import { useId } from 'react';
import { CheckIcon } from './components';
import type { UsernameState } from './useUsernameCheck';

/**
 * Username input with a live availability verdict and, when the name is
 * taken, suggestions the user can take with one click.
 *
 * The verdict is shown inside the field rather than under it so it cannot be
 * missed, and the suggestions are real <button>s so they are reachable by
 * keyboard.
 */
export default function UsernameField({
  value,
  onChange,
  state,
  disabled = false,
  hint,
  autoFocus = false,
}: {
  value: string;
  onChange: (v: string) => void;
  state: UsernameState;
  disabled?: boolean;
  hint?: string;
  autoFocus?: boolean;
}) {
  const id = useId();
  const descId = `${id}-desc`;

  const invalid = state.kind === 'invalid' || state.kind === 'taken';

  return (
    <div className="tsb-auth__field">
      <label className="tsb-auth__label" htmlFor={id}>
        Username
      </label>

      <div className="tsb-auth__inputwrap">
        <input
          id={id}
          className={
            'tsb-auth__input tsb-auth__input--adorned-wide' +
            (invalid ? ' tsb-auth__input--invalid' : '')
          }
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="e.g. juan_c"
          autoComplete="username"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          maxLength={20}
          disabled={disabled}
          autoFocus={autoFocus}
          aria-invalid={invalid || undefined}
          aria-describedby={descId}
        />

        {state.kind === 'checking' && (
          <span className="tsb-auth__adorn tsb-auth__adorn--busy">
            <span className="tsb-auth__spinner" aria-hidden="true" />
          </span>
        )}
        {state.kind === 'available' && (
          <span className="tsb-auth__adorn tsb-auth__adorn--ok">
            <CheckIcon />
            available
          </span>
        )}
        {state.kind === 'taken' && (
          <span className="tsb-auth__adorn tsb-auth__adorn--bad">taken</span>
        )}
      </div>

      {/* One live region for every verdict, so a screen reader announces the
          change instead of the user discovering it on submit. */}
      <div id={descId} aria-live="polite">
        {state.kind === 'invalid' && (
          <p className="tsb-auth__fielderr">{state.reason}</p>
        )}
        {state.kind === 'taken' && (
          <p className="tsb-auth__fielderr">That username is taken.</p>
        )}
        {state.kind === 'unknown' && (
          <p className="tsb-auth__hint">
            Could not check right now — you can still continue.
          </p>
        )}
        {(state.kind === 'empty' ||
          state.kind === 'checking' ||
          state.kind === 'available') &&
          hint && <p className="tsb-auth__hint">{hint}</p>}
      </div>

      {state.kind === 'taken' && state.suggestions.length > 0 && (
        <div className="tsb-auth__chips">
          {state.suggestions.slice(0, 3).map((s) => (
            <button
              key={s}
              type="button"
              className="tsb-auth__chip"
              onClick={() => onChange(s)}
              disabled={disabled}
            >
              {s}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
