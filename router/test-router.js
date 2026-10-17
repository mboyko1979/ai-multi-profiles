'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), cp = require('child_process'), assert = require('assert');
const readline = require('readline');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'aiaps-test-'));
const fake = path.join(root, 'backend.js'), cfg = path.join(root, 'routes.json');
const children = [];
fs.writeFileSync(fake, `
const rl=require('readline').createInterface({input:process.stdin});
rl.on('line', line => {
 const m=JSON.parse(line);
 if(process.env.TRACE)require('fs').appendFileSync(process.env.TRACE,JSON.stringify(m)+'\\n');
 if(m.method==='initialize') {
  if(process.env.HANG==='yes') return;
  const sessionCapabilities={list:{},fork:{},delete:{}};
  if(process.env.RESUME==='yes')sessionCapabilities.resume={};
  const authMethods=process.env.AUTH==='yes'?[{id:'login',name:'Sign in',_meta:{vendor:{flag:true}}},
   {id:'terminal',name:'Terminal',type:'terminal',args:[process.env.LOGIN_SCRIPT,'login'],env:{CODEX_HOME:'wrong-home'}}]:[];
  const meta={vendor:{flag:true}};
  console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{protocolVersion:1,authMethods,agentInfo:{name:'native-agent',title:'Native agent',version:'1'},_meta:meta,agentCapabilities:{loadSession:true,promptCapabilities:{image:process.env.PROFILE==='alpha'||process.env.IMAGE==='yes',audio:false},sessionCapabilities,_meta:meta}}}));
 } else if(m.method==='session/new') {
  console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{sessionId:process.env.FIXED_ID || process.env.PROFILE+'-'+Date.now()+'-'+Math.random()}}));
 } else if(m.method==='session/resume' && process.env.RESUME!=='yes') {
  console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,error:{code:-32601,message:'Resume is not supported'}}));
 } else if(m.method==='session/load') {
  console.log(JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'LEGACY_REPLAY'}}}}));
  console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{profile:process.env.PROFILE}}));
 } else if(m.method==='session/crash') process.exit(2);
 else if(m.method==='session/list') console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{sessions:JSON.parse(process.env.LIST||'[]'),nextCursor:'native-cursor',_meta:{native:true}}}));
 else if(m.method==='session/fork') console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{sessionId:process.env.FORK_ID||'fork-'+Math.random(),configOptions:[{id:'native-option'}],_meta:{native:true}}}));
 else if(m.method==='session/prompt' && m.params.prompt) console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{profile:process.env.PROFILE,prompt:m.params.prompt,_meta:m.params._meta}}));
 else if(m.method==='_native/extension' || m.method==='session/account_probe') {
  console.log(JSON.stringify({jsonrpc:'2.0',method:'_auth/status_update',params:{authStatus:{kind:'account',label:process.env.PROFILE,account:{email:process.env.PROFILE+'@example.test'},vendor:{future:true}}},_meta:{opaque:7}}));
  console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{params:m.params}}));
 }
 else if(m.method==='session/upstream') {
  console.log(JSON.stringify({jsonrpc:'2.0',id:'backend-call',method:'fs/read_text_file',params:{sessionId:m.params.sessionId,path:'test'}}));
 } else if(m.method==='session/spoof') {
  console.log(JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{sessionId:m.params.target,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'spoof'}}}}));
  console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{ok:true}}));
 } else if(m.id==='backend-call' && !m.method) console.log(JSON.stringify({jsonrpc:'2.0',id:global.saved,result:{profile:process.env.PROFILE,upstream:m.result}}));
 else console.log(JSON.stringify({jsonrpc:'2.0',id:m.id,result:{profile:process.env.PROFILE,sessionId:m.params?.sessionId}}));
 if(m.method==='session/upstream') global.saved=m.id;
});
`);
const spec = (id, extra={}) => ({command:process.execPath,args:[fake],env:{PROFILE:id,...extra}});
let config={projects:[{root:path.join(root,'A'),profile:'alpha'},{root:path.join(root,'B'),profile:'beta'}],profiles:{alpha:spec('alpha',{RESUME:'yes'}),beta:spec('beta')}};
const write = () => fs.writeFileSync(cfg, '\uFEFF'+JSON.stringify(config));
write();
function client(extraEnv={},cwd) {
 const proc=cp.spawn(process.execPath,[path.join(__dirname,'cps-router.js'),cfg],{cwd,env:{...process.env,AIAPS_REQUEST_TIMEOUT_MS:'400',...extraEnv},stdio:['pipe','pipe','pipe']});
 children.push(proc);
 const messages=[],waiters=new Map(); let errors=''; let serial=0;
 readline.createInterface({input:proc.stdout}).on('line',line=>{
  const m=JSON.parse(line); messages.push(m);
  const waiter=waiters.get(m.id); if(waiter){clearTimeout(waiter.timer);waiters.delete(m.id);waiter.resolve(m);}
 });
 proc.stderr.on('data',data=>{errors+=data;});
 const wait=id=>new Promise((resolve,reject)=>{
  const existing=messages.find(m=>m.id===id);if(existing)return resolve(existing);
  const timer=setTimeout(()=>{waiters.delete(id);reject(Error('Timeout '+id+' '+errors));},4000);
  waiters.set(id,{resolve,timer});
 });
 const send=m=>proc.stdin.write(JSON.stringify({jsonrpc:'2.0',...m})+'\n');
 return {proc,messages,send,wait,
  async request(method,params={}){const id=++serial;send({id,method,params});return wait(id);},
  async close(){proc.stdin.end();await new Promise(resolve=>{if(proc.exitCode!==null)return resolve();proc.once('exit',resolve);});}
 };
}
const ok = response => { assert(response.result, JSON.stringify(response)); return response.result; };
const rejected = response => { assert(response.error,JSON.stringify(response)); return response.error; };
(async()=>{
 let c=client();
 const init=ok(await c.request('initialize',{protocolVersion:1}));
 assert.strictEqual(init.agentCapabilities.loadSession,true);
 assert(!init.agentCapabilities.promptCapabilities.image); // intersection, never first-response wins
 assert(!('list' in init.agentCapabilities.sessionCapabilities));
 assert(!('fork' in init.agentCapabilities.sessionCapabilities));
 const alpha=ok(await c.request('session/new',{cwd:path.join(root,'A')})).sessionId;
 const beta=ok(await c.request('session/new',{cwd:path.join(root,'B')})).sessionId;
 // Direct legacy routing keeps forwarding the admitted backend's unscoped
 // notifications during its RPC, even without one negotiated project backend.
 ok(await c.request('session/account_probe',{sessionId:alpha}));
 assert.deepStrictEqual(c.messages.filter(m=>m.method==='_auth/status_update').at(-1),{jsonrpc:'2.0',method:'_auth/status_update',params:{authStatus:{kind:'account',label:'alpha',account:{email:'alpha@example.test'},vendor:{future:true}}},_meta:{opaque:7}});
 rejected(await c.request('session/load',{cwd:path.join(root,'B'),sessionId:alpha}));
 const setup=ok(await c.request('session/new',{cwd:path.join(root,'unassigned')})).sessionId;
 assert(setup.startsWith('aiaps-setup-'));
 assert(ok(await c.request('session/new',{cwd:path.join(root,'A-other')})).sessionId.startsWith('aiaps-setup-'));
 rejected(await c.request('session/new',{cwd:''}));
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:setup,prompt:[{type:'text',text:'private project data'}]})).stopReason,'end_turn');
 assert(c.messages.some(m=>m.params?.sessionId===setup && m.params.update?.content?.text.includes('start a new chat')));
 rejected(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:'unknown'}));
 rejected(await c.request('authenticate',{}));
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:alpha})).profile,'alpha');
 config.projects[0].profile='beta';write();
 assert.strictEqual(ok(await c.request('session/new',{cwd:path.join(root,'A','nested')})).sessionId.startsWith('beta-'),true);
 config.projects.push({root:path.join(root,'A','nested'),profile:'alpha'});write();
 assert.strictEqual(ok(await c.request('session/new',{cwd:path.join(root,'A','nested','child')})).sessionId.startsWith('alpha-'),true);
 assert.strictEqual(ok(await c.request('session/resume',{cwd:path.join(root,'A'),sessionId:alpha})).profile,'alpha');
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:alpha})).profile,'alpha');
 await c.request('session/spoof',{sessionId:beta,target:alpha});
 assert(!c.messages.some(m=>m.method==='session/update' && m.params?.sessionId===alpha));
 const upstreamRequest=c.request('session/upstream',{sessionId:alpha});
 for(let i=0;i<100&&!c.messages.some(m=>m.method==='fs/read_text_file');i++)await new Promise(r=>setTimeout(r,10));
 const upstream=c.messages.find(m=>m.method==='fs/read_text_file');assert(upstream);
 c.send({id:upstream.id,result:{content:'test'}});
 assert.strictEqual(ok(await upstreamRequest).profile,'alpha');
 await c.close();
 c=client();ok(await c.request('initialize',{protocolVersion:1}));
 config.projects=config.projects.filter(p=>p.root!==path.join(root,'A'));write();
 assert.strictEqual(ok(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:alpha})).profile,'alpha');
 rejected(await c.request('session/prompt',{sessionId:beta})); // must load before prompt
 await c.close();
 const originalAlpha=config.profiles.alpha;delete config.profiles.alpha;write();
 c=client();ok(await c.request('initialize',{protocolVersion:1}));
 rejected(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:alpha}));await c.close();
 config.profiles.alpha=originalAlpha;
 config.profiles.alpha.env.PROFILE='different-account';write();
 c=client();ok(await c.request('initialize',{protocolVersion:1}));
 rejected(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:alpha}));
 await c.close();
 config={projects:[{root:path.join(root,'A'),profile:'alpha'}],profiles:{alpha:spec('alpha'),bad:{command:path.join(root,'does-not-exist')},badArgs:{command:process.execPath,args:'invalid'},badCwd:{...spec('beta'),cwd:42},badEnv:{...spec('beta'),env:{INVALID:42}},hang:spec('hang',{HANG:'yes'})}};write();
 c=client();ok(await c.request('initialize',{protocolVersion:1}));
 const sid=ok(await c.request('session/new',{cwd:path.join(root,'A')})).sessionId;
 rejected(await c.request('session/crash',{sessionId:sid}));
 config.profiles.beta=spec('beta');config.projects.push({root:path.join(root,'B'),profile:'beta'});write();
 const recovered=await c.request('session/new',{cwd:path.join(root,'B')});
 // The added agent must satisfy the capabilities advertised by the existing router.
 rejected(recovered); // beta does not support alpha's advertised images
 await c.close();
 config={projects:[{root:path.join(root,'A'),profile:'alpha'},{root:path.join(root,'B'),profile:'beta'}],profiles:{alpha:spec('alpha'),beta:spec('beta')}};write();
 c=client();ok(await c.request('initialize',{protocolVersion:1}));
 const crashed=ok(await c.request('session/new',{cwd:path.join(root,'A')})).sessionId;
 rejected(await c.request('session/crash',{sessionId:crashed}));
 const healthy=ok(await c.request('session/new',{cwd:path.join(root,'B')})).sessionId;
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:healthy})).profile,'beta');
 await c.close();
 c=client({AIAPS_TEST_ACCOUNT:'original'});ok(await c.request('initialize',{protocolVersion:1}));
 const inherited=ok(await c.request('session/new',{cwd:path.join(root,'A')})).sessionId;await c.close();
 c=client({AIAPS_TEST_ACCOUNT:'changed'});ok(await c.request('initialize',{protocolVersion:1}));
 rejected(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:inherited}));await c.close();
 config.profiles.alpha.inheritEnvironment=false;write();
 c=client({AIAPS_TEST_ACCOUNT:'original'});ok(await c.request('initialize',{protocolVersion:1}));
 const isolated=ok(await c.request('session/new',{cwd:path.join(root,'A')})).sessionId;await c.close();
 c=client({AIAPS_TEST_ACCOUNT:'changed'});ok(await c.request('initialize',{protocolVersion:1}));
 assert.strictEqual(ok(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:isolated})).profile,'alpha');await c.close();
 config={projects:[{root:path.join(root,'A'),profile:'gamma'}],profiles:{gamma:spec('gamma',{RESUME:'yes'})}};write();
 c=client();const resumeInit=ok(await c.request('initialize',{protocolVersion:1}));
 assert.deepStrictEqual(resumeInit.agentCapabilities.sessionCapabilities.resume,{});
 config.profiles.beta=spec('beta');config.projects.push({root:path.join(root,'B'),profile:'beta'});write();
 rejected(await c.request('session/new',{cwd:path.join(root,'B')})); // {} advertises support; absent means unsupported
 await c.close();
 config={projects:[],profiles:{}};write();
 c=client();assert.strictEqual(ok(await c.request('initialize',{protocolVersion:2})).protocolVersion,1);
 const emptySetup=ok(await c.request('session/new',{cwd:path.join(root,'A')})).sessionId;
 assert(emptySetup.startsWith('aiaps-setup-'));
 config={projects:[{root:path.join(root,'A'),profile:'alpha'}],profiles:{alpha:spec('alpha')}};write();
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:emptySetup})).stopReason,'end_turn');
 ok(await c.request('session/new',{cwd:path.join(root,'A')}));await c.close();
 // Local setup sessions remain local after assignment and across process restarts.
 const trace=path.join(root,'requests.log');
 config.profiles.alpha.env.TRACE=trace;write();
 c=client();ok(await c.request('initialize',{protocolVersion:1}));
 rejected(await c.request('session/load',{cwd:path.join(root,'B'),sessionId:emptySetup}));
 ok(await c.request('session/load',{cwd:path.join(root,'A'),sessionId:emptySetup}));
 ok(await c.request('session/resume',{cwd:path.join(root,'A'),sessionId:emptySetup}));
 ok(await c.request('session/set_model',{sessionId:emptySetup,modelId:'previously-selected'}));
 ok(await c.request('session/set_mode',{sessionId:emptySetup,modeId:'previously-selected'}));
 assert.deepStrictEqual(ok(await c.request('session/set_config_option',{sessionId:emptySetup,configId:'reasoning',value:'medium'})).configOptions,[]);
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:emptySetup,prompt:[{type:'text',text:'must never reach alpha'}]})).stopReason,'end_turn');
 c.send({method:'session/cancel',params:{sessionId:emptySetup}});
 await c.close();
 assert.deepStrictEqual(fs.readFileSync(trace,'utf8').trim().split('\n').map(line=>JSON.parse(line).method),['initialize']);
 config={projects:[{root:path.join(root,'A'),profile:'alpha'},{root:path.join(root,'B'),profile:'beta'}],profiles:{alpha:spec('alpha',{FIXED_ID:'collision'}),beta:spec('beta',{FIXED_ID:'collision'})}};write();
 const first=client(),second=client();
 ok(await first.request('initialize',{protocolVersion:1}));ok(await second.request('initialize',{protocolVersion:1}));
 const results=await Promise.all([first.request('session/new',{cwd:path.join(root,'A')}),second.request('session/new',{cwd:path.join(root,'B')})]);
 assert.strictEqual(results.filter(r=>r.result).length,1);assert.strictEqual(results.filter(r=>r.error).length,1);
 await first.close();await second.close();
 // Authentication is advertised only for the profile assigned to this project.
 const projectA=path.join(root,'auth-project');fs.mkdirSync(projectA);
 const loginScript=path.join(root,'login.js'),loginOutput=path.join(root,'login.json');
 fs.writeFileSync(loginScript,"require('fs').writeFileSync(process.env.LOGIN_OUTPUT,JSON.stringify({home:process.env.CODEX_HOME,args:process.argv.slice(2)}));");
 config={projects:[{root:projectA,profile:'alpha'}],profiles:{
  alpha:{...spec('alpha',{AUTH:'yes',LOGIN_SCRIPT:loginScript,LOGIN_OUTPUT:loginOutput,CODEX_HOME:path.join(root,'personal')}),name:'Codex Personal'},
  beta:spec('beta',{AUTH:'yes',LOGIN_SCRIPT:loginScript})}};write();
 c=client({},projectA);
 const authInit=ok(await c.request('initialize',{protocolVersion:1}));
 assert.strictEqual(authInit.authMethods.length,2);
 assert(authInit.authMethods.every(m=>m.name.startsWith('Codex Personal:')));
 assert.deepStrictEqual(authInit.authMethods[0]._meta,{vendor:{flag:true}});
 assert.strictEqual(ok(await c.request('authenticate',{methodId:authInit.authMethods[0].id})).profile,'alpha');
 rejected(await c.request('authenticate',{methodId:'login'})); // original/unadvertised IDs cannot select accounts
 const terminal=authInit.authMethods[1]._meta['terminal-auth'];
 assert(!('command' in terminal)); // native handler must retain the router script in args
 const launch=cp.spawnSync(process.execPath,terminal.args,{cwd:projectA,env:{...process.env,...terminal.env},encoding:'utf8'});
 assert.strictEqual(launch.status,0,launch.stderr);
 assert.deepStrictEqual(JSON.parse(fs.readFileSync(loginOutput,'utf8')),{home:path.join(root,'personal'),args:['login']});
 // Native AI Assistant appends terminal args to AI Multi's base args; tolerate that prefix.
 const nativeArgs=[path.join(__dirname,'cps-router.js'),cfg,...terminal.args.slice(1)];
 assert.strictEqual(cp.spawnSync(process.execPath,nativeArgs,{cwd:projectA,env:{...process.env,...terminal.env}}).status,0);
 config.projects[0].profile='beta';write();
 rejected(await c.request('authenticate',{methodId:authInit.authMethods[0].id}));
 assert.notStrictEqual(cp.spawnSync(process.execPath,terminal.args,{cwd:projectA,env:{...process.env,...terminal.env}}).status,0);
 await c.close();
 c=client();assert.deepStrictEqual(ok(await c.request('initialize',{protocolVersion:1})).authMethods,[]);await c.close();
 // Revoke invalidates only the target profile's running sessions, pending calls,
 // stored owners and already-advertised terminal logins. Unrelated profiles stay usable.
 const revisionPath=path.join(root,'auth-revision');
 config={projects:[{root:projectA,profile:'alpha'},{root:path.join(root,'B'),profile:'beta'}],profiles:{
  alpha:{...spec('alpha',{AUTH:'yes',LOGIN_SCRIPT:loginScript,LOGIN_OUTPUT:loginOutput}),authRevisionPath:revisionPath},beta:spec('beta',{IMAGE:'yes'})}};write();
 c=client({},projectA);
 const revokeInit=ok(await c.request('initialize',{protocolVersion:1}));
 const revokedSession=ok(await c.request('session/new',{cwd:projectA})).sessionId;
 const intactSession=ok(await c.request('session/new',{cwd:path.join(root,'B')})).sessionId;
 const waiting=c.request('session/upstream',{sessionId:revokedSession});
 for(let i=0;i<100&&!c.messages.some(m=>m.method==='fs/read_text_file');i++)await new Promise(r=>setTimeout(r,10));
 assert(c.messages.some(m=>m.method==='fs/read_text_file'));
 fs.writeFileSync(revisionPath,'revoked-1');
 assert(rejected(await waiting).message.includes('revoked'));
 assert(rejected(await c.request('session/prompt',{sessionId:revokedSession})).message.includes('revoked'));
 rejected(await c.request('authenticate',{methodId:revokeInit.authMethods[0].id}));
 const staleTerminal=revokeInit.authMethods[1]._meta['terminal-auth'];
 assert.notStrictEqual(cp.spawnSync(process.execPath,staleTerminal.args,{cwd:projectA,env:{...process.env,...staleTerminal.env}}).status,0);
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:intactSession})).profile,'beta');
 const newSession=ok(await c.request('session/new',{cwd:projectA})).sessionId;
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:newSession})).profile,'alpha');
 await c.close();c=client({},projectA);ok(await c.request('initialize',{protocolVersion:1}));
 rejected(await c.request('session/load',{cwd:projectA,sessionId:revokedSession}));
 await c.close();
 // A project-scoped proxy mirrors its native profile, independent of weaker or
 // broken profiles. Native history discovery establishes inactive ownership.
 const proxyProject=path.join(root,'proxy-project');fs.mkdirSync(proxyProject);
 const foreignProject=path.join(root,'foreign-project');fs.mkdirSync(foreignProject);
 const nativeHistory={sessionId:'native-history',cwd:proxyProject,title:'Native title',_meta:{native:true}};
 const foreignHistory={sessionId:beta,cwd:path.join(root,'B'),title:'Must not reassign beta'};
 const backendTrace=path.join(root,'proxy-backend.log');
 const weakerTrace=path.join(root,'weaker-backend.log');
 config={projects:[{root:proxyProject,profile:'alpha'},{root:foreignProject,profile:'beta'}],profiles:{
  alpha:spec('alpha',{TRACE:backendTrace,LIST:JSON.stringify([nativeHistory,foreignHistory])}),
  beta:spec('beta',{TRACE:weakerTrace}),broken:{command:path.join(root,'missing-agent')}}};write();
 c=client({},proxyProject);
 const mirrored=ok(await c.request('initialize',{protocolVersion:1,clientCapabilities:{fs:{readTextFile:true}},_meta:{native:true}}));
 assert.strictEqual(mirrored.agentCapabilities.promptCapabilities.image,true);
 for(const feature of ['list','fork','delete'])assert.deepStrictEqual(mirrored.agentCapabilities.sessionCapabilities[feature],{});
 assert.deepStrictEqual(mirrored.agentCapabilities._meta,{vendor:{flag:true}});
 assert.deepStrictEqual(mirrored._meta,{vendor:{flag:true}});
 assert.strictEqual(mirrored.agentInfo.name,'native-agent');
 assert(!fs.existsSync(weakerTrace)); // unrelated agents are not launched
 const imagePrompt=[{type:'text',text:'Describe this image'},{type:'image',mimeType:'image/png',data:'iVBORw0KGgo=',_meta:{native:true}}];
 const imageSession=ok(await c.request('session/new',{cwd:proxyProject,mcpServers:[],_meta:{native:true}})).sessionId;
 assert.deepStrictEqual(ok(await c.request('session/prompt',{sessionId:imageSession,prompt:imagePrompt,_meta:{native:true}})).prompt,imagePrompt);
 const list=ok(await c.request('session/list',{cwd:proxyProject,cursor:'previous-native-cursor',_meta:{native:true}}));
 assert.deepStrictEqual(list.sessions,[nativeHistory]);
 assert.strictEqual(list.nextCursor,'native-cursor');assert.deepStrictEqual(list._meta,{native:true});
 rejected(await c.request('session/prompt',{sessionId:nativeHistory.sessionId})); // discovered is not active
 rejected(await c.request('session/delete',{sessionId:'unverified-history'}));
 const fork=ok(await c.request('session/fork',{sessionId:nativeHistory.sessionId,cwd:proxyProject,mcpServers:[]}));
 assert.deepStrictEqual(fork.configOptions,[{id:'native-option'}]);
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:fork.sessionId})).profile,'alpha');
 assert.strictEqual(ok(await c.request('session/delete',{sessionId:nativeHistory.sessionId})).profile,'alpha');
 assert.strictEqual(ok(await c.request('session/load',{sessionId:nativeHistory.sessionId,cwd:proxyProject})).profile,'alpha');
 // Conflicting ownership is filtered even in an unfiltered native list.
 assert.deepStrictEqual(ok(await c.request('session/list',{})).sessions,[nativeHistory]);
 const extensionParams={option:'native',_meta:{native:true}};
 assert.deepStrictEqual(ok(await c.request('_native/extension',extensionParams)),{params:extensionParams});
 config.projects[0].profile='beta';write();
 rejected(await c.request('session/list',{}));rejected(await c.request('_native/extension',extensionParams));
 rejected(await c.request('session/new',{cwd:proxyProject})); // reconnect for weaker capabilities
 assert.strictEqual(ok(await c.request('session/prompt',{sessionId:imageSession})).profile,'alpha');
 await c.close();
 // Restart/restoration use discovery's immutable owner, even after reassignment.
 config.projects[0].profile='alpha';write();
 c=client({},proxyProject);ok(await c.request('initialize',{protocolVersion:1}));
 assert.strictEqual(ok(await c.request('session/load',{sessionId:fork.sessionId,cwd:proxyProject})).profile,'alpha');
 await c.close();
 c=client({},foreignProject);
 assert.strictEqual(ok(await c.request('initialize',{protocolVersion:1})).agentCapabilities.promptCapabilities.image,false);
 await c.close();
 const traceMessages=fs.readFileSync(backendTrace,'utf8').trim().split('\n').map(line=>JSON.parse(line));
 assert.deepStrictEqual(traceMessages[0].params.clientCapabilities,{fs:{readTextFile:true}});
 assert.deepStrictEqual(traceMessages.find(m=>m.method==='session/list').params,{cwd:proxyProject,cursor:'previous-native-cursor',_meta:{native:true}});
 if (process.platform === 'win32') {
  const agentRoot = path.join(root, 'acp-agents'), install = path.join(agentRoot, 'codex-acp', '2.2.2');
  const older = path.join(agentRoot, '.runtimes', 'node', '9.0.0'), newer = path.join(agentRoot, '.runtimes', 'node', '24.0.0');
  fs.mkdirSync(path.join(older, 'node_modules', 'npm', 'bin'), {recursive:true});
  fs.mkdirSync(path.join(newer, 'node_modules', 'npm', 'bin'), {recursive:true});
  fs.mkdirSync(install, {recursive:true});
  fs.writeFileSync(path.join(older, 'node.exe'), 'older-runtime');
  fs.copyFileSync(fake, path.join(older, 'node_modules', 'npm', 'bin', 'npx-cli.js'));
  try { fs.linkSync(process.execPath, path.join(newer, 'node.exe')); }
  catch (_) { fs.copyFileSync(process.execPath, path.join(newer, 'node.exe')); }
  fs.copyFileSync(fake, path.join(newer, 'node_modules', 'npm', 'bin', 'npx-cli.js'));
  const project = path.join(root, 'npx-project'); fs.mkdirSync(project);
  config = {projects:[{root:project, profile:'alpha'}], profiles:{alpha:{command:'npx', args:['--no','--','pkg'], cwd:install, env:{PROFILE:'alpha'}}}};
  write();
  c = client({}, project);
  ok(await c.request('initialize', {protocolVersion:1}));
  const npxSession = ok(await c.request('session/new', {cwd:project})).sessionId;
  assert.strictEqual(ok(await c.request('session/prompt', {sessionId:npxSession})).profile, 'alpha');
  await c.close();
 }
 // Upgrade a pre-virtual chat whose provider supports load, but not resume.
 const legacyProject=path.join(root,'legacy-upgrade');fs.mkdirSync(legacyProject);
 const legacyTrace=path.join(root,'legacy-trace.jsonl');
 config={projects:[{root:legacyProject,profile:'alpha'}],profiles:{alpha:spec('alpha',{TRACE:legacyTrace,FIXED_ID:'legacy-owned',LIST:JSON.stringify([{sessionId:'legacy-owned',cwd:legacyProject}])})}};write();
 c=client({},legacyProject);ok(await c.request('initialize',{protocolVersion:1}));
 const legacyId=ok(await c.request('session/new',{cwd:legacyProject})).sessionId;
 await c.close();
 for(const name of fs.readdirSync(path.join(root,'session-owners'))) {
  const file=path.join(root,'session-owners',name),record=JSON.parse(fs.readFileSync(file,'utf8'));
  if(record.sessionId===legacyId){delete record.agentIdentity;fs.writeFileSync(file,JSON.stringify(record));}
 }
 config.virtualSessions=true;write();
 c=client({},legacyProject);
 assert(!ok(await c.request('initialize',{protocolVersion:1})).agentCapabilities.sessionCapabilities.resume);
 assert.equal(ok(await c.request('session/list',{cwd:legacyProject})).sessions[0].sessionId,legacyId);
 assert.equal(rejected(await c.request('session/resume',{cwd:legacyProject,sessionId:legacyId})).code,-32601);
 assert(!c.messages.some(m=>m.params?.update?.content?.text==='LEGACY_REPLAY'));
 rejected(await c.request('session/prompt',{sessionId:legacyId}));
 const legacyCalls=fs.readFileSync(legacyTrace,'utf8').trim().split('\n').map(JSON.parse);
 assert(!legacyCalls.some(m=>m.method==='session/load'));
 assert(legacyCalls.some(m=>m.method==='session/resume' && m.params.sessionId===legacyId));
 assert.equal(legacyCalls.filter(m=>m.method==='session/new').length,1);
 ok(await c.request('session/load',{cwd:legacyProject,sessionId:legacyId}));
 assert(c.messages.some(m=>m.params?.update?.content?.text==='LEGACY_REPLAY'));
 assert.equal(ok(await c.request('session/prompt',{sessionId:legacyId})).profile,'alpha');
 await c.close();
 assert.deepStrictEqual(fs.readFileSync(path.join(__dirname,'cps-router.js')),fs.readFileSync(path.join(__dirname,'../src/main/resources/cps-router.js')));
 console.log('PASS: routing, ownership, native profile capabilities, image payloads, history discovery, fork/delete, extensions, failures, upstream RPC, authentication, revocation, bundled parity');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>{
 for(const proc of children)if(proc.exitCode===null)proc.kill();
 if(path.dirname(root)===os.tmpdir() && path.basename(root).startsWith('aiaps-test-'))fs.rmSync(root,{recursive:true,force:true,maxRetries:10,retryDelay:100});
});
