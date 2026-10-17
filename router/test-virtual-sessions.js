'use strict';
const fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process'),readline=require('readline'),assert=require('assert'),crypto=require('crypto');
const {VirtualSessions}=require('./virtual-sessions');
// Ten medium exchanges are Medium; the same count of much shorter/longer
// exchanges must still be Low/Extra High. Include Cyrillic and public actions.
const meter=Object.create(VirtualSessions.prototype);
meter.profile=()=>({});
meter.agentChanged=()=>false;
const sample=n=>({segments:[],exchanges:Array.from({length:10},()=>({user:[{type:'text',text:'Проверь поведение. '.repeat(n)}],updates:[{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'Результат проверки и пояснение. '.repeat(n)}},{sessionUpdate:'tool_call',title:'Run checks',status:'completed'}],status:'complete'}))});
const medium=meter.estimate(sample(35),{profile:'test',exchanges:10});
assert(medium.estimatedTokens/medium.budget>0.25 && medium.estimatedTokens/medium.budget<=0.5);
assert(meter.estimate(sample(5),{profile:'test',exchanges:10}).estimatedTokens/medium.budget<=0.25);
assert(meter.estimate(sample(200),{profile:'test',exchanges:10}).estimatedTokens/medium.budget>0.75);
assert.throws(()=>meter.checkPrompt({segments:[{profile:'test',contextWindow:9000}]},'test',[{type:'text',text:'x'.repeat(2500)}]),/too large/);
meter.checkPrompt({segments:[]},'test',[{type:'image',data:'x'.repeat(2000000),mimeType:'image/png'}]);
const fullQuestion='FULL_USER_QUESTION_'+ 'я'.repeat(6000);
const fullAnswer='START_OF_ANSWER_'+ 'middle detail '.repeat(1000)+'END_OF_ANSWER';
const compactState={segments:[],exchanges:[{user:[{type:'text',text:fullQuestion}],updates:[{sessionUpdate:'agent_message_chunk',content:{type:'text',text:fullAnswer}}],status:'complete'}]};
const originalCompactState=JSON.stringify(compactState);
const compact=meter.handoff(compactState,5,'Continue');
const compactTranscript=JSON.parse(compact.text.split('Previous exchanges in chronological order:\n')[1].split('\nContinuation instructions:')[0]);
assert.equal(compactTranscript[0].user,fullQuestion);
assert(compactTranscript[0].assistant.startsWith('START_OF_ANSWER_'));
assert(compactTranscript[0].assistant.endsWith('END_OF_ANSWER'));
assert.equal(compactTranscript[0].assistant,fullAnswer);
assert.equal(compact.shortenedReplies,0);
assert.equal(JSON.stringify(compactState),originalCompactState); // the complete original chat remains intact
assert.equal(meter.compactReply('A short reply').text,'A short reply');
assert.equal(meter.compactReply('A short reply').shortened,false);
const unicodeExcerpt=meter.compactReply('я'.repeat(499)+'😀'+'я'.repeat(800)+'😀'+'я'.repeat(1499));
assert.equal(Buffer.from(unicodeExcerpt.text,'utf8').toString('utf8'),unicodeExcerpt.text);
const chunk=text=>({sessionUpdate:'agent_message_chunk',content:{type:'text',text}});
const finalAnswer='FINAL_START '+ 'complete result '.repeat(1000)+' FINAL_END';
const bounded=[chunk('I understand the task.'),{sessionUpdate:'tool_call'},chunk('Intermediate progress.'),
  {sessionUpdate:'tool_call_update'},chunk(finalAnswer.slice(0,4000)),chunk(finalAnswer.slice(4000))];
const excerpt=meter.transferReply(bounded,x=>x.text);
assert(excerpt.text.startsWith('I understand the task.'));
assert(excerpt.text.endsWith(finalAnswer));assert(!excerpt.text.includes('Intermediate progress.'));
assert(excerpt.shortened);
assert.equal(meter.transferReply([chunk(fullAnswer)],x=>x.text).text,fullAnswer);
assert.equal(meter.transferReply([chunk('Start'),{sessionUpdate:'usage_update'},chunk('Middle'),{sessionUpdate:'config_option_update'},chunk('End')],x=>x.text).text,'StartMiddleEnd');
assert.equal(meter.transferReply([chunk('First'),{...chunk('Middle'),replyBoundary:true},{...chunk(finalAnswer),replyBoundary:true}],x=>x.text).text,
  'First\n[AI Multi: intermediate agent updates omitted from transfer.]\n'+finalAnswer);
// Links anywhere in omitted public replies survive, including chunk splits,
// spaces, Unicode, balanced/escaped parentheses, reference links and file URIs.
const fileLinks=['[verification.md](C:/Projects/ai-multi-profiles/docs/verification.md:12)',
  '[My report](<C:/Projects/My Project/report (draft).md:3>)',
  '[Nested [label]](/tmp/result(one(two)).md)',
  '[Escaped](/tmp/result\\(draft\\).md)',
  '[Unicode](/tmp/результат.md "file title")','<file:///tmp/result.pdf>',
  'file:///tmp/literal.txt','[source](https://example.test/source)'];
const linkMiddle='OMITTED_PROSE_BEFORE '+fileLinks.join(' OMITTED_PROSE_BETWEEN ')+' OMITTED_PROSE_AFTER';
const linkUpdates=[chunk('FIRST_PUBLIC'),{sessionUpdate:'tool_call'},chunk(linkMiddle.slice(0,45)),chunk(linkMiddle.slice(45)),
  {sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'[PRIVATE_LINK](file:///private.txt)'}},chunk('FINAL_PUBLIC')];
const linkTransfer=meter.transferReply(linkUpdates,x=>x.text);
for(const link of fileLinks)assert(linkTransfer.text.includes(link),link);
assert(linkTransfer.text.startsWith('FIRST_PUBLIC'));assert(linkTransfer.text.endsWith('FINAL_PUBLIC'));
assert(!linkTransfer.text.includes('OMITTED_PROSE'));assert(!linkTransfer.text.includes('PRIVATE_LINK'));
assert.equal((linkTransfer.text.match(/\[verification\.md\]/g)||[]).length,1);
assert.deepEqual(meter.replyLinks(fileLinks[0]+' '+fileLinks[0]),[fileLinks[0]]);
const referenceReply=meter.transferReply([chunk('Start\n[report]: </tmp/My report.md> "Report"'),
  {...chunk('Discard prose [report] and [details][report].'),replyBoundary:true},{...chunk('End'),replyBoundary:true}],x=>x.text);
assert(referenceReply.text.includes('[details][report]\n[report]: </tmp/My report.md> "Report"'));
assert(!referenceReply.text.includes('Discard prose'));
assert.deepEqual(meter.replyLinks('[payload](data:text/plain;base64,PRIVATE_CONTENT)'),[]);
const longLink='[long](</tmp/'+ 'x'.repeat(2000)+'.md>)';
assert(meter.transferReply([chunk('Start'),{...chunk(longLink),replyBoundary:true},{...chunk('End'),replyBoundary:true}],x=>x.text).text.includes(longLink));
const linkState={segments:[],exchanges:[{user:[{type:'text',text:'Check links'}],updates:linkUpdates,status:'complete'}]};
const linkStateBefore=JSON.stringify(linkState),linkHandoff=meter.handoff(linkState,1,'',{attachmentIds:[]});
const transferredLinks=JSON.parse(linkHandoff.text.split('Previous exchanges in chronological order:\n')[1].split('\nContinuation instructions:')[0])[0].assistant;
for(const link of fileLinks)assert(transferredLinks.includes(link),link);
assert.equal(linkHandoff.attachments.length,0);assert(linkHandoff.prompt.every(x=>x.type==='text'));
assert.equal(JSON.stringify(linkState),linkStateBefore);assert(!meter.handoff(linkState,0,'').text.includes('verification.md'));
const manyLinks=Array.from({length:50},(_,i)=>'[file '+i+'](/tmp/file-'+i+'.md)');
const manyTransferred=meter.transferReply([chunk('Start'),{...chunk(manyLinks.join(' ')),replyBoundary:true},{...chunk('End'),replyBoundary:true}],x=>x.text);
assert(manyLinks.every(link=>manyTransferred.text.includes(link))); // no tool-action count limit or path clipping
const oversizedLinkState={segments:[],exchanges:[{user:[{type:'text',text:'Check'}],updates:[chunk('Start'),
  {...chunk('[large](</tmp/'+ 'x'.repeat(100000)+'.md>)'),replyBoundary:true},{...chunk('End'),replyBoundary:true}],status:'complete'}]};
const oversizedLinkTransfer=meter.handoff(oversizedLinkState,1,'');
assert.throws(()=>meter.checkPrompt(oversizedLinkState,'test',oversizedLinkTransfer.prompt),/too large/);
const steered={segments:[],exchanges:[{user:[{type:'text',text:'Message 1'}],steeringMessages:[[{type:'text',text:'Message 2'}],[{type:'text',text:'Message 3'}]],updates:[chunk(finalAnswer)],status:'complete'}]};
assert.equal(meter.handoff(steered,1,'').messages,1);
assert(!meter.handoff(steered,1,'').text.includes('Message 2'));
assert(meter.handoff(steered,2,'').text.includes('Message 2'));
assert(!meter.handoff(steered,2,'').text.includes('Message 1'));
assert(meter.handoff(steered,3,'').text.includes('Message 1'));
assert.equal(meter.handoff(steered,20,'').messages,3);
assert.equal(meter.handoff(steered,20,'').availableMessages,3);
// Streaming thoughts are excluded, but their boundaries must survive persistence.
const streaming=Object.create(VirtualSessions.prototype),streamState={sessionId:'virtual-test',segments:[]};
const streamBackend={virtual:{state:streamState,segment:{id:'segment-test',sessionId:'backend-test'}}};
const streamTurn={updates:[]},streamLive={backend:streamBackend,turn:streamTurn};
streaming.active=new Map([['virtual-test',streamLive]]);streaming.api={config:()=>({profiles:{}})};streaming.save=()=>{};
for(const update of [chunk('Task understanding'),{sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'PRIVATE_REASONING'}},chunk('Intermediate progress'),
  {sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'MORE_PRIVATE_REASONING'}},chunk(finalAnswer.slice(0,4000)),{sessionUpdate:'usage_update',size:32768},chunk(finalAnswer.slice(4000))])
 streaming.fromBackend(streamBackend,{method:'session/update',params:{sessionId:'backend-test',update}});
