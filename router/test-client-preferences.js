'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),rl=require('readline');
const settings=require('./profile-settings');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-preferences-'));
const backend=path.join(root,'backend.js'),configFile=path.join(root,'routes.json');
fs.writeFileSync(backend,`
const rl=require('readline').createInterface({input:process.stdin});let serial=0;const states=new Map();let held;
const opts=s=>[
 {id:'model',name:'Model',category:'model',type:'select',currentValue:s.model,options:['a','b'].map(value=>({value,name:value}))},
 {id:'effort',name:'Reasoning',category:'thought_level',type:'select',currentValue:s.effort,options:['low','high'].map(value=>({value,name:value}))},
 {id:'flag',name:'Flag',type:'boolean',currentValue:s.flag}];
const send=(m,result)=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));
rl.on('line',line=>{const m=JSON.parse(line),p=m.params||{};
 if(m.method==='initialize')return send(m,{protocolVersion:1,agentCapabilities:{loadSession:true},_meta:{steering:{supported:true}}});
 if(m.method==='session/new'){const id=require('crypto').randomUUID(),s={model:'a',effort:'low',flag:false};states.set(id,s);return send(m,{sessionId:id,configOptions:opts(s)});}
 const s=states.get(p.sessionId)||{model:'b',effort:'high',flag:true};
 if(m.method==='session/load')return send(m,{configOptions:opts(s)});
 if(m.method==='session/set_config_option'){
  if(p.configId==='flag' && p.value===false)return console.log(JSON.stringify({id:m.id,error:{code:-32010,message:'rejected',data:{original:true}}}));
  s[p.configId]=p.value;return send(m,{configOptions:opts(s)});
 }
 if(m.method==='session/prompt'){if(p.prompt?.[0]?.text==='hold'){held=m;return;}return send(m,{stopReason:'end_turn'});}
 if(m.method==='session/cancel'){if(held){send(held,{stopReason:'cancelled'});held=null;}return;}
 if(m.method==='_session/steering')return send(m,{outcome:'failed'});
 send(m,{});
});`);
const config={virtualSessions:true,nativePreferences:true,projects:[{root,profile:'personal'}],profiles:{}};
for(const id of ['personal','work'])config.profiles[id]={agentId:'fixture',command:process.execPath,args:[backend],inheritEnvironment:false,clientDefaults:[]};
const save=()=>fs.writeFileSync(configFile,JSON.stringify(config));
const preference=(id,value)=>({operation:'config',id,value});
const stores=new Map(),reads=[],writes=[],seen=new Set();let watcher;const children=[];
function start(host){
 const child=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),configFile],{cwd:root,windowsHide:true});children.push(child);child.stderr.resume();
 let serial=0;const pending=new Map();rl.createInterface({input:child.stdout}).on('line',line=>{const m=JSON.parse(line);pending.get(m.id)?.(m);});
 return {send(method,params){child.stdin.write(JSON.stringify({jsonrpc:'2.0',method,params})+'\n');},call(method,params={}){return new Promise((resolve,reject)=>{const id=++serial,t=setTimeout(()=>reject(Error('Timeout '+method)),5000);pending.set(id,m=>{clearTimeout(t);pending.delete(id);resolve(m);});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});},host,child};
}
const ok=m=>{assert(!m.error,JSON.stringify(m));return m.result;},values=r=>Object.fromEntries(r.configOptions.map(o=>[o.id,o.currentValue]));
(async()=>{
 save();watcher=setInterval(()=>{const dir=path.join(root,'client-preferences');if(!fs.existsSync(dir))return;
 for(const file of fs.readdirSync(dir).filter(x=>x.endsWith('.request.json'))){
  let req;try{req=JSON.parse(fs.readFileSync(path.join(dir,file)));}catch(error){if(['ENOENT','EPERM','EBUSY'].includes(error.code) || error instanceof SyntaxError)continue;throw error;}
  const response=path.join(dir,req.nonce+'.response.json');if(seen.has(req.nonce))continue;seen.add(req.nonce);
  const key=JSON.stringify([req.host,req.profile,req.agentId]);let saved=stores.get(key)||[];
  if(req.operation==='read')reads.push(req);else {writes.push(req);saved=saved.filter(x=>x.id!==req.setting.id).concat(req.setting);stores.set(key,saved);}
  fs.writeFileSync(response,JSON.stringify({nonce:req.nonce,settings:saved}));
 }},5);
 for(const host of ['assistant','air']){
  const name=host==='air'?'air-plugin:fixture':'JetBrains fixture',client=start(host);
  ok(await client.call('initialize',{protocolVersion:1,clientInfo:{name}}));
  const key=JSON.stringify([host,'personal','fixture']);stores.set(key,[preference('model','b'),preference('effort','high'),preference('flag','true')]);
  let chat=ok(await client.call('session/new',{cwd:root}));assert.deepEqual(values(chat),{model:'b',effort:'high',flag:true});assert.equal(writes.length,host==='assistant'?0:2);
  const sid=chat.sessionId;
  const initialWrites=writes.length;
  ok(await client.call('session/set_config_option',{sessionId:sid,configId:'model',value:'b'}));
  assert.equal(writes.length,initialWrites); // Native before-prompt setup repeats accepted values.
  ok(await client.call('session/set_config_option',{sessionId:sid,configId:'model',value:'a'}));
  ok(await client.call('session/set_config_option',{sessionId:sid,configId:'effort',value:'low'}));
  ok(await client.call('session/set_config_option',{sessionId:sid,configId:'flag',value:true,type:'boolean'}));
  const rejected=await client.call('session/set_config_option',{sessionId:sid,configId:'flag',value:false,type:'boolean'});assert.deepEqual(rejected.error,{code:-32010,message:'rejected',data:{original:true}});
  const count=writes.length,readCount=reads.length;
  ok(await client.call('session/load',{sessionId:sid,cwd:root}));assert.equal(reads.length,readCount);assert.equal(writes.length,count);
  const held=client.call('session/prompt',{sessionId:sid,prompt:[{type:'text',text:'hold'}]});
  await new Promise(resolve=>setTimeout(resolve,80));client.send('session/cancel',{sessionId:sid});assert.equal(ok(await held).stopReason,'cancelled');
  assert.equal(ok(await client.call('session/prompt',{sessionId:sid,prompt:[{type:'text',text:'after cancellation'}]})).stopReason,'end_turn');
  config.profiles.personal.clientDefaults=[{host,agentId:'fixture',modelOptionId:'model',modelOperation:'config',defaultModelId:'b',models:[{modelId:'b',enabled:true,settings:[preference('effort','high')]}]}];save();
  chat=ok(await client.call('session/new',{cwd:root}));assert.deepEqual(values(chat),{model:'b',effort:'high',flag:true});assert.equal(writes.length,count);
  config.profiles.personal.clientDefaults=[];save();chat=ok(await client.call('session/new',{cwd:root}));assert.deepEqual(values(chat),{model:'a',effort:'low',flag:true});
  config.projects[0].profile='work';save();chat=ok(await client.call('session/new',{cwd:root}));assert.deepEqual(values(chat),{model:'a',effort:'low',flag:false});config.projects[0].profile='personal';save();
  config.profiles.personal.agentId='other';save();chat=ok(await client.call('session/new',{cwd:root}));assert.deepEqual(values(chat),{model:'a',effort:'low',flag:false});config.profiles.personal.agentId='fixture';save();
 }
 // A stale dependent preference is checked against the selected model's fresh catalog.
 const spec={agentId:'fixture',clientDefaults:[]},option={id:'effort',name:'Effort',category:'thought_level',type:'select',currentValue:'low',options:[{value:'low',name:'Low'}]};
 const r=await settings.applyRemembered(spec,{sessionId:'s',configOptions:[option]},[preference('effort','obsolete')],()=>assert.fail('Obsolete preference sent'));
 assert.equal(r.configOptions[0].currentValue,'low');
 const dynamic={sessionId:'partial',configOptions:[{id:'model',name:'Model',category:'model',type:'select',currentValue:'a',options:['a','b'].map(value=>({value,name:value}))},{...option,currentValue:'high',options:['low','high'].map(value=>({value,name:value}))}]};
 const partial={agentId:'fixture',sessionSettings:[{agentId:'fixture',...preference('effort','high')}]};
 const applied=await settings.applyRemembered(partial,dynamic,[preference('model','b'),preference('effort','low')],async(method,p)=>{
  if(p.configId==='model'){dynamic.configOptions[0].currentValue=p.value;dynamic.configOptions[1].currentValue='low';}
  else dynamic.configOptions[1].currentValue=p.value;
  return {result:structuredClone(dynamic)};
 });
 assert.deepEqual(applied.configOptions.map(x=>x.currentValue),['b','high']);
 console.log('PASS: shared Assistant/Air precedence, partial presets, native read/write boundary, no acquisition writes, exact rejection, restore without preferences, profile/agent isolation and cancellation followed by a new prompt');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{clearInterval(watcher);await Promise.all(children.map(child=>new Promise(resolve=>{child.once('exit',resolve);child.stdin.end();})));fs.rmSync(root,{recursive:true,force:true,maxRetries:5,retryDelay:100});});
