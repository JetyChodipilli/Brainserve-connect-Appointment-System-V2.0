import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
const root=fileURLToPath(new URL('../',import.meta.url)),nativeRequire=createRequire(import.meta.url);
function modules(overrides={}) { const cache=new Map();return function load(path) {
    const filename=resolve(root,path);if(cache.has(filename))return cache.get(filename);const exports={};cache.set(filename,exports);
    const code=ts.transpileModule(readFileSync(filename,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX,esModuleInterop:true}}).outputText;
    vm.runInNewContext(code,{exports,AbortController,Date,Intl,Error,Map,Set,crypto:{randomUUID:()=>`client-${++overrides.uuid}`},setTimeout:overrides.window?.setTimeout??setTimeout,clearTimeout:overrides.window?.clearTimeout??clearTimeout,window:overrides.window,require(name){
        if(name==='react'&&overrides.react)return overrides.react;
        if(name.includes('lib/api-client')&&overrides.transport)return overrides.transport;
        if(name.endsWith('activity-api')&&overrides.api)return {activityApi:overrides.api};
        if(name.endsWith('use-task-comments')&&overrides.comments)return {useTaskComments:()=>overrides.comments};
        if(name.endsWith('use-activity-timeline')&&overrides.timeline)return {useActivityTimeline:()=>overrides.timeline};
        if(name.endsWith('.module.css'))return {__esModule:true,default:new Proxy({},{get:(_,key)=>key})};
        if(!name.startsWith('.'))return nativeRequire(name);const target=resolve(dirname(filename),name);let ext='.ts';try{readFileSync(target+ext);}catch{ext='.tsx';}return load(target+ext);
    }},{filename});return exports;
};}
const load=modules(),model=load('features/activity/activity-model.ts');
const actor={id:'actor',name:'Recorded name',role:'ROLE_EMPLOYEE',snapshotRecorded:true};
const event=(id,patch={})=>({id,occurredAt:'2026-10-04T00:00:00Z',eventType:'WORK_TASK_COMMENT_CREATED',title:'Comment added',actor,cycle:1,evidenceVersion:2,departmentId:'department',correlationId:'corr',deliveryStatus:null,note:null,...patch});
const comment=(patch={})=>({id:'comment',author:actor,body:'private comment',mentions:[],attachments:[],version:0,createdAt:'2026-10-04T00:00:00Z',editedAt:null,deletedAt:null,canEdit:true,...patch});
const discussion=(patch={})=>({comments:[],page:0,size:50,hasMore:false,canComment:true,participants:[{id:'lead',name:'Assigned Lead'}],evidence:[],...patch});
const plain=value=>JSON.parse(JSON.stringify(value));
test('same-time events order by stable ID and repeated receipts update rather than duplicate',()=>{
    const result=model.orderedActivity([event('b',{deliveryStatus:'QUEUED'})],[event('c'),event('a'),event('b',{deliveryStatus:'DELIVERED'})]);
    assert.deepEqual(plain(result.map(item=>item.id)),['a','b','c']);assert.equal(result[1].deliveryStatus,'DELIVERED');
    assert.equal(model.deliveryLabel('REQUESTED'),'Notification requested');assert.match(model.deliveryLabel('QUEUED'),/unconfirmed/);assert.match(model.deliveryLabel('SENT'),/receipt unconfirmed/);assert.equal(model.deliveryLabel('DELIVERED'),'Delivered to internal inbox');
});
test('history validates page boundaries and comments accept inert HTML but reject control characters and duplicate links',()=>{
    assert.equal(model.validateCommentDraft({body:'<script>test</script>',mentionUserIds:[],evidenceIds:[]}),null);
    for(const draft of [{body:' ',mentionUserIds:[],evidenceIds:[]},{body:'bad\0text',mentionUserIds:[],evidenceIds:[]},{body:'x',mentionUserIds:['same','same'],evidenceIds:[]}])assert.ok(model.validateCommentDraft(draft));
    assert.throws(()=>model.validateActivityPage({events:[],page:1,size:50,hasMore:false},0));assert.throws(()=>model.validateActivityPage({events:[event('x',{occurredAt:'invalid'})],page:0,size:50,hasMore:false},0));
});
test('production API carries scope IDs, fresh GETs and explicit mutation idempotency with automatic retry disabled',async()=>{
    const calls=[];const api=modules({transport:{apiRequest:async(path,init,retry)=>{calls.push({path,init,retry});},apiDownload:async(path)=>calls.push({path})}})('features/activity/api/activity-api.ts').activityApi;
    await api.timeline('appointment','visit /1',0);await api.comments('task /1',1);await api.create('task','request-key',{body:'Text',mentionUserIds:['lead'],evidenceIds:['file']});await api.edit('task','comment',4,{body:'Edited',mentionUserIds:[],evidenceIds:[]});await api.remove('task','comment',5);
    assert.equal(calls[0].path,'/appointments/visit%20%2F1/activity?page=0&size=50');assert.equal(calls[0].init.cache,'no-store');assert.match(calls[1].path,/task%20%2F1/);
    assert.equal(JSON.parse(calls[2].init.body).clientRequestId,'request-key');assert.equal(JSON.parse(calls[3].init.body).expectedVersion,4);assert.equal(calls[4].path,'/work-tasks/task/comments/comment?expectedVersion=5');for(const call of calls.slice(2))assert.equal(call.retry,false);
});
function harness(patch={},kind='comments') {
    let cursor=0,dirty=true,result;const slots=[],effects=[],listeners=new Map(),timers=new Map();let timer=0;
    const same=(a,b)=>a?.length===b?.length&&a?.every((value,index)=>Object.is(value,b[index]));
    const react={useState(initial){const i=cursor++;slots[i]??={value:typeof initial==='function'?initial():initial};return [slots[i].value,value=>{slots[i].value=typeof value==='function'?value(slots[i].value):value;dirty=true;}];},useRef(initial){const i=cursor++;return (slots[i]??={value:{current:initial}}).value;},useCallback(value,deps){const i=cursor++;if(!slots[i]||!same(slots[i].deps,deps))slots[i]={value,deps};return slots[i].value;},useEffect(callback,deps){const i=cursor++;if(!slots[i]||!same(slots[i].deps,deps)){const old=slots[i];slots[i]={deps};effects.push(()=>{old?.cleanup?.();slots[i].cleanup=callback();});}}};
    const window={setTimeout:callback=>{timers.set(++timer,callback);return timer;},clearTimeout:id=>timers.delete(id),addEventListener:(name,callback)=>{listeners.set(name,new Set([...(listeners.get(name)??[]),callback]));},removeEventListener:(name,callback)=>listeners.get(name)?.delete(callback)};
    class ApiError extends Error {constructor(status,detail='Scope removed'){super(detail);this.status=status;}}
    const calls=[];const api={comments:async()=>discussion(),create:async(id,key,draft)=>{calls.push({id,key,draft});return comment({body:draft.body});},edit:async(id,key,version,draft)=>{calls.push({id,key,version,draft});return comment({body:draft.body,version:version+1,editedAt:'2026-10-04T00:10:00Z'});},remove:async()=>comment({body:null,deletedAt:'2026-10-04T00:10:00Z',version:1}),download:async()=>({}),timeline:async()=>({events:[event('a')],page:0,size:50,hasMore:false}),...patch};
    const runner=modules({react,window,api,transport:{isBackendConfigured:true,ApiError},uuid:0})(kind==='comments'?'features/activity/hooks/use-task-comments.ts':'features/activity/hooks/use-activity-timeline.ts');
    const run=()=>kind==='comments'?runner.useTaskComments('task'):runner.useActivityTimeline('task','task');
    const render=()=>{if(!dirty)return result;dirty=false;cursor=0;result=run();effects.splice(0).forEach(effect=>effect());return result;};
    const flush=async()=>{for(let i=0;i<15;i++){render();const batch=[...timers.values()];timers.clear();batch.forEach(callback=>callback());await Promise.resolve();await Promise.resolve();}return render();};
    return {render,flush,get value(){return result;},api,calls,ApiError,event:name=>listeners.get(name)?.forEach(callback=>callback())};
}
test('failed create retains memory text and request ID for explicit retry, changed contents receive a new ID',async()=>{
    const h=harness();await h.flush();let fail=true;h.api.create=async(id,key,draft)=>{h.calls.push({id,key,draft});if(fail)throw Error('network');return comment({body:draft.body});};
    h.value.setDraft({body:'  Plain message  ',mentionUserIds:['lead'],evidenceIds:[]});h.render();await h.value.submit();h.render();assert.equal(h.value.draft.body,'  Plain message  ');assert.equal(h.calls.length,1);
    await h.value.submit();h.render();assert.equal(h.calls[1].key,h.calls[0].key);
    h.value.setDraft({body:'Changed message',mentionUserIds:['lead'],evidenceIds:[]});h.render();fail=false;await h.value.submit();h.render();assert.notEqual(h.calls[2].key,h.calls[1].key);assert.equal(h.value.draft.body,'');assert.equal(h.value.discussion.comments[0].body,'Changed message');
});
test('edit conflicts preserve notes and require explicit fresh reload before overwrite',async()=>{
    const h=harness({comments:async()=>discussion({comments:[comment({version:2})]})});await h.flush();h.value.edit(comment());h.value.setDraft({body:'Unsaved correction',mentionUserIds:[],evidenceIds:[]});h.render();h.api.edit=async()=>{throw new h.ApiError(409,'Comment changed');};await h.value.submit();h.render();assert.equal(h.value.conflict,true);assert.equal(h.value.draft.body,'Unsaved correction');
    await h.value.reload();h.render();assert.equal(h.value.editing.version,2);assert.equal(h.value.draft.body,'Unsaved correction');let written;h.api.edit=async(id,key,version,draft)=>{written={version,draft};return comment({version:3,body:draft.body});};await h.value.submit();h.render();assert.equal(written.version,2);assert.equal(h.value.editing,null);
});
test('denied attachment clears private discussion and draft; late session responses never repopulate them',async()=>{
    const h=harness({comments:async()=>discussion({comments:[comment()]})});await h.flush();h.value.setDraft({body:'secret',mentionUserIds:[],evidenceIds:[]});h.render();h.api.download=async()=>{throw new h.ApiError(404);};await h.value.download(comment(),'file');h.render();assert.equal(h.value.discussion,null);assert.equal(h.value.draft.body,'');
    let release;h.api.comments=()=>new Promise(resolve=>release=resolve);const pending=h.value.reload();h.render();h.event('brainserve:auth-session-changed');h.render();release(discussion({comments:[comment({body:'old account'})]}));await pending;h.render();assert.equal(h.value.discussion,null);assert.match(h.value.error,/Session changed/);
});
test('timeline rejects late account responses and rechecks private history on reload errors',async()=>{
    let release;const h=harness({timeline:()=>new Promise(resolve=>release=resolve)},'timeline');await h.flush();h.event('brainserve:auth-session-changed');h.render();release({events:[event('old')],page:0,size:50,hasMore:false});await h.flush();assert.equal(h.value.events.length,0);assert.match(h.value.error,/Session changed/);
    const authorized=harness({},'timeline');await authorized.flush();assert.equal(authorized.value.events.length,1);authorized.api.timeline=async()=>{throw new authorized.ApiError(403);};await authorized.value.reload();authorized.render();assert.equal(authorized.value.events.length,0);
});
test('rendered participant text and mention names are inert; read-only previews contain no mutation controls',()=>{
    const raw='<img src=x onerror=window.pwned=1><script>bad()</script>';
    const comments={discussion:discussion({comments:[comment({body:raw,mentions:[{id:'lead',name:'<b>Lead</b>'}]})]}),draft:{body:'',mentionUserIds:[],evidenceIds:[]},editing:null,error:'',saved:'',busy:false,conflict:false,revision:0};
    const Component=modules({comments,timeline:{events:[event('a',{deliveryStatus:'QUEUED'})],error:'',busy:false,hasMore:false}})('features/workboard/components/work-activity.tsx').WorkActivity;
    const markup=renderToStaticMarkup(React.createElement(Component,{taskId:'task',readOnly:true}));assert.ok(markup.includes('&lt;img'));assert.ok(!markup.includes('<script>'));assert.ok(markup.includes('&lt;b&gt;Lead&lt;/b&gt;'));assert.ok(!markup.includes('Post comment'));assert.ok(!markup.includes('Remove comment'));assert.match(markup,/Queued · delivery unconfirmed/);
});
