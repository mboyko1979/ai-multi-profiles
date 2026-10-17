'use strict';
const assert=require('assert/strict'),fs=require('fs'),os=require('os'),path=require('path');
const {VirtualSessions}=require('./virtual-sessions'),settings=require('./profile-settings');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-live-filters-'));
try {
 for(const host of ['assistant','air']) {
  const events=[],api={directory:root,send:event=>events.push(event),settingsSpec:spec=>settings.forClient(spec,{clientInfo:{name:host==='air'?'air-plugin:fixture':'JetBrains fixture'}})};
  const sessions=new VirtualSessions(api);
  const raw={configOptions:[{id:'model',type:'select',category:'model',name:'Model',currentValue:'old',_future:{kept:true},options:[{group:'Family',name:'Family',options:['old','allowed','excluded','new-agent-model'].map(value=>({value,name:value,future:7}))}]},
   {id:'effort',type:'select',currentValue:'high',options:[{value:'high',name:'High'}],future:{kept:8}}]};
  const live={backend:{},segment:{profile:'p',agentIdentity:{agentId:'fixture'}},s:{sessionId:'virtual-chat'},result:structuredClone(raw)};
  sessions.active.set('virtual-chat',live);
  const before={profiles:{p:{agentId:'fixture',clientDefaults:[]}}};
  const after=structuredClone(before);
  after.profiles.p.clientDefaults=[{host,agentId:'fixture',modelOptionId:'model',modelOperation:'config',defaultModelId:'allowed',models:[{modelId:'old',enabled:false},{modelId:'allowed',enabled:true},{modelId:'excluded',enabled:false}]}];
  sessions.refreshModelFilters(before,after);
  assert.equal(events.length,1);
  assert.deepEqual(events[0],{jsonrpc:'2.0',method:'session/update',params:{sessionId:'virtual-chat',update:{sessionUpdate:'config_option_update',configOptions:settings.filterModels(api.settingsSpec(after.profiles.p),raw).configOptions}}});
  assert.deepEqual(events[0].params.update.configOptions[0].options[0].options.map(x=>x.value),['new-agent-model','old','allowed']);
  assert.deepEqual(live.result,raw,'A presentation filter must not destroy the agent catalog or accepted settings');
  sessions.refreshModelFilters(after,after);assert.equal(events.length,1,'No duplicate notification for unchanged filters');
  const defaultsOnly=structuredClone(after);defaultsOnly.profiles.p.clientDefaults[0].defaultModelId='new-agent-model';
  sessions.refreshModelFilters(after,defaultsOnly);assert.equal(events.length,1,'Changing a default must not change this session');
  sessions.refreshModelFilters(after,before);assert.equal(events.length,2);assert.deepEqual(events[1].params.update.configOptions,raw.configOptions,'Re-enabling must restore real agent choices');
  const replacement=structuredClone(after);replacement.profiles.p.agentId='replacement';
  sessions.refreshModelFilters(before,replacement);assert.equal(events.length,2,'Do not apply replacement-agent policy to the old session');
  const otherClient=structuredClone(after);otherClient.profiles.p.clientDefaults[0].host=host==='air'?'assistant':'air';
  sessions.refreshModelFilters(before,otherClient);assert.equal(events.length,2,'Client policies must remain independent');
  live.backend=null;sessions.refreshModelFilters(before,after);assert.equal(events.length,2,'Do not publish readiness or acquire an offline session');
  sessions.closed=true;sessions.refreshModelFilters(before,after);assert.equal(events.length,2);
 }
 console.log('PASS: live filters for Assistant/Air preserve accepted model, raw agent catalog, grouped unknown fields, independent clients and agent identity; re-inclusion works without backend calls');
} finally {fs.rmSync(root,{recursive:true,force:true});}
