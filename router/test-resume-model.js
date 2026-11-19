'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),rl=require('readline'),crypto=require('crypto');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-resume-model-'));
const trace=path.join(root,'trace.jsonl'),configFile=path.join(root,'routes.json'),backend=path.join(root,'backend.js'),honor=path.join(root,'honor');
const home=path.join(root,'codex-home');
fs.writeFileSync(backend,String.raw`
const fs=require('fs'),rl=require('readline').createInterface({input:process.stdin});
const sessions=new Map();
const option=(id,values,current,category)=>({id,name:id,type:'select',category,currentValue:current,options:values.map(value=>({value,name:value}))});
const models=s=>({currentModelId:s.model+'['+s.effort+']',availableModels:['gpt-6.1-sol','gpt-6.1-terra'].flatMap(base=>['low','medium'].map(effort=>({modelId:base+'['+effort+']',name:base})))});
const options=s=>[option('model',['gpt-6.1-sol','gpt-6.1-terra'],s.model,'model'),option('reasoning_effort',['low','medium'],s.effort,'thought_level'),option('collaboration_mode',['default','plan'],s.plan,'collaboration_mode')];
const state=id=>{if(!sessions.has(id))sessions.set(id,{model:'gpt-6.1-sol',effort:'low',plan:'default'});return sessions.get(id);};
rl.on('line',line=>{
 const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,JSON.stringify({method:m.method,params:m.params||{},codexConfig:process.env.CODEX_CONFIG||null})+'\n');
 const send=result=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));
 if(m.method==='initialize')return send({protocolVersion:1,agentInfo:{name:'codex'},agentCapabilities:{loadSession:true,sessionCapabilities:{resume:{}}}});
 if(m.method==='session/new'){const id='provider-'+Date.now(),s=state(id);return send({sessionId:id,configOptions:options(s),models:models(s)});}
 const s=state(m.params.sessionId);
 if(m.method==='session/set_model'){
  const match=/^([^[]+)\[([^\]]+)\]$/.exec(m.params.modelId||'');
  if(!match)return console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,error:{code:-32602,message:'Unsupported format of modelId: '+(m.params.modelId||'')}}));
  s.model=match[1];s.effort=match[2];return send({configOptions:options(s),models:models(s)});
 }
 if(m.method==='session/set_config_option'){
  if(m.params.configId==='model')s.model=m.params.value;else if(m.params.configId==='reasoning_effort')s.effort=m.params.value;else if(m.params.configId==='collaboration_mode')s.plan=m.params.value;
  return send({configOptions:options(s)});
 }
 if(m.method==='session/load'||m.method==='session/resume'){
  let config={};try{config=JSON.parse(process.env.CODEX_CONFIG||'{}');}catch{config={};}
  if(fs.existsSync(process.env.HONOR_FILE) && config.model){s.model=config.model;if(config.model_reasoning_effort)s.effort=config.model_reasoning_effort;}
  else {s.model='gpt-6.1-sol';s.effort='low';}
  return send({configOptions:options(s),models:models(s)});
 }
 send({});
});
`);
const config={virtualSessions:true,projects:[{root,profile:'p'}],profiles:{p:{
  agentId:'acp.registry.codex-acp',command:process.execPath,args:[backend],inheritEnvironment:false,
  env:{TRACE:trace,CODEX_HOME:home,HONOR_FILE:honor}}}};
