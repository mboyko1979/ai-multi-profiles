'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process'),readline=require('readline'),crypto=require('crypto');
const settings=require('./profile-settings');
const {VirtualSessions}=require('./virtual-sessions');
(async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-setup-test-'));
 const processes=[];
 try {
  const backend=path.join(dir,'backend.js'),trace=path.join(dir,'trace.jsonl');
  fs.writeFileSync(backend,`const fs=require('fs'),rl=require('readline').createInterface({input:process.stdin});let authorized=false,authRequest;
   const send=x=>console.log(JSON.stringify({jsonrpc:'2.0',...x}));
   rl.on('line',line=>{const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,JSON.stringify({method:m.method,params:m.params})+'\\n');
    if(m.method==='initialize')send({id:m.id,result:{protocolVersion:1,agentCapabilities:{sessionCapabilities:{close:{}}},_meta:{defaultAuthMethodId:'cached'},authMethods:[{id:'cached',name:'cached_token',description:'Reuse the stored token',_meta:{custom:{preserve:true}}},{id:'browser',name:'Browser login'},{id:'terminal',type:'terminal',name:'Terminal login',_meta:{'terminal-auth':{args:['login'],env:{PROFILE_FLAG:'wrong'}}}}]}});
    else if(m.method==='authenticate'){if(m.params.methodId==='cached')return send(authorized?{id:m.id,result:{}}:{id:m.id,error:{code:-32000,message:'SECRET_PAYLOAD'}});authRequest=m.id;send({id:'url',method:'elicitation/create',params:{mode:'url',url:'https://example.com/login',message:'Sign in'}});}
    else if(m.id==='url'&&!m.method){authorized=m.result?.action==='accept';send({id:authRequest,result:{}});}
    else if(m.method==='session/new'){if(!authorized)return send({id:m.id,error:{code:-32000,message:'SECRET_PAYLOAD'}});
     send({id:m.id,result:{sessionId:'discovery-only',configOptions:[{id:'model',name:'Model',type:'select',category:'model',currentValue:'a',options:[{value:'a',name:'A'},{value:'b',name:'B'}]}]}});}
    else if(m.method==='session/close')send({id:m.id,result:{}});
    else send({id:m.id,error:{code:-32601,message:'Unexpected method'}});
   });`);
  const config=path.join(dir,'routes.json');fs.writeFileSync(config,JSON.stringify({profiles:{probe:{command:process.execPath,args:[backend],authenticationArgs:['base'],env:{TRACE:trace,PROFILE_FLAG:'isolated'},catalogKey:'identity'}},projects:[]}));
  const child=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),config,'--profile-setup'],{windowsHide:true,stdio:['pipe','pipe','pipe']});processes.push(child);child.stderr.resume();
  let next=0;const pending=new Map();
  readline.createInterface({input:child.stdout}).on('line',line=>{const m=JSON.parse(line);if(m.method){assert.equal(m.method,'elicitation/create');child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{action:'accept'}})+'\n');}else pending.get(String(m.id))?.(m);});
  async function request(method,params={}) {const id=String(++next);return await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Setup request timed out')),5000);pending.set(id,m=>{clearTimeout(timer);pending.delete(id);resolve(m);});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});}
  const advertised=(await request('_ai_multi/setup')).result.authMethods;
  assert.equal(advertised.length,3);
  const initialization=JSON.parse(fs.readFileSync(trace,'utf8').trim().split('\n')[0]);
  assert.equal(initialization.params.clientCapabilities.session?.configOptions?.boolean,undefined,'Probe requested a different option representation from the native client');
  assert.deepEqual(advertised[0],{id:'cached',name:'cached_token',description:'Reuse the stored token',_meta:{custom:{preserve:true}}});
  assert((await request('_ai_multi/defaults',{cwd:dir})).error);
  assert(!JSON.stringify(await request('_ai_multi/defaults',{cwd:dir})).includes('SECRET_PAYLOAD'));
  assert((await request('_ai_multi/authorize',{methodId:'unadvertised'})).error);
  assert((await request('_ai_multi/authorize',{methodId:'terminal'})).error);
  const terminal=(await request('_ai_multi/login_spec',{methodId:'terminal'})).result;
  assert.deepEqual(terminal.args,['base','login']);assert.equal(terminal.env.PROFILE_FLAG,'isolated');
  assert((await request('session/prompt')).error);
  assert((await request('_ai_multi/authorize',{methodId:'browser'})).result);
  const catalog=(await request('_ai_multi/defaults',{cwd:dir})).result;
  assert.equal(catalog.options[0].currentValue,'a');assert.equal(catalog.options[0].options.length,2);
  const calls=fs.readFileSync(trace,'utf8').trim().split('\n').map(x=>JSON.parse(x));
  const selectedAt=calls.findIndex(x=>x.method==='authenticate'&&x.params.methodId==='browser');
  assert(!calls.slice(selectedAt+1).some(x=>x.method==='authenticate'),'ACP default replaced the explicitly selected method');
  assert(!calls.some(x=>x.method==='session/prompt'));assert(calls.filter(x=>x.method==='session/new').every(x=>x.params.mcpServers.length===0));
  assert(!fs.existsSync(path.join(dir,'session-owners')));
  const spec={catalogKey:'identity'};const model=currentValue=>({configOptions:[{id:'model',name:'Model',type:'select',category:'model',currentValue,options:[{value:'a',name:'A'},{value:'b',name:'B'}]}]});
  settings.catalog(dir,'profile',spec,model('a'),true);settings.catalog(dir,'profile',spec,model('b'));
  const file=path.join(dir,'catalogs',crypto.createHash('sha256').update('profile').digest('hex')+'.json');
  assert.equal(JSON.parse(fs.readFileSync(file)).options[0].currentValue,'a','Live selection overwrote profile default');
  settings.catalog(dir,'profile',{catalogKey:'different-auth'},model('b'));
  assert.equal(JSON.parse(fs.readFileSync(file)).options[0].currentValue,null,'Another auth identity inherited defaults');
  const specLive={agentId:'provider',catalogKey:'chat-identity',sessionSettings:[{agentId:'provider',operation:'config',id:'model',value:'b'}]};
  const controls=(selected,extra=false)=>({configOptions:[{id:'model',name:'Model',type:'select',category:'model',currentValue:selected,
    options:(extra?['a','b','c']:['a','b']).map(value=>({value,name:value.toUpperCase()}))},
    {id:'effort',name:'Reasoning effort',type:'select',category:'thought_level',currentValue:selected==='b'?'medium':'low',
     options:['low','medium','high'].map(value=>({value,name:value}))}]});
  const active={initialize:{protocolVersion:1,agentCapabilities:{}},proc:{stdin:{write(){}}}},discovery={initialize:active.initialize,proc:{kill(){}}};
  let discoveryFailure=false;const sent=[],requests=[];
  const api={directory:dir,config:()=>({profiles:{p:specLive},projects:[]}),normalize:x=>x,fingerprint:()=> 'launch',hash:()=>'',initializeParams:()=>({}),initialized:()=>active.initialize,
   backendFor:async(p,s,isolation)=>String(isolation).startsWith('catalog:')?discovery:active,advertiseAuth:()=>({methods:[],bindings:[]}),record(){},send:m=>sent.push(m),
   request:async(b,method,params)=>{requests.push({method,params});if(method==='session/new')return b===discovery&&discoveryFailure?{error:{code:-32000}}:{result:{sessionId:'backend',...controls('a',b===discovery)}};
    if(method==='session/set_model')return {result:{}};
    if(method==='session/set_config_option')return {result:controls(params.value)};throw Error('Unexpected refresh request '+method);}};
  // A chat refresh may update available choices, but must preserve the saved
  // profile defaults even when they differ from this chat's accepted selection.
  const savedProfile=JSON.stringify(specLive);
  const v=new VirtualSessions(api),created=await v.newSession({cwd:dir},{profile:'p'}),live=v.active.get(created.sessionId);
  const liveFile=path.join(dir,'catalogs',crypto.createHash('sha256').update('p').digest('hex')+'.json');
  assert.equal(JSON.parse(fs.readFileSync(liveFile)).options[0].currentValue,null,'Session creation captured profile defaults');
  await v.control({method:'_ai_multi/refresh_options',params:{sessionId:created.sessionId}});
  assert.equal(JSON.parse(fs.readFileSync(liveFile)).options[0].currentValue,null,'Chat refresh captured profile defaults');
  settings.catalog(dir,'p',specLive,controls('a'),true);
  const requestsBeforeRefresh=requests.length;
  // Real providers may reset effort even when the same model is selected again.
  const originalRequest=api.request;
  api.request=async(b,method,params)=>{
   if(b===active && ['session/set_model','session/set_config_option'].includes(method)) {
    live.result.configOptions[1].currentValue='low';
    throw Error('Refresh mutated the active session');
   }
   return originalRequest(b,method,params);
  };
  const refreshed=await v.control({method:'_ai_multi/refresh_options',params:{sessionId:created.sessionId}});
  assert.equal(refreshed.configOptions[0].currentValue,'b');assert.equal(refreshed.configOptions[0].options.length,3);
  assert.equal(refreshed.configOptions[1].currentValue,'medium');
  assert.equal(live.s.sessionOptions.configOptions[1].currentValue,'medium');
  assert(!requests.slice(requestsBeforeRefresh).some(x=>['session/set_model','session/set_config_option'].includes(x.method)));
  const assertDefaults=()=>{
   const saved=JSON.parse(fs.readFileSync(liveFile));
   assert.equal(saved.options[0].currentValue,'a','Chat refresh changed profile default model');
   assert.equal(saved.options[1].currentValue,'low','Chat refresh changed profile default reasoning');
   assert.equal(JSON.stringify(specLive),savedProfile,'Chat refresh changed configured profile settings');
  };
  assertDefaults();
  assert(sent.some(m=>m.params?.update?.sessionUpdate==='config_option_update'));
  live.result.models={currentModelId:'b/high',availableModels:[{modelId:'b/high',name:'B High'}]};
  await v.control({method:'_ai_multi/refresh_options',params:{sessionId:created.sessionId}});
  assert.equal(live.result.models.currentModelId,'b/high','Refresh lost legacy model effort');
  assert.equal(live.result.configOptions[1].currentValue,'medium');
  // Session choices take precedence over both provider and profile defaults.
  live.result.configOptions[1].currentValue='high';
  await v.control({method:'_ai_multi/refresh_options',params:{sessionId:created.sessionId}});
  assert.equal(live.result.configOptions[1].currentValue,'high');
  assertDefaults();
  live.busy=true;await assert.rejects(()=>v.control({method:'_ai_multi/refresh_options',params:{sessionId:created.sessionId}}),/finish/);live.busy=false;
  discoveryFailure=true;await assert.rejects(()=>v.control({method:'_ai_multi/refresh_options',params:{sessionId:created.sessionId}}),/Authorize/);
  assert.equal(live.busy,false);assert.equal(live.result.configOptions[0].currentValue,'b');
  assert.equal(live.result.configOptions[1].currentValue,'high');
  assertDefaults();
  assert(!requests.some(x=>x.method==='session/prompt'));v.close();
  assert.deepEqual(fs.readFileSync(path.join(__dirname,'cps-router.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/cps-router.js')));
  console.log('PASS: explicit setup, isolated authorization, URL callbacks, terminal launch identity, no prompts, cached defaults, active refresh, retained selection, busy/failure cleanup');
 }finally{for(const process of processes)process.kill();await new Promise(r=>setTimeout(r,150));fs.rmSync(dir,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
