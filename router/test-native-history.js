'use strict';
// Test-only native-client cache. Production history belongs to AI Assistant/Air.
class NativeHistoryFixture {
 constructor(){this.rows=new Map();this.loading=new Set();this.boundaries=new Set();this.comments=new Map();}
 history(id){return structuredClone((this.rows.get(id)||[]).slice(-20));}
 before(method,params){
  const id=params.sessionId;
  if(['_ai_multi/estimate','_ai_multi/switch'].includes(method)&&params.history===undefined)params={...params,history:this.history(id)};
  if(method==='_ai_multi/switch'&&params.comment)this.comments.set(id,params.comment);
  if(['session/load','session/resume'].includes(method))this.loading.add(id);
  if(['session/prompt','_session/steering'].includes(method)){
   const blocks=params.prompt||[],handoff=blocks.some(x=>x.type==='text'&&x.text.startsWith('AI_MULTI_HANDOFF:'));
   const rows=this.rows.get(id)||[],comment=handoff?this.comments.get(id):null;
   if(!handoff||comment)rows.push({id:'native-'+rows.length,user:structuredClone(comment?[{type:'text',text:comment}]:blocks),updates:[],status:'running'});
   this.comments.delete(id);this.rows.set(id,rows);this.boundaries.add(id);
  }
  return params;
 }
 notification(m){
  if(m.method!=='session/update')return;const id=m.params.sessionId,u=m.params.update;
  if(this.loading.has(id))return;
  const row=this.rows.get(id)?.at(-1);if(!row)return;
  if(u.sessionUpdate==='agent_message_chunk'){row.updates.push({...structuredClone(u),...(this.boundaries.has(id)?{replyBoundary:true}:{})});this.boundaries.delete(id);}
  else if(['tool_call','tool_call_update','plan'].includes(u.sessionUpdate)){row.updates.push(structuredClone(u));this.boundaries.add(id);}
  else if(u.sessionUpdate==='agent_thought_chunk')this.boundaries.add(id);
 }
 complete(method,params,response){
  if(['session/load','session/resume'].includes(method))this.loading.delete(params.sessionId);
  if(method==='_session/steering'&&response?.result?.outcome!=='injected'){this.rows.get(params.sessionId)?.pop();return;}
  if(method==='session/prompt'){
   if((params.prompt||[]).some(x=>x.type==='text'&&x.text.startsWith('AI_MULTI_HANDOFF:')))return;
   const row=this.rows.get(params.sessionId)?.at(-1);
   if(response?.error?.code===-32602 && !response.error.message.includes('Could not save conversation history')){this.rows.get(params.sessionId)?.pop();return;}
   if(row){row.status=response?.error?'error':response?.result?.stopReason==='cancelled'?'cancelled':'complete';
    if(response?.error)row.updates.push({sessionUpdate:'agent_message_chunk',content:{type:'text',text:response.error.message},replyBoundary:true});}
  }
 }
 disconnected(){for(const rows of this.rows.values())for(const row of rows)if(row.status==='running')row.status='interrupted';}
}
module.exports={NativeHistoryFixture};
