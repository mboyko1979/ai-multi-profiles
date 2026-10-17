'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),rl=require('readline'),crypto=require('crypto');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-transparent-')),trace=path.join(root,'trace'),configFile=path.join(root,'routes.json'),children=[];
const backendFile=path.join(root,'agent.js');
fs.writeFileSync(backendFile,String.raw`
const fs=require('fs'),path=require('path'),crypto=require('crypto'),rl=require('readline');
const agent=process.env.AGENT,store=path.join(process.env.STORE,agent);fs.mkdirSync(store,{recursive:true});
const file=id=>path.join(store,id+'.json'),send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
rl.createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line),p=m.params||{};fs.appendFileSync(process.env.TRACE,JSON.stringify({agent,m})+'\n');
 if(m.method==='initialize')return send({id:m.id,result:{protocolVersion:1,authMethods:[],agentInfo:{name:agent,version:'fixture'},_meta:{futureExtension:{value:agent}},agentCapabilities:{loadSession:true,promptCapabilities:{image:false,_future:agent},sessionCapabilities:{...(process.env.RESUME==='yes'?{resume:{}}:{}),list:{},fork:{},delete:{},close:{}},_future:{enabled:true}}}});
 if(m.method==='session/new' && p.failCreation)return send({id:m.id,error:{code:-32007,message:'Fixture creation failed',data:{unchanged:true}}});
 if(m.method==='session/new'){const id=agent+'-'+crypto.randomUUID();fs.writeFileSync(file(id),JSON.stringify({prompts:[],cwd:p.cwd}));return send({id:m.id,result:{sessionId:id,_meta:{new:agent}}});}
 if(m.method==='session/resume' && process.env.RESUME!=='yes')return send({id:m.id,error:{code:-32601,message:'Provider resume is unsupported',data:{provider:agent,unchanged:true}}});
 if(['session/load','session/resume','session/prompt','session/fork','session/delete'].includes(m.method) && !fs.existsSync(file(p.sessionId)))return send({id:m.id,error:{code:-32004,message:'Unknown provider session',data:{agent}}});
 if(m.method==='session/load'){
  if(p.rejectModel)return send({id:m.id,error:{code:-32090,message:'Saved model is unavailable',data:{model:'retired-model',providerOwnsResolution:true}}});
  for(const update of [{sessionUpdate:'user_message_chunk',content:{type:'text',text:'PROVIDER_USER_'+agent}},{sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'PROVIDER_THOUGHT_'+agent}},{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'PROVIDER_REPLY_'+agent}},{sessionUpdate:'_future_update',value:{agent,opaque:7},_meta:{future:true}}])send({method:'session/update',params:{sessionId:p.sessionId,update}});
  return send({id:m.id,result:{models:{currentModelId:'retired-model',availableModels:[{modelId:'replacement',name:'Replacement'}]},_meta:{restored:agent},futureResult:{kept:true}}});
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
const calls=()=>fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const records=()=>fs.readdirSync(path.join(root,'conversations')).filter(f=>f.endsWith('.json')).map(f=>JSON.parse(fs.readFileSync(path.join(root,'conversations',f))));
const ok=m=>{assert(m.result,JSON.stringify(m));return m.result;},bad=m=>{assert(m.error,JSON.stringify(m));return m.error;};
function client(uid=crypto.randomUUID(),env={}) {
 const proc=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),configFile],{cwd:root,windowsHide:true,env:{...process.env,...env},stdio:['pipe','pipe','pipe']});children.push(proc);proc.stderr.resume();
 const messages=[],pending=new Map();let serial=0;
 rl.createInterface({input:proc.stdout}).on('line',line=>{const m=JSON.parse(line);messages.push(m);if(!m.method)pending.get(m.id)?.(m);});
 return {proc,messages,uid,async init(){return this.request('initialize',{protocolVersion:2,clientInfo:{name:'fixture'},_meta:{proxyConfig:{proxies:[{proxy:{headers:{'X-IntelliJ-Proxy-Client-Execution-ID':uid}}}]}}});},
 request(method,params={}){const id=++serial;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Timeout '+method)),15000);pending.set(id,m=>{clearTimeout(timer);pending.delete(id);resolve(m);});proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});},
 async close(){proc.stdin.end();if(proc.exitCode===null)await new Promise(r=>proc.once('exit',r));}};
}

async function decision(kind,answer) {
 const dir=path.join(root,'agent-recovery');
 for(let i=0;i<100;i++) {
  if(fs.existsSync(dir))for(const file of fs.readdirSync(dir).filter(x=>x.endsWith('.request.json'))) {
   const r=JSON.parse(fs.readFileSync(path.join(dir,file)));if(fs.existsSync(path.join(dir,r.nonce+'.ready.json')))continue;
   if(r.kind!==kind || fs.existsSync(path.join(dir,r.nonce+'.response.json')))continue;
   fs.writeFileSync(path.join(dir,r.nonce+'.response.json'),JSON.stringify({nonce:r.nonce,...answer}));return r;
  }
  await pause(30);
 }throw Error('No recovery dialog: '+kind);
}
(async()=>{
 let c=client(undefined,{EFC_123_456:'first'});const uid=c.uid;
 ok(await c.init());const id=ok(await c.request('session/new',{cwd:root})).sessionId;
 let saved=records().find(x=>x.sessionId===id),sid=saved.segments[0].sessionId;
 assert(saved.segments[0].restoreFingerprint);await c.close();
 c=client(uid,{EFC_123_456:'second'});
 const [a,b]=await Promise.all([c.init(),c.init()]);assert.deepEqual(ok(a),ok(b));
 ok(await c.request('session/load',{sessionId:id,cwd:root,future:'preserved'}));await c.close();
 assert.equal(calls().filter(x=>x.m.method==='session/load').at(-1).m.params.sessionId,sid);
 assert.equal(calls().filter(x=>x.m.method==='session/load').at(-1).m.params.future,'preserved');
 // Simulate a real old record: only opaque full environment digests exist.
 saved=records().find(x=>x.sessionId===id);delete saved.segments[0].restoreFingerprint;
 saved.segments[0].fingerprint='historic-full-env';saved.segments[0].launchFingerprint='historic-launch-env';
 const recordFile=path.join(root,'conversations',crypto.createHash('sha256').update(JSON.stringify(id)).digest('hex')+'.json');
 fs.writeFileSync(recordFile,JSON.stringify(saved));
 c=client(uid);ok(await c.init());
 ok(await c.request('session/resume',{sessionId:id,cwd:root}));await c.close();
 assert.equal(calls().filter(x=>x.m.method==='session/resume').at(-1).m.params.sessionId,sid);
 assert.equal(calls().filter(x=>x.m.method==='session/new').length,1,'Restoration replaced the original session');
 assert(!calls().some(x=>x.m.method==='session/prompt'),'Restoration transferred history');
 assert(records().find(x=>x.sessionId===id).segments[0].restoreFingerprint);
 // Earliest virtual chats saved only author.agentId and an opaque fingerprint.
 // A matching saved registered agent restores without an opaque-fingerprint confirmation.
 saved=records().find(x=>x.sessionId===id);
 delete saved.segments[0].restoreFingerprint;delete saved.segments[0].launchFingerprint;delete saved.segments[0].agentIdentity;
 saved.segments[0].fingerprint='earliest-opaque-env';
 fs.writeFileSync(recordFile,JSON.stringify(saved));
 c=client(uid);ok(await c.init());
 ok(await c.request('session/load',{sessionId:id,cwd:root}));await c.close();
 assert.equal(calls().filter(x=>x.m.method==='session/load').at(-1).m.params.sessionId,sid);
 assert.equal(calls().filter(x=>x.m.method==='session/new').length,1);
 assert(records().find(x=>x.sessionId===id).segments[0].restoreFingerprint);
 // Environment and profile-default changes do not identify a different registered agent.
 config.profiles.p.env.PROVIDER_SETTING='changed';write();c=client(uid);
 config.profiles.p.sessionSettings=[{agentId:'registry.one',operation:'model',id:'model',value:'new-profile-default'}];write();
 const restoreStart=calls().length;ok(await c.init());
 const restored=ok(await c.request('session/load',{sessionId:id,cwd:root}));
 assert.equal(restored.models.currentModelId,'retired-model');
 const rejected=bad(await c.request('session/load',{sessionId:id,cwd:root,rejectModel:true}));
 assert.deepEqual(rejected,{code:-32090,message:'Saved model is unavailable',data:{model:'retired-model',providerOwnsResolution:true}});
 assert(!calls().slice(restoreStart).some(x=>['session/new','session/set_model','session/set_config_option'].includes(x.m.method)));
 await c.close();delete config.profiles.p.sessionSettings;
 // Native quota handling can discard its acquired session. Its explicit profile
 // selection stages local recovery, which must obtain consent without old restore.
 const localDir=path.join(root,'local-chats');fs.mkdirSync(localDir,{recursive:true});
 const localHash=crypto.createHash('sha256').update(JSON.stringify(id)).digest('hex');
 saved=records().find(x=>x.sessionId===id);
 const localBinding=Object.fromEntries(['sessionId','cwd','currentSegment','revision'].map(k=>[k,saved[k]]));
 fs.writeFileSync(path.join(localDir,localHash+'.view.json'),JSON.stringify({...localBinding,readOnly:true,reason:'disconnected'}));
 fs.writeFileSync(path.join(localDir,localHash+'.target.json'),JSON.stringify({...localBinding,profile:'other'}));
 const disconnectedStart=calls().length;c=client(uid);let recoveryInit=c.init();
 await decision('profile_switch',{approved:false});bad(await recoveryInit);await c.close();
 assert.equal(records().find(x=>x.sessionId===id).profile,'p','Cancel changed committed binding');
 assert(!calls().slice(disconnectedStart).some(x=>['session/new','session/load','session/resume','session/prompt'].includes(x.m.method)),'Unapproved recovery touched a provider session');
 assert(!fs.existsSync(path.join(localDir,localHash+'.view.json')),'Cancelled switch blocked original retry');
 fs.writeFileSync(path.join(localDir,localHash+'.view.json'),JSON.stringify({...localBinding,readOnly:true,reason:'disconnected'}));
 fs.writeFileSync(path.join(localDir,localHash+'.target.json'),JSON.stringify({...localBinding,profile:'other'}));
 c=client(uid);recoveryInit=c.init();await decision('profile_switch',{approved:true,newChat:false,consent:true,exchanges:0,history:[]});ok(await recoveryInit);
 ok(await c.request('session/load',{sessionId:id,cwd:root}));await c.close();
 assert.equal(records().find(x=>x.sessionId===id).profile,'other');
 assert(!calls().slice(disconnectedStart).some(x=>x.agent==='one'&&['session/load','session/resume','session/prompt'].includes(x.m.method)),'Recovery retried exhausted provider');
 assert(!calls().slice(disconnectedStart).some(x=>x.agent==='other'&&x.m.params?.sessionId===sid),'Replacement received old session ID');
 // Profile deletion is resolved before touching a provider; local viewing never loads a session.
 c=client();const deletedUid=c.uid;ok(await c.init());const deletedId=ok(await c.request('session/new',{cwd:root})).sessionId;
 ok(await c.request('session/prompt',{sessionId:deletedId,prompt:[{type:'text',text:'Saved local exchange'}]}));await c.close();
 const deletedState=records().find(x=>x.sessionId===deletedId),deletedSid=deletedState.segments[0].sessionId;
 const deletedFile=path.join(root,'conversations',crypto.createHash('sha256').update(JSON.stringify(deletedId)).digest('hex')+'.json');
 const original=fs.readFileSync(deletedFile,'utf8');delete config.profiles.p;config.projects[0].profile='other';write();const unchangedRoutes=fs.readFileSync(configFile,'utf8');
 const deletionStart=calls().length;c=client(deletedUid);let pending=c.init();
 const missing=await decision('profile-missing',{approved:false});assert.equal(missing.from.agentId,'registry.one');assert.equal(missing.profiles[0].id,'other');
 assert.equal(bad(await pending).code,-32001);assert.equal(calls().length,deletionStart);assert.equal(fs.readFileSync(deletedFile,'utf8'),original);
 pending=c.init();await decision('profile-missing',{approved:true,readOnly:true});
 assert.equal(bad(await pending).data['ai-multi'].readOnly,true);assert.equal(calls().length,deletionStart);
 assert.equal(bad(await c.init()).data['ai-multi'].readOnly,true);assert.equal(fs.readFileSync(deletedFile,'utf8'),original);await c.close();
 const digest=crypto.createHash('sha256').update(JSON.stringify(deletedId)).digest('hex');
 const target=path.join(root,'local-chats',digest+'.target.json'),view=path.join(root,'local-chats',digest+'.view.json');
 const select=()=>fs.writeFileSync(target,JSON.stringify({sessionId:deletedId,cwd:deletedState.cwd,currentSegment:deletedState.currentSegment,revision:deletedState.revision,profile:'other'}));
 select();c=client(deletedUid);pending=c.init();await decision('profile_deleted',{approved:false});bad(await pending);await c.close();
 assert(fs.existsSync(view));assert.equal(fs.readFileSync(deletedFile,'utf8'),original);
 select();c=client(deletedUid);pending=c.init();await decision('profile_deleted',{approved:true,newChat:false,exchanges:1,consent:true,history:[{id:'native-local-1',user:[{type:'text',text:'Saved local exchange'}],updates:[]}]});ok(await pending);
 const load=ok(await c.request('session/load',{sessionId:deletedId,cwd:root}));assert.equal(load.sessionId,deletedId);
 const recovered=records().find(x=>x.sessionId===deletedId);assert.equal(recovered.profile,'other');assert.equal(recovered.segments[0].sessionId,deletedSid);
 assert.equal(recovered.switches.at(-1).reason,'profile_deleted');assert.equal(recovered.exchanges.length,1);
 assert(!fs.existsSync(view));assert(!fs.existsSync(target));
 const targetCalls=calls().slice(deletionStart);assert(targetCalls.every(x=>x.agent==='other'));
 assert(!targetCalls.some(x=>['session/load','session/resume','session/prompt'].includes(x.m.method)),'Replacement received an old ID or unapproved prompt');
 ok(await c.request('session/prompt',{sessionId:deletedId,prompt:[{type:'text',text:recovered.pendingHandoff.token}]}));
 assert(calls().at(-1).m.params.prompt[0].text.includes('Saved local exchange'));await c.close();
 assert.equal(fs.readFileSync(configFile,'utf8'),unchangedRoutes);
 const settings=require('./profile-settings');
 const scoped={agentId:'registry.one',sessionSettings:[{agentId:'registry.one',host:'air',operation:'config',id:'reasoning',value:'low'},
  {agentId:'registry.one',host:'assistant',operation:'config',id:'reasoning',value:'high'}],agentDefaults:[]};
 for(const [name,expected] of [['air-plugin:idea','low'],['JetBrains IntelliJ IDEA','high']]) {
  const spec=settings.forClient(scoped,{clientInfo:{name}});assert.equal(settings.preferences(spec).saved.length,1);
  let applied;
  await settings.apply(spec,{sessionId:'fixture',configOptions:[{id:'reasoning',name:'Reasoning',type:'select',currentValue:'medium',options:['low','medium','high'].map(value=>({value,name:value}))}]},async(method,p)=>{
   applied=p.value;return {result:{configOptions:[{id:'reasoning',name:'Reasoning',type:'select',currentValue:p.value,options:['low','medium','high'].map(value=>({value,name:value}))}]}};
  });assert.equal(applied,expected);
 }
 assert.equal(settings.preferences(settings.forClient(scoped,{clientInfo:{name:'unknown'}})).saved.length,0);
 console.log('PASS: original profile/agent and load/resume; no restore defaults or obsolete-model substitution; exact provider errors; deleted-profile selection/cancel; local read-only without backend/load; later consented profile transfer without old ID; unchanged Default Profile');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{for(const child of children)if(child.exitCode===null)child.stdin.end();await pause(300);for(const child of children)if(child.exitCode===null)child.kill();await pause(200);fs.rmSync(root,{recursive:true,force:true});});
