import page from "./page.js";

const reply = (value, status=200) => Response.json(value, {status,headers:{"Cache-Control":"private, no-store"}});
const matches = async (a,b) => {
  if (!a || !b || a.length>2048) return false;
  const digest=async s=>new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(s)));
  const [x,y]=await Promise.all([digest(a),digest(b)]);
  return x.reduce((diff,v,i)=>diff|(v^y[i]),0)===0;
};
const allowed=(method,path)=>method==="GET" ? path==="/ping"||/^\/api\/catalog\?page=[1-9][0-9]{0,2}$/.test(path) :
  method==="POST" && (/^\/api\/jobs$/.test(path)||/^\/api\/jobs\/[a-f0-9]{32}\/(render|retry|short|metadata)$/.test(path));

export default {
  async fetch(request,env) {
    const url=new URL(request.url), path=url.pathname;
    if (!env.AGENT_SECRET || !env.PANEL_PASSWORD) return reply({detail:"Панель не настроена."},503);
    const agent=path==="/agent/poll"||path==="/agent/result";
    if (agent) {
      if (!await matches(request.headers.get("Authorization"),"Bearer "+env.AGENT_SECRET))
        return reply({detail:"Доступ закрыт."},401);
    } else {
      if (!await matches(request.headers.get("Authorization"),"Basic "+btoa("t2x2:"+env.PANEL_PASSWORD))) {
        if (request.headers.has("Authorization")) {
          const ip = request.headers.get("CF-Connecting-IP") || "unknown";
          const limiter = env.RELAY.get(env.RELAY.idFromName("login:"+ip));
          const attempt = await limiter.fetch("https://internal/login-failure", {method:"POST"});
          if (attempt.status === 429) return attempt;
        }
        return new Response("Введи логин и пароль монтажки.",{status:401,headers:{"WWW-Authenticate":'Basic realm="T2x2"', "Cache-Control":"no-store"}});
      }
      if (request.method==="POST" && request.headers.get("Origin")!==url.origin)
        return reply({detail:"Недопустимый источник запроса."},403);
      if (path==="/" && request.method==="GET")
        return new Response(page,{headers:{"Content-Type":"text/html; charset=utf-8","Cache-Control":"no-store"}});
    }
    if ((request.headers.get("Content-Length")||0)>524288) return reply({detail:"Слишком большой запрос."},413);
    return env.RELAY.get(env.RELAY.idFromName("panel")).fetch(request);
  }
};

