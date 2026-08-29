import { FormEvent, useRef, useState } from "react";
import { isApiError, login, messageFor, register, setToken } from "./api";

type Props = {
  onSignedIn: () => void;
};

export function AuthPage({ onSignedIn }: Props) {
  const inFlight = useRef(false);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      const result = mode === "register"
        ? await register(username, password)
        : await login(username, password);
      setToken(result.token);
      onSignedIn();
    } catch (err) {
      if (isApiError(err)) {
        setError(messageFor(err));
      } else if (err instanceof TypeError) {
        setError("Could not reach the API. If you just started Compose, wait until it is up.");
      } else {
        setError("Could not reach the API. If you just started Compose, wait until it is up.");
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <header className="hero">
        <p className="eyebrow">MeowPay</p>
        <h1>{mode === "login" ? "Sign in" : "Create a cat"}</h1>
        <p className="lede">Send treats to another cat. New cats start with 100 treats.</p>
      </header>

      <form className="card" onSubmit={onSubmit}>
        <label>
          Username
          <input
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error ? <p className="banner error" role="alert">{error}</p> : null}
        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Working…" : mode === "login" ? "Sign in" : "Register"}
        </button>
      </form>

      <p className="switch">
        {mode === "login" ? (
          <button
            type="button"
            className="link"
            onClick={() => {
              setMode("register");
              setError(null);
            }}
          >
            Need an account? Register
          </button>
        ) : (
          <button
            type="button"
            className="link"
            onClick={() => {
              setMode("login");
              setError(null);
            }}
          >
            Already a cat? Sign in
          </button>
        )}
      </p>
    </main>
  );
}