fs.writeFileSync(configFile,JSON.stringify(config));
const children=[];
const events=()=>fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const records=()=>fs.readdirSync(path.join(root,'conversations')).filter(f=>f.endsWith('.json')).map(f=>JSON.parse(fs.readFileSync(path.join(root,'conversations',f))));
const ok=m=>{assert(m.result,JSON.stringify(m));return m.result;};
function client(options={}) {
 const args=[path.join(__dirname,'cps-router.js'),configFile];
 if(options.restoreSession)args.push('--restore-session',options.restoreSession);
 const proc=cp.spawn(process.execPath,args,{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});
 children.push(proc);proc.stderr.resume();
 const pending=new Map();let serial=0;
 rl.createInterface({input:proc.stdout}).on('line',line=>{const m=JSON.parse(line);if(!m.method)pending.get(m.id)?.(m);});
 return {async init(){return this.request('initialize',{protocolVersion:1,clientInfo:{name:'air-plugin:fixture'}});},
  request(method,params={}){const id=++serial;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Timeout '+method)),15000);pending.set(id,m=>{clearTimeout(timer);pending.delete(id);resolve(m);});proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});},
  async close(){proc.stdin.end();if(proc.exitCode===null)await new Promise(r=>proc.once('exit',r));}};
}
(async()=>{
 const created=client();assert(ok(await created.init()));
 const first=ok(await created.request('session/new',{cwd:root}));
 const sid=first.sessionId;
 assert.equal(first.models.currentModelId,'gpt-6.1-sol[low]');
 const plan=ok(await created.request('session/set_config_option',{sessionId:sid,configId:'collaboration_mode',value:'plan'}));
 assert.equal(plan.configOptions.find(x=>x.id==='collaboration_mode').currentValue,'plan');
 const selected=ok(await created.request('session/set_config_option',{sessionId:sid,configId:'model',value:'gpt-6.1-terra'}));
 assert.equal(selected.configOptions.find(x=>x.id==='model').currentValue,'gpt-6.1-terra');
 const effort=ok(await created.request('session/set_config_option',{sessionId:sid,configId:'reasoning_effort',value:'medium'}));
 assert.equal(effort.models.currentModelId,'gpt-6.1-terra[medium]','Config model and models surface diverged after the committed selection');
 assert.equal(effort.configOptions.find(x=>x.id==='collaboration_mode').currentValue,'plan');
 await created.close();
 const saved=records().find(x=>x.sessionId===sid);
 assert.equal(saved.sessionOptions.models.currentModelId,'gpt-6.1-terra[medium]');
 const before=events().length;
 const resumed=client({restoreSession:sid});assert(ok(await resumed.init()));
 const loaded=ok(await resumed.request('session/load',{sessionId:sid,cwd:root}));
 assert.equal(loaded.models.currentModelId,'gpt-6.1-terra[medium]');
 assert.equal(loaded.configOptions.find(x=>x.id==='model').currentValue,'gpt-6.1-terra');
 assert.equal(loaded.configOptions.find(x=>x.id==='reasoning_effort').currentValue,'medium');
 assert.equal(loaded.configOptions.find(x=>x.id==='collaboration_mode').currentValue,'default');
 const restoreEvents=events().slice(before);
 const pinned=restoreEvents.find(x=>x.method==='initialize');
 assert.deepEqual(JSON.parse(pinned.codexConfig),{model:'gpt-6.1-terra',model_reasoning_effort:'medium'});
 const setters=restoreEvents.filter(x=>x.method==='session/set_config_option').map(x=>[x.params.configId,x.params.value]);
 assert.deepEqual(setters,[['model','gpt-6.1-terra'],['reasoning_effort','medium']]);
 assert(!restoreEvents.some(x=>x.method==='session/set_model'||x.method==='session/new'),'Restore reapplied a profile default or used the rejected bare model setter');
 const repeated=events().length;
 assert(ok(await resumed.request('session/set_model',{sessionId:sid,modelId:'gpt-6.1-terra'})));
 assert.equal(events().slice(repeated).filter(x=>x.method==='session/set_model').map(x=>x.params.modelId).join(','),'gpt-6.1-terra[medium]');
 assert(!events().slice(repeated).some(x=>x.method==='session/set_config_option'),'Repeating the committed model reset its defaults');
 const changed=events().length;
 assert(ok(await resumed.request('session/set_model',{sessionId:sid,modelId:'gpt-6.1-terra[low]'})));
 assert.deepEqual(events().slice(changed).map(x=>x.method),['session/set_model']);
 assert.equal(events().at(-1).params.modelId,'gpt-6.1-terra[low]');
 await resumed.close();
 fs.writeFileSync(honor,'1');
 const honoredStart=events().length;
 const honored=client({restoreSession:sid});assert(ok(await honored.init()));
 const matched=ok(await honored.request('session/resume',{sessionId:sid,cwd:root}));
 assert.equal(matched.models.currentModelId,'gpt-6.1-terra[low]');
 assert(!events().slice(honoredStart).some(x=>['session/set_config_option','session/set_model','session/new'].includes(x.method)),'A resume that already has the committed model sent another setter');
 await honored.close();
 assert.equal(records().find(x=>x.sessionId===sid).sessionOptions.models.currentModelId,'gpt-6.1-terra[low]');
 console.log('PASS: restore pins the committed Codex model, repairs a default resume, and accepts a bare model id');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{
 for(const child of children)if(child.exitCode===null)child.stdin.end();
 await new Promise(r=>setTimeout(r,300));
 for(const child of children)if(child.exitCode===null)child.kill();
 await new Promise(r=>setTimeout(r,200));
 fs.rmSync(root,{recursive:true,force:true});
});
