/*
 * FarmerShoot — Verifier v8 · LIVE INSPECTOR + PATCHER (Frida / frida-il2cpp-bridge)
 * ---------------------------------------------------------------------------
 * ЧТО ДЕЛАЕТ (всё на ЖИВОМ процессе вашей игры, имена резолвятся по именам —
 * оффсеты не хардкодятся, переживает обновления):
 *
 *   1) DUMP       — свежее дерево сборок/классов/полей в файл ("свежие данные").
 *   2) AUTO-WATCH — сам находит числовые поля в оружейных/боевых классах и
 *                   печатает ТОЛЬКО то, что реально изменилось: old -> new (Δ).
 *                   → стреляете — счётчик патронов сам всплывёт как падающее число.
 *   3) WATCH      — ручной список полей, если точные имена уже знаете.
 *   4) PATCH      — ЗАПИСЬ значений: поставить абсолют (ammo=999) ИЛИ умножить
 *                   (jump ×3 — наглядно прыгает втрое выше/ниже).
 *   5) ENFORCE    — переустанавливать патч каждый кадр (если игра сбрасывает).
 *   6) RESTORE    — вернуть оригинальные значения.
 *
 * Зависимость: frida-il2cpp-bridge (https://github.com/vfsfitvnm/frida-il2cpp-bridge)
 *   npm i frida-il2cpp-bridge frida-compile
 *   npx frida-compile farmer_v8.js -o farmer_v8.compiled.js
 *
 * Запуск:
 *   ROOT:     frida -U -f <package.name> -l farmer_v8.compiled.js
 *   NO-ROOT:  objection patchapk -s your_game.apk   (вшить frida-gadget в СВОЙ apk)
 *             затем:  frida -U Gadget -l farmer_v8.compiled.js
 *
 * «Кнопки» в консоли Frida:
 *   rpc.exports.dump()      — выгрузить дерево в файл
 *   rpc.exports.autofind()  — список кандидатов (ammo/jump/health…) в файл
 *   rpc.exports.check()     — разовый снимок watch-полей
 *   rpc.exports.patch()     — применить все CONFIG.patch
 *   rpc.exports.restore()   — откатить патчи к оригиналу
 *   rpc.exports.enforce(1)  / rpc.exports.enforce(0)  — вкл/выкл удержание
 *
 * ЧЕСТНО: репак+переподпись ломают подпись → онлайн анти-чит/Play Integrity
 * могут не пустить. Это ВАШ проект — тестируйте офлайн / dev-билд.
 */

import "frida-il2cpp-bridge";

// ======================= НАСТРОЙКА (редактируйте тут) =======================
const CONFIG = {
  tickMs: 400,
  dumpToFile: true,
  dumpAssemblies: [],            // пусто = все; обычно игра в "Assembly-CSharp"

  // --- AUTO-WATCH: турн-ки, имена полей знать НЕ нужно -----------------------
  autoWatch: true,
  // Классы берём из этих namespace/подстрок (из восстановленного манифеста игры):
  autoWatchNameMatch: [
    "Weaponry", "Weapon", "Ammo", "Magazine", "Clip", "Bullet", "Round",
    "Health", "Damage", "HitBox", "Armor",
    "Movement", "Locomotion", "Jump", "Player", "Character", "Motor", "Controller",
  ],
  autoWatchTypes: [              // какие типы полей считаем "значениями"
    "System.Int32","System.Single","System.Int16","System.Byte","System.SByte",
    "System.Int64","System.UInt32","System.UInt16","System.Double","System.Boolean",
  ],
  maxAutoClasses: 60,            // предохранители производительности
  maxInstancesPerClass: 3,
  printBaseline: false,          // true = напечатать все значения на старте

  // --- WATCH вручную (если знаете точные имена из DUMP) ----------------------
  watch: [
    // { cls: "Balders.farmershoot.Gameplay.Features.Weaponry.Data.Magazine", field: "ammo", kind: "instance" },
  ],

  // --- PATCH: запись значений (абсолют ИЛИ множитель) ------------------------
  // kind: "static" | "instance". Задайте ЛИБО value (абсолют), ЛИБО mul (множитель).
  // enforce: true = держать каждый кадр (для полей, которые игра пересчитывает).
  patch: [
    // ПРИМЕРЫ — подставьте реальные cls/field из DUMP/autofind:
    // { cls: "<...PlayerMovement>", field: "jumpForce", kind: "instance", mul: 3,  enforce: true,  label: "Прыжок ×3" },
    // { cls: "<...Weaponry.Magazine>", field: "ammo",   kind: "instance", value: 999, enforce: true, label: "Патроны=999" },
    // { cls: "<...PlayerMovement>", field: "moveSpeed", kind: "instance", mul: 2,  enforce: true,  label: "Скорость ×2" },
  ],
  patchOnStart: false,           // true = применить патчи сразу при старте

  // --- autofind: куда смотреть за ammo/jump/health ---------------------------
  autoFindFieldSubstrings: [
    "ammo","Ammo","clip","Clip","mag","Mag","round","Round","bullet","Bullet",
    "health","Health","hp","HP","armor","Armor","damage","Damage",
    "jump","Jump","gravity","Gravity","speed","Speed","force","Force",
    "move","Move","velocity","Velocity","height","Height",
  ],
};
// ===========================================================================

