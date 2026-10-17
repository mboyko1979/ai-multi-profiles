'use strict';
const assert=require('assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),rl=require('readline');
(async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-hosts-')),children=[];
 try{
  const backend=path.join(dir,'backend.js'),config=path.join(dir,'routes.json');
  fs.writeFileSync(backend,`const rl=require('readline').createInterface({input:process.stdin}),fs=require('fs'),path=require('path');const p=process.env.PROFILE,file=path.join(process.env.STORE,p+'.sessions');rl.on('line',line=>{const m=JSON.parse(line);let result;
  if(m.method==='initialize')result={protocolVersion:1,authMethods:[],agentCapabilities:{loadSession:true,sessionCapabilities:{list:{}}}};
  else if(m.method==='session/new'){result={sessionId:p+'-'+Math.random(),configOptions:[{id:'model',name:'Model',type:'select',category:'model',currentValue:p,options:[{value:p,name:p+' model'}]}]};fs.appendFileSync(file,JSON.stringify({sessionId:result.sessionId,cwd:m.params.cwd})+String.fromCharCode(10));}
  else if(m.method==='session/list')result={sessions:fs.existsSync(file)?fs.readFileSync(file,'utf8').trim().split(String.fromCharCode(10)).map(JSON.parse):[]};
  else if(m.method==='session/set_config_option')result={configOptions:[{id:'model',name:'Model',type:'select',category:'model',currentValue:m.params.value,options:[{value:p,name:p+' model'}]}]};
  else result={stopReason:'end_turn'};console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result}));});`);
  const spec=p=>({name:p,command:process.execPath,args:[backend],env:{PROFILE:p,STORE:dir}});
  fs.writeFileSync(config,JSON.stringify({virtualSessions:true,projects:[{root:dir,profile:'a'}],profiles:{a:spec('a'),b:spec('b')}}));
  const original=fs.readFileSync(config,'utf8');
  function client(profile,initial=false){
   const args=[path.join(__dirname,'cps-router.js'),config,...(profile?[initial?'--initial-profile':'--profile',profile]:[])];
   const proc=cp.spawn(process.execPath,args,{cwd:dir,windowsHide:true,stdio:['pipe','pipe','pipe']});children.push(proc);proc.stderr.resume();
   let serial=0;const pending=new Map();rl.createInterface({input:proc.stdout}).on('line',line=>{const m=JSON.parse(line);if(!m.method)pending.get(String(m.id))?.(m);});
   return async(method,params={})=>{const id=String(++serial);return new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('Host request timed out')),5000);pending.set(id,m=>{clearTimeout(timeout);pending.delete(id);resolve(m);});proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});};
  }
  const assistant=client(),air=client('b'),removed=client('missing');
  const init={protocolVersion:1,clientCapabilities:{},clientInfo:{name:'Generic ACP host',version:'1'}};
  assert((await assistant('initialize',init)).result);assert((await air('initialize',init)).result);
  assert((await removed('initialize',init)).error);
  const a=(await assistant('session/new',{cwd:dir,mcpServers:[]})).result;
  const b=(await air('session/new',{cwd:dir,mcpServers:[]})).result;
  assert.equal(a.configOptions[0].currentValue,'a');assert.equal(b.configOptions[0].currentValue,'b');
  const withoutProject=(await air('session/new',{cwd:os.tmpdir(),mcpServers:[]})).result;
  assert.equal(withoutProject.configOptions[0].currentValue,'b','Discovery without a mapped project lost its explicit profile');
  assert((await air('session/load',{sessionId:a.sessionId,cwd:dir,mcpServers:[]})).error);
  assert((await air('_ai_multi/switch',{sessionId:b.sessionId,profile:'a',fresh:true})).error);
  assert.deepEqual((await air('session/list',{cwd:dir})).result.sessions.map(x=>x.sessionId),[b.sessionId]);
  assert.equal((await assistant('session/list',{cwd:dir})).result.sessions.length,1);
  assert((await air('session/set_config_option',{sessionId:b.sessionId,configId:'model',value:'b'})).result);
  assert((await assistant('session/prompt',{sessionId:a.sessionId,prompt:[{type:'text',text:'fixture'}]})).result);
  assert((await air('session/prompt',{sessionId:b.sessionId,prompt:[{type:'text',text:'fixture'}]})).result);
  const launched=client('b',true);
  assert((await launched('initialize',init)).result);
  const selected=(await launched('session/new',{cwd:dir,mcpServers:[]})).result;
  assert.equal(selected.configOptions[0].currentValue,'b','Air new-chat choice used the project default');
  const ready=(await launched('_ai_multi/describe',{sessionId:selected.sessionId})).result;
  const switched=await launched('_ai_multi/switch',{sessionId:selected.sessionId,profile:'a',fresh:true,revision:ready.revision});
  assert(switched.result,JSON.stringify(switched));
  const committed=(await launched('_ai_multi/describe',{sessionId:selected.sessionId})).result;
  assert.equal(committed.profile,'a');
  for(const child of children)child.stdin.end();
  await new Promise(r=>setTimeout(r,500));
  const restored=client('b',true);
  assert((await restored('initialize',init)).result);
  assert((await restored('session/load',{sessionId:selected.sessionId,cwd:dir,mcpServers:[]})).result,'Initial profile prevented restoration of the committed segment');
  assert.equal((await restored('_ai_multi/describe',{sessionId:selected.sessionId})).result.profile,'a','Restoration reapplied the launch profile');
  assert.equal(fs.readFileSync(config,'utf8'),original,'Host selection changed Default Profile');
  assert.deepEqual(fs.readFileSync(path.join(__dirname,'virtual-sessions.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/virtual-sessions.js')));
  console.log('PASS: simultaneous generic hosts, explicit profile/model discovery without project mapping, model setter, independent prompts, scoped list/restore, removed profile refusal, unchanged Default Profile');
 }finally{for(const p of children)p.kill();await new Promise(r=>setTimeout(r,200));fs.rmSync(dir,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
