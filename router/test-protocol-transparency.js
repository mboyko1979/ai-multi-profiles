'use strict';
const fs=require('fs'),path=require('path'),cp=require('child_process'),rl=require('readline'),assert=require('assert/strict');
// Shared transport regressions for every ACP agent and native client.
const base=fs.mkdtempSync(path.join(require('os').tmpdir(),'ai-multi-protocol-')),router=path.join(__dirname,'cps-router.js');
fs.mkdirSync(base,{recursive:true});
const backend=path.join(base,'agent.js');
fs.writeFileSync(backend,String.raw`
const fs=require('fs');let held,upstream;
const send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
require('readline').createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line),p=m.params||{};
 fs.appendFileSync(process.env.TRACE,JSON.stringify({agent:process.env.AGENT,m})+'\n');
 if(m.method==='initialize')return send(process.env.INIT_ERROR?{id:m.id,error:{code:-32077,message:'Native initialization refused',data:{retryAfter:12},_meta:{native:true}}}:{id:m.id,result:{protocolVersion:1,agentInfo:{name:process.env.AGENT},agentCapabilities:{loadSession:true,promptCapabilities:{image:process.env.AGENT==='p'}}}});
 if(m.method==='session/new')return send({id:m.id,result:{sessionId:'native-session-'+process.env.AGENT}});
 if(m.method==='_audit/hold'){held=m;return;}
 if(m.method==='_audit/release'){if(held){send({id:held.id,result:{released:true}});held=null;}return send({id:m.id,result:{}});}
 if(m.method==='$/cancel_request'||m.method==='$/cancelRequest'){if(held&&p.requestId===held.id){send({id:held.id,error:{code:-32800,message:'Cancelled'}});held=null;}return;}
 if(m.method==='_audit/upstream'){
  upstream=m;send({id:'native-upstream',method:'fs/read_text_file',params:{sessionId:p.sessionId,path:'fixture-only.txt'}});
  return send({method:'$/cancel_request',params:{requestId:'native-upstream'}});
 }
 if(!m.method&&m.id==='native-upstream')return send({id:upstream.id,result:{completed:true}});
 if(m.id!==undefined)send({id:m.id,result:{}});
});
`);
const results=[];
async function scenario(name,options,body){
 const dir=path.join(base,name);fs.mkdirSync(dir,{recursive:true});
 const trace=path.join(dir,'trace.jsonl');fs.writeFileSync(trace,'');
 const spec=id=>({agentId:'acp.registry.'+id,command:process.execPath,args:[backend],inheritEnvironment:false,env:{TRACE:trace,AGENT:id,...(options.error?{INIT_ERROR:'1'}:{})}});
 const config={virtualSessions:true,projects:options.noDefault?[]:[{root:dir,profile:'p'}],profiles:{p:spec('p'),...(options.noDefault||options.two?{q:spec('q')}:{})}};
 const file=path.join(dir,'routes.json');fs.writeFileSync(file,JSON.stringify(config));
 const child=cp.spawn(process.execPath,[router,file],{cwd:dir,windowsHide:true});child.stderr.resume();
 const pending=new Map(),messages=[];let serial=0;
 rl.createInterface({input:child.stdout}).on('line',line=>{const m=JSON.parse(line);messages.push(m);pending.get(m.id)?.(m);});
 const send=m=>child.stdin.write(JSON.stringify({jsonrpc:'2.0',...m})+'\n');
 const call=(method,params={})=>{const id='client-'+ ++serial;return {id,promise:new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(Error('Timeout '+name+' '+method)),5000);pending.set(id,m=>{clearTimeout(t);pending.delete(id);resolve(m)});send({id,method,params});})};};
 const events=()=>fs.readFileSync(trace,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
 const until=async predicate=>{for(let i=0;i<500;i++){if(predicate())return;await new Promise(r=>setTimeout(r,10));}throw Error('Probe condition not reached: '+name);};
 try {await body({call,send,events,messages,until,dir});}
 finally {child.stdin.end();await new Promise(resolve=>child.exitCode!==null?resolve():child.once('exit',resolve));}
}
(async()=>{
 await scenario('initialize-error',{error:true},async c=>{
  const reply=await c.call('initialize',{protocolVersion:1}).promise;
  assert.deepEqual(reply.error,{code:-32077,message:'Native initialization refused',data:{retryAfter:12},_meta:{native:true}});
  results.push({finding:'initialize-error-loss',expected:{code:-32077,message:'Native initialization refused',data:{retryAfter:12}},actual:reply.error});
 });
 await scenario('rpc-and-notification',{},async c=>{
  assert((await c.call('initialize',{protocolVersion:1}).promise).result);
  const sid=(await c.call('session/new',{cwd:c.dir,mcpServers:[]}).promise).result.sessionId;
  for(const method of ['$/cancel_request','$/cancelRequest']){
   const held=c.call('_audit/hold',{sessionId:sid});
   await c.until(()=>c.events().some(x=>x.m.method==='_audit/hold'));
   c.send({method,params:{requestId:held.id}});
   await c.until(()=>c.events().some(x=>x.m.method===method));
   const events=c.events(),wireHold=events.filter(x=>x.m.method==='_audit/hold').at(-1).m;
   const cancellation=events.find(x=>x.m.method===method).m;
   assert.equal(cancellation.params.requestId,wireHold.id);
   results.push({finding:'client-rpc-cancellation',method,wireRequestId:wireHold.id,forwardedCancelId:cancellation.params.requestId});
   assert.equal((await held.promise).error.code,-32800);
  }
  const up=c.call('_audit/upstream',{sessionId:sid});
  await c.until(()=>c.messages.some(x=>x.method==='fs/read_text_file')&&c.messages.some(x=>x.method==='$/cancel_request'));
  const request=c.messages.find(x=>x.method==='fs/read_text_file'),cancel=c.messages.find(x=>x.method==='$/cancel_request');
  assert.equal(request.id,cancel.params.requestId);
  results.push({finding:'agent-rpc-cancellation',nativeRequestId:request.id,forwardedCancelId:cancel.params.requestId});
  c.send({id:request.id,error:{code:-32800,message:'Fixture cancelled'}});await up.promise;
  c.send({method:'_audit/notification',params:{sessionId:sid,extension:{preserved:true}}});
  await c.until(()=>c.events().some(x=>x.m.method==='_audit/notification'));
  const notification=c.events().find(x=>x.m.method==='_audit/notification').m;
  assert.equal(notification.id,undefined);
  results.push({finding:'notification-becomes-request',actual:notification});
 });
 await scenario('cancel-original-owner',{two:true},async c=>{
  await c.call('initialize',{protocolVersion:1}).promise;
  const sid=(await c.call('session/new',{cwd:c.dir,mcpServers:[]}).promise).result.sessionId;
  const held=c.call('_audit/hold',{sessionId:sid});
  await c.until(()=>c.events().some(x=>x.m.method==='_audit/hold'));
  const state=(await c.call('_ai_multi/describe',{sessionId:sid}).promise).result;
  const switched=await c.call('_ai_multi/switch',{sessionId:sid,profile:'q',revision:state.revision,fresh:true,retainPrepared:true}).promise;
  assert(switched.result,JSON.stringify(switched));
  c.send({method:'$/cancel_request',params:{requestId:held.id}});
  assert.equal((await held.promise).error.code,-32800);
  const cancellations=c.events().filter(x=>x.m.method==='$/cancel_request');
  assert.equal(cancellations.length,1);assert.equal(cancellations[0].agent,'p');
  c.send({method:'$/cancel_request',params:{requestId:held.id}});
  await c.call('_audit/release',{sessionId:sid}).promise;
  assert.equal(c.events().filter(x=>x.m.method==='$/cancel_request').length,1,'Completed request cancellation escaped to the replacement agent');
 });
 await scenario('no-default',{noDefault:true},async c=>{
  const initialized=await c.call('initialize',{protocolVersion:1}).promise;
  const agents=c.events().filter(x=>x.m.method==='initialize').map(x=>x.agent).sort();
  assert.deepEqual(agents,[]);assert.deepEqual(initialized.result.agentCapabilities,{});
  results.push({finding:'no-default-launches-all-agents',agents,advertised:initialized.result.agentCapabilities});
 });
 fs.writeFileSync(path.join(base,'results.json'),JSON.stringify(results,null,2));
 console.log('PASS: exact initialize errors, bidirectional cancellation IDs and aliases, genuine session notifications, unassigned setup starts no providers and has no aggregate capabilities');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>fs.rmSync(base,{recursive:true,force:true}));
