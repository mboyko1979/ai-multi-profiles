'use strict';
// Synthetic ACP fixture for ChatStartupProbe; never connects to a provider.
const fs = require('fs');
const profile = process.env.PROFILE;
const send = message => process.stdout.write(JSON.stringify({jsonrpc:'2.0', ...message}) + '\n');
const options = () => [{id:'model', name:'Model', category:'model', type:'select',
  currentValue:profile + '-model', options:[{value:profile + '-model', name:profile + ' Model'}]}];
const update = (sessionId, sessionUpdate, text) => send({method:'session/update',
  params:{sessionId, update:{sessionUpdate, content:{type:'text', text}}}});
require('readline').createInterface({input:process.stdin}).on('line', line => {
  const message = JSON.parse(line);
  // Keep MCP environment and prompt bodies out of diagnostic files.
  fs.appendFileSync(process.env.TRACE, JSON.stringify({method:message.method, sessionId:message.params?.sessionId}) + '\n');
  if (message.method === 'initialize') {
    setTimeout(() => send({id:message.id, result:{protocolVersion:1, authMethods:[],
      agentCapabilities:{loadSession:true, sessionCapabilities:{resume:{}}}}}), 1000);
  } else if (message.method === 'session/new') {
    send({id:message.id, result:{sessionId:profile + '-' + require('crypto').randomUUID(), configOptions:options()}});
  } else if (message.method === 'session/load' || message.method === 'session/resume') {
    update(message.params.sessionId, 'user_message_chunk', 'fixture question');
    update(message.params.sessionId, 'agent_message_chunk', 'fixture answer');
    // Restoring without configOptions is valid even though new supplied them.
    send({id:message.id, result:{}});
  } else if (message.method === 'session/set_config_option') {
    send({id:message.id, result:{configOptions:options()}});
  } else if (message.method === 'session/prompt') {
    update(message.params.sessionId, 'agent_message_chunk', 'fixture reply');
    send({id:message.id, result:{stopReason:'end_turn'}});
  } else if (message.id !== undefined) send({id:message.id, result:{}});
});
