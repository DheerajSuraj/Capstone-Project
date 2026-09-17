import { useEffect, useState, type FormEvent } from 'react';
import { useAuth } from './AuthContext';
import { ApiError, validateUsername } from './api';
import UsernameField from './UsernameField';
import { useUsernameCheck } from './useUsernameCheck';
import { Banner, TsbMark } from './components';

/**
 * The second half of the Google flow.
 *
 * Google returns a verified email, a display name and a picture. It does not
 * return anything that could serve as a public handle, and TSB puts a handle
 * next to every entry on every competition leaderboard. So a first-time
 * Google user stops here once, picks a name, and is never asked again.
 *
 * The verified identity is held server-side behind `pendingToken`; this
 * screen only ever sends that token back, never the email.
 */
export default function ChooseUsername() {
  const { pending, claimUsername, cancelPending } = useAuth();

  const [username, setUsername] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const usernameState = useUsernameCheck(username);

  // Seed with Google's first suggestion so most people can just press
  // Continue. They can still clear it and type their own.
  useEffect(() => {
    if (pending?.suggestions?.length && username === '') {
      setUsername(pending.suggestions[0]);
    }
    // Seeding is a one-off; re-running on every keystroke would fight the user.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending]);

  if (!pending) return null;

  const initial =
    (pending.displayName ?? pending.email).trim().charAt(0).toUpperCase() || '?';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;

    setError(null);
    setBusy(true);
    try {
      await claimUsername(username.trim());
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'PENDING_EXPIRED') {
          setError(
            'That sign-in attempt expired. Please start again with Google.',
          );
        } else if (err.code === 'USERNAME_TAKEN') {
          setError('That username was taken a moment ago. Try another.');
        } else {
          setError(err.message);
        }
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  const canSubmit =
    !busy &&
    validateUsername(username.trim()) === null &&
    usernameState.kind !== 'taken' &&
    usernameState.kind !== 'checking';

  return (
    <div className="tsb-auth">
      <div className="tsb-auth__card">
        <div className="tsb-auth__head">
          <TsbMark />
          <h1 className="tsb-auth__title">One last thing</h1>
          <p className="tsb-auth__sub">
            Google gave us your email, but not a name to put on the
            leaderboard. Pick one.
          </p>
        </div>

        {error && <Banner kind="error">{error}</Banner>}

        <div className="tsb-auth__identity">
          {pending.avatarUrl ? (
            <img
              className="tsb-auth__avatar"
              src={pending.avatarUrl}
              alt=""
              referrerPolicy="no-referrer"
            />
          ) : (
            <div className="tsb-auth__avatar" aria-hidden="true">
              {initial}
            </div>
          )}
          <div className="tsb-auth__identitytext">
            <div className="tsb-auth__identitytitle">Signed in with Google</div>
            <div className="tsb-auth__identitymail">{pending.email}</div>
          </div>
          <button
            type="button"
            className="tsb-auth__chip"
            onClick={cancelPending}
            disabled={busy}
          >
            Not you?
          </button>
        </div>

        <form onSubmit={submit} noValidate>
          <UsernameField
            value={username}
            onChange={setUsername}
            state={usernameState}
            disabled={busy}
            autoFocus
            hint="3–20 characters. Letters, numbers and underscores. This is shown next to your strategies on every competition leaderboard."
          />

          <button
            type="submit"
            className="tsb-auth__submit"
            style={{ width: '100%' }}
            disabled={!canSubmit}
          >
            {busy ? 'Setting up…' : 'Continue to TSB'}
          </button>
        </form>

        <p className="tsb-auth__legal">
          You can change this later in Settings, but not while you have a
          strategy entered in a running competition.
        </p>
      </div>
    </div>
  );
}
