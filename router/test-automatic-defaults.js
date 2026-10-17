'use strict';
const assert=require('assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),rl=require('readline');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-auto-defaults-'));
const configFile=path.join(root,'routes.json'),trace=path.join(root,'trace.jsonl'),backend=path.join(root,'backend.js');
fs.writeFileSync(backend,String.raw`
 const fs=require('fs'),rl=require('readline').createInterface({input:process.stdin});let serial=0;
 const option=value=>({id:'effort',name:'Effort',type:'select',currentValue:value,options:[{value:'low',name:'Low'}]});
 const send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
 rl.on('line',line=>{const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,JSON.stringify({method:m.method,params:m.params})+'\n');
 if(m.method==='initialize')send({id:m.id,result:{protocolVersion:1,agentCapabilities:{loadSession:true}}});
 else if(m.method==='session/new')send({id:m.id,result:{sessionId:process.pid+'-'+ ++serial,configOptions:[option('low')]}});
 else if(m.method==='session/load')send({id:m.id,result:{}});
 else if(m.method==='session/set_config_option')send(m.params.value==='rejected'?{id:m.id,error:{code:-32070,message:'Agent rejected saved effort',data:{option:'effort',native:true}}}:{id:m.id,result:{configOptions:[option(m.params.value)]}});
 else send({id:m.id,result:{}});});
`);
const agent='fixture.agent',setting=value=>({agentId:agent,operation:'config',id:'effort',value});
const config={virtualSessions:true,projects:[{root,profile:'target'}],profiles:{target:{agentId:agent,command:process.execPath,args:[backend],inheritEnvironment:false,env:{TRACE:trace},automaticDefaults:true,frozenDefaultsRequired:false,defaultsAgentId:'',sessionSettings:[]}}};
const save=()=>fs.writeFileSync(configFile,JSON.stringify(config)),events=()=>fs.existsSync(trace)?fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse):[];
save();let child,watcher;const pending=new Map();let serial=0;const requests=[];
async function run(){
 child=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),configFile],{cwd:root,stdio:['pipe','pipe','pipe'],windowsHide:true});child.stderr.resume();
 rl.createInterface({input:child.stdout}).on('line',line=>{const m=JSON.parse(line);pending.get(m.id)?.(m);});
 const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++serial,timer=setTimeout(()=>reject(Error('Timeout '+method)),15000);pending.set(id,m=>{clearTimeout(timer);pending.delete(id);resolve(m);});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});
 const seen=new Set();watcher=setInterval(()=>{const dir=path.join(root,'agent-recovery');if(!fs.existsSync(dir))return;
   for(const name of fs.readdirSync(dir).filter(x=>x.endsWith('.request.json'))){if(seen.has(name))continue;seen.add(name);const req=JSON.parse(fs.readFileSync(path.join(dir,name)));requests.push(req);
     assert.equal(req.profile,'target');assert.equal(req.agentId,agent);
     assert.equal(req.kind,'profile-settings-error');assert.equal(req.reason,'Agent rejected saved effort');
     fs.writeFileSync(path.join(dir,req.nonce+'.response.json'),JSON.stringify({nonce:req.nonce,approved:true}));
   }
 },10);
 assert((await call('initialize',{protocolVersion:1,clientInfo:{name:'JetBrains fixture'}})).result);
 const first=await Promise.all([call('session/new',{cwd:root}),call('session/new',{cwd:root})]);
 for(const reply of first){assert(reply.result,JSON.stringify(reply));assert.equal(reply.result.configOptions[0].currentValue,'low');}
 assert.equal(requests.filter(x=>x.kind==='defaults-bootstrap').length,0);
 assert.deepEqual(config.profiles.target.sessionSettings,[]);assert.equal(events().filter(x=>x.method==='session/set_config_option').length,0);assert.equal(config.projects[0].profile,'target');
 // Saved defaults are applied, with explicit selections taking precedence.
 config.profiles.target.defaultsAgentId=agent;config.profiles.target.frozenDefaultsRequired=true;config.profiles.target.agentDefaults=[setting('low')];
 // No age-based refresh; old values are sent to the agent even outside its advertised catalog.
 config.profiles.target.defaultsObservedAt=1;config.profiles.target.sessionSettings=[setting('old-but-accepted')];save();
 const third=await call('session/new',{cwd:root});assert.equal(third.result.configOptions[0].currentValue,'old-but-accepted');
 assert.equal(requests.length,0);
 const recordFor=id=>fs.readdirSync(path.join(root,'conversations')).filter(x=>x.endsWith('.json'))
   .map(x=>JSON.parse(fs.readFileSync(path.join(root,'conversations',x),'utf8'))).find(x=>x.sessionId===id);
 assert.deepEqual(recordFor(first[0].result.sessionId).segments[0].nativePreferenceIds,[]);
 assert.deepEqual(recordFor(third.result.sessionId).segments[0].nativePreferenceIds,['effort']);
 const before=events().filter(x=>x.method==='session/set_config_option').length;
 config.profiles.target.defaultsAgentId='';save();
 assert((await call('session/load',{cwd:root,sessionId:first[0].result.sessionId})).result);
 assert.equal(recordFor(first[0].result.sessionId).segments[0].nativePreferencesRestored,true);
 assert.equal(requests.length,0);assert.equal(events().filter(x=>x.method==='session/set_config_option').length,before);
 config.profiles.target.defaultsAgentId=agent;config.profiles.target.sessionSettings=[setting('rejected')];save();
 const rejected=await call('session/new',{cwd:root});assert.deepEqual(rejected.error,{code:-32070,message:'Agent rejected saved effort',data:{option:'effort',native:true}});
 assert.equal(requests.filter(x=>x.kind==='profile-settings-error').length,1);
 assert.equal(requests.filter(x=>x.kind==='defaults-bootstrap').length,0);
 console.log('PASS: unconfigured profiles preserve native defaults without capture or setters, explicit values/project default retained, agent accepts unlisted values, raw rejection opens only target Settings, restore bypasses capture/setters, no age expiry');
}
run().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{clearInterval(watcher);if(child){child.stdin.end();await new Promise(r=>child.exitCode!==null?r():child.once('exit',r));}fs.rmSync(root,{recursive:true,force:true});});
