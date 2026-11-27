'use client';

// 启动门禁：会话探测 → 个人数据加载 → 主界面；未登录时渲染登录页。
import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { onSessionLost, probeSession, signIn, signOut } from '@/lib/auth';
import { loadUserData, markUserDataStale, onSyncChange, type SyncState, getSyncState } from '@/lib/storage';
import { errMsg } from '@/api';

interface BootContextValue {
  user: string;
  syncState: SyncState;
  logout: () => Promise<void>;
  reloadData: () => Promise<void>;
}

const BootContext = createContext<BootContextValue>({
  user: '',
  syncState: { status: 'ready', kind: null, error: '' },
  logout: async () => {},
  reloadData: async () => {},
});

export const useBoot = () => useContext(BootContext);

type Stage = 'loading' | 'login' | 'ready';

export function BootGate({ children }: { children: React.ReactNode }) {
  const [stage, setStage] = useState<Stage>('loading');
  const [user, setUser] = useState('');
  const [error, setError] = useState('');
  const [syncState, setSyncState] = useState<SyncState>(getSyncState());

  const boot = useCallback(async () => {
    setStage('loading');
    setError('');
    try {
      const session = await probeSession();
      if (session.authEnabled && !session.authenticated) {
        setStage('login');
        return;
      }
      await loadUserData();
      setStage('ready');
    } catch (e) {
      setError(errMsg(e));
      setStage('loading');
    }
  }, []);

  useEffect(() => {
    void boot();
  }, [boot]);

  useEffect(() => onSyncChange(setSyncState), []);

  useEffect(() => onSessionLost(() => {
    markUserDataStale({ preservePending: true });
    setStage('login');
  }), []);

  const logout = useCallback(async () => {
    await signOut();
    markUserDataStale({ preservePending: true });
    setStage('login');
  }, []);

  const reloadData = useCallback(async () => {
    await loadUserData();
  }, []);

  if (stage === 'login') {
    return <LoginScreen onSuccess={(name) => {
      setUser(name);
      void loadUserData().finally(() => setStage('ready'));
    }} />;
  }

  if (stage === 'loading') {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4">
        <div className="text-2xl font-bold tracking-wide">
          Animeko<span className="text-brand-500">Web</span>
        </div>
        {error ? (
          <>
            <p className="max-w-sm text-center text-sm text-red-400">{error}</p>
            <button type="button" className="btn-primary" onClick={() => void boot()}>重试</button>
          </>
        ) : (
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" />
        )}
      </div>
    );
  }

  return (
    <BootContext.Provider value={{ user, syncState, logout, reloadData }}>
      {children}
    </BootContext.Provider>
  );
}

function LoginScreen({ onSuccess }: { onSuccess: (user: string) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    const message = await signIn(username, password);
    setBusy(false);
    if (message) {
      setError(message);
      return;
    }
    onSuccess(username);
  };

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <form onSubmit={submit} className="glass-panel w-full max-w-sm space-y-5 p-8">
        <div className="text-center">
          <div className="text-2xl font-bold">Animeko<span className="text-brand-500">Web</span></div>
          <p className="mt-1 text-sm text-gray-400">登录你的自托管追番服务</p>
        </div>
        <div>
          <label htmlFor="login-username" className="mb-1 block text-xs text-gray-400">用户名</label>
          <input id="login-username" className="input-field" value={username} autoComplete="username"
            onChange={(e) => setUsername(e.target.value)} placeholder="仅 Token 鉴权时可留空" />
        </div>
        <div>
          <label htmlFor="login-password" className="mb-1 block text-xs text-gray-400">密码 / Token</label>
          <input id="login-password" type="password" className="input-field" value={password}
            autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} required />
        </div>
        {error && <p className="text-sm text-red-400" role="alert">{error}</p>}
        <button type="submit" className="btn-primary w-full" disabled={busy || !password}>
          {busy ? '登录中…' : '登 录'}
        </button>
      </form>
    </div>
  );
}
