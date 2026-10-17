"use strict";
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),rl=require('readline');
const settings=require('./profile-settings');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-concrete-')),configFile=path.join(root,'routes.json'),trace=path.join(root,'trace.jsonl'),backend=path.join(root,'backend.js');
const agent='fixture';const setting=(id,value)=>({agentId:agent,operation:'config',id,value});
const preset=host=>({agentId:agent,host,defaultModelId:'b',modelOptionId:'model',modelOperation:'config',models:[
 {modelId:'b',enabled:true,settings:[setting('effort','high'),setting('fast-mode','off')]},
 {modelId:'a',enabled:false,settings:[setting('effort','low'),setting('fast-mode','off')]},
 {modelId:'c',enabled:true,settings:[setting('effort','low'),setting('fast-mode','off')]}]});
fs.writeFileSync(backend,String.raw`
const fs=require('fs'),rl=require('readline').createInterface({input:process.stdin});let count=0;const sessions=new Map();let rejectedOnce=false;
const option=(id,values,current,category)=>({id,name:id,type:'select',category,currentValue:current,options:values.map(value=>({value,name:value,extension:true})),_meta:{native:true}});
const options=s=>[option('model',['a','b','c'],s.model,'model'),option('effort',['low','high'],s.effort,'thought_level'),option('fast-mode',['off','on'],s.fast,'model_config')];
rl.on('line',line=>{const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,JSON.stringify(m)+'\n');const send=result=>console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));
 if(m.method==='initialize')return send({protocolVersion:1,agentInfo:{name:'fixture'},agentCapabilities:{loadSession:true,sessionCapabilities:{resume:{}}}});
 if(m.method==='session/new'){const id='s'+ ++count,s={model:'a',effort:'low',fast:'off'};sessions.set(id,s);return send({sessionId:id,configOptions:options(s),_meta:{original:true}});}
 const s=sessions.get(m.params.sessionId)||{model:'b',effort:'high',fast:'off'};
 if(['session/load','session/resume'].includes(m.method))return send({configOptions:options(s),restoreExtension:m.params.extension});
 if(m.method==='session/set_config_option'){const p=m.params;if(p.configId==='fast-mode' && p.value==='on' && !rejectedOnce){rejectedOnce=true;return console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,error:{code:-32602,message:'Temporarily rejected saved fast mode'}}));}if(p.configId==='model')s.model=p.value;else if(p.configId==='effort')s.effort=p.value;else s.fast=p.value;return send({configOptions:options(s),extension:'accepted',_meta:{setter:p.configId}});}
 send({});
});
`);
const config={virtualSessions:true,projects:[{root,profile:'p'}],profiles:{p:{agentId:agent,command:process.execPath,args:[backend],inheritEnvironment:false,env:{TRACE:trace},clientDefaults:[preset('assistant'),{...preset('air'),defaultModelId:'c'}]}}};
const save=()=>fs.writeFileSync(configFile,JSON.stringify(config)),events=()=>fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
let child,watcher;const pending=new Map();let serial=0;const requests=[];
async function run(){
 // Real SDK boolean discrimination: no string/boolean guessing.
 const boolean={id:'flag',name:'Flag',operation:'config',type:'boolean',currentValue:false};
 await settings.applyConcrete({agentId:agent,clientDefaults:[{...preset('assistant'),defaultModelId:'',modelOptionId:'',models:[{modelId:'',enabled:true,settings:[setting('flag','true')]}]}]},
   {sessionId:'bool',configOptions:[boolean]},async(method,p)=>{assert.equal(p.type,'boolean');assert.equal(p.value,true);return {result:{configOptions:[{...boolean,currentValue:true}]}}});
 const nativeSpec=settings.forClient(config.profiles.p,{clientInfo:{name:'JetBrains.IntelliJ IDEA',_meta:{platform:'intellij',jbai:true}}});
 assert.equal(settings.concrete(nativeSpec).host,'assistant');
 assert.equal(settings.concrete(settings.forClient(config.profiles.p,{clientInfo:{name:'air-plugin:fixture'}})).host,'air');
 save();child=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),configFile],{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});child.stderr.resume();
 rl.createInterface({input:child.stdout}).on('line',line=>{const m=JSON.parse(line);pending.get(m.id)?.(m);});
 const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++serial,timer=setTimeout(()=>reject(Error('Timeout '+method)),12000);pending.set(id,m=>{clearTimeout(timer);pending.delete(id);resolve(m)});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});
 watcher=setInterval(()=>{const dir=path.join(root,'agent-recovery');if(!fs.existsSync(dir))return;for(const name of fs.readdirSync(dir).filter(x=>x.endsWith('.request.json'))){if(requests.some(x=>name===x.nonce+'.request.json'))continue;let req;try{req=JSON.parse(fs.readFileSync(path.join(dir,name)));}catch(e){if(e.code==='ENOENT')continue;throw e;}requests.push(req);fs.writeFileSync(path.join(dir,req.nonce+'.response.json'),JSON.stringify({nonce:req.nonce,approved:true}));}},10);
 assert((await call('initialize',{protocolVersion:1,clientInfo:{name:'JetBrains fixture'}})).result);
 const first=await call('session/new',{cwd:root});assert(first.result,JSON.stringify(first));const sid=first.result.sessionId;
 const model=o=>o.configOptions.find(x=>x.id==='model');
 assert.equal(model(first.result).currentValue,'b');assert.deepEqual(model(first.result).options.map(x=>x.value),['b','c']);assert(model(first.result).options[0].extension);assert(first.result._meta.original);
 const before=events().filter(x=>x.method==='session/set_config_option').length;
 config.profiles.p.clientDefaults[0].defaultModelId='c';config.profiles.p.clientDefaults[0].models[0].enabled=false;save();
 for(const method of ['session/load','session/resume']){
  const reply=await call(method,{sessionId:sid,cwd:root,extension:{method}});assert(reply.result,JSON.stringify(reply));assert.equal(model(reply.result).currentValue,'b');assert(model(reply.result).options.some(x=>x.value==='b'));assert.deepEqual(reply.result.restoreExtension,{method});
 }
 assert.equal(events().filter(x=>x.method==='session/set_config_option').length,before,'Restore applied new profile defaults');
 const changed=await call('session/set_config_option',{sessionId:sid,configId:'model',value:'c',_meta:{client:true}});assert(changed.result,JSON.stringify(changed));assert.deepEqual(model(changed.result).options.map(x=>x.value),['c']);assert.equal(changed.result.configOptions.find(x=>x.id==='effort').currentValue,'low');assert.equal(changed.result.extension,'accepted');assert.equal(changed.result._meta.setter,'model','Internal preset setter overwrote client-selected method metadata');
 // A legacy boolean snapshot is invalid against the real select catalog. Keep creation pending;
 // opening/closing Settings is not an application and cannot release the request.
 config.profiles.p.clientDefaults[0].models[2].settings.find(x=>x.id==='fast-mode').value='false';save();
 const starts=events().filter(x=>x.method==='session/new').length;let completed=false;
 const waiting=call('session/new',{cwd:root}).then(r=>{completed=true;return r});
 for(let i=0;i<60 && !requests.length;i++)await new Promise(r=>setTimeout(r,20));
 assert.equal(requests.length,1);assert.equal(requests[0].host,'assistant');assert.equal(requests[0].modelId,'c');assert.equal(requests[0].issues[0].configId,'fast-mode');assert.match(requests[0].reason,/false.*Deprecated/);
 await new Promise(r=>setTimeout(r,180));assert.equal(completed,false,'Settings acknowledgement completed a failed new chat');
 assert(!events().some(x=>x.method==='session/set_config_option' && x.params.value==='false'),'Sent a deprecated value to the agent');
 config.profiles.p.clientDefaults[0].models[2].settings.find(x=>x.id==='fast-mode').value='off';save();
 const corrected=await waiting;assert(corrected.result,JSON.stringify(corrected));assert.equal(events().filter(x=>x.method==='session/new').length,starts+1,'Created a replacement session after Apply');
 assert.equal(model(corrected.result).currentValue,'c');
 // An explicit Refresh/Apply can retry the same values after a provider rejection.
 // The committed revision matters even when the selected values did not change.
 config.profiles.p.clientDefaults[0].models[2].settings.find(x=>x.id==='fast-mode').value='on';config.profiles.p.clientDefaults[0].revision=1;save();
 const retryStart=events().filter(x=>x.method==='session/new').length;const retrying=call('session/new',{cwd:root});
 for(let i=0;i<60 && requests.length<2;i++)await new Promise(r=>setTimeout(r,20));assert.equal(requests.length,2);assert.match(requests[1].reason,/Temporarily rejected/);
 config.profiles.p.clientDefaults[0].revision=2;save();const retried=await retrying;assert(retried.result,JSON.stringify(retried));
 assert.equal(retried.result.configOptions.find(x=>x.id==='fast-mode').currentValue,'on');assert.equal(events().filter(x=>x.method==='session/new').length,retryStart+1);
 // Remove defaults during correction: roll partial setters back to this session's
 // original agent values, without a replacement session or touching Air.
 config.profiles.p.clientDefaults[0].models[2].settings.find(x=>x.id==='fast-mode').value='false';save();
 const rollbackStart=events().filter(x=>x.method==='session/new').length;
 const removing=call('session/new',{cwd:root});
 for(let i=0;i<60 && requests.length<3;i++)await new Promise(r=>setTimeout(r,20));assert.equal(requests.length,3);
 config.profiles.p.clientDefaults=config.profiles.p.clientDefaults.filter(x=>x.host!=='assistant');save();
 const rolledBack=await removing;assert(rolledBack.result,JSON.stringify(rolledBack));assert.equal(model(rolledBack.result).currentValue,'a');
 assert.equal(events().filter(x=>x.method==='session/new').length,rollbackStart+1);
 // Subsequent native creation sends no setters.

 const count=events().filter(x=>x.method==='session/set_config_option').length;const native=await call('session/new',{cwd:root});assert.equal(model(native.result).currentValue,'a');assert.equal(events().filter(x=>x.method==='session/set_config_option').length,count);assert.equal(config.profiles.p.clientDefaults[0].host,'air');
 console.log('PASS: concrete per-model defaults, boolean ACP types, model order/filter/extensions, restore ignores changed defaults, disabled current retained, explicit model defaults, deprecated startup waits for Apply on same session, independent remove');
}
run().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{clearInterval(watcher);if(child){child.stdin.end();await new Promise(r=>child.exitCode!==null?r():child.once('exit',r));}fs.rmSync(root,{recursive:true,force:true});});