function out(l){ console.log(l); }

let FILE_DIR=null;
function resolveFilesDir(){
  try{
    const AT=Java.use("android.app.ActivityThread");
    const ctx=AT.currentApplication().getApplicationContext();
    const f=ctx.getExternalFilesDir(null);
    FILE_DIR=f?f.getAbsolutePath():null;
  }catch(e){ FILE_DIR=null; }
  return FILE_DIR;
}
function writeFile(name,text){
  if(!FILE_DIR) return null;
  const p=FILE_DIR+"/"+name;
  try{ const f=new File(p,"w"); f.write(text); f.flush(); f.close(); return p; }
  catch(e){ return null; }
}

// ------------------------------ DUMP ----------------------------------------
function dump(){
  const L=[]; L.push("# FarmerShoot v8 LIVE dump · unity "+Il2Cpp.unityVersion,"");
  const asms=Il2Cpp.domain.assemblies;
  L.push("# assemblies ("+asms.length+"):"); for(const a of asms) L.push("  "+a.name); L.push("");
  const want=CONFIG.dumpAssemblies;
  for(const a of asms){
    if(want.length && want.indexOf(a.name)<0) continue;
    const img=a.image; L.push("=== "+a.name+"  (classes: "+img.classCount+") ===");
    for(const k of img.classes){
      const fd=k.fields.map(f=>(f.isStatic?"static ":"")+f.type.name+" "+f.name+" @0x"+f.offset.toString(16)).join(", ");
      L.push("  "+k.type.name+" { "+fd+" }");
    }
    L.push("");
  }
  const t=L.join("\n"); out("[v8] dump: "+asms.length+" assemblies");
  if(CONFIG.dumpToFile){ const p=writeFile("v8_dump.txt",t); out(p?"[v8] dump -> "+p:"[v8] (no file dir)\n"+t.split("\n").slice(0,40).join("\n")); }
  else out(t);
  return t;
}

// ----------------------------- autoFind -------------------------------------
function autoFind(){
  const subs=CONFIG.autoFindFieldSubstrings; if(!subs.length) return;
  const hits=[];
  for(const a of Il2Cpp.domain.assemblies) for(const k of a.image.classes) for(const f of k.fields)
    if(subs.some(s=>f.name.indexOf(s)>=0))
      hits.push(k.type.name+" . "+f.name+"  ("+f.type.name+(f.isStatic?", static":"")+")");
  const t=hits.join("\n"); out("[v8] autoFind: "+hits.length+" полей-кандидатов");
  const p=writeFile("v8_field_candidates.txt",t);
  out(p?"[v8] кандидаты -> "+p:t.split("\n").slice(0,60).join("\n"));
}

