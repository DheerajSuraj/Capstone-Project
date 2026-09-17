import { useState, type FormEvent } from 'react';
import { useAuth } from './AuthContext';
import {
  ApiError,
  MIN_PASSWORD_LENGTH,
  passwordStrength,
  validateUsername,
} from './api';
import GoogleButton from './GoogleButton';
import UsernameField from './UsernameField';
import { useUsernameCheck } from './useUsernameCheck';
import {
  Banner,
  LegalNote,
  PasswordField,
  StrengthMeter,
  TsbMark,
} from './components';

export default function SignUp({ onGoToSignIn }: { onGoToSignIn: () => void }) {
  const { signUp, signInWithGoogle } = useAuth();

  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [confirmTouched, setConfirmTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [emailTaken, setEmailTaken] = useState(false);

  const usernameState = useUsernameCheck(username);
  const strength = passwordStrength(password);

  // Only complain about the confirmation once they have left the field.
  // Checking on every keystroke means the message is wrong for the entire
  // time they are typing, which trains people to ignore it.
  const confirmMismatch =
    confirmTouched && confirm.length > 0 && confirm !== password;

  function describe(e: unknown): string {
    if (e instanceof ApiError) {
      switch (e.code) {
        case 'EMAIL_TAKEN':
          setEmailTaken(true);
          return 'That email already has an account.';
        case 'USERNAME_TAKEN':
          return 'That username was taken a moment ago. Try another.';
        case 'WEAK_PASSWORD':
          return e.message;
        case 'NETWORK':
          return 'Could not reach the server. Check your connection.';
        default:
          return e.message;
      }
    }
    return 'Something went wrong. Please try again.';
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;

    setError(null);
    setEmailTaken(false);
    setBusy(true);
    try {
      await signUp({
        username: username.trim(),
        email: email.trim(),
        password,
      });
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  async function google(credential: string) {
    setError(null);
    setBusy(true);
    try {
      await signInWithGoogle(credential);
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  const canSubmit =
    !busy &&
    validateUsername(username.trim()) === null &&
    usernameState.kind !== 'taken' &&
    usernameState.kind !== 'checking' &&
    email.trim().length > 3 &&
    email.includes('@') &&
    password.length >= MIN_PASSWORD_LENGTH &&
    confirm === password;

  return (
    <div className="tsb-auth">
      <div className="tsb-auth__card">
        <div className="tsb-auth__head">
          <TsbMark />
          <h1 className="tsb-auth__title">Create your account</h1>
          <p className="tsb-auth__sub">Free. No real money is ever involved.</p>
        </div>

        {error && (
          <Banner kind="error">
            {error}
            {emailTaken && (
              <>
                {' '}
                <a
                  href="/login"
                  onClick={(e) => {
                    e.preventDefault();
                    onGoToSignIn();
                  }}
                >
                  Sign in instead
                </a>
              </>
            )}
          </Banner>
        )}

        <GoogleButton onCredential={google} onError={setError} text="signup_with" />

        <div className="tsb-auth__or">
          <div className="tsb-auth__rule" />
          <span className="tsb-auth__ortext">OR</span>
          <div className="tsb-auth__rule" />
        </div>

        <form onSubmit={submit} noValidate>
          <UsernameField
            value={username}
            onChange={setUsername}
            state={usernameState}
            disabled={busy}
            hint="3–20 characters. Letters, numbers and underscores. Shown publicly on leaderboards."
          />

          <div className="tsb-auth__field">
            <label className="tsb-auth__label" htmlFor="tsb-email">
              Email
            </label>
            <input
              id="tsb-email"
              className={
                'tsb-auth__input' + (emailTaken ? ' tsb-auth__input--invalid' : '')
              }
              type="email"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                setEmailTaken(false);
              }}
              placeholder="you@example.com"
              autoComplete="email"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              disabled={busy}
              aria-invalid={emailTaken || undefined}
            />
          </div>

          <PasswordField
            id="tsb-new-password"
            label="Password"
            value={password}
            onChange={setPassword}
            placeholder={`At least ${MIN_PASSWORD_LENGTH} characters`}
            autoComplete="new-password"
            disabled={busy}
          >
            <StrengthMeter score={strength.score} label={strength.label} />
          </PasswordField>

          <PasswordField
            id="tsb-confirm-password"
            label="Confirm password"
            value={confirm}
            onChange={setConfirm}
            onBlur={() => setConfirmTouched(true)}
            placeholder="Re-enter your password"
            autoComplete="new-password"
            disabled={busy}
            invalid={confirmMismatch}
            error={confirmMismatch ? 'Passwords do not match.' : null}
          />

          <button
            type="submit"
            className="tsb-auth__submit"
            style={{ width: '100%' }}
            disabled={!canSubmit}
          >
            {busy ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="tsb-auth__alt">
          Already have an account?{' '}
          <a
            href="/login"
            onClick={(e) => {
              e.preventDefault();
              onGoToSignIn();
            }}
          >
            Sign in
          </a>
        </p>

        <LegalNote />
      </div>
    </div>
  );
}
