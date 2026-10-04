import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const ts = require("typescript");
function runtime(path, dependencies = {}, timers = {}) {
  const source = readFileSync(new URL(`../features/search/${path}`, import.meta.url), "utf8");
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const moduleInstance = { exports: {} };
  new Function("require", "module", "exports", "setTimeout", "clearTimeout", js)(name => {
    if (!(name in dependencies)) throw new Error(`Unexpected dependency ${name}`);
    return dependencies[name];
  }, moduleInstance, moduleInstance.exports, timers.setTimeout ?? setTimeout, timers.clearTimeout ?? clearTimeout);
  return moduleInstance.exports;
}
const model = runtime("search-model.ts");
function deferred() { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; }
function harness() {
  const slots = []; let cursor = 0; const effects = []; const layoutEffects = []; const timers = new Map(); let timerId = 0;
  const calls = [], opens = [], published = [];
  const react = {
    useState(initial) { const i=cursor++; if (!(i in slots)) slots[i]=initial; return [slots[i],next => { slots[i]=typeof next==="function"?next(slots[i]):next; }]; },
    useRef(initial) { const i=cursor++; if (!(i in slots)) slots[i]={current:initial}; return slots[i]; },
    useLayoutEffect(effect,deps) { const i=cursor++; const previous=slots[i]; if(!previous || deps.some((dep,n)=>!Object.is(dep,previous.deps[n]))) { slots[i]={deps};layoutEffects.push(effect); } },
    useEffect(effect,deps) { const i=cursor++; const previous=slots[i]; if(!previous || deps.some((dep,n)=>!Object.is(dep,previous.deps[n]))) {
      slots[i]={deps,cleanup:previous?.cleanup}; effects.push(()=>{slots[i].cleanup?.();slots[i].cleanup=effect();});
    } },
  };
  const api = {
    query(query,pages,signal) { const d=deferred();calls.push({query,pages,signal,...d});return d.promise; },
    open(type,id,signal) { const d=deferred();opens.push({type,id,signal,...d});return d.promise; },
  };
  const searchHook=runtime("use-unified-search.ts", {react,"./search-api":{searchApi:api},"./search-model":model}, {
    setTimeout(fn) { const id=++timerId;timers.set(id,fn);return id; },clearTimeout(id) {timers.delete(id);},
  }).useUnifiedSearch;
  return {calls,opens,published,
    render(identity="account-a",disabled=false) { cursor=0;const state=searchHook(identity,r=>published.push(r),disabled);while(layoutEffects.length)layoutEffects.shift()();return state; },
    effects() {while(effects.length)effects.shift()();},
    debounce() { const pending=[...timers.values()];timers.clear();pending.forEach(fn=>fn()); },
    async settle() {await Promise.resolve();await Promise.resolve();},
  };
}
const response = query => ({query,groups:[{type:"worksheets",label:"Worksheets",available:true,coverage:"Own",number:0,size:5,totalElements:1,totalPages:1,items:[{id:"task-a",title:"Needle delivery",subtitle:"Engineering",status:"ASSIGNED"}]}]});

test("search bounds and literal query encoding cannot expand query scope", () => {
  assert.equal(model.validSearchQuery("x"),false);assert.equal(model.validSearchQuery("x".repeat(101)),false);
  assert.equal(model.validSearchQuery("ab\u0000"),false);assert.equal(model.validSearchQuery("ab%_"),true);
  const params = new URLSearchParams(model.searchQueryString("  ab%_  ",{...model.INITIAL_SEARCH_PAGES,worksheets:2}));
  assert.equal(params.get("q"),"ab%_");assert.equal(params.get("worksheetsPage"),"2");assert.equal(params.get("employeesPage"),"0");
  assert.throws(()=>model.searchQueryString("Needle",{...model.INITIAL_SEARCH_PAGES,employees:1000}));
  assert.throws(()=>model.searchQueryString("Needle",model.INITIAL_SEARCH_PAGES,26));
});
test("feature API sends grouped pages and every open to backend authorization with cancellation", async () => {
  const requests=[];const {searchApi}=runtime("search-api.ts",{"../../lib/api-client":{apiRequest:(path,options)=>{requests.push({path,options});return Promise.resolve({});}},"./search-model":model});
  const signal=new AbortController().signal;
  await searchApi.query("Needle",{...model.INITIAL_SEARCH_PAGES,appointments:1},signal);
  await searchApi.open("worksheets","a/b",signal);
  assert.equal(new URLSearchParams(requests[0].path.split("?")[1]).get("appointmentsPage"),"1");
  assert.equal(requests[1].path,"/search/worksheets/a%2Fb/open");
  requests.forEach(r=>{assert.equal(r.options.signal,signal);assert.equal(r.options.cache,"no-store");});
});
test("hook aborts previous query and cannot render a stale response after account switch even before effect cleanup", async () => {
  const h=harness();let s=h.render();h.effects();s.setQuery("Needle");s=h.render();h.effects();h.debounce();
  assert.equal(h.calls.length,1);
  s=h.render("account-b");assert.equal(s.result,undefined);
  h.calls[0].resolve(response("Needle"));await h.settle();assert.equal(h.render("account-b").result,undefined);
  h.effects();assert.equal(h.calls[0].signal.aborted,true);h.debounce();h.calls[1].resolve(response("Needle"));await h.settle();
  assert.equal(h.render("account-b").result.query,"Needle");
});
test("empty success, backend failure and loading remain distinct; failed open removes stale names", async () => {
  const h=harness();let s=h.render();h.effects();s.setQuery("Needle");s=h.render();h.effects();h.debounce();
  assert.equal(h.render().loading,true);h.calls[0].resolve({query:"Needle",groups:[]});await h.settle();
  s=h.render();assert.deepEqual(s.result.groups,[]);assert.equal(s.error,undefined);assert.equal(s.loading,false);
  s.setQuery("Missing");s=h.render();h.effects();h.debounce();h.calls[1].reject(new Error("Backend unavailable"));await h.settle();
  s=h.render();assert.equal(s.result,undefined);assert.match(s.error,/Backend unavailable/);
  s.setQuery("Needle");s=h.render();h.effects();h.debounce();h.calls[2].resolve(response("Needle"));await h.settle();
  s=h.render();void s.open("worksheets",s.result.groups[0].items[0]);h.opens[0].reject(new Error("Revoked"));await h.settle();
  assert.equal(h.render().result,undefined);assert.match(h.render().error,/current workspace/);assert.equal(h.published.length,0);
});
test("group pagination preserves independent pages and late record opens cannot cross queries or identities", async () => {
  const h=harness();let s=h.render();h.effects();s.setQuery("Needle");s=h.render();h.effects();h.debounce();h.calls[0].resolve(response("Needle"));await h.settle();
  s=h.render();const item=s.result.groups[0].items[0];void s.open("worksheets",item);
  s.changePage("worksheets",1);s=h.render();assert.equal(s.result,undefined);h.effects();h.debounce();
  assert.equal(h.opens[0].signal.aborted,true);assert.equal(h.calls[1].pages.worksheets,1);assert.equal(h.calls[1].pages.appointments,0);
  h.opens[0].resolve({...item,type:"worksheets",route:"work",detail:{}});await h.settle();assert.equal(h.published.length,0);
  h.calls[1].resolve(response("Needle"));await h.settle();s=h.render();void s.open("worksheets",item);
  h.render("account-b");h.opens[1].resolve({...item,type:"worksheets",route:"work",detail:{}});await h.settle();assert.equal(h.published.length,0);
});
