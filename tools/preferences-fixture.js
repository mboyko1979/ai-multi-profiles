'use strict';
const fs=require('fs'),send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));let held;
const opts=[{id:'model',name:'Model',category:'model',type:'select',currentValue:'a',options:[{value:'a',name:'A'},{value:'b',name:'B'},{value:'c',name:'C'}]}];
opts.push({id:'collaboration_mode',name:'Collaboration mode',category:'collaboration_mode',type:'select',currentValue:'default',options:[{value:'default',name:'Default'},{value:'plan',name:'Plan'}]});
const stateFile=process.env.TRACE+'.'+process.env.PROFILE+'.state.json';
const save=()=>fs.writeFileSync(stateFile,JSON.stringify(opts));
require('readline').createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line),p=m.params||{},respond=result=>send({id:m.id,result});
 fs.appendFileSync(process.env.TRACE,JSON.stringify({profile:process.env.PROFILE,pid:process.pid,method:m.method,sessionId:p.sessionId,configId:p.configId,value:p.value})+'\n');
 if(m.method==='initialize')return respond({protocolVersion:1,agentCapabilities:{loadSession:true,promptCapabilities:{image:process.env.PROFILE!=='grok'},sessionCapabilities:{close:{}}},_meta:{steering:{supported:process.env.PROFILE!=='grok'},fixtureUnknown:{profile:process.env.PROFILE}}});
 if(m.method==='session/new'){save();return respond({sessionId:require('crypto').randomUUID(),...(process.env.PROFILE==='grok'?{models:{currentModelId:'grok-a',availableModels:[{modelId:'grok-a',name:'Grok A'}]}}:{configOptions:opts})});}
 if(m.method==='session/load'){if(fs.existsSync(stateFile))opts.splice(0,opts.length,...JSON.parse(fs.readFileSync(stateFile,'utf8')));return respond({configOptions:opts});}
 if(m.method==='session/prompt'){
  if(p.prompt?.some(x=>x.text==='hold')){held=m;return;}
  send({method:'session/update',params:{sessionId:p.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'Completed answer'}}}});return respond({stopReason:'end_turn'});
 }
 if(m.method==='session/cancel'){if(held){send({id:held.id,result:{stopReason:'cancelled'}});held=null;}return;}
 if(m.method==='_session/steering'){
  respond({outcome:'startedNewTurn'});
  if(held){send({id:held.id,result:{stopReason:'end_turn'}});held=null;}
  setTimeout(()=>{
   send({method:'session/update',params:{sessionId:p.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'Completed steering answer'}}}});
   send({method:'session/update',params:{sessionId:p.sessionId,update:{sessionUpdate:'session_info_update',_meta:{codex:{threadStatus:{type:'idle'}}}}}});
  },150);return;
 }
 if(m.method==='session/set_config_option'){opts.find(o=>o.id===p.configId).currentValue=p.value;save();return respond({configOptions:opts});}
 respond({});
});
