'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),readline=require('readline');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-lifecycle-')),trace=path.join(root,'trace.jsonl'),routers=[];
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const alive=pid=>{try{process.kill(pid,0);return true;}catch{return false;}};
const events=()=>fs.existsSync(trace)?fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse):[];
// Windows recycles PIDs during this rapid-switch fixture. A historical PID can
// belong to a new fixture child or an unrelated process; kill(pid, 0) alone cannot
// establish ownership. Query only IDs whose current command references this fixture.
function running(role) {
 const created=[...new Map(events().filter(e=>e.event==='created').map(e=>[e.pid,e])).values()];
 if(process.platform!=='win32')return created.filter(e=>(!role||e.role===role)&&alive(e.pid));
 const escaped=root.replace(/'/g,"''");
 const output=cp.execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',
  `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('${escaped}') -and -not $_.CommandLine.Contains('cps-router.js') -and $_.Name -eq 'node.exe' } | ForEach-Object ProcessId`],{encoding:'utf8',windowsHide:true});
 const owned=new Set(output.trim().split(/\s+/).map(Number));
 return created.filter(e=>(!role||e.role===role)&&owned.has(e.pid));
}
async function until(check,message){for(let i=0;i<120;i++){if(check())return;await pause(25);}throw Error(message);}
const mock=path.join(root,'mock.js');
fs.writeFileSync(mock,`const fs=require('fs'),cp=require('child_process'),rl=require('readline');
const record=e=>fs.appendFileSync(process.env.TRACE,JSON.stringify({...e,profile:process.env.PROFILE,pid:process.pid,ppid:process.ppid})+'\\n');
record({event:'created',role:'backend'});
const descendant="const fs=require('fs');fs.appendFileSync(process.argv[1],JSON.stringify({event:'created',role:'descendant',pid:process.pid,ppid:process.ppid})+'\\\\n');setInterval(()=>{},1000)";
cp.spawn(process.execPath,['-e',descendant,process.env.TRACE],{stdio:'ignore',windowsHide:true});
const send=m=>process.stdout.write(JSON.stringify({jsonrpc:'2.0',...m})+'\\n');
const options={configOptions:[{id:'model',type:'select',category:'model',name:'Model',currentValue:'a',options:[{value:'a',name:'A'},{value:'b',name:'B'}]}]};
rl.createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);record({event:'request',method:m.method,sessionId:m.params?.sessionId});
const failure=process.env.CONTROL?fs.readFileSync(process.env.CONTROL,'utf8'):process.env.FAIL;
if(m.method==='initialize') {if(failure==='initialize')return;return send({id:m.id,result:{protocolVersion:1,authMethods:[{id:'cached',name:'Cached'}],agentCapabilities:{loadSession:true,promptCapabilities:{image:true},sessionCapabilities:process.env.NO_CLOSE?{}:{close:{}}}}});}
if(m.method==='session/new') {if(failure==='new')return send({id:m.id,error:{code:-32000,message:'creation failed'}});return send({id:m.id,result:{sessionId:require('crypto').randomUUID(),...options}});}
if(m.method==='session/load')return send({id:m.id,result:{sessionId:m.params.sessionId,...options}});
if(m.method==='session/set_config_option') {if(process.env.FAIL==='preset')return send({id:m.id,result:{}});return send({id:m.id,result:{configOptions:options.configOptions.map(o=>({...o,currentValue:m.params.value}))}});}
if(m.method==='session/prompt')send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'mock answer'}}}});
if(m.id!==undefined)send({id:m.id,result:m.method==='session/prompt'?{stopReason:'end_turn'}:{}});
});`);
function client(options={}) {
 const file=path.join(root,Math.random()+'.json');
 const spec=p=>({agentId:'mock',command:process.execPath,args:[mock],inheritEnvironment:true,env:{TRACE:trace,PROFILE:p,...options.env},
  sessionSettings:options.preset?[{agentId:'mock',operation:'config',id:'model',value:'b'}]:[]});
 fs.writeFileSync(file,JSON.stringify({virtualSessions:!options.setup&&!options.probe,projects:[{root,profile:'a'}],profiles:options.setup?{probe:spec('setup')}:{a:spec('a'),b:spec('b')}}));
 const proc=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),file,...(options.setup?['--profile-setup']:options.probe?['--probe-defaults']:[])],
  // Windows process startup can exceed 200 ms while an IDE/build is running.
  {cwd:root,env:{...process.env,AIAPS_REQUEST_TIMEOUT_MS:'1500'},stdio:['pipe','pipe','pipe'],windowsHide:true});
 routers.push(proc);proc.stderr.resume();let serial=0;const pending=new Map();
 readline.createInterface({input:proc.stdout}).on('line',line=>{const m=JSON.parse(line),entry=pending.get(m.id);if(entry){pending.delete(m.id);clearTimeout(entry.timer);entry.resolve(m);}});
 return {proc,request(method,params={}){if(['_ai_multi/estimate','_ai_multi/switch'].includes(method))params={history:[],...params};const id=++serial;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Timeout: '+method)),7000);pending.set(id,{resolve,timer});proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});},
  async close(){proc.stdin.end();await until(()=>proc.exitCode!==null||proc.signalCode!==null,'Router did not close');}};
}
const ok=m=>{assert(!m.error,JSON.stringify(m.error));return m.result;};
async function empty(){await until(()=>running().length===0,'Owned backend/descendant survived cleanup');}
(async()=>{try {
 const c=client();ok(await c.request('initialize',{protocolVersion:1}));const id=ok(await c.request('session/new',{cwd:root})).sessionId;
 await until(()=>running('descendant').length===1,'Mock descendant did not start');assert.equal(running('backend').length,1,'Negotiation backend was duplicated');
 for(let i=0;i<12;i++){const s=ok(await c.request('_ai_multi/describe',{sessionId:id}));ok(await c.request('_ai_multi/switch',{sessionId:id,profile:i%2?'a':'b',fresh:true,revision:s.revision}));assert.equal(running('backend').length,1,'Old segment survived a switch');}
 assert.equal(events().filter(e=>e.method==='session/close').length,12,'Previous provider sessions were not closed');
 ok(await c.request('session/close',{sessionId:id}));await empty();assert(fs.readdirSync(path.join(root,'conversations')).some(f=>f.endsWith('.json')),'History was deleted');
 assert(!fs.readdirSync(path.join(root,'conversations')).some(f=>f.endsWith('.lock')),'Lease survived session close');
 ok(await c.request('session/load',{sessionId:id,cwd:root}));ok(await c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text:'mock question'}]}));
 const previousPid=running('backend')[0].pid;
 let state=ok(await c.request('_ai_multi/describe',{sessionId:id}));const prepared=ok(await c.request('_ai_multi/switch',{sessionId:id,profile:'b',exchanges:1,consent:true,revision:state.revision}));
 assert.equal(running('backend').length,2,'Rollback must retain exactly one previous backend');
 assert(ok(await c.request('_ai_multi/abort_handoff',{sessionId:id,token:prepared.token})).aborted);assert.equal(running('backend').length,1);assert(alive(previousPid),'Rollback lost its live previous backend');
 state=ok(await c.request('_ai_multi/describe',{sessionId:id}));const switched=ok(await c.request('_ai_multi/switch',{sessionId:id,profile:'b',exchanges:1,consent:true,revision:state.revision}));
 ok(await c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text:switched.token}]}));assert.equal(running('backend').length,1);assert(!alive(previousPid),'Consumed handoff retained old backend');
 const beforeRefresh=running('backend')[0].pid,closeCount=events().filter(e=>e.method==='session/close').length;
 for(let i=0;i<5;i++){ok(await c.request('_ai_multi/refresh_options',{sessionId:id}));assert.equal(running('backend').length,1);assert(alive(beforeRefresh));}
 assert.equal(events().filter(e=>e.method==='session/close').length,closeCount+5,'Discovery sessions were not closed before refresh returned');
 await c.close();await empty();
 for(const fail of ['initialize','new','preset']) {
  const broken=client({env:{FAIL:fail},preset:fail==='preset'});
  const init=await broken.request('initialize',{protocolVersion:1});
  if(fail==='initialize')assert(init.error);else{ok(init);assert((await broken.request('session/new',{cwd:root})).error);}
  await empty();assert(!fs.readdirSync(path.join(root,'conversations')).some(f=>f.endsWith('.lock')));await broken.close();
 }
 for(const mode of ['setup','probe'])for(const noClose of [false,true]) {
  const discovery=client({[mode]:true,env:noClose?{NO_CLOSE:'1'}:{}});
  if(mode==='setup'){ok(await discovery.request('_ai_multi/setup'));ok(await discovery.request('_ai_multi/authorize',{methodId:'cached'}));}
  const n=events().filter(e=>e.method==='session/close').length;
  ok(await discovery.request('_ai_multi/defaults',{profile:'a',cwd:root}));await empty();
  assert.equal(events().filter(e=>e.method==='session/close').length,n+(noClose?0:1));await discovery.close();
 }
 const control=path.join(root,'failure-control');fs.writeFileSync(control,'');
 const refreshFailure=client({env:{CONTROL:control}});ok(await refreshFailure.request('initialize',{protocolVersion:1}));
 const refreshId=ok(await refreshFailure.request('session/new',{cwd:root})).sessionId,activePid=running('backend')[0].pid;
 for(const failure of ['initialize','new']) {
  fs.writeFileSync(control,failure);
  for(let i=0;i<3;i++){assert((await refreshFailure.request('_ai_multi/refresh_options',{sessionId:refreshId})).error);assert.equal(running('backend').length,1);assert(alive(activePid));}
 }
 fs.writeFileSync(control,'');await refreshFailure.close();await empty();
 // Unsupported provider close still stops the owned process tree on EOF.
 const persistent=client({env:{NO_CLOSE:'1'}});ok(await persistent.request('initialize',{protocolVersion:1}));ok(await persistent.request('session/new',{cwd:root}));await persistent.close();await empty();
 assert.deepEqual(fs.readFileSync(path.join(__dirname,'cps-router.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/cps-router.js')));
 assert.deepEqual(fs.readFileSync(path.join(__dirname,'virtual-sessions.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/virtual-sessions.js')));
 console.log('PASS: bounded profile switches, rollback/consumed handoff, explicit close, retained history, failed initialize/new/presets, model refresh and profile setup/probe session closure, unsupported-close fallback, owned descendant cleanup, bundled parity');
}finally{
 for(const proc of routers)if(proc.exitCode===null&&proc.signalCode===null){proc.stdin.end();}
 await pause(300);
 for(const proc of routers)if(alive(proc.pid)){if(process.platform==='win32')cp.spawnSync('taskkill',['/PID',String(proc.pid),'/T','/F'],{stdio:'ignore',windowsHide:true});else proc.kill('SIGKILL');}
 for(const e of running())if(process.platform==='win32')cp.spawnSync('taskkill',['/PID',String(e.pid),'/T','/F'],{stdio:'ignore',windowsHide:true});else try{process.kill(e.pid,'SIGKILL');}catch(_){}
 await pause(100);assert.equal(running().length,0,'Test-owned processes remain');
 const target=path.resolve(root);assert.equal(path.dirname(target),path.resolve(os.tmpdir()));assert(path.basename(target).startsWith('ai-multi-lifecycle-'));fs.rmSync(target,{recursive:true,force:true,maxRetries:5,retryDelay:100});
}})().catch(e=>{console.error(e);process.exitCode=1;});
