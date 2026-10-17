'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),rl=require('readline'),crypto=require('crypto');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-transparent-')),trace=path.join(root,'trace'),configFile=path.join(root,'routes.json'),children=[];
const backendFile=path.join(root,'agent.js');
fs.writeFileSync(backendFile,String.raw`
const fs=require('fs'),path=require('path'),crypto=require('crypto'),rl=require('readline');
const agent=process.env.AGENT,store=path.join(process.env.STORE,agent);fs.mkdirSync(store,{recursive:true});
const file=id=>path.join(store,id+'.json'),send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
const identity=stage=>({authStatus:{kind:'account',label:agent,account:{email:agent+'@example.test',futureAccount:{kept:true}},vendor:{stage,agent}},futureNotification:{kept:7}});
const push=stage=>{send({method:'_auth/status_update',params:identity(stage),_meta:{future:true}});send({method:'_fixture/future_notification',params:{agent,stage,future:{kept:true}}});};
rl.createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line),p=m.params||{};fs.appendFileSync(process.env.TRACE,JSON.stringify({agent,m})+'\n');
 if(m.method==='initialize'){
  if(process.env.AUTH_TIMING==='before')push('initial');
  send({id:m.id,result:{protocolVersion:1,authMethods:[],agentInfo:{name:agent,version:'fixture'},_meta:{futureExtension:{value:agent}},agentCapabilities:{loadSession:true,_meta:{authStatus:{future:agent}},promptCapabilities:{image:false,_future:agent},sessionCapabilities:{...(process.env.RESUME==='yes'?{resume:{}}:{}),list:{},fork:{},delete:{},close:{}},_future:{enabled:true}}}});
  if(process.env.AUTH_TIMING!=='before')setImmediate(()=>push('initial'));
  return;
 }
 if(m.method==='_fixture/schedule_status'){setTimeout(()=>push(p.stage),p.delay);return send({id:m.id,result:{scheduled:true}});}
 if(m.method==='session/new' && p.failCreation)return send({id:m.id,error:{code:-32007,message:'Fixture creation failed',data:{unchanged:true}}});
 if(m.method==='session/new'){const id=agent+'-'+crypto.randomUUID();fs.writeFileSync(file(id),JSON.stringify({prompts:[],cwd:p.cwd}));return send({id:m.id,result:{sessionId:id,_meta:{new:agent}}});}
 if(m.method==='session/resume' && process.env.RESUME!=='yes')return send({id:m.id,error:{code:-32601,message:'Provider resume is unsupported',data:{provider:agent,unchanged:true}}});
 if(['session/load','session/resume','session/prompt','session/fork','session/delete'].includes(m.method) && !fs.existsSync(file(p.sessionId)))return send({id:m.id,error:{code:-32004,message:'Unknown provider session',data:{agent}}});
 if(m.method==='session/load'){
  for(const update of [{sessionUpdate:'user_message_chunk',content:{type:'text',text:'PROVIDER_USER_'+agent}},{sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'PROVIDER_THOUGHT_'+agent}},{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'PROVIDER_REPLY_'+agent}},{sessionUpdate:'_future_update',value:{agent,opaque:7},_meta:{future:true}}])send({method:'session/update',params:{sessionId:p.sessionId,update}});
  return send({id:m.id,result:{_meta:{restored:agent},futureResult:{kept:true}}});
 }
 if(m.method==='session/resume')return send({id:m.id,result:{_meta:{resumed:agent},futureResult:{kept:true}}});
 if(m.method==='session/prompt'){
  const record=JSON.parse(fs.readFileSync(file(p.sessionId)));record.prompts.push(p.prompt);fs.writeFileSync(file(p.sessionId),JSON.stringify(record));
  send({method:'session/update',params:{sessionId:p.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:agent+' reply'}}}});
  return send({id:m.id,result:{stopReason:'end_turn',_meta:{agent}}});
 }
 if(m.method==='session/list')return send({id:m.id,result:{sessions:fs.readdirSync(store).map(f=>({sessionId:f.slice(0,-5),cwd:JSON.parse(fs.readFileSync(path.join(store,f))).cwd,_meta:{agent}})),nextCursor:'provider-cursor',_meta:{providerOwnsPagination:true}}});
 if(m.method==='session/fork'){const id=agent+'-'+crypto.randomUUID();fs.copyFileSync(file(p.sessionId),file(id));send({method:'session/update',params:{sessionId:id,update:{sessionUpdate:'_fork_ready',agent}}});return send({id:m.id,result:{sessionId:id,_meta:{fork:agent}}});}
 if(m.method==='session/delete'){fs.unlinkSync(file(p.sessionId));return send({id:m.id,result:{_meta:{deleted:agent}}});}
 if(m.method==='session/close')return send({id:m.id,result:{_meta:{closed:agent}}});
 return send({id:m.id,result:{params:p,_meta:{extension:agent}}});
});
`);
const spec=(agent,resume=true)=>({name:'Same profile',agentId:'registry.'+agent,agentName:'Agent '+agent,command:process.execPath,args:[backendFile],env:{AGENT:agent,RESUME:resume?'yes':'no',STORE:root,TRACE:trace}});
let config={virtualSessions:true,projects:[{root,profile:'p'}],profiles:{p:spec('one'),other:spec('other')}};
const write=()=>fs.writeFileSync(configFile,JSON.stringify(config));write();
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const waitFor=async condition=>{for(let i=0;i<200 && !condition();i++)await pause(10);};
const calls=()=>fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const records=()=>fs.readdirSync(path.join(root,'conversations')).filter(f=>f.endsWith('.json')).map(f=>JSON.parse(fs.readFileSync(path.join(root,'conversations',f))));
const ok=m=>{assert(m.result,JSON.stringify(m));return m.result;},bad=m=>{assert(m.error,JSON.stringify(m));return m.error;};
const nativeHistory=new (require('./test-native-history').NativeHistoryFixture)();
function client(uid=crypto.randomUUID()) {
 const proc=cp.spawn(process.execPath,[process.env.AI_MULTI_ROUTER_FIXTURE || path.join(__dirname,'cps-router.js'),configFile],{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});children.push(proc);proc.stderr.resume();
 const messages=[],pending=new Map();let serial=0;
 rl.createInterface({input:proc.stdout}).on('line',line=>{const m=JSON.parse(line);messages.push(m);nativeHistory.notification(m);if(!m.method)pending.get(m.id)?.(m);});
 return {proc,messages,uid,async init(){return this.request('initialize',{protocolVersion:2,clientInfo:{name:'fixture'},_meta:{proxyConfig:{proxies:[{proxy:{headers:{'X-IntelliJ-Proxy-Client-Execution-ID':uid}}}]}}});},
 request(method,params={}){params=nativeHistory.before(method,params);const id=++serial;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Timeout '+method)),15000);pending.set(id,m=>{clearTimeout(timer);pending.delete(id);nativeHistory.complete(method,params,m);resolve(m);});proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});},
 async close(){proc.stdin.end();if(proc.exitCode===null)await new Promise(r=>proc.once('exit',r));}};
}
async function approval(pending,approved,selection={},expected={from:'one',to:'two'}) {
 const dir=path.join(root,'agent-recovery');
 for(let i=0;i<100;i++){
  if(fs.existsSync(dir))for(const name of fs.readdirSync(dir).filter(x=>x.endsWith('.request.json'))){
   const request=JSON.parse(fs.readFileSync(path.join(dir,name)));
   if(fs.existsSync(path.join(dir,request.nonce+'.response.json')) || fs.existsSync(path.join(dir,request.nonce+'.ready.json')))continue;
   assert.equal(request.from.agentId,'registry.'+expected.from);assert.equal(request.to.agentId,'registry.'+expected.to);
   assert.equal(request.from.profileName,request.to.profileName);assert.equal(Object.keys(request.estimates).length,21);
   assert(!calls().some(x=>x.agent===expected.to&&['session/new','session/load','session/resume','session/prompt'].includes(x.m.method)));
   fs.writeFileSync(path.join(dir,request.nonce+'.response.json'),JSON.stringify({nonce:request.nonce,approved,newChat:false,exchanges:2,history:nativeHistory.history(request.sessionId),comment:'Continue carefully',consent:true,attachmentIds:[],...selection}));
   return {request,result:await pending};
  }
  await pause(50);
 }
 throw Error('No agent recovery dialog request');
}
(async()=>{
 // A real agent pushes connection identity once, often after initialize has
 // completed and before any session RPC is pending. Preserve the exact push.
 for(const timing of ['after','before']){
  config.profiles.p.env.AUTH_TIMING=timing;write();
  const startup=client();const startupInit=ok(await startup.init());
  assert.deepEqual(startupInit.agentCapabilities._meta.authStatus,{future:'one'});
  await waitFor(()=>startup.messages.some(x=>x.method==='_auth/status_update'));
  const status=startup.messages.filter(x=>x.method==='_auth/status_update');
  assert.equal(status.length,1,'Missing or duplicate '+timing+'-initialize account push');
  assert.deepEqual(status[0],{jsonrpc:'2.0',method:'_auth/status_update',params:{authStatus:{kind:'account',label:'one',account:{email:'one@example.test',futureAccount:{kept:true}},vendor:{stage:'initial',agent:'one'}},futureNotification:{kept:7}},_meta:{future:true}});
  assert(startup.messages.findIndex(x=>x.id===1)<startup.messages.indexOf(status[0]),'Account push overtook client initialization');
  assert.equal(startup.messages.filter(x=>x.method==='_fixture/future_notification').length,1);
  await startup.close();
 }
 delete config.profiles.p.env.AUTH_TIMING;write();
 // Forks on one backend remain unambiguous; several different backends do not.
 // Closing the second source publishes the first source's latest actual push.
 const shared=client(null);ok(await shared.init());const sharedId=ok(await shared.request('session/new',{cwd:root})).sessionId;
 const sharedFork=ok(await shared.request('session/fork',{sessionId:sharedId,cwd:root})).sessionId;
 const beforeShared=shared.messages.length;
 ok(await shared.request('_fixture/schedule_status',{sessionId:sharedId,stage:'shared-fork',delay:0}));
 await waitFor(()=>shared.messages.slice(beforeShared).some(x=>x.params?.authStatus?.vendor?.stage==='shared-fork'));
 assert.equal(shared.messages.slice(beforeShared).filter(x=>x.method==='_auth/status_update').at(-1).params.authStatus.vendor.stage,'shared-fork');
 ok(await shared.request('_fixture/schedule_status',{sessionId:sharedId,stage:'while-ambiguous',delay:200}));
 const additional=ok(await shared.request('session/new',{cwd:root})).sessionId;
 const beforeAmbiguous=shared.messages.length;await pause(300);
 assert(!shared.messages.slice(beforeAmbiguous).some(x=>x.method==='_auth/status_update'),'Ambiguous source published a connection identity');
 ok(await shared.request('session/close',{sessionId:additional}));
 await waitFor(()=>shared.messages.slice(beforeAmbiguous).some(x=>x.params?.authStatus?.vendor?.stage==='while-ambiguous'));
 assert.equal(shared.messages.slice(beforeAmbiguous).filter(x=>x.method==='_auth/status_update').at(-1).params.authStatus.vendor.stage,'while-ambiguous');
 await shared.close();
 let c=client();const uid=c.uid,init=ok(await c.init());
 assert.equal(init.protocolVersion,1);assert.equal(init.agentCapabilities.promptCapabilities.image,false);assert.equal(init.agentCapabilities.promptCapabilities._future,'one');assert.deepEqual(init.agentCapabilities._future,{enabled:true});assert.deepEqual(init._meta.futureExtension,{value:'one'});
 assert(calls().filter(x=>x.m.method==='initialize').every(x=>x.m.params.protocolVersion===1));
 const initializedCount=calls().filter(x=>x.m.method==='initialize').length;
 assert.equal(bad(await c.request('session/new',{cwd:root,failCreation:true})).code,-32007);
 assert.deepEqual(ok(await c.init()),init);
 assert.equal(calls().filter(x=>x.m.method==='initialize').length,initializedCount,'Retry created another negotiation backend');
 bad(await c.request('initialize',{protocolVersion:1,clientCapabilities:{terminal:true}}));
 const id=ok(await c.request('session/new',{cwd:root,mcpServers:[]})).sessionId;
 ok(await c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text:'OLD_LOGICAL_HISTORY'}]}));
 const initial=records().find(x=>x.sessionId===id),oldProviderId=initial.segments[0].sessionId;
 assert.equal(initial.segments[0].agentIdentity.agentId,'registry.one');await c.close();
 c=client(uid);ok(await c.init());
 const params={sessionId:id,cwd:root,mcpServers:[],_meta:{opaque:{forwarded:true}},futureParameter:'unchanged'};
 const loaded=ok(await c.request('session/load',params));assert.deepEqual(loaded.futureResult,{kept:true});assert.equal(loaded._meta.restored,'one');
 const updates=c.messages.filter(m=>m.method==='session/update').map(m=>m.params.update);
 assert(updates.some(x=>x.content?.text==='PROVIDER_USER_one'));assert(updates.some(x=>x.content?.text==='PROVIDER_THOUGHT_one'));assert(updates.some(x=>x.sessionUpdate==='_future_update'&&x._meta.future));assert(!JSON.stringify(updates).includes('OLD_LOGICAL_HISTORY'));
 const loadCall=calls().filter(x=>x.m.method==='session/load').at(-1).m;assert.equal(loadCall.params.sessionId,oldProviderId);assert.equal(loadCall.params.futureParameter,'unchanged');assert.deepEqual(loadCall.params._meta,params._meta);
 const before=c.messages.length;ok(await c.request('session/resume',params));assert(!c.messages.slice(before).some(m=>m.method==='session/update'));
 ok(await c.request('session/prompt',{sessionId:id,prompt:[{type:'_future_content',opaque:{kept:true}}]}));assert.equal(calls().filter(x=>x.m.method==='session/prompt').at(-1).m.params.prompt[0].type,'_future_content');
 const listed=ok(await c.request('session/list',{cwd:root,cursor:'unchanged-cursor'}));assert.equal(listed.nextCursor,'provider-cursor');assert(listed.sessions.some(x=>x.sessionId===id));assert.equal(calls().filter(x=>x.m.method==='session/list').at(-1).m.params.cursor,'unchanged-cursor');
 const fork=ok(await c.request('session/fork',{sessionId:id,cwd:root,_meta:{forkExtension:true}}));assert.notEqual(fork.sessionId,id);assert.equal(fork._meta.fork,'one');
 for(let i=0;i<100&&!c.messages.some(x=>x.params?.sessionId===fork.sessionId&&x.params.update?.sessionUpdate==='_fork_ready');i++)await pause(10);
 assert(c.messages.some(x=>x.params?.sessionId===fork.sessionId&&x.params.update?.sessionUpdate==='_fork_ready'));
 ok(await c.request('session/close',{sessionId:id}));ok(await c.request('session/prompt',{sessionId:fork.sessionId,prompt:[{type:'text',text:'fork remains live'}]}));
 ok(await c.request('session/delete',{sessionId:fork.sessionId}));assert(!records().some(x=>x.sessionId===fork.sessionId));await c.close();
 // A changed agent never receives the previous agent's ID. Rejecting recovery creates nothing.
 config.profiles.p=spec('two');write();c=client(uid);
 const declined=await approval(c.init(),false);assert.equal(bad(declined.result).code,-32001);await c.close();
 assert.equal(records().find(x=>x.sessionId===id).currentSegment,initial.currentSegment);
 // Explicit consent starts one new backend session and preserves historical identity snapshots.
 c=client(uid);const accepted=await approval(c.init(),true);ok(accepted.result);
 ok(await c.request('session/load',{sessionId:id,cwd:root,mcpServers:[{name:'approved-tools',command:'fixture',args:[],env:[]}]}));
 const recovered=records().find(x=>x.sessionId===id),last=recovered.segments.find(x=>x.id===recovered.currentSegment);
 assert.equal(recovered.segments[0].agentIdentity.agentId,'registry.one');assert.equal(last.agentIdentity.agentId,'registry.two');assert.notEqual(last.sessionId,oldProviderId);
 assert.equal(calls().filter(x=>x.agent==='two'&&x.m.method==='session/new').length,1);
 assert(!calls().some(x=>x.agent==='two'&&x.m.params?.sessionId===oldProviderId));
 const created=calls().find(x=>x.agent==='two'&&x.m.method==='session/new');assert.equal(created.m.params.mcpServers[0].name,'approved-tools');
 const token=recovered.pendingHandoff.token;
 // A real user prompt admitted during restoration consumes the approved transfer once.
 ok(await c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text:'NEW_USER_REQUEST'}]}));
 const outgoing=calls().filter(x=>x.agent==='two'&&x.m.method==='session/prompt').at(-1).m.params.prompt;
 assert(JSON.stringify(outgoing).includes('OLD_LOGICAL_HISTORY'));assert(JSON.stringify(outgoing).includes('NEW_USER_REQUEST'));
 bad(await c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text:token}]}));await c.close();
 // Unsupported resume is delegated unchanged; no load substitution or capabilities inflation.
 config.profiles.p=spec('two',false);write();c=client();const loadOnly=ok(await c.init());assert(!loadOnly.agentCapabilities.sessionCapabilities.resume);
 const loadId=ok(await c.request('session/new',{cwd:root})).sessionId;await c.close();c=client();ok(await c.init());
 const count=calls().filter(x=>x.m.method==='session/load').length;
 assert.deepEqual(bad(await c.request('session/resume',{sessionId:loadId,cwd:root})),{code:-32601,message:'Provider resume is unsupported',data:{provider:'two',unchanged:true}});
 assert.equal(calls().filter(x=>x.m.method==='session/load').length,count);await c.close();
 // Replacing the agent without sharing history also requires approval and starts empty.
 config.profiles.p=spec('three');write();c=client(uid);
 ok((await approval(c.init(),true,{newChat:true,consent:false,exchanges:0},{from:'two',to:'three'})).result);
 ok(await c.request('session/load',{sessionId:id,cwd:root,mcpServers:[]}));
 assert(!records().find(x=>x.sessionId===id).pendingHandoff);
 ok(await c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text:'ONLY_FRESH_REQUEST'}]}));
 assert.deepEqual(calls().filter(x=>x.agent==='three'&&x.m.method==='session/prompt').at(-1).m.params.prompt,[{type:'text',text:'ONLY_FRESH_REQUEST'}]);await c.close();
 // Approval is bound to the exact profile configuration, not just its display name.
 config.profiles.p=spec('four');write();c=client(uid);const stale=c.init();
 for(let i=0;i<100&&!fs.readdirSync(path.join(root,'agent-recovery')).some(n=>n.endsWith('.request.json')&&!fs.existsSync(path.join(root,'agent-recovery',n.replace('.request.json','.ready.json'))));i++)await pause(25);
 config.profiles.p=spec('five');write();
 assert(bad((await approval(stale,true,{}, {from:'three',to:'four'})).result).message.includes('changed while recovery was open'));
 assert(!calls().some(x=>['four','five'].includes(x.agent)&&x.m.method==='session/new'));await c.close();
 // Multiple profile switches restore only the last committed agent, with the client's exact method.
 const multi=client();ok(await multi.init());const multiId=ok(await multi.request('session/new',{cwd:root})).sessionId;
 ok(await multi.request('session/prompt',{sessionId:multiId,prompt:[{type:'text',text:'FIRST_PROFILE_MESSAGE'}]}));
 const estimate=ok(await multi.request('_ai_multi/estimate',{sessionId:multiId,profile:'other',exchanges:1}));
 const switchResult=ok(await multi.request('_ai_multi/switch',{sessionId:multiId,profile:'other',exchanges:1,revision:estimate.revision,consent:true}));
 ok(await multi.request('session/prompt',{sessionId:multiId,prompt:[{type:'text',text:switchResult.token}]}));await multi.close();
 const multiReload=client(multi.uid);const multiInit=ok(await multiReload.init());assert.equal(multiInit._meta.futureExtension.value,'other');
 await pause(100);assert.equal(multiReload.messages.filter(x=>x.method==='_auth/status_update').at(-1).params.authStatus.account.email,'other@example.test');
 ok(await multiReload.request('session/load',{sessionId:multiId,cwd:root}));assert.equal(calls().filter(x=>x.m.method==='session/load').at(-1).agent,'other');
 assert(!multiReload.messages.some(x=>x.params?.update?.content?.text?.includes('FIRST_PROFILE_MESSAGE')));await multiReload.close();
 // A target preview must not change the live account. Nor may a retained old
 // backend overwrite it while a consented continuation waits to be delivered.
 const isolated=client();ok(await isolated.init());const isolatedId=ok(await isolated.request('session/new',{cwd:root})).sessionId;
 await pause(100);ok(await isolated.request('session/prompt',{sessionId:isolatedId,prompt:[{type:'text',text:'Identity isolation'}]}));
 const beforePreview=isolated.messages.length;
 const isolatedEstimate=ok(await isolated.request('_ai_multi/estimate',{sessionId:isolatedId,profile:'other',exchanges:1}));
 await pause(100);assert(!isolated.messages.slice(beforePreview).some(x=>x.method==='_auth/status_update' || x.method==='_fixture/future_notification'),'Preview leaked connection notifications');
 ok(await isolated.request('_fixture/schedule_status',{stage:'retained-source',delay:500}));
 const isolatedSwitch=ok(await isolated.request('_ai_multi/switch',{sessionId:isolatedId,profile:'other',exchanges:1,revision:isolatedEstimate.revision,consent:true}));
 await pause(600);
 const switchedStatus=isolated.messages.slice(beforePreview).filter(x=>x.method==='_auth/status_update');
 assert.equal(switchedStatus.length,1);assert.equal(switchedStatus[0].params.authStatus.account.email,'other@example.test');
 assert(!isolated.messages.slice(beforePreview).some(x=>x.params?.stage==='retained-source' || x.params?.authStatus?.vendor?.stage==='retained-source'),'Retained source overwrote selected agent');
 const beforeAbort=isolated.messages.length;
 assert.equal(ok(await isolated.request('_ai_multi/abort_handoff',{sessionId:isolatedId,token:isolatedSwitch.token})).aborted,true);
 await waitFor(()=>isolated.messages.slice(beforeAbort).some(x=>x.params?.authStatus?.vendor?.stage==='retained-source'));
 const restoredStatus=isolated.messages.slice(beforeAbort).filter(x=>x.method==='_auth/status_update');
 assert.equal(restoredStatus.length,1);assert.equal(restoredStatus[0].params.authStatus.vendor.stage,'retained-source');
 assert.equal(restoredStatus[0].params.authStatus.account.email,'five@example.test');
 await isolated.close();
 assert.equal(config.projects[0].profile,'p');
 console.log('PASS: actual capabilities/extensions, v1 negotiation of v2 offer, exact load/resume and provider replay/errors, last segment, future content, provider pagination, fork/close/delete, agent snapshots, cancelled and consented replacement, original MCP, one native continuation and no old ID sent to a new agent');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{
 for(const child of children)if(child.exitCode===null)child.stdin.end();
 await Promise.all(children.map(p=>p.exitCode!==null?null:new Promise(r=>p.once('exit',r))));
 if(path.dirname(root)===os.tmpdir() && path.basename(root).startsWith('ai-multi-transparent-'))fs.rmSync(root,{recursive:true,force:true,maxRetries:10,retryDelay:100});
});
