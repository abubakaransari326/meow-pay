import { FormEvent, useEffect, useRef, useState } from "react";
import {
  HistoryItem,
  MeResponse,
  Recipient,
  UnauthorizedError,
  fetchHistory,
  fetchMe,
  fetchRecipients,
  isApiError,
  messageFor,
  sendTreats,
} from "./api";

type Props = {
  onSignedOut: () => void;
  onUnauthorized: () => void;
};

type Snapshot = {
  recipientUsername: string;
  amount: number;
};

export function SendPage({ onSignedOut, onUnauthorized }: Props) {
  const inFlight = useRef(false);
  const [me, setMe] = useState<MeResponse | null>(null);
  const [recipients, setRecipients] = useState<Recipient[]>([]);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [recipient, setRecipient] = useState("");
  const [amount, setAmount] = useState("10");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([fetchMe(), fetchRecipients(), fetchHistory()])
      .then(([meRes, recipientRes, historyRes]) => {
        setMe(meRes);
        setRecipients(recipientRes);
        setHistory(historyRes);
        setRecipient(recipientRes[0]?.username ?? "");
      })
      .catch((err) => {
        if (err instanceof UnauthorizedError) {
          onUnauthorized();
          return;
        }
        setLoadError("Could not load your wallet. Is the API up?");
      });
  }, [onUnauthorized]);

  function onSend(event: FormEvent) {
    event.preventDefault();
    if (snapshot || busy || recipients.length === 0) {
      return;
    }
    if (!recipient) {
      setError("Pick a recipient.");
      return;
    }
    const parsed = Number(amount);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      setError("Amount must be a whole number greater than 0.");
      return;
    }
    setError(null);
    setSuccess(null);
    setSnapshot({ recipientUsername: recipient, amount: parsed });
  }

  function onCancel() {
    setSnapshot(null);
    setBusy(false);
    inFlight.current = false;
  }

  async function onConfirm() {
    if (!snapshot || inFlight.current) {
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError(null);
    setSuccess(null);
    const pending = snapshot;
    try {
      const result = await sendTreats(
        pending.recipientUsername,
        pending.amount,
        crypto.randomUUID()
      );
      setSnapshot(null);
      setAmount("10");
      setSuccess(`Sent ${result.amount} treats to ${result.recipientUsername}.`);
      inFlight.current = false;
      setBusy(false);
    } catch (err) {
      if (err instanceof UnauthorizedError) {
        onUnauthorized();
        return;
      }
      if (isApiError(err)) {
        setError(messageFor(err));
        if (err.error === "INSUFFICIENT_FUNDS") {
          Promise.all([fetchMe(), fetchHistory()])
            .then(([meRes, historyRes]) => {
              setMe(meRes);
              setHistory(historyRes);
            })
            .catch(() => undefined);
        }
      } else {
        setError("Network error. If this was a retry of the same send, it was not sent twice.");
      }
      inFlight.current = false;
      setBusy(false);
      return;
    }
    try {
      const [meRes, historyRes] = await Promise.all([fetchMe(), fetchHistory()]);
      setMe(meRes);
      setHistory(historyRes);
    } catch (err) {
      if (err instanceof UnauthorizedError) {
        onUnauthorized();
        return;
      }
      setError("Could not refresh balance.");
    }
  }

  if (loadError) {
    return (
      <div className="dash">
        <p className="banner error" role="alert">{loadError}</p>
        <button type="button" className="link" onClick={onSignedOut}>
          Sign out
        </button>
      </div>
    );
  }

  if (!me) {
    return (
      <div className="dash">
        <p className="lede">Loading…</p>
      </div>
    );
  }

  const locked = snapshot !== null;

  return (
    <div className="dash">
      <header className="dash-header">
        <div>
          <p className="eyebrow">Signed in as</p>
          <h1>{me.username}</h1>
          <p className="balance">{me.balance} treats</p>
        </div>
        <button type="button" className="link" onClick={onSignedOut}>
          Sign out
        </button>
      </header>

      <div className="dash-grid">
        <form className="card" onSubmit={onSend}>
          <h2>Send treats</h2>
          <label>
            Recipient
            <select
              value={locked ? snapshot.recipientUsername : recipient}
              onChange={(e) => setRecipient(e.target.value)}
              disabled={locked}
              required
            >
              {recipients.length === 0 ? (
                <option value="">No other cats yet</option>
              ) : null}
              {recipients.map((cat) => (
                <option key={cat.username} value={cat.username}>
                  {cat.username}
                </option>
              ))}
            </select>
          </label>
          <label>
            Amount
            <input
              inputMode="numeric"
              value={locked ? String(snapshot.amount) : amount}
              onChange={(e) => setAmount(e.target.value)}
              disabled={locked}
              required
            />
          </label>
          {locked ? (
            <p className="banner" role="status">
              Send {snapshot.amount} to {snapshot.recipientUsername}?
            </p>
          ) : null}
          {error ? <p className="banner error" role="alert">{error}</p> : null}
          {success ? <p className="banner ok" role="status">{success}</p> : null}
          {locked ? (
            <div className="actions">
              <button
                type="button"
                className="primary"
                disabled={busy}
                onClick={onConfirm}
              >
                {busy ? "Sending…" : "Confirm"}
              </button>
              <button type="button" className="ghost" disabled={busy} onClick={onCancel}>
                Cancel
              </button>
            </div>
          ) : (
            <button
              type="submit"
              className="primary"
              disabled={busy || recipients.length === 0}
            >
              Send
            </button>
          )}
        </form>

        <section className="card">
          <h2>Recent transfers</h2>
          {history.length === 0 ? (
            <p className="lede">No transfers yet. Send someone a treat.</p>
          ) : (
            <ul className="history">
              {history.map((row) => (
                <li key={row.id} className={historyClass(row)}>
                  <span>
                    {row.direction === "IN" ? "From" : "To"} {row.counterpartyUsername}
                  </span>
                  <span>{row.amount} treats</span>
                  <span>
                    {row.status === "REJECTED" ? "Rejected" : "Completed"}
                  </span>
                  <time dateTime={row.createdAt}>{formatWhen(row.createdAt)}</time>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}

function historyClass(row: HistoryItem): string {
  if (row.status === "REJECTED") {
    return "rejected";
  }
  return row.direction === "IN" ? "in" : "out";
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
