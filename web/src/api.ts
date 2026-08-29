const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

const TOKEN_KEY = "meowpay.token";

export type ApiError = {
  error: string;
  message: string;
};

export type AuthResponse = { token: string; username: string };
export type MeResponse = { username: string; balance: number };
export type Recipient = { username: string };
export type TransferResponse = {
  id: string;
  senderUsername: string;
  recipientUsername: string;
  amount: number;
  status: "COMPLETED" | "REJECTED";
  createdAt: string;
};
export type HistoryItem = {
  id: string;
  counterpartyUsername: string;
  direction: "IN" | "OUT";
  amount: number;
  status: "COMPLETED" | "REJECTED";
  createdAt: string;
};

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export function isApiError(value: unknown): value is ApiError {
  return Boolean(
    value &&
      typeof value === "object" &&
      "error" in value &&
      typeof (value as ApiError).error === "string"
  );
}

export class UnauthorizedError extends Error {
  constructor() {
    super("Please sign in.");
    this.name = "UnauthorizedError";
  }
}

function isAuthPath(path: string): boolean {
  return path === "/api/auth/login" || path === "/api/auth/register";
}

async function parseBody(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return { error: "VALIDATION", message: "Invalid response from server." };
  }
}

async function request<T>(
  path: string,
  options: RequestInit & { idempotencyKey?: string } = {}
): Promise<T> {
  const headers = new Headers(options.headers);
  if ((options.method ?? "GET").toUpperCase() === "POST") {
    headers.set("Content-Type", "application/json");
  }
  const token = getToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (options.idempotencyKey) {
    headers.set("Idempotency-Key", options.idempotencyKey);
  }

  const res = await fetch(`${API_URL}${path}`, { ...options, headers });
  const body = await parseBody(res);

  if (res.status === 401) {
    if (isAuthPath(path) && isApiError(body)) {
      throw body;
    }
    if (isAuthPath(path)) {
      throw { error: "UNAUTHORIZED", message: "Wrong username or password." } satisfies ApiError;
    }
    setToken(null);
    throw new UnauthorizedError();
  }

  if (isApiError(body)) {
    throw body;
  }

  if (!res.ok) {
    throw { error: "INTERNAL", message: "Something went wrong." } satisfies ApiError;
  }

  return body as T;
}

export function register(username: string, password: string) {
  return request<AuthResponse>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export function login(username: string, password: string) {
  return request<AuthResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export function fetchMe() {
  return request<MeResponse>("/api/me");
}

export function fetchRecipients() {
  return request<Recipient[]>("/api/recipients");
}

export function fetchHistory() {
  return request<HistoryItem[]>("/api/me/transfers");
}

export async function sendTreats(
  recipientUsername: string,
  amount: number,
  idempotencyKey: string
): Promise<TransferResponse> {
  const options: RequestInit & { idempotencyKey: string } = {
    method: "POST",
    body: JSON.stringify({ recipientUsername, amount }),
    idempotencyKey,
  };
  try {
    return await request<TransferResponse>("/api/transfers", options);
  } catch (err) {
    if (err instanceof TypeError) {
      return request<TransferResponse>("/api/transfers", options);
    }
    throw err;
  }
}

export function messageFor(error: ApiError): string {
  if (error.message) {
    return error.message;
  }
  switch (error.error) {
    case "INSUFFICIENT_FUNDS":
      return "Not enough treats.";
    case "USERNAME_TAKEN":
      return "That username is taken.";
    case "SAME_CAT":
      return "You cannot send treats to yourself.";
    case "NOT_FOUND":
      return "No cat with that username.";
    case "UNAUTHORIZED":
      return "Wrong username or password.";
    case "IDEMPOTENCY_CONFLICT":
      return "This key was already used with a different send.";
    case "VALIDATION":
      return "Check the form and try again.";
    default:
      return "Something went wrong.";
  }
}
