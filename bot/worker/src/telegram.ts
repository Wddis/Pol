// Вызовы Bot API: без библиотек, только fetch. Ошибки Telegram не роняют обработку (возвращается ok=false).

export interface TgResult {
  ok: boolean;
  description?: string;
  result?: unknown;
}

export class Telegram {
  constructor(private token: string, private api = "https://api.telegram.org") {}

  async call(method: string, params: Record<string, unknown>): Promise<TgResult> {
    try {
      const r = await fetch(`${this.api}/bot${this.token}/${method}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(params),
      });
      const body = (await r.json()) as TgResult;
      if (!body.ok) console.warn(`Telegram ${method}: ${body.description}`);
      return body;
    } catch (e) {
      console.warn(`Telegram ${method}: ${e}`);
      return { ok: false, description: String(e) };
    }
  }
}

// Только то, что читает бот
export interface TgUser {
  id: number;
  username?: string;
}
export interface TgMessage {
  message_id: number;
  chat: { id: number; type: string };
  from?: TgUser;
  text?: string;
}
export interface TgCallback {
  id: string;
  from: TgUser;
  data?: string;
  message?: TgMessage;
}
export interface TgUpdate {
  update_id: number;
  message?: TgMessage;
  callback_query?: TgCallback;
}