assert.deepEqual(streamTurn.updates,[]);assert(!JSON.stringify(streamTurn).includes('PRIVATE_REASONING'));
const image={type:'image',data:'IMAGE_PAYLOAD',mimeType:'image/png',uri:'file:///screenshot.png'};
const audio={type:'audio',data:'AUDIO_PAYLOAD',mimeType:'audio/wav'};
const resource={type:'resource',resource:{uri:'file:///notes.txt',text:'PRIVATE_FILE_CONTENT '.repeat(2000),mimeType:'text/plain'}};
const link={type:'resource_link',uri:'file:///result.pdf',name:'result.pdf',mimeType:'application/pdf'};
const attachmentState={segments:[],exchanges:[{id:'first',user:[{type:'text',text:'Analyze attachments'},image,audio,resource],updates:[chunk('Here is the result'),{sessionUpdate:'agent_message_chunk',content:link}],status:'complete'},
  {id:'second',user:[{type:'text',text:'Follow-up'}],steeringMessages:[[{type:'text',text:'Steering'},image]],updates:[],status:'complete'}]};
const allCaps={image:true,audio:true,embeddedContext:true};
// Native custom image attachments arrive with a generated temp name, not the
// original filename. Give them a readable label without changing their URI.
const tempName='ai-chat-custom-attachment-temp-file-4ec3af80-d0b6-4639-a1d2-2befb824109e-123.png';
const tempBlock={type:'resource_link',name:tempName,uri:'file:///C:/Temp/'+tempName};
const namesState={segments:[],exchanges:[{id:'names',user:[tempBlock,{...tempBlock,title:'sample-A1.png'},
  {type:'resource_link',uri:'file:///C:/Temp/%D1%84%D0%B0%D0%B9%D0%BB.wav'},
  {type:'audio',mimeType:'audio/wav',data:'WAVE',name:'sample-A1.wav'}],updates:[],status:'complete'}]};
const namesSnapshot=JSON.stringify(namesState);
const namesTransfer=meter.handoff(namesState,1,'',{capabilities:allCaps});
assert.equal(namesTransfer.attachments[0].name,'Image (PNG) · attachment 1');
assert.equal(namesTransfer.attachments[0].sourceName,tempName);
assert.equal(namesTransfer.attachments[0].originalNameKnown,false);
assert.equal(namesTransfer.attachments[1].name,'sample-A1.png');
assert.equal(namesTransfer.attachments[2].name,'файл.wav');
assert.equal(namesTransfer.attachments[3].name,'sample-A1.wav');
assert.deepEqual(namesTransfer.prompt.find(x=>x.type==='resource_link'),tempBlock);
assert.equal(JSON.stringify(namesState),namesSnapshot);
meter.api={config:()=>({profiles:{}})};
assert.equal(meter.content({segments:[]},namesState.exchanges[0].user[3]).name,'sample-A1.wav');
assert.equal(meter.content({segments:[]},{type:'resource',name:'outer.txt',resource:{uri:'file:///tmp/file',title:'inner.txt',text:'text'}}).resource.title,'inner.txt');
const attachmentSnapshot=JSON.stringify(attachmentState);
const attachmentTransfer=meter.handoff(attachmentState,3,'',{capabilities:allCaps});
assert.equal(attachmentTransfer.attachments.length,5);
assert(attachmentTransfer.attachments.every(x=>x.selected));
assert.equal(attachmentTransfer.prompt.filter(x=>x.type==='image').length,2); // distinct occurrences retain their origin
assert(attachmentTransfer.prompt.some(x=>x.type==='audio' && x.data==='AUDIO_PAYLOAD'));
assert(attachmentTransfer.prompt.some(x=>x.type==='resource' && x.resource.text===resource.resource.text));
assert(attachmentTransfer.prompt.some(x=>x.type==='resource_link' && x.name==='result.pdf'));
assert(attachmentTransfer.prompt.some(x=>x.text?.includes('from assistant in original exchange 1')));
assert(!JSON.stringify(attachmentTransfer.attachments).includes('IMAGE_PAYLOAD')); // checklist never contains payloads
const clearAttachments=meter.handoff(attachmentState,3,'',{capabilities:allCaps,attachmentIds:[]});
assert(clearAttachments.prompt.every(x=>x.type==='text'));assert(!clearAttachments.text.includes('PRIVATE_FILE_CONTENT'));
assert(clearAttachments.estimatedTokens<attachmentTransfer.estimatedTokens);
const onlyLink=meter.handoff(attachmentState,3,'',{capabilities:allCaps,attachmentIds:['first:reply:1']});
assert.deepEqual(onlyLink.prompt.filter(x=>x.type!=='text'),[link]);assert(!JSON.stringify(onlyLink.prompt).includes('PRIVATE_FILE_CONTENT'));
const textOnlyAttachments=meter.handoff(attachmentState,3,'',{capabilities:{}});
assert(textOnlyAttachments.attachments.filter(x=>x.type==='image').every(x=>!x.supported && !x.selected));
assert(textOnlyAttachments.attachments.filter(x=>x.type==='resource_link').every(x=>x.selected));
assert.throws(()=>meter.handoff(attachmentState,3,'',{capabilities:{},attachmentIds:['first:user:1']}),/unsupported/);
assert.throws(()=>meter.handoff(attachmentState,1,'',{capabilities:allCaps,attachmentIds:['first:user:1']}),/selection changed/);
assert.throws(()=>meter.handoff(attachmentState,3,'',{capabilities:allCaps,attachmentIds:'all'}),/Invalid attachment/);
assert.equal(meter.handoff(attachmentState,0,'',{capabilities:allCaps}).attachments.length,0);
assert.equal(JSON.stringify(attachmentState),attachmentSnapshot);
assert(!meter.attachmentSupport({type:'video'},allCaps).supported);
const verboseActions=Array.from({length:100},(_,i)=>[
 {sessionUpdate:'tool_call',toolCallId:'action-'+i,title:'COMMAND_'+i+' '+ 'LONG_COMMAND '.repeat(1000),kind:'execute',status:'pending',locations:[{path:'x'.repeat(2000),line:i}]},
 {sessionUpdate:'tool_call_update',toolCallId:'action-'+i,status:'completed'}]).flat();
verboseActions.push({sessionUpdate:'plan',entries:Array.from({length:20},()=>({content:'p'.repeat(2000),priority:'medium',status:'pending'}))});
const originalActions=JSON.stringify(verboseActions),actionExcerpt=meter.compactActions(verboseActions);
assert.equal(actionExcerpt.actions.length,21);assert.equal(actionExcerpt.omitted,95);
assert(actionExcerpt.actions[0].title.startsWith('COMMAND_80'));
assert(actionExcerpt.actions.slice(0,20).every(x=>x.status==='completed' && x.title.length<=201 && x.locations[0].path.length<=161));
assert.equal(actionExcerpt.actions.at(-1).entries.length,5);
assert.equal(JSON.stringify(verboseActions),originalActions);
assert(Buffer.byteLength(JSON.stringify(actionExcerpt.actions),'utf8')<12000);
const root=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-handoff-')),project=path.join(root,'project'),other=path.join(root,'other');
const storageFault=path.join(root,'storage-fault'),preload=path.join(root,'storage-fault.js');
fs.writeFileSync(preload,`const fs=require('fs'),rename=fs.renameSync;fs.renameSync=function(a,b){if(String(b).includes('conversations')&&fs.existsSync(${JSON.stringify(storageFault)}))throw Object.assign(Error('simulated Windows sharing violation'),{code:'EPERM'});return rename.apply(this,arguments);};`);
fs.mkdirSync(project);fs.mkdirSync(other);
const fake=path.join(root,'agent.js'),configPath=path.join(root,'routes.json'),trace=path.join(root,'trace.jsonl');
fs.writeFileSync(path.join(project,'uncommitted.txt'),'preserve this change');
const linkedFile=path.join(project,'linked report (draft).md'),wireLink='[linked report](<'+linkedFile+'>)';
fs.writeFileSync(linkedFile,'FILE_CONTENT_MUST_NOT_BE_EMBEDDED');
const replyFile=path.join(root,'agent-output.txt');fs.writeFileSync(replyFile,'GENERATED_AGENT_FILE');
const replyWave=Buffer.alloc(44+320);replyWave.write('RIFF');replyWave.writeUInt32LE(replyWave.length-8,4);
replyWave.write('WAVEfmt ',8);replyWave.writeUInt32LE(16,16);replyWave.writeUInt16LE(1,20);replyWave.writeUInt16LE(1,22);
replyWave.writeUInt32LE(16000,24);replyWave.writeUInt32LE(32000,28);replyWave.writeUInt16LE(2,32);replyWave.writeUInt16LE(16,34);
replyWave.write('data',36);replyWave.writeUInt32LE(320,40);
const agentReplyBlocks=[
 {type:'image',mimeType:'image/png',data:'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII='},
 {type:'audio',mimeType:'audio/wav',data:replyWave.toString('base64')},
 {type:'resource',resource:{uri:'file:///agent-note.txt',mimeType:'text/plain',text:'AGENT_EMBEDDED_RESOURCE'}},
 {type:'resource_link',uri:require('url').pathToFileURL(replyFile).href,name:'agent-output.txt',mimeType:'text/plain'}];
