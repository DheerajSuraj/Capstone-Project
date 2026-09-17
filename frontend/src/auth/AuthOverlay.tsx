import { useEffect, useState } from 'react';
import { useAuth } from './AuthContext';
import SignIn from './SignIn';
import SignUp from './SignUp';
import ChooseUsername from './ChooseUsername';
import './auth.css';

export default function AuthOverlay({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const { status } = useAuth();
  const [screen, setScreen] = useState<'signin' | 'signup'>('signin');

  // Signed in — get out of the way.
  useEffect(() => {
    if (status === 'authenticated') onClose();
  }, [status, onClose]);

  // Esc dismisses, except while picking a username: at that point they have
  // a verified Google identity and no account yet, and closing strands it.
  useEffect(() => {
    if (!open || status === 'needs_username') return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, status, onClose]);

  if (!open || status === 'authenticated' || status === 'booting') return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        overflowY: 'auto',
        background: '#14171c',
      }}
    >
      {status !== 'needs_username' && (
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          style={{
            position: 'absolute',
            top: 16,
            right: 16,
            width: 44,
            height: 44,
            border: 'none',
            borderRadius: 8,
            background: 'transparent',
            color: '#8b909a',
            fontSize: 24,
            cursor: 'pointer',
            zIndex: 1,
          }}
        >
          ×
        </button>
      )}

      {status === 'needs_username' ? (
        <ChooseUsername />
      ) : screen === 'signin' ? (
        <SignIn onGoToSignUp={() => setScreen('signup')} />
      ) : (
        <SignUp onGoToSignIn={() => setScreen('signin')} />
      )}
    </div>
  );
}