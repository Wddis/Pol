// Minimal MCP stdio client: initialize, list tools, call one tool with no arguments.
// Usage: node mcp-smoke.mjs <node> <server.js> <tool>
import { spawn } from 'node:child_process';
const [,, node, server, tool] = process.argv;
const p = spawn(node, [server], { stdio: ['pipe','pipe','pipe'] });
let buf=''; const pending=new Map(); let id=0;
p.stdout.on('data', d => { buf+=d; let i; while((i=buf.indexOf('\n'))>=0){const l=buf.slice(0,i);buf=buf.slice(i+1); try{const m=JSON.parse(l); pending.get(m.id)?.(m);}catch{}} });
p.stderr.on('data', d => process.stderr.write('[stderr] '+d));
const rpc=(method,params)=>new Promise(r=>{const i=++id;pending.set(i,r);p.stdin.write(JSON.stringify({jsonrpc:'2.0',id:i,method,params})+'\n');});
const init = await rpc('initialize',{protocolVersion:'2025-06-18',capabilities:{},clientInfo:{name:'t',version:'1'}});
console.log('server:', JSON.stringify(init.result?.serverInfo));
p.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
const tl = await rpc('tools/list',{});
console.log('tools:', tl.result.tools.map(t=>t.name).join(', '));
const r = await Promise.race([rpc('tools/call',{name:tool,arguments:{}}), new Promise(r=>setTimeout(()=>r({timeout:true}),20000))]);
console.log(tool, '->', JSON.stringify(r.result ?? r.error ?? r).slice(0,600));
p.kill(); process.exit(0);