fs.writeFileSync(fake, `
const fs=require('fs'),rl=require('readline').createInterface({input:process.stdin});
const profile=process.env.PROFILE;let held;
const send=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
// This fixture owns persistence; the proxy must neither create nor reapply preferences.
const preferenceFile=process.env.TRACE+'.preference-'+profile;
const sessionFile=id=>process.env.TRACE+'.settings-'+id;
const defaultEffort=()=>fs.existsSync(preferenceFile)?fs.readFileSync(preferenceFile,'utf8'):'low';
const effort=id=>fs.existsSync(sessionFile(id))?fs.readFileSync(sessionFile(id),'utf8'):defaultEffort();
const options=value=>[{id:'reasoning_effort',name:'Reasoning effort',category:'thought_level',type:'select',currentValue:value,options:['low','high'].map(value=>({value,name:value}))}];
rl.on('line',line=>{const m=JSON.parse(line);fs.appendFileSync(process.env.TRACE,JSON.stringify({profile,m})+'\\n');
 if(process.env.AUTH_FILE && ['session/new','session/load','session/resume','session/prompt'].includes(m.method) && !fs.existsSync(process.env.AUTH_FILE))
  return send({id:m.id,error:{code:-32000,message:'Sign in to continue this chat'}});
 if(process.env.AUTH_FILE && m.method==='authenticate'){
  if(fs.existsSync(process.env.AUTH_FILE+'.fail'))return send({id:m.id,error:{code:-32000,message:'Login cancelled'}});
  fs.writeFileSync(process.env.AUTH_FILE,'authenticated');return send({id:m.id,result:{}});
 }
 if(m.method==='initialize')send({id:m.id,result:{protocolVersion:1,_meta:{steering:{supported:profile==='codex'},private:'DO_NOT_STORE_PROVIDER_META'},authMethods:[{id:'login',name:'Sign in'},...(process.env.LOGIN_SCRIPT?[{id:'terminal-login',name:'Terminal sign in',_meta:{'terminal-auth':{command:process.execPath,args:[process.env.LOGIN_SCRIPT]}}}]:[])],agentCapabilities:{loadSession:true,promptCapabilities:{image:profile==='codex',audio:profile==='codex',embeddedContext:true},sessionCapabilities:process.env.NO_RESUME?{list:{}}:{resume:{},list:{}}}}});
 else if(m.method==='session/fork')send({id:m.id,error:{code:-32601,message:'Fork is unsupported by this agent'}});
 else if(m.method==='session/list')send({id:m.id,result:{sessions:fs.readdirSync(require('path').dirname(process.env.TRACE)).filter(x=>x.startsWith(require('path').basename(process.env.TRACE)+'.settings-'+profile+'-')).map(x=>({sessionId:x.slice((require('path').basename(process.env.TRACE)+'.settings-').length),cwd:m.params.cwd}))}});
 else if(m.method==='session/new'){const id=profile+'-'+Math.random();fs.writeFileSync(sessionFile(id),defaultEffort());send({method:'session/update',params:{sessionId:'backend-pending',update:{sessionUpdate:'available_commands_update',availableCommands:[{name:'review',description:'Review changes'}]}}});send({id:m.id,result:{sessionId:id,configOptions:options(effort(id))}});}
 else if(['session/load','session/resume'].includes(m.method)){
  if(m.method==='session/load')send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'PROVIDER_REPLAY_DUPLICATE'}}}});
  send({id:m.id,result:{configOptions:options(effort(m.params.sessionId))}});
 }
 else if(m.method==='session/set_config_option'){
  if(m.params.configId!=='reasoning_effort'||!['low','high'].includes(m.params.value))return send({id:m.id,error:{code:-32602,message:'Invalid option'}});
  fs.writeFileSync(sessionFile(m.params.sessionId),m.params.value);fs.writeFileSync(preferenceFile,m.params.value);
  send({id:m.id,result:{configOptions:options(effort(m.params.sessionId))}});
 }else if(m.method==='_fixture/options'){
  fs.writeFileSync(sessionFile(m.params.sessionId),m.params.value);
  send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'config_option_update',configOptions:options(m.params.value)}}});
  send({id:m.id,result:{}});
 }
 else if(m.method==='session/prompt'){
  const text=(m.params.prompt||[]).map(x=>x.text).join('');
  if(profile!=='codex' && (m.params.prompt||[]).some(x=>x.type==='image'))return send({id:m.id,error:{code:-32602,message:'This agent does not support images'}});
  if((m.params.prompt||[]).some(x=>x.mimeType==='image/x-rejected'))return send({id:m.id,error:{code:-32602,message:'Unsupported image format'}});
  if(text==='quota')return send({id:m.id,error:{code:-32000,message:'Quota exhausted'}});
  if(text==='hold'){held=m;return;}
  if(text==='REPLY_ATTACHMENTS'){
   for(const content of ${JSON.stringify(agentReplyBlocks)})send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content}}});
   return send({id:m.id,result:{stopReason:'end_turn'}});
  }
  if(text==='LINK_TASK'){
   const update=u=>send({method:'session/update',params:{sessionId:m.params.sessionId,update:u}});
   const part=text=>update({sessionUpdate:'agent_message_chunk',content:{type:'text',text}});
   part('LINK_TASK_START');update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'[PRIVATE](file:///private.txt)'}});
   const middle='OMITTED_PROGRESS_BEFORE '+${JSON.stringify(wireLink)}+' OMITTED_PROGRESS_AFTER';
   part(middle.slice(0,40));part(middle.slice(40));update({sessionUpdate:'tool_call',toolCallId:'link-boundary',title:'Finish',status:'completed'});
   part('LINK_TASK_FINAL');return send({id:m.id,result:{stopReason:'end_turn'}});
  }
  if(/^MIXED_LINK_\\d+$/.test(text)){
   const number=+text.split('_').at(-1),extension=['png','wav','mp4','txt','pdf'][(number-1)%5];
   const file=${JSON.stringify(project)}+'/'+profile+'-'+number+'.'+extension;
   fs.writeFileSync(file,'FILE_CONTENT_MUST_NOT_BE_EMBEDDED_'+profile+'_'+number);
   const update=u=>send({method:'session/update',params:{sessionId:m.params.sessionId,update:u}});
   const part=text=>update({sessionUpdate:'agent_message_chunk',content:{type:'text',text}});
   part('MIXED_FIRST_'+number);update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'PRIVATE_MIXED_LINK'}});
   const middle='OMITTED_MIXED_PROSE [created '+profile+' '+number+'](<'+file+'>) OMITTED_MIXED_PROSE';
   part(middle.slice(0,50));part(middle.slice(50));update({sessionUpdate:'tool_call',toolCallId:'mixed-boundary',title:'Finish',status:'completed'});
   part('MIXED_FINAL_'+number);return send({id:m.id,result:{stopReason:'end_turn'}});
  }
  if(text==='storage-failure')fs.writeFileSync(process.env.STORAGE_FAULT,'locked');
  send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'HIDDEN_THOUGHT'}}}});
  send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'tool_call',toolCallId:'tool-1',title:'Check project',kind:'read',status:'completed',rawInput:{secret:'DO_NOT_STORE'},rawOutput:'HUGE_OUTPUT',locations:[{path:'file.txt',line:1}]}}});
  for(const text of [profile+' reply ', 'second part'])send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text}}}});
  if(text==='ATTACHMENT_TASK')send({method:'session/update',params:{sessionId:m.params.sessionId,update:{sessionUpdate:'agent_message_chunk',content:{type:'resource_link',uri:'file:///generated.pdf',name:'generated.pdf',mimeType:'application/pdf'}}}});
  send({id:m.id,result:{stopReason:'end_turn'}});
 }else if(m.method==='_session/steering')send({id:m.id,result:{outcome:(m.params.prompt||[]).some(x=>x.text?.length>100000)?'rejected':held?'injected':'failed'}});
 else if(m.method==='session/cancel'){if(held){send({id:held.id,result:{stopReason:'cancelled'}});held=null;}}
 else if(m.method==='session/upstream'){global.up=m.id;send({id:'permission',method:'session/request_permission',params:{sessionId:m.params.sessionId,toolCall:{toolCallId:'tool-1',title:'test'},options:[]}});}
 else if(m.id==='permission'&&!m.method)send({id:global.up,result:{permission:m.result}});
 else send({id:m.id,result:{}});
 if(m.params?.sessionId)global.sid=m.params.sessionId;
});
`);
const spec=id=>({name:id,command:process.execPath,args:[fake],inheritEnvironment:false,env:{PROFILE:id,TRACE:trace,STORAGE_FAULT:storageFault}});
const config={virtualSessions:true,projects:[{root:project,profile:'codex'},{root:other,profile:'grok'}],profiles:{codex:spec('codex'),grok:spec('grok')}};
const write=()=>fs.writeFileSync(configPath,JSON.stringify(config));write();
const processes=[];
const nativeHistory=new (require('./test-native-history').NativeHistoryFixture)();
function client(cwd=project,injectStorageFault=false){
 const proc=cp.spawn(process.execPath,[...(injectStorageFault?['--require',preload]:[]),path.join(__dirname,'cps-router.js'),configPath],{cwd,stdio:['pipe','pipe','pipe']});processes.push(proc);
 proc.once('exit',()=>nativeHistory.disconnected());
 let serial=0,stderr='';const messages=[],waiters=new Map();
 readline.createInterface({input:proc.stdout}).on('line',line=>{const m=JSON.parse(line);messages.push(m);nativeHistory.notification(m);const w=waiters.get(m.id);if(w){clearTimeout(w.timer);waiters.delete(m.id);w.resolve(m);}});
 proc.stderr.on('data',x=>stderr+=x);
 const send=m=>proc.stdin.write(JSON.stringify({jsonrpc:'2.0',...m})+'\n');
 return {messages,send,proc,request(method,params={}){params=nativeHistory.before(method,params);const id=++serial;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Timeout '+method+' '+stderr)),6000);waiters.set(id,{resolve:m=>{nativeHistory.complete(method,params,m);resolve(m);},timer});send({id,method,params});});},async close(){proc.stdin.end();await new Promise(r=>proc.exitCode!==null?r():proc.once('exit',r));}};
}
const ok=r=>{assert(r.result,JSON.stringify(r));return r.result;},bad=r=>{assert(r.error,JSON.stringify(r));return r.error;};
const prompt=(c,id,text)=>c.request('session/prompt',{sessionId:id,prompt:[{type:'text',text}]});
const traces=()=>fs.readFileSync(trace,'utf8').trim().split('\n').map(JSON.parse);
const records=()=>fs.readdirSync(path.join(root,'conversations')).filter(x=>x.endsWith('.json')).map(x=>JSON.parse(fs.readFileSync(path.join(root,'conversations',x),'utf8')));
async function estimate(c,id,profile,count=10,comment=''){return ok(await c.request('_ai_multi/estimate',{sessionId:id,profile,exchanges:count,comment}));}
async function switchTo(c,id,profile,count=10,comment=''){
 const e=await estimate(c,id,profile,count,comment);
 return ok(await c.request('_ai_multi/switch',{sessionId:id,profile,exchanges:count,comment,consent:true,revision:e.revision}));
}
(async()=>{
 const pending={s:{segments:[],pendingHandoff:{token:'AI_MULTI_HANDOFF:test',text:'x'.repeat(120000)},exchanges:[]},segment:{profile:'test'},busy:false};
 await assert.rejects(()=>meter.prompt(pending,{prompt:[{type:'text',text:pending.s.pendingHandoff.token}]}),/too large/);
 assert(pending.s.pendingHandoff);assert.equal(pending.s.exchanges.length,0);assert.equal(pending.busy,false);
 // Real router failure after the backend starts streaming: retain output and committed JSON,
 // return a storage error, then recover without restarting or contacting a different profile.
 const faulty=client(project,true);ok(await faulty.request('initialize',{protocolVersion:1}));
 const faultyId=ok(await faulty.request('session/new',{cwd:project})).sessionId;
 const failure=bad(await prompt(faulty,faultyId,'storage-failure'));assert(failure.message.includes('Could not save conversation history (EPERM)'));
 assert.equal(faulty.proc.exitCode,null);assert.equal(ok(await faulty.request('_ai_multi/describe',{sessionId:faultyId})).busy,false);
 assert(faulty.messages.some(m=>m.params?.sessionId===faultyId&&m.params.update?.content?.text==='codex reply '));
 assert.equal(records().find(x=>x.sessionId===faultyId).exchanges.at(-1).status,'running');
 assert(fs.readdirSync(path.join(root,'conversations')).some(x=>x.endsWith('.tmp')));
 fs.unlinkSync(storageFault);ok(await prompt(faulty,faultyId,'Recover storage'));
 const recoveredRecord=records().find(x=>x.sessionId===faultyId);assert.equal(recoveredRecord.exchanges[0].status,'error');assert.equal(recoveredRecord.exchanges.at(-1).status,'complete');
 assert(!fs.readdirSync(path.join(root,'conversations')).some(x=>x.endsWith('.tmp')));await faulty.close();
 const initParams={protocolVersion:1,_meta:{proxyConfig:{proxies:[{proxy:{headers:{'X-IntelliJ-Proxy-Client-Execution-ID':'00000000-0000-0000-0000-000000000001',proxy_key:'DO_NOT_STORE_PROXY_SECRET'}}}]}}};
 let c=client();assert.equal(ok(await c.request('initialize',initParams))._meta.steering.supported,true);
 const id=ok(await c.request('session/new',{cwd:project,mcpServers:[]})).sessionId;assert(id.startsWith('aiaps-chat-'));
 // Full persisted handoff across two profiles and a router restart. Links stay
 // ordinary history text even with every attachment deselected.
 const linkClient=client();ok(await linkClient.request('initialize',{protocolVersion:1}));
 const linkChat=ok(await linkClient.request('session/new',{cwd:project})).sessionId;
 ok(await prompt(linkClient,linkChat,'LINK_TASK'));
 const linkEstimate=await estimate(linkClient,linkChat,'grok',1);
 assert.equal(linkEstimate.attachments.length,0);assert.equal(linkEstimate.shortenedReplies,1);
 const linkSwitch=ok(await linkClient.request('_ai_multi/switch',{sessionId:linkChat,profile:'grok',exchanges:1,attachmentIds:[],consent:true,revision:linkEstimate.revision}));
 await linkClient.close();
 const linkResumed=client();ok(await linkResumed.request('initialize',{protocolVersion:1}));
 ok(await linkResumed.request('session/resume',{sessionId:linkChat,cwd:project}));
 bad(await prompt(linkResumed,linkChat,linkSwitch.token));
 assert.equal(ok(await linkResumed.request('_ai_multi/describe',{sessionId:linkChat})).profile,'codex');
 const resumedLinkSwitch=await switchTo(linkResumed,linkChat,'grok',1);
 ok(await prompt(linkResumed,linkChat,resumedLinkSwitch.token));
 const linkPrompt=traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt').at(-1).m.params.prompt;
 assert(linkPrompt.every(x=>x.type==='text'));
 const linkHistory=JSON.parse(linkPrompt[0].text.split('Previous exchanges in chronological order:\n')[1].split('\nContinuation instructions:')[0]);
 assert(linkHistory[0].assistant.includes(wireLink));assert(linkHistory[0].assistant.endsWith('LINK_TASK_FINAL'));
 assert(!JSON.stringify(linkPrompt).includes('OMITTED_PROGRESS'));assert(!JSON.stringify(linkPrompt).includes('FILE_CONTENT_MUST_NOT_BE_EMBEDDED'));
 assert(!JSON.stringify(linkPrompt).includes('PRIVATE'));assert.equal(fs.readFileSync(linkedFile,'utf8'),'FILE_CONTENT_MUST_NOT_BE_EMBEDDED');
 const backWithLinks=await switchTo(linkResumed,linkChat,'codex',1);ok(await prompt(linkResumed,linkChat,backWithLinks.token));
 const backPrompt=traces().filter(x=>x.profile==='codex' && x.m.method==='session/prompt').at(-1).m.params.prompt;
 assert(JSON.parse(backPrompt[0].text.split('Previous exchanges in chronological order:\n')[1].split('\nContinuation instructions:')[0])[0].assistant.includes(wireLink));
 assert.equal(JSON.parse(fs.readFileSync(configPath,'utf8')).projects[0].profile,'codex');await linkResumed.close();
 // Twelve chronological messages spanning four backend segments. The last ten
 // must retain references from BOTH profiles, excluding the first two messages.
 // Handoff continuations change segment inside the same logical exchange.
 const mixedClient=client();ok(await mixedClient.request('initialize',{protocolVersion:1}));
 const mixedChat=ok(await mixedClient.request('session/new',{cwd:project})).sessionId;
 const mixedExpected=[];let mixedProfile='codex';
 for(let number=1;number<=12;number++){
  if([4,7,10].includes(number)){
   mixedProfile=mixedProfile==='codex'?'grok':'codex';
   const switchedMixed=await switchTo(mixedClient,mixedChat,mixedProfile,10);ok(await prompt(mixedClient,mixedChat,switchedMixed.token));
  }
  ok(await prompt(mixedClient,mixedChat,'MIXED_LINK_'+number));
  const extension=['png','wav','mp4','txt','pdf'][(number-1)%5],file=project+'/'+mixedProfile+'-'+number+'.'+extension;
  mixedExpected.push({number,profile:mixedProfile,file,link:'[created '+mixedProfile+' '+number+'](<'+file+'>)'});
 }
 const mixedRecord=records().find(x=>x.sessionId===mixedChat);
 assert.equal(mixedRecord.exchanges.length,12);assert.equal(mixedRecord.segments.length,4);
 for(const number of [3,6,9])assert(mixedRecord.exchanges[number-1].continuations.length===1);
 const mixedPreview=await estimate(mixedClient,mixedChat,'codex',10);
 assert.equal(mixedPreview.messages,10);assert.equal(mixedPreview.availableMessages,12);
 assert.equal(mixedPreview.attachments.length,0);
 const mixedSwitch=ok(await mixedClient.request('_ai_multi/switch',{sessionId:mixedChat,profile:'codex',exchanges:10,attachmentIds:[],consent:true,revision:mixedPreview.revision}));
 await mixedClient.close();
 const mixedResumed=client();ok(await mixedResumed.request('initialize',{protocolVersion:1}));
 ok(await mixedResumed.request('session/resume',{sessionId:mixedChat,cwd:project}));
 bad(await prompt(mixedResumed,mixedChat,mixedSwitch.token));
 const confirmedMixed=await switchTo(mixedResumed,mixedChat,'codex',10);
 ok(await prompt(mixedResumed,mixedChat,confirmedMixed.token));
 const mixedPrompt=traces().filter(x=>x.profile==='codex' && x.m.method==='session/prompt').at(-1).m.params.prompt;
 const mixedHistory=JSON.parse(mixedPrompt[0].text.split('Previous exchanges in chronological order:\n')[1].split('\nContinuation instructions:')[0]);
 assert.deepEqual(mixedHistory.map(e=>e.originalExchange),Array.from({length:10},(_,i)=>i+3));
 assert.deepEqual(mixedHistory.map(e=>e.user),Array.from({length:10},(_,i)=>'MIXED_LINK_'+(i+3)));
 for(const item of mixedExpected){
  if(item.number<=2)assert(!mixedHistory.some(e=>e.assistant.includes(item.link)));
  else assert(mixedHistory[item.number-3].assistant.includes(item.link),item.link);
  assert.equal(fs.readFileSync(item.file,'utf8'),'FILE_CONTENT_MUST_NOT_BE_EMBEDDED_'+item.profile+'_'+item.number);
 }
 assert.deepEqual([...new Set(mixedExpected.slice(2).map(e=>e.profile))].sort(),['codex','grok']);
 assert(mixedPrompt.every(x=>x.type==='text'));assert(!JSON.stringify(mixedPrompt).includes('FILE_CONTENT_MUST_NOT_BE_EMBEDDED'));
 assert(!JSON.stringify(mixedPrompt).includes('OMITTED_MIXED_PROSE'));assert(!JSON.stringify(mixedPrompt).includes('PRIVATE_MIXED_LINK'));
 assert.equal(JSON.parse(fs.readFileSync(configPath,'utf8')).projects[0].profile,'codex');await mixedResumed.close();
 // Genuine ACP agent output blocks, rather than links in assistant prose.
 // An agent's INPUT capabilities do not prohibit it from producing media.
 const outputClient=client(other);ok(await outputClient.request('initialize',{protocolVersion:1}));
 const outputChat=ok(await outputClient.request('session/new',{cwd:other})).sessionId;
 ok(await prompt(outputClient,outputChat,'REPLY_ATTACHMENTS'));
 const receivedOutput=outputClient.messages.filter(m=>m.params?.sessionId===outputChat && m.params.update?.sessionUpdate==='agent_message_chunk').map(m=>m.params.update.content);
 assert.deepEqual(receivedOutput,agentReplyBlocks);
 const outputEstimate=await estimate(outputClient,outputChat,'codex',1);
 assert.equal(outputEstimate.attachments.length,4);assert(outputEstimate.attachments.every(x=>x.author==='assistant' && x.selected && x.supported));
 const outputSwitch=await switchTo(outputClient,outputChat,'codex',1);await outputClient.close();
 const outputResumed=client(other);ok(await outputResumed.request('initialize',{protocolVersion:1}));
 ok(await outputResumed.request('session/resume',{sessionId:outputChat,cwd:other}));bad(await prompt(outputResumed,outputChat,outputSwitch.token));
 const confirmedOutput=await switchTo(outputResumed,outputChat,'codex',1);ok(await prompt(outputResumed,outputChat,confirmedOutput.token));
 const transferredOutput=traces().filter(x=>x.profile==='codex' && x.m.method==='session/prompt').at(-1).m.params.prompt.filter(x=>x.type!=='text');
 assert.deepEqual(transferredOutput,agentReplyBlocks);assert.equal(fs.readFileSync(replyFile,'utf8'),'GENERATED_AGENT_FILE');
 assert.equal(JSON.parse(fs.readFileSync(configPath,'utf8')).projects.find(x=>x.root===other).profile,'grok');await outputResumed.close();
 const attachmentClient=client();ok(await attachmentClient.request('initialize',{protocolVersion:1}));
 const attachmentChat=ok(await attachmentClient.request('session/new',{cwd:project})).sessionId;
 assert(bad(await attachmentClient.request('session/prompt',{sessionId:attachmentChat,prompt:[{...image,mimeType:'image/x-rejected'}]})).message.includes('Unsupported image format'));
 assert.equal(attachmentClient.proc.exitCode,null);assert.equal(ok(await attachmentClient.request('_ai_multi/describe',{sessionId:attachmentChat})).busy,false);
 ok(await attachmentClient.request('session/prompt',{sessionId:attachmentChat,prompt:[{type:'text',text:'ATTACHMENT_TASK'},image,audio,{...resource,resource:{...resource.resource,text:'FILE_CONTENT'}}]}));
 const beforeAttachmentPreview=traces().length;
 const attachmentPreview=await estimate(attachmentClient,attachmentChat,'grok',1);
 assert(traces().slice(beforeAttachmentPreview).every(x=>x.m.method==='initialize')); // no summary or model prompt
 assert.equal(attachmentPreview.attachments.length,4);
 assert(attachmentPreview.attachments.filter(x=>['image','audio'].includes(x.type)).every(x=>!x.supported && !x.selected));
 const attachmentIds=attachmentPreview.attachments.filter(x=>x.selected).map(x=>x.id);
 assert.equal(attachmentIds.length,2);
 bad(await attachmentClient.request('_ai_multi/switch',{sessionId:attachmentChat,profile:'grok',exchanges:1,revision:attachmentPreview.revision,consent:true,attachmentIds:[attachmentPreview.attachments.find(x=>x.type==='image').id]}));
 assert.equal(ok(await attachmentClient.request('_ai_multi/describe',{sessionId:attachmentChat})).profile,'codex');
 const attachmentSwitch=ok(await attachmentClient.request('_ai_multi/switch',{sessionId:attachmentChat,profile:'grok',exchanges:1,revision:attachmentPreview.revision,consent:true,attachmentIds}));
 const pendingAttachment=records().find(x=>x.sessionId===attachmentChat).pendingHandoff;
 assert(!pendingAttachment.prompt && !pendingAttachment.text && !pendingAttachment.comment);
 assert(!JSON.stringify(records().find(x=>x.sessionId===attachmentChat)).includes('FILE_CONTENT'));
 await attachmentClient.close();
 // Transfer bytes are not persisted. A new native-client approval is needed after disconnect.
 const resumedAttachments=client();ok(await resumedAttachments.request('initialize',{protocolVersion:1}));
 ok(await resumedAttachments.request('session/resume',{sessionId:attachmentChat,cwd:project}));
 bad(await prompt(resumedAttachments,attachmentChat,attachmentSwitch.token));
 const reopenedPreview=await estimate(resumedAttachments,attachmentChat,'grok',1);
 const reopenedSwitch=ok(await resumedAttachments.request('_ai_multi/switch',{sessionId:attachmentChat,profile:'grok',exchanges:1,revision:reopenedPreview.revision,consent:true,attachmentIds}));
 ok(await prompt(resumedAttachments,attachmentChat,reopenedSwitch.token));
 const sentAttachments=traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt').at(-1).m.params.prompt;
 assert(sentAttachments.some(x=>x.type==='resource' && x.resource.text==='FILE_CONTENT'));
 assert(sentAttachments.some(x=>x.type==='resource_link' && x.name==='generated.pdf'));
 assert(!sentAttachments.some(x=>['image','audio'].includes(x.type)));
 await resumedAttachments.close();
 for(let i=0;i<50 && !c.messages.some(m=>m.params?.sessionId===id&&m.params.update?.sessionUpdate==='available_commands_update');i++)await new Promise(r=>setTimeout(r,10));
 const startup=c.messages.find(m=>m.params?.sessionId===id&&m.params.update?.sessionUpdate==='available_commands_update');
 assert.equal(startup.params.update.availableCommands[0].name,'review');
 assert.equal(records().find(x=>x.sessionId===id).startupUpdates,undefined);
 assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:id})).profile,'codex');
 for(let i=1;i<=12;i++)ok(await prompt(c,id,'Task '+i));
  const before=records().find(x=>x.sessionId===id);assert.equal(before.exchanges.length,12);assert.equal(before.exchanges[0].updates,undefined);
  assert.equal(before.segments[0].author.profileId,'codex');
  assert(before.exchanges.every(x=>!x.user && !x.updates));assert.equal(before.version,2);
 assert(!JSON.stringify(before).includes('HIDDEN_THOUGHT'));assert(!JSON.stringify(before).includes('DO_NOT_STORE'));assert(!JSON.stringify(before).includes('HUGE_OUTPUT'));
 assert(!JSON.stringify(before).includes('DO_NOT_STORE_PROXY_SECRET'));
 const preview=await estimate(c,id,'grok',5);assert.equal(preview.exchanges,5);
 for(let messages=0;messages<=20;messages++)assert.equal((await estimate(c,id,'grok',messages)).exchanges,Math.min(messages,12));
 for(const invalid of [-1,21,1.5,'0'])bad(await c.request('_ai_multi/estimate',{sessionId:id,profile:'grok',exchanges:invalid}));
  const huge=await estimate(c,id,'grok',10,'x'.repeat(120000));assert(huge.overBudget);
  const beforeOversize=traces().length;
  const beforeOversizeState=ok(await c.request('_ai_multi/describe',{sessionId:id}));
  assert(bad(await c.request('_ai_multi/switch',{sessionId:id,profile:'grok',exchanges:10,comment:'x'.repeat(120000),consent:true,revision:huge.revision})).message.includes('too large'));
  assert.deepStrictEqual(ok(await c.request('_ai_multi/describe',{sessionId:id})),beforeOversizeState);
  assert.equal(traces().length,beforeOversize); // An oversized explicit transfer is rejected before creating a backend.
 const passthroughChat=ok(await c.request('session/new',{cwd:project})).sessionId;
 ok(await prompt(c,passthroughChat,'x'.repeat(120000)));
 ok(await c.request('session/prompt',{sessionId:passthroughChat,prompt:[{type:'resource',resource:{uri:'file:///large.txt',text:'\u044f'.repeat(60000)}}]}));
 assert.equal(traces().filter(x=>x.m.method==='session/prompt').at(-1).m.params.prompt[0].resource.text.length,60000);
 const largeChat=ok(await c.request('session/new',{cwd:project})).sessionId;
  const largeSwitch=await switchTo(c,largeChat,'grok',20,'x'.repeat(40000));assert.equal(largeSwitch.supportsSteering,false);
 ok(await prompt(c,largeChat,largeSwitch.token));
  assert(traces().filter(x=>x.profile==='grok'&&x.m.method==='session/prompt').at(-1).m.params.prompt[0].text.length>40000);
 bad(await c.request('_ai_multi/switch',{sessionId:id,profile:'grok',exchanges:5,revision:preview.revision,consent:false}));
 assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:id})).profile,'codex'); // cancel/no consent leaves session unchanged
 const zeroChat=ok(await c.request('session/new',{cwd:project})).sessionId;
 ok(await prompt(c,zeroChat,'PRIVATE_HISTORY_NOT_SELECTED'));
 const zeroSwitch=await switchTo(c,zeroChat,'grok',0);ok(await prompt(c,zeroChat,zeroSwitch.token));
 const zeroText=traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt').at(-1).m.params.prompt[0].text;
 assert(!zeroText.includes('PRIVATE_HISTORY_NOT_SELECTED'));assert(zeroText.includes('No previous history was shared'));
 assert(zeroText.includes('Ask the user what they want to do.'));
 const grokPrompts=traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt').length;
 const unsupportedImage=bad(await c.request('session/prompt',{sessionId:zeroChat,prompt:[{type:'image',data:'test',mimeType:'image/png'}]}));
 assert.equal(unsupportedImage.message,'This agent does not support images');
 assert.equal(traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt').length,grokPrompts+1);
 assert.equal(ok(await c.request('_session/steering',{sessionId:zeroChat,prompt:[{type:'text',text:'not supported'}]})).outcome,'failed');
 const imageSwitch=await switchTo(c,zeroChat,'codex',0);ok(await prompt(c,zeroChat,imageSwitch.token));
 ok(await c.request('session/prompt',{sessionId:zeroChat,prompt:[{type:'image',data:'test',mimeType:'image/png'}]}));
 assert.equal(traces().filter(x=>x.profile==='codex' && x.m.method==='session/prompt').at(-1).m.params.prompt[0].type,'image');
 const switched=await switchTo(c,id,'grok',5,'Continue carefully');ok(await prompt(c,id,switched.token));
 let handoffs=traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt' && x.m.params.prompt[0].text?.includes('Continue carefully'));assert.equal(handoffs.length,1);
 const h=handoffs[0].m.params.prompt[0].text;assert(!h.includes('"Task 7"'));assert(h.includes('Task 8'));assert(h.includes('Task 12'));assert(h.includes('Continue carefully'));assert.equal((h.match(/Task 12/g)||[]).length,1);
 bad(await prompt(c,id,switched.token)); // never resubmit an already consumed continuation
 assert(c.messages.some(m=>m.params?.sessionId===id&&m.params.update?.content?.text==='grok reply '));
 assert(!c.messages.filter(m=>m.method==='session/update').some(m=>m.params.sessionId.startsWith('grok-')));
 const upstream=c.request('session/upstream',{sessionId:id});
 for(let i=0;i<100&&!c.messages.some(m=>m.method==='session/request_permission');i++)await new Promise(r=>setTimeout(r,10));
 const permission=c.messages.find(m=>m.method==='session/request_permission');assert(permission);assert.equal(permission.params.sessionId,id);
 assert(permission.params.toolCall.toolCallId.endsWith(':tool-1'));
 c.send({id:permission.id,result:{outcome:{outcome:'cancelled'}}});assert.equal(ok(await upstream).permission.outcome.outcome,'cancelled');
 assert.equal(JSON.parse(fs.readFileSync(configPath,'utf8')).projects[0].profile,'codex');
 bad(await c.request('session/fork',{sessionId:id,cwd:project}));
 const back=await switchTo(c,id,'codex',10);ok(await prompt(c,id,back.token));assert.equal(records().find(x=>x.sessionId===id).segments.length,3);
 bad(await prompt(c,id,'quota'));
 const codexRequests=traces().filter(x=>x.profile==='codex').length;
 const quota=await switchTo(c,id,'grok',10);ok(await prompt(c,id,quota.token));
 assert.equal(traces().filter(x=>x.profile==='codex').length,codexRequests);assert(traces().at(-1).m.params.prompt[0].text?.includes('Quota exhausted'));
 assert.equal(fs.readFileSync(path.join(project,'uncommitted.txt'),'utf8'),'preserve this change');
 const fresh=ok(await c.request('session/new',{cwd:project})).sessionId;
 const ready=ok(await c.request('_ai_multi/describe',{sessionId:fresh}));
 assert.equal(ready.virtual,true);assert.equal(ready.exchanges,0);assert.equal(ready.busy,false);
 const promptsBefore=traces().filter(x=>x.m.method==='session/prompt').length;
 const defaultsBefore=fs.readFileSync(configPath,'utf8');
 const freshSwitch=ok(await c.request('_ai_multi/switch',{sessionId:fresh,profile:'grok',fresh:true,revision:ready.revision}));
 assert.equal(freshSwitch.supportsSteering,false);
 assert.equal(freshSwitch.token,null);assert.equal(freshSwitch.profile,'grok');
 const selected=ok(await c.request('_ai_multi/describe',{sessionId:fresh}));
 assert.equal(selected.profile,'grok');assert.equal(selected.exchanges,0);assert.equal(selected.busy,false);
 assert(!records().find(x=>x.sessionId===fresh).pendingHandoff);
 bad(await c.request('_ai_multi/switch',{sessionId:fresh,profile:'codex',fresh:true,revision:ready.revision}));
 assert.equal(ok(await c.request('_ai_multi/switch',{sessionId:fresh,profile:'codex',fresh:true,revision:selected.revision})).supportsSteering,true);
 const freshBack=ok(await c.request('_ai_multi/describe',{sessionId:fresh}));
 ok(await c.request('_ai_multi/switch',{sessionId:fresh,profile:'grok',fresh:true,revision:freshBack.revision}));
 assert.equal(traces().filter(x=>x.m.method==='session/prompt').length,promptsBefore);
 assert.equal(fs.readFileSync(configPath,'utf8'),defaultsBefore);
 assert.equal(config.projects[0].profile,'codex');
 ok(await prompt(c,fresh,'New task'));
 assert.equal(traces().filter(x=>x.profile==='grok' && x.m.method==='session/prompt').at(-1).m.params.prompt[0].text,'New task');
 assert.equal(records().find(x=>x.sessionId===fresh).exchanges.length,1);assert.equal(config.projects[0].profile,'codex');
 const started=ok(await c.request('_ai_multi/describe',{sessionId:fresh}));
 bad(await c.request('_ai_multi/switch',{sessionId:fresh,profile:'codex',fresh:true,revision:started.revision}));
 assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:fresh})).profile,'grok');
 const defaultChat=ok(await c.request('session/new',{cwd:project})).sessionId;assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:defaultChat})).profile,'codex');
 // Retain actual empty-draft providers, including accepted settings, through
 // repeated selections. A cache of catalogs/IDs cannot satisfy these assertions.
 const preparedChat=ok(await c.request('session/new',{cwd:project})).sessionId;
 const preparedCodex=records().find(x=>x.sessionId===preparedChat).segments[0].sessionId;
 const preparedStart=traces().length;
 ok(await c.request('session/set_config_option',{sessionId:preparedChat,configId:'reasoning_effort',value:'high'}));
 async function selectPrepared(profile) {
   const state=ok(await c.request('_ai_multi/describe',{sessionId:preparedChat}));
   return ok(await c.request('_ai_multi/switch',{sessionId:preparedChat,profile,fresh:true,retainPrepared:true,revision:state.revision}));
 }
 const preparedGrok=await selectPrepared('grok');
 assert.equal(preparedGrok.initialize.agentCapabilities.promptCapabilities.image,false);
 const preparedGrokId=records().find(x=>x.sessionId===preparedChat).segments.find(x=>x.profile==='grok').sessionId;
 for(const profile of ['codex','grok','codex']) {
   const selected=await selectPrepared(profile);
   assert.equal(selected.session.sessionId,preparedChat);
   assert.equal(selected.initialize.agentCapabilities.promptCapabilities.image,profile==='codex');
   if(profile==='codex')assert.equal(selected.session.configOptions[0].currentValue,'high');
 }
 assert.equal(traces().slice(preparedStart).filter(x=>x.m.method==='session/new').length,1);
 assert(!traces().slice(preparedStart).some(x=>['session/load','session/resume','session/prompt'].includes(x.m.method)));
 assert.equal(records().find(x=>x.sessionId===preparedChat).segments.find(x=>x.profile==='codex').sessionId,preparedCodex);
 assert.equal(records().find(x=>x.sessionId===preparedChat).segments.find(x=>x.profile==='grok').sessionId,preparedGrokId);
 ok(await prompt(c,preparedChat,'First committed task'));
 assert.equal(traces().filter(x=>x.m.method==='session/prompt').at(-1).m.params.sessionId,preparedCodex);
 assert.equal(records().find(x=>x.sessionId===preparedChat).segments.length,1);
 assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:preparedChat})).profile,'codex');
 ok(await c.request('session/set_config_option',{sessionId:preparedChat,configId:'reasoning_effort',value:'low'}));
 const explicitUid='00000000-0000-0000-0000-000000000002',starts=path.join(root,'chat-starts');fs.mkdirSync(starts);
 const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
 const hash=v=>crypto.createHash('sha256').update(JSON.stringify(canonical(v))).digest('hex');
 fs.writeFileSync(path.join(starts,hash(explicitUid)+'.json'),JSON.stringify({chatUid:explicitUid,profile:'grok',cwd:project,specHash:hash(config.profiles.grok),createdAt:Date.now()}));
 const explicit=client(),traceStart=traces().length;
 const explicitInit=ok(await explicit.request('initialize',{protocolVersion:1,_meta:{proxyConfig:{proxies:[{proxy:{headers:{'X-IntelliJ-Proxy-Client-Execution-ID':explicitUid}}}]}}}));
 assert.equal(explicitInit.agentCapabilities.promptCapabilities.image,false); // The bound provider owns its capabilities.
 ok(await explicit.request('_fixture/agent-extension',{_meta:{explicit:true}}));
 assert.equal(traces().at(-1).profile,'grok');
 ok(await explicit.request('authenticate',{methodId:explicitInit.authMethods[0].id})); // Auth follows explicit chat, not Default Profile.
 const explicitChat=ok(await explicit.request('session/new',{cwd:project})).sessionId;
 assert.equal(ok(await explicit.request('_ai_multi/describe',{sessionId:explicitChat})).profile,'grok');
 assert(traces().slice(traceStart).every(x=>x.profile==='grok'));assert.equal(config.projects[0].profile,'codex');
 await explicit.close();
 const cancelledSwitch=await switchTo(c,defaultChat,'grok',5);
 assert(ok(await c.request('_ai_multi/abort_handoff',{sessionId:defaultChat,token:cancelledSwitch.token})).aborted);
 assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:defaultChat})).profile,'codex');
 assert(!ok(await c.request('_ai_multi/abort_handoff',{sessionId:id,token:quota.token})).aborted); // consumed prompts cannot be undone
  const held=prompt(c,defaultChat,'hold');await new Promise(r=>setTimeout(r,60));
  const heldPromptCount=traces().filter(x=>x.m.method==='_session/steering').length;
  assert.equal(ok(await c.request('_session/steering',{sessionId:defaultChat,prompt:[{type:'text',text:'x'.repeat(120000)}]})).outcome,'rejected');
  assert.equal(traces().filter(x=>x.m.method==='_session/steering').length,heldPromptCount+1);
  assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:defaultChat})).busy,true);
 const steering=ok(await c.request('_session/steering',{sessionId:defaultChat,prompt:[{type:'text',text:'Follow-up while working'}],_meta:{native:true}}));
 assert.equal(steering.outcome,'injected');assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:defaultChat})).busy,true);
 const steerTrace=traces().filter(x=>x.m.method==='_session/steering').at(-1);
 assert.equal(steerTrace.profile,'codex');assert.notEqual(steerTrace.m.params.sessionId,defaultChat);assert.deepEqual(steerTrace.m.params._meta,{native:true});
 bad(await c.request('_ai_multi/switch',{sessionId:defaultChat,profile:'grok',fresh:true,revision:records().find(x=>x.sessionId===defaultChat).revision}));
 bad(await c.request('_ai_multi/switch',{sessionId:defaultChat,profile:'grok',exchanges:5,consent:true,revision:records().find(x=>x.sessionId===defaultChat).revision}));
 bad(await prompt(c,defaultChat,'duplicate pending'));
 c.send({method:'session/cancel',params:{sessionId:defaultChat}});assert.equal(ok(await held).stopReason,'cancelled');
 const partial=await switchTo(c,defaultChat,'grok',5);ok(await prompt(c,defaultChat,partial.token));
 assert(traces().filter(x=>x.profile==='grok'&&x.m.method==='session/prompt').at(-1).m.params.prompt[0].text?.includes('cancelled'));
 assert(traces().filter(x=>x.profile==='grok'&&x.m.method==='session/prompt').at(-1).m.params.prompt[0].text?.includes('Follow-up while working'));
 const count=records().find(x=>x.sessionId===id).exchanges.length;
 bad(await c.request('session/list',{cwd:project})); // This synthetic connection owns several profiles: do not invent a combined agent list.
 await c.close();
 // Mock chats share a transport; real IDEA chats each have their own execution UID.
 // Give the other persisted fixtures distinct UIDs before testing native reconnect.
 const originalUid=initParams._meta.proxyConfig.proxies[0].proxy.headers['X-IntelliJ-Proxy-Client-Execution-ID'];
 for(const file of fs.readdirSync(path.join(root,'conversations')).filter(x=>x.endsWith('.json'))){
  const location=path.join(root,'conversations',file),record=JSON.parse(fs.readFileSync(location,'utf8'));
  if(record.sessionId!==id && record.clientChatId===originalUid){record.clientChatId=crypto.randomUUID();fs.writeFileSync(location,JSON.stringify(record));}
 }
 const reconnectTraceCount=traces().length;
 c=client();const restored=ok(await c.request('initialize',initParams));assert.equal(restored._meta.steering.supported,false);
 assert.equal(restored.authMethods[0].name,'grok: Sign in');
 const listed=ok(await c.request('session/list',{cwd:project}));assert(listed.sessions.some(x=>x.sessionId===id));
 assert(traces().slice(reconnectTraceCount).every(x=>x.profile==='grok' && ['initialize','session/list'].includes(x.m.method))); // Re-read only the last agent's actual capabilities.
 ok(await c.request('session/load',{cwd:project,sessionId:id,mcpServers:[]}));
 const replay=c.messages.filter(m=>m.params?.sessionId===id&&m.params.update?.sessionUpdate==='user_message_chunk');
 assert.equal(replay.length,0); // This provider's fixture emits only its replay marker, never the router archive.
 assert(c.messages.some(m=>m.params?.sessionId===id && m.params.update?.content?.text==='PROVIDER_REPLAY_DUPLICATE'));
 assert.equal(records().find(x=>x.sessionId===id).exchanges.length,count);
 assert.equal(ok(await c.request('_ai_multi/describe',{sessionId:id})).profile,'grok');
 assert(!c.messages.some(m=>m.params?.sessionId===id&&m.params.update?.sessionUpdate==='agent_thought_chunk'));
 ok(await prompt(c,id,'After restart'));assert.equal(traces().filter(x=>x.m.method==='session/prompt').at(-1).profile,'grok');
 const outsider=client(other);ok(await outsider.request('initialize',{protocolVersion:1}));bad(await outsider.request('session/load',{cwd:other,sessionId:id}));bad(await outsider.request('_ai_multi/describe',{sessionId:id}));await outsider.close();
 const another=client();ok(await another.request('initialize',{protocolVersion:1}));bad(await another.request('session/resume',{sessionId:id,cwd:project}));await another.close();
 const crashed=client();ok(await crashed.request('initialize',{protocolVersion:1}));
 const crashId=ok(await crashed.request('session/new',{cwd:project})).sessionId;
 prompt(crashed,crashId,'hold').catch(()=>{});
 assert(ok(await crashed.request('_ai_multi/describe',{sessionId:crashId})).busy);
 crashed.proc.kill();await new Promise(r=>crashed.proc.exitCode!==null?r():crashed.proc.once('exit',r));
 const recovered=client();ok(await recovered.request('initialize',{protocolVersion:1}));ok(await recovered.request('session/load',{cwd:project,sessionId:crashId}));
 assert.equal(records().find(x=>x.sessionId===crashId).exchanges.at(-1).status,'interrupted');
 const unfinished=await switchTo(recovered,crashId,'grok',5);ok(await prompt(recovered,crashId,unfinished.token));
 assert(traces().filter(x=>x.profile==='grok'&&x.m.method==='session/prompt').at(-1).m.params.prompt[0].text?.includes('interrupted'));
 await recovered.close();
 await c.close();
 // Upgrade an older saved chat that did not persist its native feature flag.
 const olderFile=fs.readdirSync(path.join(root,'conversations')).filter(x=>x.endsWith('.json')).map(x=>path.join(root,'conversations',x)).find(x=>JSON.parse(fs.readFileSync(x,'utf8')).sessionId===id);
 const older=JSON.parse(fs.readFileSync(olderFile,'utf8'));delete older.segments.find(x=>x.id===older.currentSegment).clientMeta;delete older.initializeSnapshot._meta;
 fs.writeFileSync(olderFile,JSON.stringify(older));const migrationStart=traces().length;
 const migrated=client();assert.equal(ok(await migrated.request('initialize',initParams))._meta.steering.supported,false);
 assert(traces().slice(migrationStart).every(x=>x.profile==='grok' && x.m.method==='initialize'));await migrated.close();
 config.profiles.grok.env.ACCOUNT='different';write();c=client();ok(await c.request('initialize',{protocolVersion:1}));
 const beforeEnvironmentRestore=traces().length;ok(await c.request('session/load',{cwd:project,sessionId:id}));
 assert(!traces().slice(beforeEnvironmentRestore).some(x=>x.m.method==='session/new'));await c.close();
 // Configuration belongs to the selected ACP. Requests/metadata reach that backend;
 // acknowledgement, notifications and resume replace the stale creation snapshot.
 const routesBeforeSettings=fs.readFileSync(configPath,'utf8');
 let settings=client();ok(await settings.request('initialize',{protocolVersion:1}));
 const settingsChat=ok(await settings.request('session/new',{cwd:project,_meta:{providerExtension:'preserve'}}));
 assert.equal(settingsChat.configOptions[0].currentValue,'low');
 const settingsId=settingsChat.sessionId;
 assert.deepEqual(traces().filter(x=>x.m.method==='session/new').at(-1).m.params._meta,{providerExtension:'preserve'});
 ok(await settings.request('_fixture/agent-extension',{_meta:{native:true}}));
 assert.equal(traces().at(-1).profile,'codex');assert.deepEqual(traces().at(-1).m.params._meta,{native:true});
 const configured=ok(await settings.request('session/set_config_option',{sessionId:settingsId,configId:'reasoning_effort',value:'high',_meta:{native:true}}));
 assert.equal(configured.configOptions[0].currentValue,'high');
 let settingsRecord=records().find(x=>x.sessionId===settingsId);
 assert.equal(settingsRecord.sessionOptions.configOptions[0].currentValue,'high');
 const setterTrace=traces().filter(x=>x.m.method==='session/set_config_option').at(-1);
 assert.equal(setterTrace.profile,'codex');assert.equal(setterTrace.m.params.sessionId,settingsRecord.segments[0].sessionId);
 assert.deepEqual(setterTrace.m.params._meta,{native:true});
 bad(await settings.request('session/set_config_option',{sessionId:settingsId,configId:'reasoning_effort',value:'invalid'}));
 assert.equal(records().find(x=>x.sessionId===settingsId).sessionOptions.configOptions[0].currentValue,'high');
 const preferredChat=ok(await settings.request('session/new',{cwd:project}));
 assert.equal(preferredChat.configOptions[0].currentValue,'high'); // Owned and remembered by the mock ACP.
 const settersBeforeSwitch=traces().filter(x=>x.m.method==='session/set_config_option').length;
 const switchState=ok(await settings.request('_ai_multi/describe',{sessionId:settingsId}));
 ok(await settings.request('_ai_multi/switch',{sessionId:settingsId,profile:'grok',fresh:true,revision:switchState.revision}));
 assert.equal(records().find(x=>x.sessionId===settingsId).sessionOptions.configOptions[0].currentValue,'low');
 // A separate native connection has exactly one chat; unscoped extensions must
 // follow that chat after switching rather than the project's default backend.
 const extensionClient=client();ok(await extensionClient.request('initialize',{protocolVersion:1}));
 const extensionChat=ok(await extensionClient.request('session/new',{cwd:project})).sessionId;
 const extensionState=ok(await extensionClient.request('_ai_multi/describe',{sessionId:extensionChat}));
 ok(await extensionClient.request('_ai_multi/switch',{sessionId:extensionChat,profile:'grok',fresh:true,revision:extensionState.revision}));
 ok(await extensionClient.request('_fixture/agent-extension',{_meta:{native:true}}));
 assert.equal(traces().at(-1).profile,'grok');assert.deepEqual(traces().at(-1).m.params._meta,{native:true});
 await extensionClient.close();
 assert(bad(await settings.request('_fixture/agent-extension',{})).message.includes('sessionId'));
 const grokState=ok(await settings.request('_ai_multi/describe',{sessionId:settingsId}));
 ok(await settings.request('_ai_multi/switch',{sessionId:settingsId,profile:'codex',fresh:true,revision:grokState.revision}));
 assert.equal(records().find(x=>x.sessionId===settingsId).sessionOptions.configOptions[0].currentValue,'high');
 assert.equal(traces().filter(x=>x.m.method==='session/set_config_option').length,settersBeforeSwitch);
 ok(await settings.request('_fixture/options',{sessionId:settingsId,value:'low'}));
 assert.equal(records().find(x=>x.sessionId===settingsId).sessionOptions.configOptions[0].currentValue,'low');
 ok(await prompt(settings,settingsId,'Keep response history'));
 await settings.close();
 settingsRecord=records().find(x=>x.sessionId===settingsId);
 // Provider state changes independently of the cached chat; resume must ask it.
 const segment=settingsRecord.segments.find(x=>x.id===settingsRecord.currentSegment);
 fs.writeFileSync(trace+'.settings-'+segment.sessionId,'high');
 const cachedFile=fs.readdirSync(path.join(root,'conversations')).map(x=>path.join(root,'conversations',x)).find(x=>x.endsWith('.json')&&JSON.parse(fs.readFileSync(x,'utf8')).sessionId===settingsId);
 settingsRecord.version=1;settingsRecord.exchanges[0].updates=[{sessionUpdate:'config_option_update',configOptions:configured.configOptions.map(x=>({...x,currentValue:'low'}))}];
 fs.writeFileSync(cachedFile,JSON.stringify(settingsRecord));
 settings=client();ok(await settings.request('initialize',{protocolVersion:1}));
 const resumedOptions=ok(await settings.request('session/load',{cwd:project,sessionId:settingsId}));
 assert.equal(resumedOptions.configOptions[0].currentValue,'high');
 assert.equal(records().find(x=>x.sessionId===settingsId).sessionOptions.configOptions[0].currentValue,'high');
 assert(!settings.messages.some(x=>x.params?.sessionId===settingsId&&x.params.update?.sessionUpdate==='config_option_update'&&x.params.update.configOptions[0].currentValue==='low'));
 assert.equal(fs.readFileSync(configPath,'utf8'),routesBeforeSettings);
 await settings.close();
 config.profiles.codex.env.NO_RESUME='1';write();
 const loadOnly=client();ok(await loadOnly.request('initialize',{protocolVersion:1}));
 const loadOnlyId=ok(await loadOnly.request('session/new',{cwd:project})).sessionId;
 ok(await prompt(loadOnly,loadOnlyId,'Single virtual replay'));await loadOnly.close();
 const loadAgain=client();ok(await loadAgain.request('initialize',{protocolVersion:1}));
 ok(await loadAgain.request('session/load',{cwd:project,sessionId:loadOnlyId}));
 assert(loadAgain.messages.some(x=>x.params?.sessionId===loadOnlyId&&x.params.update?.content?.text==='PROVIDER_REPLAY_DUPLICATE'));
 assert.equal(loadAgain.messages.filter(x=>x.params?.sessionId===loadOnlyId&&x.params.update?.sessionUpdate==='agent_message_chunk').length,1);
 await loadAgain.close();
 // Reauthorize the same logical/provider session, including a legacy record,
 // failed login, a router restart and repeated revocation while a turn is active.
 const authFile=path.join(root,'recover-auth'),authRevisionPath=path.join(root,'recover-revision'),recoveryLoginScript=path.join(root,'recover-login.js');
 fs.writeFileSync(recoveryLoginScript,"require('fs').writeFileSync(process.env.AUTH_FILE,'authenticated by terminal')");
 fs.writeFileSync(authFile,'authenticated');
 config.profiles.recover={...spec('recover'),authRevisionPath,env:{...spec('recover').env,AUTH_FILE:authFile,LOGIN_SCRIPT:recoveryLoginScript}};
 config.projects[0].profile='recover';write();
 const recoveryParams={protocolVersion:1,_meta:{proxyConfig:{proxies:[{proxy:{headers:{'X-IntelliJ-Proxy-Client-Execution-ID':crypto.randomUUID()}}}]}}};
 let recovery=client();ok(await recovery.request('initialize',recoveryParams));
 const recoveryId=ok(await recovery.request('session/new',{cwd:project})).sessionId;
 ok(await prompt(recovery,recoveryId,'Retain this history'));
 ok(await recovery.request('session/set_config_option',{sessionId:recoveryId,configId:'reasoning_effort',value:'high'}));
 const heldRecovery=prompt(recovery,recoveryId,'hold');
 await new Promise(r=>setTimeout(r,150));
 fs.unlinkSync(authFile);fs.writeFileSync(authRevisionPath,'revoked-first');
 assert.equal(bad(await heldRecovery).code,-32000);
 await recovery.close();
 const recoveryFile=fs.readdirSync(path.join(root,'conversations')).map(x=>path.join(root,'conversations',x)).find(x=>x.endsWith('.json')&&JSON.parse(fs.readFileSync(x,'utf8')).sessionId===recoveryId);
 const beforeRecovery=JSON.parse(fs.readFileSync(recoveryFile,'utf8'));
 const recoverySegment=beforeRecovery.segments[0],providerSessionId=recoverySegment.sessionId,oldFingerprint=recoverySegment.fingerprint;
 delete recoverySegment.launchFingerprint; // Versions before this fix stored only the combined digest.
 fs.writeFileSync(recoveryFile,JSON.stringify(beforeRecovery));
 config.projects[0].profile='grok';write();const recoveryRoutes=fs.readFileSync(configPath,'utf8'),recoveryTraceStart=traces().length;
 recovery=client();const recoveryInit=ok(await recovery.request('initialize',recoveryParams));
 assert.equal(recoveryInit.authMethods[0].name,'recover: Sign in');
 assert.notEqual(recoveryInit.authMethods[0].id,recoverySegment.authMethods[0].id);
 const deniedRecovery=bad(await recovery.request('session/load',{sessionId:recoveryId,cwd:project}));
 assert.equal(deniedRecovery.code,-32000,JSON.stringify(deniedRecovery));
 assert.equal(records().find(x=>x.sessionId===recoveryId).segments[0].fingerprint,oldFingerprint);
 fs.writeFileSync(authFile+'.fail','cancel login');
 assert.equal(bad(await recovery.request('authenticate',{methodId:recoveryInit.authMethods[0].id})).code,-32000);
 fs.unlinkSync(authFile+'.fail');
 ok(await recovery.request('authenticate',{methodId:recoveryInit.authMethods[0].id}));
 const recoveredSession=ok(await recovery.request('session/load',{sessionId:recoveryId,cwd:project}));
 assert.equal(recoveredSession.sessionId,recoveryId);assert.equal(recoveredSession.configOptions[0].currentValue,'high');
 let recoveryRecord=records().find(x=>x.sessionId===recoveryId);
 assert.equal(recoveryRecord.segments[0].sessionId,providerSessionId);
 assert.equal(recoveryRecord.exchanges.length,beforeRecovery.exchanges.length);
 assert.deepEqual(recoveryRecord.exchanges,beforeRecovery.exchanges);
 assert(recoveryRecord.segments[0].launchFingerprint);assert.notEqual(recoveryRecord.segments[0].fingerprint,oldFingerprint);
 ok(await prompt(recovery,recoveryId,'Continue after sign in'));
 const previousAuthMethod=recoveryRecord.segments[0].authMethods[0].id;
 fs.unlinkSync(authFile);fs.writeFileSync(authRevisionPath,'revoked-second');
 await new Promise(r=>setTimeout(r,150));
 ok(await recovery.request('authenticate',{methodId:previousAuthMethod}));
 ok(await recovery.request('session/resume',{sessionId:recoveryId,cwd:project}));
 ok(await prompt(recovery,recoveryId,'Continue after second sign in'));
 assert.equal(records().find(x=>x.sessionId===recoveryId).segments[0].sessionId,providerSessionId);
 assert(traces().slice(recoveryTraceStart).every(x=>x.profile==='recover'));
 assert(!traces().slice(recoveryTraceStart).some(x=>x.m.method==='session/new'));
 assert.equal(traces().filter(x=>x.profile==='recover' && x.m.method==='session/prompt' && x.m.params.prompt[0].text==='hold').length,1);
 assert.equal(fs.readFileSync(configPath,'utf8'),recoveryRoutes);
 await recovery.close();
 // Native terminal login must follow this retained chat when Default Profile
 // points elsewhere. The wrapper is run for real, against a local fake login.
 fs.unlinkSync(authFile);fs.writeFileSync(authRevisionPath,'revoked-terminal');
 recovery=client();const terminalInit=ok(await recovery.request('initialize',recoveryParams));
 const terminal=terminalInit.authMethods.find(x=>x.name==='recover: Terminal sign in')._meta['terminal-auth'];
 cp.execFileSync(process.execPath,terminal.args,{cwd:project,env:{...process.env,...terminal.env},windowsHide:true});
 assert.equal(fs.readFileSync(authFile,'utf8'),'authenticated by terminal');
 ok(await recovery.request('session/resume',{sessionId:recoveryId,cwd:project}));
 assert.equal(records().find(x=>x.sessionId===recoveryId).segments[0].sessionId,providerSessionId);
 assert.equal(fs.readFileSync(configPath,'utf8'),recoveryRoutes);await recovery.close();
 // An environment change with the same registered agent does not replace its saved session.
 config.profiles.recover.env.CHANGED_LAUNCH='yes';write();
 recovery=client();ok(await recovery.request('initialize',recoveryParams));
 ok(await recovery.request('session/resume',{sessionId:recoveryId,cwd:project}));
 assert.equal(records().find(x=>x.sessionId===recoveryId).segments[0].sessionId,providerSessionId);await recovery.close();
 delete config.profiles.recover.env.CHANGED_LAUNCH;config.projects[0].profile='codex';write();
 assert.equal(fs.readFileSync(path.join(__dirname,'virtual-sessions.js'),'utf8'),fs.readFileSync(path.join(__dirname,'../src/main/resources/virtual-sessions.js'),'utf8'));
 console.log('PASS: links in omitted replies survive chunk splits, profile handoff, restart and round trip without embedding files; storage failure survives streaming and recovers, virtual default chat, 5/10-exchange handoff, fresh chat, exhausted quota, restart replay, cancellation/partial work, project/account isolation, duplicate prevention, consent, unchanged Default Profile, private-data exclusion, provider-owned configuration forwarding/acknowledgement/updates/resume');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{for(const p of processes)if(p.exitCode===null)p.stdin.end();await Promise.all(processes.map(p=>p.exitCode!==null?null:new Promise(r=>p.once('exit',r))));if(path.dirname(root)===os.tmpdir()&&path.basename(root).startsWith('ai-multi-handoff-'))fs.rmSync(root,{recursive:true,force:true,maxRetries:10,retryDelay:100});});