export class Relay {
  constructor(ctx) {
    this.sql=ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS job_snapshot (id TEXT PRIMARY KEY, state TEXT NOT NULL, changed INTEGER NOT NULL)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, payload TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL, changed INTEGER NOT NULL, response TEXT)");
  }
  put(key,value) {
    this.sql.exec("INSERT OR REPLACE INTO kv VALUES (?,?)",key,JSON.stringify(value));
  }
  get(key,fallback) {
    const row=this.sql.exec("SELECT value FROM kv WHERE key = ?",key).toArray()[0];
    return row ? JSON.parse(row.value) : fallback;
  }
  async fetch(request) {
    if (request.url === "https://internal/login-failure" && request.method === "POST") {
      const now = Date.now();
      const previous = this.get("login", {start:0, count:0});
      const windowMs = 300000;
      const current = now - previous.start < windowMs ? previous : {start:now,count:0};
      if (current.count >= 5) return new Response("Слишком много попыток входа.", {
        status:429, headers:{"Retry-After":String(Math.ceil((current.start + windowMs - now)/1000)), "Cache-Control":"no-store"}
      });
      current.count++;
      this.put("login", current);
      return new Response(null, {status:204});
    }
    const path=new URL(request.url).pathname,now=Date.now();
    if(request.method==="POST" && path==="/agent/poll"){
      const body=await request.json();
      this.put("heartbeat",now);
      if(Array.isArray(body.states)) {
        for (const state of body.states.slice(0,30)) {
          if (/^[a-f0-9]{32}$/.test(state.id)) this.sql.exec(
            "INSERT OR REPLACE INTO job_snapshot VALUES (?,?,?)",state.id,JSON.stringify(state),state.changed || now);
        }
      }
      this.sql.exec("DELETE FROM commands WHERE state NOT IN ('pending','claimed') AND changed<?",now-86400000);
      this.sql.exec("UPDATE commands SET state='failed', changed=? WHERE state='pending' AND created<?",now,now-300000);
      this.sql.exec("UPDATE commands SET state='pending', changed=? WHERE state='claimed' AND changed<?",now,now-120000);
      const command=this.sql.exec("SELECT id,payload FROM commands WHERE state='pending' ORDER BY created LIMIT 1").toArray()[0];
      if(command) this.sql.exec("UPDATE commands SET state='claimed',changed=? WHERE id=?",now,command.id);
      return reply({command:command?{id:command.id,...JSON.parse(command.payload)}:null});
    }
    if(request.method==="POST" && path==="/agent/result"){
      const body=await request.json();
      this.sql.exec("UPDATE commands SET state='done',changed=?,response=? WHERE id=? AND state='claimed'",
                    now,JSON.stringify(body.result),body.id);
      return reply({ok:true});
    }
    if(request.method==="GET" && path==="/relay/status"){
      const heartbeat=this.get("heartbeat",0);
      return reply({online:now-heartbeat<25000,heartbeat});
    }
    const pageRoute = /^\/api\/jobs\/page\/([0-9]+)$/.exec(path);
    if(request.method==="GET" && (path==="/api/jobs" || pageRoute)) {
      const raw = pageRoute ? pageRoute[1] : null;
      const page = raw === null ? 1 : Number(raw);
      if (!Number.isSafeInteger(page) || page < 1 || page > 500) return reply({detail:"Некорректная страница."},400);
      const rows = this.sql.exec("SELECT state FROM job_snapshot ORDER BY changed DESC,id LIMIT 26 OFFSET ?",(page-1)*25).toArray();
      if (!rows.length && raw === null) return reply(this.get("states",[]));
      const states = rows.slice(0,25).map(row=>JSON.parse(row.state));
      return reply(raw===null ? states : {states,next:rows.length>25?page+1:null});
    }
    const id=/^\/api\/jobs\/([a-f0-9]{32})\/state$/.exec(path)?.[1];
    if(request.method==="GET" && id){
      const row=this.sql.exec("SELECT state FROM job_snapshot WHERE id=?",id).toArray()[0];
      const state=row?JSON.parse(row.state):this.get("states",[]).find(x=>x.id===id);
      return state?reply(state):reply({detail:"Статус пока не получен."},404);
    }
    if(request.method==="POST" && path==="/relay/command"){
      const body=await request.json();
      if(!/^[a-f0-9-]{36}$/.test(body.id)||!allowed(body.method,body.path))return reply({detail:"Недопустимая команда."},400);
      const old=this.sql.exec("SELECT id FROM commands WHERE id=?",body.id).toArray()[0];
      if(old){
        this.sql.exec("UPDATE commands SET state='pending', created=?, changed=? WHERE id=? AND state='failed'",now,now,body.id);
        return reply({id:body.id},202);
      }
      if(now-this.get("heartbeat",0)>=25000)return reply({detail:"Codex не подключён."},503);
      if(this.sql.exec("SELECT count(*) AS n FROM commands WHERE state IN ('pending','claimed')").one().n>=12)
        return reply({detail:"Очередь заполнена."},429);
      this.sql.exec("INSERT INTO commands VALUES (?,?,'pending',?,?,NULL)",body.id,JSON.stringify(body),now,now);
      return reply({id:body.id},202);
    }
    const command=/^\/relay\/command\/([a-f0-9-]{36})$/.exec(path)?.[1];
    if(request.method==="GET" && command){
      const row=this.sql.exec("SELECT state,response FROM commands WHERE id=?",command).toArray()[0];
      return row?reply({state:row.state,result:row.response?JSON.parse(row.response):null}):reply({detail:"Команда не найдена."},404);
    }
    return reply({detail:"Недоступно."},404);
  }
}