// --------------------------- helpers: классы/поля ---------------------------
function findClass(name){
  for(const a of Il2Cpp.domain.assemblies){ try{ const k=a.image.tryClass(name); if(k) return k; }catch(e){} }
  for(const a of Il2Cpp.domain.assemblies) for(const k of a.image.classes)
    if(k.name===name||k.type.name===name) return k;
  return null;
}
function isNumericType(tn){ return CONFIG.autoWatchTypes.indexOf(tn)>=0; }

// --------------------------- AUTO-WATCH -------------------------------------
let autoClasses=[];            // [{klass, numFields:[field...]}]
function buildAutoWatch(){
  autoClasses=[];
  const m=CONFIG.autoWatchNameMatch;
  for(const a of Il2Cpp.domain.assemblies){
    for(const k of a.image.classes){
      const nm=k.type.name;
      if(!m.some(s=>nm.indexOf(s)>=0)) continue;
      let numFields=[];
      try{ numFields=k.fields.filter(f=>!f.isStatic && isNumericType(f.type.name)); }catch(e){ continue; }
      if(!numFields.length) continue;
      autoClasses.push({klass:k, fields:numFields, name:nm});
      if(autoClasses.length>=CONFIG.maxAutoClasses) break;
    }
    if(autoClasses.length>=CONFIG.maxAutoClasses) break;
  }
  out("[v8] auto-watch: классов "+autoClasses.length+" (ищу меняющиеся числовые поля)");
}

const autoLast={};             // key -> value
function autoTick(baseline){
  let changes=0;
  for(const c of autoClasses){
    let insts=[];
    try{ insts=Il2Cpp.gc.choose(c.klass); }catch(e){ continue; }
    const n=Math.min(insts.length, CONFIG.maxInstancesPerClass);
    for(let i=0;i<n;i++){
      for(const f of c.fields){
        let v; try{ v=String(insts[i].field(f.name).value); }catch(e){ continue; }
        const key=c.name+"#"+i+"."+f.name;
        const prev=autoLast[key];
        if(prev!==undefined && prev!==v){
          let d=""; const dn=Number(v)-Number(prev); if(!isNaN(dn)) d="  (Δ "+(dn>0?"+":"")+dn+")";
          out("  ~ "+c.name+"."+f.name+"  "+prev+" -> "+v+d);
          changes++;
        } else if(baseline && CONFIG.printBaseline){
          out("  = "+c.name+"."+f.name+" = "+v);
        }
        autoLast[key]=v;
      }
    }
  }
  if(baseline) out("[v8] baseline снят. Играйте/стреляйте — изменения появятся ниже (в т.ч. патроны).");
  else if(changes===0){ /* тихо */ }
}

// --------------------------- WATCH вручную ----------------------------------
const wLast={};
function readWatch(w){
  const k=findClass(w.cls); if(!k) return {ok:false,v:"<no class "+w.cls+">"};
  try{
    if(w.kind==="static") return {ok:true,v:String(k.field(w.field).value)};
    const o=Il2Cpp.gc.choose(k); if(!o.length) return {ok:false,v:"<no instance>"};
    return {ok:true,v:String(o[0].field(w.field).value),n:o.length};
  }catch(e){ return {ok:false,v:"<err "+w.field+": "+e.message+">"}; }
}
function snapshot(tag){
  if(!CONFIG.watch.length){ out("[v8] watch[] пуст (используется auto-watch)"); return; }
  out("──── "+tag+" ────");
  for(const w of CONFIG.watch){
    const key=w.cls+"."+w.field, r=readWatch(w), p=wLast[key]; let mk="";
    if(r.ok && p!==undefined && p!==r.v){ mk="  <<< CHANGED "+p+" -> "+r.v; const d=Number(r.v)-Number(p); if(!isNaN(d)) mk+="  (Δ "+(d>0?"+":"")+d+")"; }
    out("  "+w.cls+"."+w.field+(r.n?" [×"+r.n+"]":"")+" = "+r.v+mk);
    if(r.ok) wLast[key]=r.v;
  }
}

