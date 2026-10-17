'use strict';
const assert=require('assert'),fs=require('fs'),os=require('os'),path=require('path'),crypto=require('crypto');
const settings=require('./profile-settings'),{VirtualSessions}=require('./virtual-sessions');
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-presets-'));
const option=(id,values,current,category=id)=>({id,name:id,type:'select',category,currentValue:current,options:values.map(value=>({value,name:value.toUpperCase()}))});
const selection=(id,value,agentId='test',operation='config')=>({id,value,agentId,operation});
const jwt=claims=>'synthetic.'+Buffer.from(JSON.stringify(claims)).toString('base64url')+'.signature';
(async()=>{try {
  const spec={agentId:'test',configurationKey:'launch-v1',sessionSettings:[selection('effort','high'),selection('model','b'),selection('removed','old'),selection('permission','readonly'),selection('flag','true')]};
  let opts=[option('model',['a','b'],'a'),option('effort',['low'],'low','thought_level'),option('permission',['ask','readonly'],'ask','mode'),{id:'flag',name:'Flag',type:'boolean',currentValue:false}];
  const calls=[];
  const configured=await settings.apply(spec,{sessionId:'s',configOptions:opts},async(method,p)=>{
    calls.push({method,p});assert.equal(method,'session/set_config_option');
    if(p.configId==='model'){opts[0].currentValue=p.value;opts[1]=option('effort',['low','high'],'low','thought_level');}
    else opts.find(o=>o.id===p.configId).currentValue=p.value;
    return {result:{configOptions:structuredClone(opts)}};
  });
  assert.deepEqual(calls.map(x=>x.p.configId),['model','effort','permission','flag']);
  assert.equal(configured.result.configOptions.find(x=>x.id==='effort').currentValue,'high');
  assert.equal(configured.result.configOptions.find(x=>x.id==='flag').currentValue,true);
  assert.deepEqual(configured.warnings,['Saved option unavailable: removed']);
  assert.equal(spec.sessionSettings[2].value,'old'); // Unsupported saved values never deleted.
  let dependent=[option('provider',['one','two'],'one','_provider')];const dependentCalls=[];
  const late=await settings.apply({agentId:'test',sessionSettings:[selection('effort','high'),selection('provider','two')]},
    {sessionId:'late',configOptions:dependent},async(method,p)=>{
      dependentCalls.push(p.configId);
      if(p.configId==='provider')dependent=[option('provider',['one','two'],'two','_provider'),option('effort',['low','high'],'low','thought_level')];
      else dependent[1].currentValue=p.value;
      return {result:{configOptions:structuredClone(dependent)}};
    });
  assert.deepEqual(dependentCalls,['provider','effort']);assert.deepEqual(late.warnings,[]);
  await assert.rejects(()=>settings.apply({agentId:'test',sessionSettings:[selection('model','b')]},
    {sessionId:'original',configOptions:[option('model',['a','b'],'a')]},async()=>({result:{sessionId:'replacement'}})),/replace the session/);
  await assert.rejects(()=>settings.apply({agentId:'test',sessionSettings:[selection('model','b')]},
    {sessionId:'original',configOptions:[option('model',['a','b'],'a')]},async()=>({result:{}})),/did not confirm/);
  const unknown=await settings.apply({agentId:'other',sessionSettings:spec.sessionSettings},{sessionId:'u',configOptions:[]},()=>{throw Error('Cross-agent setting applied')});
  assert.deepEqual(unknown.warnings,[]);
  const subset=await settings.apply(spec,{sessionId:'u',configOptions:[option('model',['a'],'a')]},()=>{throw Error('Unsupported operation sent')});
  assert.equal(subset.warnings.length,5); // No reasoning/permission controls on this agent.
  const reject=await settings.apply({agentId:'test',sessionSettings:[selection('effort','high')]},{sessionId:'r',configOptions:[option('effort',['low','high'],'low')]},async()=>({error:{message:'backend private payload'}}));
  assert.equal(reject.result.configOptions[0].currentValue,'low');assert(!JSON.stringify(reject.warnings).includes('private'));
  const legacy=await settings.apply({agentId:'test',sessionSettings:[selection('model','b','test','model'),selection('mode','plan','test','mode')]},
    {sessionId:'l',models:{currentModelId:'a',availableModels:[{modelId:'a',name:'A'},{modelId:'b',name:'B'}]},modes:{currentModeId:'code',availableModes:[{id:'code',name:'Code'},{id:'plan',name:'Plan'}]}},
    async(method,p)=>{assert(['session/set_model','session/set_mode'].includes(method));return {result:{}}});
  assert.equal(legacy.result.models.currentModelId,'b');assert.equal(legacy.result.modes.currentModeId,'plan');
  const grouped=settings.descriptors({configOptions:[{...option('group',[],'x'),options:[{group:'vendor',options:[{value:'x',name:'X'}]}]},option('api_key',['private'],'private'),{id:'future',type:'secret'}]});
  assert.equal(grouped.length,1);assert.deepEqual(grouped[0].options,[{value:'x',name:'X'}]);
  settings.cache(dir,'p',spec,configured.result,{agentInfo:{version:'test'},_meta:{accessToken:'private'}});
  const cached=JSON.parse(fs.readFileSync(path.join(dir,'capabilities',crypto.createHash('sha256').update('p').digest('hex')+'.json'),'utf8'));
  assert.equal(cached.configurationKey,'launch-v1');assert(!JSON.stringify(cached).includes('accessToken'));
  // Settings discovers a fresh provider session, never a restored/modified chat or profile preset.
  const probeTrace=path.join(dir,'defaults-trace.jsonl'),probeBackend=path.join(dir,'defaults-backend.js');
  fs.writeFileSync(probeBackend,`const fs=require('fs'),rl=require('readline').createInterface({input:process.stdin});
    rl.on('line',line=>{const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,line+'\\n');
      const send=x=>console.log(JSON.stringify({jsonrpc:'2.0',...x}));
      if(m.method==='initialize')send({id:m.id,result:{protocolVersion:1,agentCapabilities:{}}});
      else if(m.method==='session/new'){
        if(process.env.AUTH_REQUIRED==='true')return send({id:m.id,error:{code:-32000,message:'PRIVATE_AUTH_PAYLOAD'}});
        send({id:m.id,result:{sessionId:'empty-preview',configOptions:[{id:'effort',name:'Reasoning effort',type:'select',currentValue:process.env.DEFAULT_EFFORT,options:[{value:'low',name:'Low'},{value:'high',name:'High'}]},{id:'api_key',name:'Secret',type:'select',currentValue:'PRIVATE_TOKEN',options:[{value:'PRIVATE_TOKEN',name:'Secret'}]}]}});
      } else send({id:m.id,error:{code:-32601,message:'Unexpected call'}});
    });`);
  async function probe(defaultValue,authRequired=false,method='_ai_multi/defaults') {
    const config=path.join(dir,'defaults-config.json');
    fs.writeFileSync(config,JSON.stringify({profiles:{p:{command:process.execPath,args:[probeBackend],
      env:{TRACE:probeTrace,DEFAULT_EFFORT:defaultValue,AUTH_REQUIRED:String(authRequired)},
      sessionSettings:[selection('effort','high')]}},projects:[]}));
    const child=require('child_process').spawn(process.execPath,[path.join(__dirname,'cps-router.js'),config,'--probe-defaults'],{stdio:['pipe','pipe','pipe'],windowsHide:true});
    child.stderr.resume();
    const response=new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{child.stdin.end();reject(Error('Defaults probe timed out'));},5000);
      require('readline').createInterface({input:child.stdout}).on('line',line=>{const m=JSON.parse(line);if(m.id===1){clearTimeout(timer);resolve(m);child.stdin.end();}});
      child.on('error',reject);
    });
    const exited=new Promise(resolve=>child.on('exit',resolve));
    child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:1,method,params:{profile:'p',cwd:dir}})+'\n');
    const result=await response;await exited;return result;
  }
  assert.equal((await probe('low')).result.options[0].currentValue,'low');
  assert.equal((await probe('high')).result.options[0].currentValue,'high');
  const unauthorized=await probe('low',true);assert(unauthorized.error);assert(!JSON.stringify(unauthorized).includes('PRIVATE_AUTH_PAYLOAD'));
  assert((await probe('low',false,'session/prompt')).error);
  const discoveryCalls=fs.readFileSync(probeTrace,'utf8').trim().split('\n').map(x=>JSON.parse(x));
  assert.deepEqual(discoveryCalls.map(x=>x.method),['initialize','session/new','initialize','session/new','initialize','session/new']);
  assert(discoveryCalls.filter(x=>x.method==='session/new').every(x=>x.params.mcpServers.length===0));
  assert(!fs.existsSync(path.join(dir,'session-owners')));
  const homes=['personal','work'].map(name=>{const home=path.join(dir,name);fs.mkdirSync(home);return home;});
  for(let i=0;i<2;i++)fs.writeFileSync(path.join(homes[i],'auth.json'),JSON.stringify({tokens:{id_token:jwt({email:i?'work@example.com':'personal@example.com',exp:Date.now()/1000+3600,'https://api.openai.com/auth':{chatgpt_account_id:'account-'+i}}),access_token:'DO_NOT_DISPLAY',refresh_token:'DO_NOT_DISPLAY'}}));
  const codex=i=>({agentId:'acp.registry.codex-acp',inheritEnvironment:false,env:{CODEX_HOME:homes[i]}});
  assert.equal(settings.accountIdentity(codex(0)).email,'personal@example.com');assert.equal(settings.accountIdentity(codex(1)).email,'work@example.com');
  assert.equal(settings.accountIdentity(codex(0)).verified,false);assert(!JSON.stringify(settings.accountIdentity(codex(0))).includes('DO_NOT_DISPLAY'));
  assert(!settings.accountIdentity({...codex(0),env:{...codex(0).env,OPENAI_API_KEY:'synthetic'}}).email);
  const authWork=JSON.parse(fs.readFileSync(path.join(homes[1],'auth.json'),'utf8'));
  fs.writeFileSync(path.join(homes[1],'auth.json'),JSON.stringify({...authWork,OPENAI_API_KEY:'SYNTHETIC_KEY'}));
  assert(!settings.accountIdentity(codex(1)).email);
  fs.writeFileSync(path.join(homes[1],'auth.json'),'{"tokens":{"id_token":"malformed"}}');assert(!settings.accountIdentity(codex(1)).email);
  fs.writeFileSync(path.join(homes[1],'auth.json'),JSON.stringify({tokens:{id_token:jwt({email:'expired@example.com',exp:1})}}));
  assert.equal(settings.accountIdentity(codex(1)).email,'expired@example.com');assert.equal(settings.accountIdentity(codex(1)).status,'');assert.equal(settings.accountIdentity(codex(1)).verified,false);
  assert(!settings.accountIdentity({agentId:'unknown'}).email);
  assert(!settings.accountIdentity({...codex(0),env:{CODEX_HOME:path.relative(process.cwd(),homes[0])}}).email);
  fs.writeFileSync(path.join(homes[1],'auth.json'),JSON.stringify({tokens:{account_id:'different-account',id_token:jwt({email:'ambiguous@example.com','https://api.openai.com/auth':{chatgpt_account_id:'claimed-account'}})}}));
  assert(!settings.accountIdentity(codex(1)).email); // Conflicting local identity metadata is not displayed.
  const genericHome=path.join(dir,'generic');fs.mkdirSync(genericHome);
  const grok={agentId:'acp.registry.grok-build',inheritEnvironment:false,env:{GROK_HOME:genericHome}};
  const publicAccount={email:'grok@example.com',user_id:'grok-user',key:'DO_NOT_DISPLAY',refresh_token:'DO_NOT_DISPLAY',expires_at:'2099-01-01T00:00:00Z'};
  fs.writeFileSync(path.join(genericHome,'auth.json'),JSON.stringify({'https://auth.example::client':publicAccount}));
  assert.equal(settings.accountIdentity(grok).email,'grok@example.com');assert.equal(settings.accountIdentity(grok).accountId,'grok-user');
  assert.equal(settings.accountIdentity(grok).verified,false);assert(!JSON.stringify(settings.accountIdentity(grok)).includes('DO_NOT_DISPLAY'));
  assert(!settings.accountIdentity({...grok,env:{...grok.env,XAI_API_KEY:'synthetic'}}).email);
  assert(!settings.accountIdentity({...grok,env:{GROK_HOME:path.relative(process.cwd(),genericHome)}}).email);
  fs.writeFileSync(path.join(genericHome,'auth.json'),JSON.stringify({account:publicAccount}));
  assert.equal(settings.accountIdentity({...grok,agentId:'acp.registry.future-vendor',env:{FUTURE_HOME:genericHome}}).email,'grok@example.com');
  fs.writeFileSync(path.join(genericHome,'auth.json'),JSON.stringify({account:{...publicAccount,email:'bad\nemail',user_id:'bad\nidentifier'}}));
  assert(!settings.accountIdentity(grok).email);assert(!settings.accountIdentity(grok).accountId);
  fs.writeFileSync(path.join(genericHome,'auth.json'),JSON.stringify({'issuer::one':publicAccount,'issuer::two':{...publicAccount,user_id:'other-user'}}));
  assert(!settings.accountIdentity(grok).email);assert(settings.accountIdentity(grok).status.includes('ambiguous'));
  fs.writeFileSync(path.join(genericHome,'auth.json'),JSON.stringify({account:{...publicAccount,expires_at:'2000-01-01T00:00:00Z'}}));
  assert.equal(settings.accountIdentity(grok).status,'');assert.equal(settings.accountIdentity(grok).verified,false);
  fs.writeFileSync(path.join(genericHome,'auth.json'),'malformed');assert(!settings.accountIdentity(grok).email);
  fs.writeFileSync(path.join(genericHome,'auth.json'),' '.repeat(1048577));assert(!settings.accountIdentity(grok).email);
  const instructions=path.join(dir,'rules.md');fs.writeFileSync(instructions,'Answer in Russian.');
  assert.equal(settings.instructions({instructionsSource:'file',instructionsFile:instructions}),'Answer in Russian.');
  assert.throws(()=>settings.instructions({instructionsSource:'file',instructionsFile:path.join(dir,'missing.md')}),/unavailable/);
  const native=path.join(homes[0],'AGENTS.md');fs.writeFileSync(native,'Native rules');assert.equal(settings.instructions({...codex(0),instructionsSource:'file',instructionsFile:native}),'');
  // Exercise the real virtual lifecycle independently of host UI (Air and AI Assistant use the same wire).
  const configuration={profiles:{p:{...spec,sessionSettings:[selection('model','b')],instructionsSource:'file',instructionsFile:instructions}},projects:[]};
  const backend={fingerprint:'launch-only',initialize:{protocolVersion:1,agentCapabilities:{loadSession:true},agentInfo:{version:'mock'}},proc:{stdin:{write(){}}}};
  const prompts=[],requests=[];
  const api={directory:dir,config:()=>configuration,normalize:x=>x,fingerprint:()=> 'launch-only',hash:()=>'',initializeParams:()=>({}),initialized:()=>backend.initialize,
    backendFor:async()=>({...backend}),advertiseAuth:()=>({methods:[],bindings:[]}),record(){},send(){},request:async(b,method,p)=>{
      requests.push({method,p});if(method==='session/new')return {result:{sessionId:'backend',configOptions:[option('model',['a','b'],'a')]}};
      if(method==='session/set_config_option')return {result:{configOptions:[option('model',['a','b'],p.value)]}};
      if(method==='session/load')return {result:{configOptions:[option('model',['a','b'],'a')]}};
      if(method==='session/prompt'){prompts.push(p.prompt);return {result:{stopReason:'end_turn'}};}
      throw Error('Unexpected method '+method);
    }};
  const virtual=new VirtualSessions(api),r=await virtual.newSession({cwd:dir},{profile:'p'});let live=virtual.active.get(r.sessionId);
  assert.equal(r.configOptions[0].currentValue,'b');
  // A user's last model belongs to that chat, never to the profile's next session or switch.
  virtual.fromBackend(live.backend,{method:'session/update',params:{sessionId:'backend',update:{sessionUpdate:'config_option_update',configOptions:[option('model',['a','b'],'a')]}}});
  const nextSession=await virtual.newSession({cwd:dir},{profile:'p'});
  assert.equal(nextSession.configOptions[0].currentValue,'b');
  configuration.profiles.q={...configuration.profiles.p,sessionSettings:[selection('model','a')]};
  let state=virtual.active.get(r.sessionId).s;
  await virtual.control({method:'_ai_multi/switch',params:{sessionId:r.sessionId,profile:'q',fresh:true,revision:state.revision}});
  state=virtual.active.get(r.sessionId).s;
  await virtual.control({method:'_ai_multi/switch',params:{sessionId:r.sessionId,profile:'p',fresh:true,revision:state.revision}});
  const returned=virtual.active.get(r.sessionId);
  assert.equal(returned.result.configOptions[0].currentValue,'b');
  // Continue the checks on the new segment selected above.
  live=returned;
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir,'capabilities',crypto.createHash('sha256').update('p').digest('hex')+'.json'),'utf8')).options[0].currentValue,'b'); // Existing cache is untouched.
  fs.writeFileSync(instructions,'CHANGED RULES');configuration.profiles.p.sessionSettings=[selection('model','a')];
  assert.equal(live.result.configOptions[0].currentValue,'b');assert.equal(live.segment.profileInstructions,'Answer in Russian.');
  await virtual.prompt(live,{prompt:[{type:'text',text:'First task'}]});await virtual.prompt(live,{prompt:[{type:'text',text:'Second task'}]});
  assert(prompts[0][0].text.includes('Answer in Russian.'));assert(!JSON.stringify(prompts).includes('CHANGED RULES'));
  assert.equal(prompts[1].length,1);assert(!JSON.stringify(live.s.exchanges).includes('Answer in Russian.'));
  virtual.fromBackend(live.backend,{method:'session/update',params:{sessionId:'backend',update:{sessionUpdate:'config_option_update',configOptions:[option('model',['a','b'],'a')]}}});
  assert.equal(live.s.sessionOptions.configOptions[0].currentValue,'a');
  live.result.modes={currentModeId:'code',availableModes:[{id:'code',name:'Code'},{id:'plan',name:'Plan'}]};
  virtual.fromBackend(live.backend,{method:'session/update',params:{sessionId:'backend',update:{sessionUpdate:'current_mode_update',currentModeId:'plan'}}});
  assert.equal(live.s.sessionOptions.modes.currentModeId,'plan');
  await virtual.close();
  const reloaded=new VirtualSessions(api);await reloaded.restore({sessionId:r.sessionId,cwd:dir},'session/load');
  assert.equal(requests.filter(x=>x.method==='session/set_config_option').length,3); // Two creations and switching back, never restore.
  await reloaded.close();
  // Profile defaults are frozen by explicit refresh. Agent defaults, preview
  // caches and selections in another chat cannot change the saved baseline.
  const freshDir=path.join(dir,'fresh-defaults');fs.mkdirSync(freshDir);
  const freshSpec={agentId:'test',catalogKey:'fresh-catalog',configurationKey:'fresh-config',sessionSettings:[],
    frozenDefaultsRequired:true,defaultsAgentId:'test',agentDefaults:[selection('effort','high')]};
  const freshConfig={profiles:{p:freshSpec},projects:[]};
  settings.catalog(freshDir,'p',freshSpec,{configOptions:[option('effort',['low','high'],'high')]},true);
  settings.cache(freshDir,'p',freshSpec,{configOptions:[option('effort',['low','high'],'high')]},{});
  let agentDefault='low',created=0;const freshRequests=[];
  const fresh=new VirtualSessions({...api,directory:freshDir,config:()=>freshConfig,request:async(b,method,p)=>{
    freshRequests.push({method,p});
    if(method==='session/new')return {result:{sessionId:'fresh-'+(++created),configOptions:[option('effort',['low','high'],agentDefault)]}};
    if(method==='session/set_config_option')return {result:{configOptions:[option('effort',['low','high'],p.value)]}};
    throw Error('Unexpected fresh defaults method '+method);
  }});
  const firstFresh=await fresh.newSession({cwd:freshDir},{profile:'p'});
  assert.equal(firstFresh.configOptions[0].currentValue,'high');
  const firstLive=fresh.active.get(firstFresh.sessionId);
  fresh.fromBackend(firstLive.backend,{method:'session/update',params:{sessionId:firstLive.segment.sessionId,
    update:{sessionUpdate:'config_option_update',configOptions:[option('effort',['low','high'],'low')]}}});
  assert.equal((await fresh.newSession({cwd:freshDir},{profile:'p'})).configOptions[0].currentValue,'high');
  agentDefault='high';
  assert.equal((await fresh.newSession({cwd:freshDir},{profile:'p'})).configOptions[0].currentValue,'high');
  freshSpec.sessionSettings=[selection('effort','low')];
  assert.equal((await fresh.newSession({cwd:freshDir},{profile:'p'})).configOptions[0].currentValue,'low');
  freshSpec.sessionSettings=[]; // Use agent default returns to the saved profile baseline.
  assert.equal((await fresh.newSession({cwd:freshDir},{profile:'p'})).configOptions[0].currentValue,'high');
  freshSpec.agentDefaults=[selection('effort','low')]; // Successful explicit refresh only.
  assert.equal((await fresh.newSession({cwd:freshDir},{profile:'p'})).configOptions[0].currentValue,'low');
  assert.equal(created,6);
  assert.deepEqual(freshRequests.filter(x=>x.method==='session/set_config_option').map(x=>x.p.value),['high','high','low','low']);
  freshSpec.defaultsAgentId='other';
  await assert.rejects(()=>fresh.newSession({cwd:freshDir},{profile:'p'}),/Refresh agent defaults/);
  assert.equal(created,6); // Missing/wrong-agent snapshots fail before session/new.
  const unsupported=await settings.apply({...freshSpec,defaultsAgentId:'test'},
    {sessionId:'unsupported',configOptions:[option('effort',['high'],'high')]},()=>{throw Error('Unsupported value sent');});
  assert.equal(unsupported.result.configOptions[0].currentValue,'high');
  assert.deepEqual(unsupported.warnings,['Saved value unavailable: effort']);
  const legacyFast=await settings.apply({agentId:'test',agentDefaults:[selection('fast-mode','false')]},
    {sessionId:'legacy-fast',configOptions:[option('fast-mode',['off','on'],'off')]},()=>{throw Error('Guessed a boolean/select conversion');});
  assert.equal(legacyFast.result.configOptions[0].currentValue,'off');
  assert.deepEqual(legacyFast.warnings,['Saved value unavailable: fast-mode']);
  await assert.rejects(()=>settings.apply({...freshSpec,defaultsAgentId:'test'},
    {sessionId:'rejected',configOptions:[option('effort',['low','high'],'high')]},async()=>({error:{code:-1,message:'rejected'}})),/saved profile default/);
  await fresh.close();
  assert.deepEqual(fs.readFileSync(path.join(__dirname,'profile-settings.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/profile-settings.js')));
  assert.deepEqual(fs.readFileSync(path.join(__dirname,'codex-account.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/codex-account.js')));
  assert.deepEqual(fs.readFileSync(path.join(__dirname,'local-account.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/local-account.js')));
  console.log('PASS: dynamic and legacy ACP presets, provider isolation, unsupported values, account homes/security, instruction snapshots, effective state, edits and restoration');
}finally {fs.rmSync(dir,{recursive:true,force:true});}})().catch(e=>{console.error(e);process.exitCode=1;});
