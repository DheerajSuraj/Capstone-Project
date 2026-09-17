import { useEffect, useState, type FormEvent } from 'react';
import { useAuth } from './AuthContext';
import { ApiError } from './api';
import GoogleButton from './GoogleButton';
import {
  Banner,
  LegalNote,
  PasswordField,
  TsbMark,
} from './components';

function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export default function SignIn({
  onGoToSignUp,
}: {
  onGoToSignUp: () => void;
}) {
  const { signIn, signInWithGoogle } = useAuth();

  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lockedFor, setLockedFor] = useState(0);

  // Count the rate-limit lock down so the user can see it clear, rather than
  // being told "try again later" with no idea when later is.
  useEffect(() => {
    if (lockedFor <= 0) return;
    const t = window.setInterval(() => {
      setLockedFor((s) => {
        if (s <= 1) {
          setError(null);
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => window.clearInterval(t);
  }, [lockedFor]);

  function describe(e: unknown): string {
    if (e instanceof ApiError) {
      switch (e.code) {
        case 'INVALID_CREDENTIALS':
          // Deliberately does not say WHICH was wrong. Saying "no account
          // with that email" turns this form into a tool for discovering
          // which of our users exist.
          return 'Email or password is incorrect.';
        case 'RATE_LIMITED':
          setLockedFor(e.retryAfterSeconds ?? 900);
          return 'Too many attempts.';
        case 'NETWORK':
          return 'Could not reach the server. Check your connection.';
        case 'ACCOUNT_HAS_PASSWORD':
          return e.message;
        default:
          return e.message;
      }
    }
    return 'Something went wrong. Please try again.';
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || lockedFor > 0) return;

    setError(null);
    setBusy(true);
    try {
      await signIn({ identifier: identifier.trim(), password });
      // On success the provider flips status and the page swaps out from
      // under us — nothing more to do here.
    } catch (err) {
      setError(describe(err));
      setPassword('');
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

  const locked = lockedFor > 0;
  const canSubmit =
    !busy && !locked && identifier.trim().length > 0 && password.length > 0;

  return (
    <div className="tsb-auth">
      <div className="tsb-auth__card">
        <div className="tsb-auth__head">
          <TsbMark />
          <h1 className="tsb-auth__title">Welcome back</h1>
          <p className="tsb-auth__sub">
            Sign in to build, test and prove your strategies.
          </p>
        </div>

        {locked ? (
          <Banner kind="warn">
            Too many sign-in attempts. Try again in{' '}
            {formatCountdown(lockedFor)}.
          </Banner>
        ) : (
          error && <Banner kind="error">{error}</Banner>
        )}

        <GoogleButton onCredential={google} onError={setError} text="continue_with" />

        <div className="tsb-auth__or">
          <div className="tsb-auth__rule" />
          <span className="tsb-auth__ortext">OR</span>
          <div className="tsb-auth__rule" />
        </div>

        <form onSubmit={submit} noValidate>
          <div className="tsb-auth__field">
            <label className="tsb-auth__label" htmlFor="tsb-identifier">
              Email or username
            </label>
            <input
              id="tsb-identifier"
              className="tsb-auth__input"
              type="text"
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              placeholder="you@example.com"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              disabled={busy || locked}
            />
          </div>

          <PasswordField
            id="tsb-password"
            label="Password"
            value={password}
            onChange={setPassword}
            placeholder="Enter your password"
            autoComplete="current-password"
            disabled={busy || locked}
            trailing={
              <a className="tsb-auth__link--quiet" href="/forgot-password">
                Forgot password?
              </a>
            }
          />

          <button
            type="submit"
            className="tsb-auth__submit"
            style={{ width: '100%' }}
            disabled={!canSubmit}
          >
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="tsb-auth__alt">
          New to TSB?{' '}
          <a
            href="/signup"
            onClick={(e) => {
              e.preventDefault();
              onGoToSignUp();
            }}
          >
            Create an account
          </a>
        </p>

        <LegalNote />
      </div>
    </div>
  );
}