// ------------------------------ PATCH ---------------------------------------
// Храним оригинал и целевое значение для корректного enforce/restore.
const patchState=[];           // {p, targets:[{setter, orig, target}]}
function computeTarget(cur, p){
  if(p.mul!==undefined){ const n=Number(cur); return isNaN(n)?cur:(n*p.mul); }
  return p.value;
}
function applyField(obj, name, p, store){
  try{
    const fld=obj.field(name);
    const orig=fld.value;
    const target=computeTarget(orig, p);
    fld.value=target;
    store.push({setter:(val)=>{try{obj.field(name).value=val;}catch(e){}}, orig, target});
    return {orig, target};
  }catch(e){ return null; }
}
function applyPatches(){
  if(!CONFIG.patch.length){ out("[v8] patch[] пуст — добавьте в CONFIG.patch"); return; }
  patchState.length=0;
  for(const p of CONFIG.patch){
    const k=findClass(p.cls);
    if(!k){ out("[v8] PATCH: класс не найден: "+p.cls); continue; }
    const store=[]; let applied=0;
    if(p.kind==="static"){
      try{
        const fld=k.field(p.field); const orig=fld.value; const target=computeTarget(orig,p);
        fld.value=target; store.push({setter:(v)=>{try{k.field(p.field).value=v;}catch(e){}}, orig, target}); applied=1;
        out("[v8] PATCH "+(p.label||"")+"  "+p.cls+"."+p.field+": "+orig+" -> "+target);
      }catch(e){ out("[v8] PATCH static err "+p.field+": "+e.message); }
    } else {
      let insts=[]; try{ insts=Il2Cpp.gc.choose(k); }catch(e){}
      if(!insts.length){ out("[v8] PATCH: нет живого объекта "+p.cls+" (зайдите в матч/заспавнитесь)"); }
      for(const o of insts){ const r=applyField(o,p.field,p,store); if(r){ applied++; if(applied===1) out("[v8] PATCH "+(p.label||"")+"  "+p.cls+"."+p.field+": "+r.orig+" -> "+r.target); } }
    }
    patchState.push({p, store});
  }
  out("[v8] патчи применены. Проверьте в игре (напр. высоту прыжка).");
}
function enforceTick(){
  for(const ps of patchState){ if(!ps.p.enforce) continue; for(const t of ps.store) t.setter(t.target); }
}
function restorePatches(){
  for(const ps of patchState) for(const t of ps.store) t.setter(t.orig);
  out("[v8] оригинальные значения восстановлены");
}

// ------------------------------ RPC -----------------------------------------
let enforceOn=false;
rpc.exports={
  dump:     ()=>Il2Cpp.perform(()=>dump()),
  autofind: ()=>Il2Cpp.perform(()=>autoFind()),
  check:    ()=>Il2Cpp.perform(()=>snapshot("CHECK")),
  patch:    ()=>Il2Cpp.perform(()=>{ applyPatches(); enforceOn=true; }),
  restore:  ()=>Il2Cpp.perform(()=>{ enforceOn=false; restorePatches(); }),
  enforce:  (on)=>{ enforceOn=!!(on===undefined?true:on); out("[v8] enforce = "+enforceOn); },
};

// ------------------------------ MAIN ----------------------------------------
Il2Cpp.perform(()=>{
  resolveFilesDir();
  out("============ FarmerShoot Verifier v8 (LIVE + PATCH) ============");
  out("[v8] il2cpp готов. Unity "+Il2Cpp.unityVersion+(FILE_DIR?"  files: "+FILE_DIR:"  (нет files dir)"));
  out("[v8] «кнопки»: rpc.exports.dump()/.autofind()/.check()/.patch()/.restore()/.enforce(1|0)");

  dump();
  autoFind();

  if(CONFIG.autoWatch){ buildAutoWatch(); autoTick(true); }
  if(CONFIG.watch.length) snapshot("BASELINE");
  if(CONFIG.patchOnStart){ applyPatches(); enforceOn=true; }

  setInterval(()=>Il2Cpp.perform(()=>{
    if(CONFIG.autoWatch) autoTick(false);
    if(CONFIG.watch.length) snapshot("tick");
    if(enforceOn) enforceTick();
  }), CONFIG.tickMs);
});
