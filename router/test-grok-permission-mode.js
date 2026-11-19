'use strict';
const fs = require('fs'), os = require('os'), path = require('path'), assert = require('assert');
const mode = require('./grok-permission-mode');
const {VirtualSessions} = require('./virtual-sessions');
assert.deepStrictEqual(fs.readFileSync(path.join(__dirname, 'grok-permission-mode.js')), fs.readFileSync(path.join(__dirname, '../src/main/resources/grok-permission-mode.js')));
assert.equal(fs.readFileSync(path.join(__dirname, 'virtual-sessions.js'), 'utf8'), fs.readFileSync(path.join(__dirname, '../src/main/resources/virtual-sessions.js'), 'utf8'));

assert.equal(mode.readMode('[ui]\npermission_mode = "auto"\n'), 'auto');
assert.equal(mode.readMode('[cli]\npermission_mode = "auto"\n[ui]\nyolo = false\n'), null);
const rewritten = mode.writeText('[cli]\nauto_update = true\n\n[ui]\nyolo = false\ncompact_mode = false\n', 'auto');
assert.equal(mode.readMode(rewritten), 'auto');
assert(rewritten.includes('yolo = false'));
assert.equal(mode.readMode(mode.writeText(rewritten, 'ask')), 'ask');
assert.equal(mode.readMode(mode.writeText('', 'always-approve')), 'always-approve');
assert.deepEqual(mode.metaFor('auto'), {autoMode:true, yoloMode:false});
assert.deepEqual(mode.metaFor('always-approve'), {yoloMode:true, autoMode:false});
assert.equal(mode.needsCustom({agentId:'acp.registry.grok-build'}, {}), true);
assert.equal(mode.needsCustom({agentId:'acp.registry.codex-acp'}, {}), false);
assert.equal(mode.needsCustom({agentId:'acp.registry.grok-build'}, {modes:{availableModes:[{id:'plan'}]}}), false);
assert.equal(mode.preferred({env:{}}, [{operation:'mode', value:'always-approve'}]), 'always-approve');
const described = require('./profile-settings').descriptors(mode.advertise({configOptions:[]}, 'ask'));
assert.equal(described.find(option => option.operation === 'mode').name, 'Permission mode');
assert.deepEqual(described.find(option => option.operation === 'mode').options.map(option => option.value), ['ask', 'auto', 'always-approve']);

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'aiaps-grok-mode-'));
const home = path.join(root, 'home');
fs.mkdirSync(home);
fs.writeFileSync(path.join(home, 'config.toml'), '[ui]\nyolo = false\npermission_mode = "auto"\n');
const calls = [];
const backend = {profile:'g', fingerprint:'fp', initialize:{protocolVersion:1, agentInfo:{name:'grok', title:'Grok'}}, proc:{stdin:{write(line){calls.push(JSON.parse(line));}}}, failed:false};
const profiles = {
  g:{name:'Grok', command:'grok', agentId:'acp.registry.grok-build', agentName:'Grok', env:{GROK_HOME:home}, sessionSettings:[], agentDefaults:[]},
  c:{name:'Codex', command:'codex', agentId:'acp.registry.codex-acp', agentName:'Codex', env:{}, sessionSettings:[], agentDefaults:[]}
};
const api = {
  directory:root, normalize:value => value, fingerprint:() => 'fp', hash:() => 'h',
  config:() => ({profiles}), settingsSpec:spec => spec,
  backendFor:async () => backend,
  request:async (_backend, method, params) => {
    calls.push({method, params});
    if (method === 'session/new') return {result:{sessionId:(params._meta ? 'should-not-see-meta' : 'backend-session'), configOptions:[]}};
    if (method === 'session/close' || method === 'session/resume') return {result:{sessionId:params.sessionId}};
    return {result:{}};
  },
  record() {}, advertiseAuth:() => ({methods:[], bindings:[]}), send() {}, initializeParams:() => ({}), initialized:() => ({}),
  configureSession:async (_profile, _spec, result) => ({result})
};
async function main() {
const sessions = new VirtualSessions(api);
const created = await sessions.newSession({cwd:root, mcpServers:[]}, {profile:'g', spec:profiles.g});
assert.deepEqual(created.modes.availableModes.map(item => item.id), ['ask', 'auto', 'always-approve']);
assert.equal(created.modes.currentModeId, 'auto');
const opened = calls.filter(call => call.method === 'session/new' || call.method === 'session/close' || call.method === 'session/resume');
assert.deepEqual(opened.map(call => call.method), ['session/new', 'session/close', 'session/resume']);
assert.equal(opened[0].params._meta, undefined);
assert.equal(opened[2].params._meta.autoMode, true);
assert.equal(opened[2].params.sessionId, 'backend-session');
const id = created.sessionId;
await sessions.handle({jsonrpc:'2.0', id:7, method:'session/set_mode', params:{sessionId:id, modeId:'ask'}});
assert.equal(mode.readMode(fs.readFileSync(path.join(home, 'config.toml'), 'utf8')), 'ask');
assert(fs.readFileSync(path.join(home, 'config.toml'), 'utf8').includes('yolo = false'));
const switched = calls.filter(call => call.method === 'session/resume').at(-1);
assert.deepEqual(switched.params._meta, {autoMode:false, yoloMode:false});
assert.equal(sessions.active.get(id).result.modes.currentModeId, 'ask');

// Ask is already on the chip, but the process started from permission_mode=auto.
// Choosing Ask again must write the file first, stop that process, and resume.
fs.writeFileSync(path.join(home, 'config.toml'), '[ui]\nyolo = false\npermission_mode = "auto"\n');
const stale = {profile:'g', fingerprint:'fp', startedAt:1, initialize:{protocolVersion:1, agentInfo:{name:'grok', title:'Grok'}}, proc:{stdin:{write(){}}}, failed:false, grokPermissionMode:'auto'};
let spawned = null;
const stopped = [];
api.stopBackend = async backend => { stopped.push(backend); };
api.backendFor = async () => {
  spawned = {profile:'g', fingerprint:'fp', startedAt:Date.now(), initialize:stale.initialize, proc:{stdin:{write(){}}}, failed:false};
  return spawned;
};
sessions.active.get(id).backend = stale;
calls.length = 0;
await sessions.handle({jsonrpc:'2.0', id:8, method:'session/set_mode', params:{sessionId:id, modeId:'ask'}});
assert.equal(stopped[0], stale);
assert.equal(mode.readMode(fs.readFileSync(path.join(home, 'config.toml'), 'utf8')), 'ask');
const restarted = calls.filter(call => call.method === 'session/resume');
assert.equal(restarted.length, 1);
assert.deepEqual(restarted[0].params._meta, {autoMode:false, yoloMode:false});
assert.equal(restarted[0].params.sessionId, 'backend-session');
assert.equal(calls.some(call => call.method === 'session/close'), false);
assert.equal(sessions.active.get(id).backend, spawned);
assert.equal(sessions.active.get(id).backend.grokPermissionMode, 'ask');
calls.length = 0;
api.request = async (_backend, method, params) => {
  calls.push({method, params});
  if (method === 'session/new') return {result:{sessionId:'codex-session', modes:{currentModeId:'agent', availableModes:[{id:'agent', name:'Agent'}]}}};
  return {result:{}};
};
const codex = await sessions.newSession({cwd:root, mcpServers:[]}, {profile:'c', spec:profiles.c});
assert.equal(codex.modes.currentModeId, 'agent');
assert.equal(calls.some(call => call.method === 'session/close'), false);
console.log('grok permission mode checks passed');
}
main().catch(error => { console.error(error); process.exit(1); });
