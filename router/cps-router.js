#!/usr/bin/env node
'use strict';
// ACP stdio router. Stdout is protocol-only; account routing fails closed.
const fs = require('fs'), path = require('path'), cp = require('child_process');
const crypto = require('crypto'), readline = require('readline');
const profileSettings = require('./profile-settings');
const configPath = process.argv[2];
const fixedProfileIndex = process.argv.indexOf('--profile');
const fixedProfile = fixedProfileIndex < 0 ? null : process.argv[fixedProfileIndex + 1];
if (fixedProfileIndex >= 0 && (!fixedProfile || fixedProfile.startsWith('--'))) throw Error('Missing profile ID');
const initialProfileIndex = process.argv.indexOf('--initial-profile');
const initialProfile = initialProfileIndex < 0 ? null : process.argv[initialProfileIndex + 1];
if (initialProfileIndex >= 0 && (!initialProfile || initialProfile.startsWith('--'))) throw Error('Missing initial profile ID');
const ownerDir = path.join(path.dirname(configPath), 'session-owners');
const parse = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const log = (...args) => process.stderr.write('[AIAPS] ' + args.join(' ') + '\n');
const send = msg => process.stdout.write(JSON.stringify(msg) + '\n');
const fail = (msg, message, code = -32602, data) => {
  if (msg.id !== undefined) send({jsonrpc:'2.0', id:msg.id, error:{code, message,...(data===undefined?{}:{data})}});
  log(message);
};
const readConfig = () => {
  const cfg = parse(configPath);
  if (!cfg.profiles || typeof cfg.profiles !== 'object' || Array.isArray(cfg.profiles) || !Array.isArray(cfg.projects)) throw Error('Routing configuration is invalid');
  return cfg;
};
const authRevision = spec => {
  if (!spec.authRevisionPath) return '';
  try { return fs.readFileSync(spec.authRevisionPath, 'utf8').trim(); }
  catch (e) { if (e.code === 'ENOENT') return ''; throw e; }
};
const processStops = new WeakMap();
function stopProcess(proc) {
  if (processStops.has(proc)) return processStops.get(proc);
  const stopped = new Promise(resolve => {
    if (!proc.pid || proc.exitCode !== null || proc.signalCode !== null) return resolve();
    let done = false, killCompleted = process.platform !== 'win32';
    const finish = () => { if (done || !killCompleted) return; done = true; clearTimeout(deadline); proc.off('close', finish); resolve(); };
    const deadline = setTimeout(() => {
      try { if (proc.exitCode === null && proc.signalCode === null) proc.kill('SIGKILL'); } catch (_) {}
      killCompleted = true;
      finish();
    }, 5000);
    proc.once('close', finish);
    if (process.platform === 'win32') {
      // Kill only this freshly owned root and its descendants, before killing the root separately.
      cp.execFile(path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'taskkill.exe'),
        ['/PID', String(proc.pid), '/T', '/F'], {windowsHide:true, timeout:4000}, error => {
          killCompleted = true;
          if (error) try { if (proc.exitCode === null && proc.signalCode === null) proc.kill(); } catch (_) {}
          if (proc.exitCode !== null || proc.signalCode !== null) finish();
        });
    } else {
      // Backends start their own process group; do not signal the host's group.
      try { process.kill(-proc.pid, 'SIGKILL'); } catch (_) { try { proc.kill('SIGKILL'); } catch (_) { finish(); } }
    }
  });
  processStops.set(proc, stopped); return stopped;
}
// Windows CreateProcess cannot start npx.cmd. Use the managed node.exe and npm's npx-cli.js instead of a shell.
function directLaunch(command, args, cwd) {
  if (process.platform !== 'win32' || typeof command !== 'string') return {command, args, runtimeDir:null};
  const base = path.win32.basename(command).toLowerCase();
  const kind = base === 'npx' || base === 'npx.cmd' || base === 'npx.bat' ? 'npx'
    : base === 'npm' || base === 'npm.cmd' || base === 'npm.bat' ? 'npm' : null;
  if (!kind) return {command, args, runtimeDir:null};
  const cliName = kind === 'npx' ? 'npx-cli.js' : 'npm-cli.js';
  const dirs = [];
  if (path.win32.isAbsolute(command)) dirs.push(path.dirname(path.win32.normalize(command)));
  if (cwd) {
    try {
      const runtimes = path.resolve(cwd, '..', '..', '.runtimes', 'node');
      const versions = fs.readdirSync(runtimes, {withFileTypes:true}).filter(entry => entry.isDirectory()).map(entry => entry.name);
      versions.sort((a, b) => {
        const pa = a.split('.').map(part => parseInt(part, 10) || 0), pb = b.split('.').map(part => parseInt(part, 10) || 0);
        for (let i = 0; i < Math.max(pa.length, pb.length); i++) if ((pb[i] || 0) !== (pa[i] || 0)) return (pb[i] || 0) - (pa[i] || 0);
        return 0;
      });
      for (const version of versions) dirs.push(path.join(runtimes, version));
    } catch (_) { /* A custom working directory has no JetBrains-managed runtime. */ }
  }
  for (const dir of (process.env.PATH || process.env.Path || '').split(path.delimiter)) if (dir) dirs.push(dir);
  for (const dir of dirs) {
    const node = path.join(dir, 'node.exe'), cli = path.join(dir, 'node_modules', 'npm', 'bin', cliName);
    try { if (fs.statSync(node).isFile() && fs.statSync(cli).isFile()) return {command:node, args:[cli, ...args], runtimeDir:dir}; }
    catch (_) { /* Try the next runtime directory. */ }
  }
  return {command, args, runtimeDir:null};
}
function applyRuntime(env, runtimeDir) {
  if (!runtimeDir) return env;
  const current = env.PATH || env.Path || '';
  const lower = current.toLowerCase(), dir = runtimeDir.toLowerCase();
  const next = lower === dir || lower.startsWith(dir + path.delimiter.toLowerCase()) ? current : runtimeDir + path.delimiter + current;
  env.PATH = next; env.Path = next;
  const defaults = {NPM_CONFIG_CACHE:path.join(runtimeDir, 'npm-cache'), NPM_CONFIG_MIN_RELEASE_AGE:'0', NPM_CONFIG_GLOBAL:'false',
    NPM_CONFIG_DRY_RUN:'false', NPM_CONFIG_INCLUDE:'optional', NPM_CONFIG_ENGINE_STRICT:'false', NPM_CONFIG_STRICT_PEER_DEPS:'false', NODE_USE_SYSTEM_CA:'1'};
  for (const [key, value] of Object.entries(defaults)) if (env[key] === undefined) env[key] = value;
  return env;
}
// Native terminal authentication launches AI Multi's command. Redirect that login
// to the recorded profile executable with exactly that profile's environment.
const loginIndex = process.argv.indexOf('--profile-login', 3);
if (loginIndex >= 0) {
  try {
    const cfg = readConfig(), profile = process.argv[loginIndex + 1], spec = cfg.profiles[profile];
    const expected = process.argv[loginIndex + 2], login = JSON.parse(process.argv[loginIndex + 3]);
    if (!spec || crypto.createHash('sha256').update(JSON.stringify(spec)).digest('hex') !== expected) throw Error('Profile changed; start a new chat to sign in');
    if (authRevision(spec) !== (login.authRevision || '')) throw Error('Profile authorization was revoked; start a new chat to sign in');
    const normalizeLogin = value => process.platform === 'win32' ? path.resolve(value).toLowerCase() : path.resolve(value);
    const cwd = normalizeLogin(login.cwd);
    const selected = cfg.projects.map(p => ({...p,root:normalizeLogin(p.root)})).sort((a,b)=>b.root.length-a.root.length)
      .find(p=>cwd === p.root || cwd.startsWith(p.root.endsWith(path.sep) ? p.root : p.root + path.sep));
    let explicitChat = false;
    if (typeof login.chatUid === 'string' && /^[0-9a-f-]{36}$/i.test(login.chatUid)) {
      const digest = crypto.createHash('sha256').update(JSON.stringify(login.chatUid)).digest('hex');
      const startFile = path.join(path.dirname(configPath), 'chat-starts', digest + '.json');
      if (fs.existsSync(startFile)) {
        const start = parse(startFile);
        explicitChat = start.chatUid === login.chatUid && start.profile === profile && normalizeLogin(start.cwd) === cwd && Date.now() - start.createdAt < 300000;
      }
      const conversations = path.join(path.dirname(configPath), 'conversations');
      if (fs.existsSync(conversations)) for (const file of fs.readdirSync(conversations).filter(x => x.endsWith('.json'))) {
        const state = parse(path.join(conversations, file)), segment = state.segments?.find(x => x.id === state.currentSegment);
        if (state.clientChatId === login.chatUid && normalizeLogin(state.cwd) === cwd && segment?.profile === profile &&
            segment.fingerprint === (login.conversationFingerprint || login.fingerprint)) explicitChat = true;
      }
    }
    if (selected?.profile !== profile && !explicitChat) throw Error('Project/chat profile changed; start a new chat to sign in');
    const env = {...(spec.inheritEnvironment === false ? {} : process.env),
      ...Object.fromEntries((login.envKeys || []).filter(key=>process.env[key] !== undefined).map(key=>[key,process.env[key]])), ...spec.env};
    const launched = directLaunch(login.command || spec.command, login.args, spec.cwd || process.cwd());
    applyRuntime(env, launched.runtimeDir);
    const child = cp.spawn(launched.command, launched.args, {
      cwd:spec.cwd || process.cwd(), env, stdio:'inherit', windowsHide:true, shell:false, detached:process.platform !== 'win32'
    });
    child.on('error', () => { clearInterval(monitor); log('Could not start profile login'); process.exitCode = 1; });
    const monitor = setInterval(() => {
      try { if (authRevision(spec) !== (login.authRevision || '')) stopProcess(child); }
      catch (_) { stopProcess(child); }
    }, 100);
    child.on('exit', code => { clearInterval(monitor); process.exitCode = code ?? 1; });
    for (const signal of ['SIGTERM','SIGINT']) process.on(signal, () => {
      clearInterval(monitor); stopProcess(child).finally(() => process.exit(0));
    });
  } catch (e) { log(e.message); process.exitCode = 1; }
} else {
const normalize = value => {
  const result = path.resolve(value);
  return process.platform === 'win32' ? result.toLowerCase() : result;
};
const route = cwd => {
  if (typeof cwd !== 'string' || !cwd.trim()) throw Error('A project working directory is required');
  const cfg = readConfig(), resolved = normalize(cwd);
  const selectedProfile = fixedProfile ?? initialProfile;
  if (selectedProfile !== null) {
    const spec=cfg.profiles[selectedProfile];
    if (!spec) throw Error('Selected profile is no longer available');
    return {profile:selectedProfile,spec};
  }
  const roots = cfg.projects.map(p => ({...p, root:normalize(p.root)})).sort((a,b) => b.root.length-a.root.length);
  const id = roots.find(p => resolved === p.root || resolved.startsWith(p.root.endsWith(path.sep) ? p.root : p.root + path.sep))?.profile;
  if (!id || !cfg.profiles[id]) return null;
  return {profile:id, spec:cfg.profiles[id]};
};
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key,canonical(value[key])])) : value;
const hash = value => crypto.createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
const environmentFor = spec => ({...(spec.inheritEnvironment === false ? {} : process.env), ...(spec.env || {})});
const fingerprintForRevision = (spec, revision) => {
  return hash({command:spec.command, args:spec.args || [], cwd:normalize(spec.cwd || process.cwd()), env:environmentFor(spec),
    ...(revision ? {authRevision:revision} : {})});
};
const fingerprint = spec => fingerprintForRevision(spec, authRevision(spec));
const launchFingerprint = spec => fingerprintForRevision(spec, '');
// Windows creates per-process EFC markers. Keep every provider/configuration
// variable in the restore identity; omit only OS process bookkeeping.
const restoreFingerprint = spec => hash({command:spec.command,args:spec.args || [],cwd:normalize(spec.cwd || process.cwd()),
  inheritEnvironment:spec.inheritEnvironment!==false,
  env:Object.fromEntries(Object.entries(environmentFor(spec)).filter(([key])=>!/^EFC_\d+_\d+$/i.test(key) && !key.startsWith('=')))});
const settingsSpec = spec => profileSettings.forClient(spec,initializeParams);
const sessions = new Map(), backends = new Map(), pending = new Map(), upstream = new Map();
const authentication = new Map();
const stoppingBackends = new Set();
let counter = 0, initializeParams, advertised, negotiatedBackend, initializationPromise, closed = false;
let lastNotificationBackend, lastPublishedAuthNotification;
function connectionNotificationBackend() {
  const context = virtual?.notificationContext();
  return context ? context.backend : virtual?.initialBackend || negotiatedBackend;
}
function flushConnectionNotifications(backend = connectionNotificationBackend()) {
  if (!advertised || !backend || backend !== connectionNotificationBackend() || backend.failed || backend.stopPromise) return;
  const queued = backend.connectionNotifications || [];
  backend.connectionNotifications = [];
  // The identity belongs to the connection's selected agent. A retained source
  // may become current again after a cancelled continuation; replay only its
  // actual last push, in memory, without deriving a status or capability.
  if ((lastNotificationBackend !== backend || lastPublishedAuthNotification !== backend.lastAuthNotification) &&
      backend.lastAuthNotification && !queued.includes(backend.lastAuthNotification)) {
    send(backend.lastAuthNotification);
    lastPublishedAuthNotification = backend.lastAuthNotification;
  }
  lastNotificationBackend = backend;
  for (const notification of queued) {
    send(notification);
    if (notification.method === '_auth/status_update') lastPublishedAuthNotification = notification;
  }
}
function connectionNotification(backend, msg) {
  // Direct legacy routing can multiplex project sessions on one connection.
  // Preserve its existing request-scoped forwarding; this binding belongs to
  // the native virtual-session path, whose current agent is explicit.
  if (!virtual) return false;
  if (msg.id !== undefined || !msg.method || msg.params?.sessionId !== undefined) return false;
  if (backend.purpose === 'catalog' || backend.purpose === 'preview' || backend.stopPromise) return true;
  if (msg.method === '_auth/status_update') backend.lastAuthNotification = msg;
  if (backend === connectionNotificationBackend() && advertised) {
    (backend.connectionNotifications ||= []).push(msg);
    flushConnectionNotifications(backend);
  } else if (!backend.virtual || backend.virtual.suppress) {
    // Initialization notifications can follow its response before the await
    // continuation binds this backend. Publish them only once that binding wins.
    (backend.connectionNotifications ||= []).push(msg);
  }
  return true;
}
async function creationSpec(profile,spec,cwd) {
  // An unconfigured profile follows the agent's normal session defaults.
  profileSettings.preferences(settingsSpec(spec));
  return spec;
}
async function nativePreferences(operation,profile,agentId,result,setting) {
  if(!readConfig().nativePreferences)return [];
  const host=settingsSpec({}).settingsHost;
  if(!host || !agentId)return [];
  return require('./agent-recovery').requestPreferences(path.dirname(configPath),
    {operation,profile,agentId,host,options:profileSettings.descriptors(result),...(setting?{setting}:{})},()=>closed);
}
async function configureSession(profile,spec,result,backend,cwd) {
  let current=result,awaitedCorrection=false;
  for(;;) {
    const selected=settingsSpec(spec);
    try {
      // Removing defaults while correcting startup rolls back our partial setters
      // to the real values this very session initially reported.
      if(awaitedCorrection && !profileSettings.concrete(selected)) {
        const options=profileSettings.descriptors(result),model=options.find(x=>x.category==='model' || x.operation==='model');
        const initial={agentId:spec.agentId,defaultModelId:model?.currentValue || '',modelOptionId:model?.id || '',modelOperation:model?.operation || 'config',
          models:[{modelId:model?.currentValue || '',enabled:true,settings:options.filter(x=>x!==model && x.currentValue!==undefined)
            .map(x=>({agentId:spec.agentId,id:x.id,operation:x.operation,value:String(x.currentValue)}))}]};
        current=(await profileSettings.applyConcrete({...selected,clientDefaults:[initial]},current,(method,params)=>request(backend,method,params))).result;
      }
      const applied=await profileSettings.apply(selected,current,(method,params)=>request(backend,method,params));
      const remembered=await nativePreferences('read',profile,spec.agentId,applied.result);
      applied.result=await profileSettings.applyRemembered(selected,applied.result,remembered,(method,params)=>request(backend,method,params));
      return {...applied,clientDefaults:profileSettings.concrete(selected),
        preferenceIds:profileSettings.preferences(selected).saved.filter(x=>x.operation==='config').map(x=>x.id),instructions:profileSettings.instructions(spec)};
    }
    catch(error) {
      if(Array.isArray(selected.clientDefaults)) {
        if(!error.presetIssues)throw error;
        awaitedCorrection=true;current=error.current || current;
        const revision=hash(selected.clientDefaults);
        await require('./agent-recovery').requestConfiguration(path.dirname(configPath),
          {kind:'profile-settings-error',profile,agentId:spec.agentId || '',cwd,host:error.host,modelId:error.modelId,
            reason:error.message.slice(0,4096),issues:error.presetIssues,options:profileSettings.descriptors(current)},()=>closed);
        // Keep this exact new-session request and backend session pending. Closing
        // Settings is not consent to substitute defaults or to create another chat.
        while(!closed && !backend.failed) {
          const updated=readConfig().profiles[profile];
          if(!updated || launchFingerprint(updated)!==launchFingerprint(spec))throw Error('Profile executable or authorization environment changed while configuring the new chat. Start a new chat with that profile.');
          if(hash(settingsSpec(updated).clientDefaults)!==revision) {spec=updated;break;}
          await new Promise(resolve=>setTimeout(resolve,100));
        }
        if(closed || backend.failed)throw Error(backend.failed || 'Conversation router is closing');
        continue;
      }
      // Compatibility for external routing configurations predating concrete presets.
      if(spec.automaticDefaults)await require('./agent-recovery').requestConfiguration(path.dirname(configPath),
        {kind:'profile-settings-error',profile,agentId:spec.agentId || '',cwd,reason:error.message.slice(0,4096)},()=>closed).catch(()=>{});
      throw error;
    }
  }
}
function advertiseAuth(backend, conversation) {
  const spec = readConfig().profiles[backend.profile];
  if (!spec) return {methods:[], bindings:[]};
  const methods = authMethods({profile:backend.profile, spec, conversation}, backend);
  const bindings = methods.map((method, index) => ({id:method.id, originalId:backend.initialize.authMethods[index].id,
    profile:backend.profile, fingerprint:backend.fingerprint, cwd:process.cwd()}));
  return {methods, bindings};
}
const virtual = readConfig().virtualSessions === true ? new (require('./virtual-sessions').VirtualSessions)({
  directory:path.dirname(configPath), config:readConfig, normalize, fingerprint, launchFingerprint, restoreFingerprint, settingsSpec, backendFor, request, record, send, owner, log, fixedProfile,
  initialized:() => advertised, initializeParams:() => initializeParams, hash, advertiseAuth,
  stopBackend, creationSpec, configureSession, nativePreferences, flushConnectionNotifications,
  rememberAuth:(id, saved) => authentication.set(id, saved)
}) : null;
// Both native clients retain their admitted session metadata. A saved model-list
// edit needs a presentation update even when the agent has no new notification.
let displayedConfig=readConfig();
const refreshModelFilters=()=>{
  if(closed || !virtual)return;
  try {
    const current=readConfig();
    virtual.refreshModelFilters(displayedConfig,current);
    displayedConfig=current;
  } catch(error) { log('Could not refresh model-list policy: '+error.message); }
};
fs.watchFile(configPath,{persistent:false,interval:250},refreshModelFilters);
const timeoutMs = Number(process.env.AIAPS_REQUEST_TIMEOUT_MS || 30000);
const ownerPath = sid => path.join(ownerDir, hash(sid) + '.json');
const validSid = sid => { if (typeof sid !== 'string' || !sid || sid.length > 1024) throw Error('Invalid sessionId'); };
function record(sid, backend, cwd, activate = true) {
  validSid(sid);
  const entry = {sessionId:sid, profile:backend.profile, fingerprint:backend.fingerprint, cwd:normalize(cwd),
    agentIdentity:{agentId:backend.spec.agentId || '',agentName:backend.spec.agentName || ''}};
  publishOwner(entry, backend, activate);
}
function publishOwner(entry, backend = null, activate = true) {
  const sid = entry.sessionId;
  fs.mkdirSync(ownerDir, {recursive:true});
  const file = ownerPath(sid), tmp = file + '.' + process.pid + '.' + crypto.randomUUID() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(entry), {flag:'wx', mode:0o600});
  try {
    // Hard-link publication is atomic and never replaces another process's owner.
    try { fs.linkSync(tmp, file); }
    catch (e) {
      if (e.code !== 'EEXIST') throw e;
      const existing = parse(file);
      // Older ownership records predate the agent snapshot. Accept an exactly
      // matching original binding without rewriting it or guessing its history.
      const sameLegacyBinding=entry.agentIdentity && !existing.agentIdentity && hash({...existing,agentIdentity:entry.agentIdentity})===hash(entry);
      if (hash(existing) !== hash(entry) && !sameLegacyBinding) throw Error('Session ownership conflict; refusing cross-profile access');
    }
  } finally { fs.unlinkSync(tmp); }
  if (activate) sessions.set(sid, {entry, backend});
}
const setupText = 'Choose a project profile in the AI Multi profile menu or open AI Multi Settings. Then start a new chat. This setup chat does not send requests to any account.';
function newSetup(cwd) {
  const entry = {sessionId:'aiaps-setup-' + crypto.randomUUID(), kind:'setup', cwd:normalize(cwd)};
  publishOwner(entry);
  return {sessionId:entry.sessionId};
}
function setupRequest(msg) {
  if (msg.method === 'session/prompt') {
    // A real, local configuration session: never inspect/forward prompt content,
    // run tools, choose a default account, or promote this session after assignment.
    send({jsonrpc:'2.0', method:'session/update', params:{sessionId:msg.params.sessionId,
      update:{sessionUpdate:'agent_message_chunk', content:{type:'text', text:setupText}}}});
    send({jsonrpc:'2.0', id:msg.id, result:{stopReason:'end_turn'}});
  } else if (['session/set_model','session/set_mode','session/set_config_option'].includes(msg.method)) {
    // Clients may apply remembered preferences before prompting. These affect
    // only this local session, which has no AI model or external execution.
    const saved = sessions.get(msg.params.sessionId);
    saved.preferences = {...saved.preferences, [msg.method]:msg.params};
    send({jsonrpc:'2.0', id:msg.id, result:msg.method === 'session/set_config_option' ? {configOptions:[]} : {}});
  } else if (msg.method !== 'session/cancel') {
    fail(msg, 'This is a local setup chat. Select a profile and start a new chat.', -32601);
  }
}
function owner(sid, cwd) {
  validSid(sid);
  const active = sessions.get(sid);
  if (active) {
    if (active.failure) throw Error(active.failure);
    if (fixedProfile !== null && active.entry.profile !== fixedProfile) throw Error('Session belongs to another profile');
    if (cwd && normalize(cwd) !== active.entry.cwd) throw Error('Session belongs to a different project working directory');
    return active;
  }
  let entry;
  try { entry = parse(ownerPath(sid)); }
  catch (_) { throw Error('Cannot verify the original profile of this session. Start a new chat; restoration was refused.'); }
  if (entry.sessionId !== sid || typeof entry.cwd !== 'string' || !entry.cwd) throw Error('Invalid session ownership record');
  if (fixedProfile !== null && entry.profile !== fixedProfile) throw Error('Session belongs to another profile');
  if (cwd && normalize(cwd) !== entry.cwd) throw Error('Session belongs to a different project working directory');
  if (entry.kind === 'setup') {
    if (!sid.startsWith('aiaps-setup-') || entry.profile || entry.fingerprint) throw Error('Invalid setup session ownership record');
    return {entry, backend:null};
  }
  if (entry.kind || !entry.profile || !entry.fingerprint) throw Error('Invalid session ownership record');
  const spec = readConfig().profiles[entry.profile];
  if (!spec || fingerprint(spec) !== entry.fingerprint) throw Error('The original session profile was removed or its launch configuration changed. Restoration was refused.');
  return {entry, spec};
}
function request(backend, method, params, timed = true, limitMs = timeoutMs) {
  return new Promise((resolve, reject) => {
    checkAuthorization(backend);
    if (backend.failed) return reject(Error(backend.failed));
    if (backend.stopPromise && method !== 'session/close') return reject(Error('ACP backend is closing'));
    const id = 'aiaps:' + ++counter;
    const timer = timed ? setTimeout(() => { pending.delete(id); reject(Error('ACP backend request timed out: ' + method)); }, limitMs) : null;
    pending.set(id, {backend, resolve, reject, timer, method, sessionId:params?.sessionId});
    try { backend.proc.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n'); }
    catch (e) { pending.delete(id); clearTimeout(timer); reject(e); }
  });
}
function forgetBackend(backend, reason, preserveFailure = false) {
  backend.failed = reason;
  for (const [id,item] of pending) if (item.backend === backend) {
    pending.delete(id); clearTimeout(item.timer); item.reject(Object.assign(Error(reason), backend.failureCode ? {code:backend.failureCode} : {}));
  }
  for (const [id,item] of upstream) if (item.backend === backend) upstream.delete(id);
  for (const [id,item] of authentication) if (item.backend === backend) authentication.delete(id);
  for (const [id,item] of sessions) if (item.backend === backend) {
    if(preserveFailure)sessions.set(id,{entry:item.entry,failure:reason});
    else sessions.delete(id);
  }
  for (const [key,item] of backends) if (item === backend) backends.delete(key);
  if (negotiatedBackend === backend) negotiatedBackend = null;
  backend.connectionNotifications = []; backend.lastAuthNotification = undefined;
  if (lastNotificationBackend === backend) { lastNotificationBackend = undefined; lastPublishedAuthNotification = undefined; }
}
function lifecycle(backend, event, reason) {
  log(JSON.stringify({event, routerPid:process.pid, routerParentPid:process.ppid, parentPid:process.pid, pid:backend.proc.pid || null,
    profile:backend.profile, agent:backend.spec.agentId || '', purpose:backend.purpose,
    sessionId:backend.sessionId || null, segmentId:backend.virtual?.segment?.id || null,
    reason, activeInstances:backends.size, stoppingInstances:Math.max(0,stoppingBackends.size-(event==='stopped'?1:0))}));
}
function stopBackend(backend, reason = 'released', graceful = true) {
  if (!backend) return Promise.resolve();
  if (backend.stopPromise) return backend.stopPromise;
  backend.stopPromise = Promise.resolve().then(async () => {
    try {
      // Session closure is optional ACP. Unsupported/broken providers still lose their owned process tree.
      if (graceful && !closed && !backend.failed && backend.sessionIds?.size &&
          backend.initialize?.agentCapabilities?.sessionCapabilities?.close != null) {
        for (const sessionId of [...backend.sessionIds])
          await request(backend, 'session/close', {sessionId}, true, Math.min(timeoutMs, 1000)).catch(() => {});
      }
    } finally {
      forgetBackend(backend, 'ACP backend closed: ' + reason);
      lifecycle(backend, 'stopping', reason);
      await stopProcess(backend.proc);
      backend.lines?.close();
      for (const stream of [backend.proc.stdin,backend.proc.stdout,backend.proc.stderr]) stream?.destroy();
      lifecycle(backend, 'stopped', reason);
      backend.virtual = null;
    }
  });
  stoppingBackends.add(backend.stopPromise);
  backend.stopPromise.finally(() => stoppingBackends.delete(backend.stopPromise)).catch(() => {});
  return backend.stopPromise;
}
function backendFailed(backend, reason) {
  if (backend.failed) return;
  forgetBackend(backend, reason, true);
  log(reason);
  stopBackend(backend, 'backend failure', false).catch(() => {});
}
function checkAuthorization(backend) {
  if (backend.failed) return false;
  try { if (authRevision(backend.spec) === backend.authRevision) return true; }
  catch (_) { /* Fail closed if the revocation marker cannot be read. */ }
  backend.failureCode = -32000; // ACP authentication required; the conversation remains restorable.
  backendFailed(backend, 'Profile authorization was revoked. Sign in again to continue this chat.');
  return false;
}
const authMonitor = setInterval(() => { for (const backend of backends.values()) checkAuthorization(backend); }, 100);
authMonitor.unref();
async function backendFor(profile, spec, isolationKey = '') {
  if (closed) throw Error('ACP router is closing');
  if (!initializeParams) throw Error('ACP initialize is required first');
  if (!spec || typeof spec.command !== 'string' || !spec.command.trim()) throw Error('Profile executable is missing');
  if (spec.args !== undefined && (!Array.isArray(spec.args) || spec.args.some(arg => typeof arg !== 'string'))) throw Error('Profile arguments must be an array of strings');
  if (spec.cwd !== undefined && typeof spec.cwd !== 'string') throw Error('Profile working directory must be a string');
  if (spec.env !== undefined && (!spec.env || typeof spec.env !== 'object' || Array.isArray(spec.env) || Object.values(spec.env).some(value => typeof value !== 'string'))) throw Error('Profile environment must contain string values');
  const revision = fingerprint(spec), key = profile + ':' + revision + ':' + isolationKey;
  let backend = backends.get(key);
  if (!backend && virtual && /^[0-9a-f-]{36}$/i.test(isolationKey) && negotiatedBackend &&
      negotiatedBackend.profile === profile && negotiatedBackend.fingerprint === revision &&
      !negotiatedBackend.virtual && !negotiatedBackend.claimed && !negotiatedBackend.failed && !negotiatedBackend.stopPromise) {
    // The first segment can own the initialized negotiation instance; no second tree is needed.
    backend = negotiatedBackend; backend.claimed = true; backend.purpose = 'session';
    for (const [oldKey,item] of backends) if (item === backend) backends.delete(oldKey);
    backends.set(key, backend);
  }
  if (!backend) {
    backend = {profile, spec, fingerprint:revision, authRevision:authRevision(spec), sessionIds:new Set(), authKey:hash(key).slice(0,20),
      purpose:isolationKey.startsWith('catalog:') ? 'catalog' : isolationKey.startsWith('attachment-preview:') ? 'preview' : isolationKey ? 'session' : 'negotiate'};
    const cwd = spec.cwd || process.cwd();
    const launched = directLaunch(spec.command, spec.args || [], cwd);
    const env = applyRuntime(environmentFor(spec), launched.runtimeDir);
    backend.proc = cp.spawn(launched.command, launched.args, {
      cwd, env, stdio:['pipe','pipe','pipe'], windowsHide:true, shell:false, detached:process.platform !== 'win32'
    });
    backends.set(key, backend);
    lifecycle(backend, 'created', 'acquired');
    backend.proc.stderr.on('data', data => process.stderr.write(data));
    backend.proc.on('error', () => backendFailed(backend, 'Could not start ACP profile ' + profile + '. Check its executable and working directory.'));
    backend.proc.stdin.on('error', () => backendFailed(backend, 'ACP input closed for profile ' + profile));
    backend.proc.on('exit', code => backendFailed(backend, 'ACP profile ' + profile + ' exited (' + code + ')'));
    backend.lines = readline.createInterface({input:backend.proc.stdout}).on('line', line => fromBackend(backend, line));
    backend.ready = request(backend, 'initialize', initializeParams).then(response => {
      if (response.error || !response.result) throw Error('ACP initialization failed for profile ' + profile);
      backend.initialize = response.result;
      return backend;
    }).catch(async error => {
      await stopBackend(backend, 'initialization failed', false);
      throw error;
    });
    backend.ready.catch(() => {});
  }
  return backend.ready;
}
function intersection(values) {
  if (!values.length) return {};
  const result = {};
  for (const key of Object.keys(values[0] || {})) {
    const items = values.map(v => v?.[key]);
    if (items.every(v => v === true)) result[key] = true;
    else if (items.every(v => v && typeof v === 'object' && !Array.isArray(v))) result[key] = intersection(items);
    else if (items.every(v => JSON.stringify(v) === JSON.stringify(items[0])) && items[0] !== undefined) result[key] = items[0];
  }
  return result;
}
function authMethods(selected, backend) {
  if (!selected || !backend || backend.initialize.protocolVersion !== 1) return [];
  return (backend.initialize.authMethods || []).map((method, index) => {
    const id = 'aiaps-auth:' + backend.authKey + ':' + index;
    authentication.set(id, {backend, originalId:method.id, cwd:process.cwd()});
    const result = {...method, id, name:(selected.spec.name || selected.profile) + ': ' + method.name};
    const terminal = method._meta?.['terminal-auth'];
    if (method.type === 'terminal' || terminal) {
      const args = [...(!terminal?.command ? selected.spec.authenticationArgs || [] : []), ...(terminal?.args || method.args || [])];
      const terminalEnv = terminal?.env || method.env || {};
      const login = {command:terminal?.command, args, envKeys:Object.keys(terminalEnv), cwd:process.cwd(), authRevision:backend.authRevision,
        chatUid:virtual?.clientChatId(initializeParams), fingerprint:backend.fingerprint,
        ...(selected.conversation ? {conversationFingerprint:selected.conversation.fingerprint} : {})};
      const wrapper = [configPath, '--profile-login', selected.profile,
        crypto.createHash('sha256').update(JSON.stringify(selected.spec)).digest('hex'), JSON.stringify(login)];
      // AI Multi's native baseArgs are empty and its ACP args are omitted for
      // terminal login. Supply the full router invocation in metadata args.
      // Omit command: JetBrains otherwise strips a node script from these args.
      result.type = 'agent';
      delete result.args; delete result.env;
      result._meta = {...method._meta, 'terminal-auth':{label:terminal?.label,
        args:[__filename, ...wrapper], env:{...terminalEnv, ...selected.spec.env}}};
    }
    return result;
  });
}
function supports(actual, required) {
  return Object.entries(required || {}).every(([key,value]) => value && typeof value === 'object'
    ? actual?.[key] != null && typeof actual[key] === 'object' && supports(actual[key],value)
    : !value || JSON.stringify(actual?.[key]) === JSON.stringify(value));
}
async function checkedBackend(profile, spec) {
  const backend = await backendFor(profile, spec);
  const required = {...advertised.agentCapabilities};
  if (virtual) { delete required.loadSession; delete required.sessionCapabilities; }
  if (backend.initialize.protocolVersion !== advertised.protocolVersion || !supports(backend.initialize.agentCapabilities, required)) {
    throw Error('Profile ACP capabilities differ from the initialized router. Start a new chat to negotiate capabilities.');
  }
  return backend;
}
async function selectedBackend() {
  // Agent-level extensions belong to the backend currently bound to this chat,
  // including an explicitly selected profile and a subsequent profile switch.
  const current=await virtual?.contextBackend();
  if(current) {
    checkAuthorization(current);
    if(current.failed)throw Error(current.failed);
    return current;
  }
  const selected = virtual?.startProfile(process.cwd()) || route(process.cwd());
  if (!negotiatedBackend || !selected || selected.profile !== negotiatedBackend.profile || fingerprint(selected.spec) !== negotiatedBackend.fingerprint) {
    throw Error('Project profile changed or is not selected. Start a new chat to negotiate its capabilities.');
  }
  checkAuthorization(negotiatedBackend);
  if (negotiatedBackend.failed) throw Error(negotiatedBackend.failed);
  return negotiatedBackend;
}
function requireSessionCapability(backend, name) {
  if (backend.initialize.agentCapabilities?.sessionCapabilities?.[name] == null) throw Error('Profile does not support session/' + name);
}
async function fromClient(line) {
  let msg;
  try { msg=JSON.parse(line); } catch (_) { fail({id:null}, 'Invalid ACP JSON', -32700); return; }
  if (!msg || typeof msg !== 'object' || Array.isArray(msg)) { fail({id:null}, 'Invalid ACP request', -32600); return; }
  try {
    if (process.argv.includes('--profile-setup') && msg.method) {
      const spec = readConfig().profiles.probe;
      if (!spec) throw Error('Selected provider is unavailable');
      if (!initializeParams) initializeParams = spec.probeInitialize || {protocolVersion:1,clientCapabilities:{
        auth:{terminal:true},elicitation:{url:{},form:{}},_meta:{'terminal-auth':true}},clientInfo:{name:'AI Multi profile setup',version:'1'}};
      const backend = await backendFor('probe',spec);
      if (backend.initialize.protocolVersion !== 1) throw Error('Selected provider does not support ACP v1');
      const methods = backend.initialize.authMethods || [];
      if (msg.method === '_ai_multi/setup') {
        send({jsonrpc:'2.0',id:msg.id,result:{authMethods:methods}});return;
      }
      if (msg.method === '_ai_multi/authorize') {
        const method=methods.find(m=>m.id===msg.params?.methodId);
        if (!method) throw Error('Choose an advertised authentication method');
        if (method.type==='terminal' || method._meta?.['terminal-auth']) throw Error('Use terminal authentication for this method');
        const response = await request(backend,'authenticate',{methodId:msg.params.methodId},false);
        if (!response.error) backend.setupAuthenticated = true;
        send(response.error ? {jsonrpc:'2.0',id:msg.id,error:{code:response.error.code,message:'Agent authorization did not complete'}}
          : {jsonrpc:'2.0',id:msg.id,result:{}});return;
      }
      if (msg.method === '_ai_multi/login_spec') {
        const method = methods.find(m=>m.id===msg.params?.methodId), terminal=method?._meta?.['terminal-auth'];
        if (!method || !(method.type==='terminal' || terminal)) throw Error('This method does not use terminal authentication');
        const cwd=spec.cwd || process.cwd();
        const launch=directLaunch(terminal?.command || spec.command,
          [...(!terminal?.command ? spec.authenticationArgs || [] : []),...(terminal?.args || method.args || [])],cwd);
        const env=applyRuntime({...terminal?.env,...method.env,...spec.env},launch.runtimeDir);
        send({jsonrpc:'2.0',id:msg.id,result:{command:launch.command,args:launch.args,cwd,env,inheritEnvironment:spec.inheritEnvironment!==false}});return;
      }
      if (msg.method === '_ai_multi/defaults') {
        // Honor a fresh connection's ACP hint, never replace an explicit selection.
        const selected=backend.initialize._meta?.defaultAuthMethodId;
        if (!backend.setupAuthenticated && typeof selected==='string' && methods.some(m=>m.id===selected)) {
          const auth=await request(backend,'authenticate',{methodId:selected},false);
          if (auth.error) throw Error('Authorization expired. Authorize this profile again.');
        }
        let result;
        try {
          const response=await request(backend,'session/new',{cwd:msg.params?.cwd || process.cwd(),mcpServers:[]});
          if(response.error)throw Error('Authorization is required or expired. Authorize this profile, then refresh session options.');
          result={options:spec.discoverModels?profileSettings.modelDefaults(response.result):profileSettings.descriptors(response.result),observedAt:Date.now(),warnings:[],
            models:spec.discoverModels?await profileSettings.discoverModels(spec,response.result,(method,params)=>request(backend,method,params),
              values=>log('Model-default metadata '+JSON.stringify({agentId:spec.agentId,agentVersion:backend.initialize.agentInfo?.version || '',
                recommendationRequested:initializeParams.clientCapabilities?._meta?.jetbrains?.air?.capabilities?.includes('recommendedValue')===true,...values}))):[]};
        } finally { await stopBackend(backend, 'profile setup discovery finished'); }
        send({jsonrpc:'2.0',id:msg.id,result});
        return;
      }
      throw Error('Only profile authorization and session options are available on this connection');
    }
    if (process.argv.includes('--probe-defaults') && msg.method) {
      if (msg.method !== '_ai_multi/defaults') throw Error('Only defaults discovery is available on this connection');
      const spec = readConfig().profiles[msg.params?.profile];
      if (!spec) throw Error('Selected provider is unavailable');
      initializeParams = {protocolVersion:1,clientCapabilities:{},clientInfo:{name:'AI Multi defaults',version:'1'}};
      const backend = await backendFor(msg.params.profile,spec);
      if (backend.initialize.protocolVersion !== 1) throw Error('Selected provider does not support ACP v1');
      let result;
      try {
        const response = await request(backend,'session/new',{cwd:msg.params.cwd,mcpServers:[]});
        if (response.error) throw Error('Could not obtain provider defaults. Check this profile\'s authorization and try again.');
        result={options:profileSettings.descriptors(response.result),observedAt:Date.now(),warnings:[]};
      } finally { await stopBackend(backend, 'defaults probe finished'); }
      send({jsonrpc:'2.0',id:msg.id,result});
      return;
    }
    if (!msg.method) {
      const saved = upstream.get(msg.id);
      if (!saved) return;
      upstream.delete(msg.id);
      if (checkAuthorization(saved.backend)) saved.backend.proc.stdin.write(JSON.stringify({...msg,id:saved.id})+'\n');
      return;
    }
    if (msg.method === 'initialize') {
      const offered={...(msg.params || {}),protocolVersion:1};
      if (initializeParams) {
        if (hash(initializeParams)!==hash(offered))throw Error('Router is already initialized with different parameters');
        if (initializationPromise)await initializationPromise;
        if (!advertised)throw Error('ACP initialization did not complete');
        send({jsonrpc:'2.0',id:msg.id,result:advertised});return;
      }
      initializeParams=offered;
      initializationPromise=(async()=>{
      const restoredInitialization = await virtual?.initializeFor(initializeParams);
      if (restoredInitialization) {
        advertised = restoredInitialization;
        send({jsonrpc:'2.0',id:msg.id,result:advertised}); flushConnectionNotifications(); return;
      }
      const selected = virtual?.selectedForNewChat(initializeParams) || route(process.cwd());
      if (selected) {
        // Capabilities belong to this project's profile. Other configured agents
        // must not disable images or native UI extensions on its connection.
        negotiatedBackend = await backendFor(selected.profile, selected.spec);
        if (negotiatedBackend.initialize.protocolVersion !== 1) throw Error('Profile does not support ACP v1');
        advertised = {...negotiatedBackend.initialize, authMethods:authMethods(selected, negotiatedBackend)};
        if (virtual) {
          // Capabilities remain those of this profile's actual agent.
          advertised._meta = {...advertised._meta, 'ai-multi':{virtualSessions:true,controlVersion:1}};
        }
        send({jsonrpc:'2.0', id:msg.id, result:advertised});
        flushConnectionNotifications();
        return;
      }
      // Without a project assignment, keep a conservative setup connection.
      const cfg = readConfig();
      const results = await Promise.allSettled(Object.entries(cfg.profiles).map(([id,spec]) => backendFor(id,spec)));
      const ready = results.filter(r => r.status === 'fulfilled').map(r => r.value.initialize);
      const protocolVersion = 1;
      const compatible = ready.filter(r => r.protocolVersion === protocolVersion);
      const capabilities = intersection(compatible.map(r => r.agentCapabilities || {}));
      // Unscoped listing and forked IDs need separate ownership handling. Do not
      // advertise optional methods that the router cannot route safely.
      delete capabilities._meta;
      if (capabilities.sessionCapabilities) {
        for (const key of ['list','fork','delete','_meta']) delete capabilities.sessionCapabilities[key];
      }
      advertised = {
        protocolVersion,
        agentCapabilities:capabilities,
        authMethods:[], agentInfo:{name:'ai-agent-project-switcher', title:'AI Multi', version:'0.2.0-dev.10'}
      };
      send({jsonrpc:'2.0', id:msg.id, result:advertised});
      return;
      })().catch(async error=>{
        await Promise.allSettled([...backends.values()].map(backend=>stopBackend(backend,'initialization failed',false)));
        initializeParams=undefined;advertised=undefined;negotiatedBackend=undefined;
        virtual?.resetInitialization();throw error;
      }).finally(()=>{initializationPromise=undefined;});
      await initializationPromise;return;
    }

    if (!advertised) throw Error('Wait for ACP initialization before starting a session');
    if (virtual && await virtual.handle(msg, msg.method === 'session/new' ? virtual.startProfile(msg.params?.cwd) || route(msg.params?.cwd) : null)) return;
    if (msg.method === 'authenticate') {
      let saved = authentication.get(msg.params?.methodId);
      if (!saved && virtual && await virtual.rehydrateAuth(msg.params?.methodId)) saved = authentication.get(msg.params?.methodId);
      if (!saved) throw Error('Unknown profile authentication method; start a new chat');
      const selected = route(saved.cwd);
      const explicit = virtual?.hasProfileContext(saved.backend.profile, saved.backend.fingerprint, saved.cwd);
      if (!explicit && (!selected || selected.profile !== saved.backend.profile || fingerprint(selected.spec) !== saved.backend.fingerprint)) {
        throw Error('Project profile changed; start a new chat to sign in to the selected profile');
      }
      send({...await request(saved.backend, msg.method, {...msg.params, methodId:saved.originalId}, false), id:msg.id});
      return;
    }
    if (msg.method === 'session/new') {
      const selected = route(msg.params?.cwd);
      if (!selected) {
        send({jsonrpc:'2.0', id:msg.id, result:newSetup(msg.params.cwd)});
        return;
      }
      selected.spec=await creationSpec(selected.profile,selected.spec,msg.params.cwd);
      const backend = await checkedBackend(selected.profile, selected.spec);
      const response = await request(backend,msg.method,msg.params);
      if (response.result?.sessionId) {
        const configured=await configureSession(selected.profile,selected.spec,response.result,backend,msg.params.cwd);
        response.result=configured.result;
        record(response.result.sessionId,backend,msg.params.cwd);
      }
      send({...response,id:msg.id}); return;
    }
    if (msg.method === 'session/list') {
      const backend = await virtual?.listingBackend() || await selectedBackend();
      requireSessionCapability(backend, 'list');
      const response = await request(backend, msg.method, msg.params);
      if (response.result) {
        if (!Array.isArray(response.result.sessions)) throw Error('Invalid profile session list');
        const discovered = [];
        for (const entry of response.result.sessions) {
          if (typeof entry.cwd !== 'string' || !path.isAbsolute(entry.cwd)) continue;
          if (msg.params?.cwd && normalize(entry.cwd) !== normalize(msg.params.cwd)) continue;
          // Backend discovery proves ownership for native history too. A listed
          // session remains inactive until load/resume, and conflicting immutable
          // records are never reassigned to a different profile.
          try {
            const mapped=virtual?virtual.listedEntry(backend,entry):entry;
            if(!mapped)continue;
            if(mapped.sessionId===entry.sessionId)record(entry.sessionId, backend, entry.cwd, false);
            discovered.push(mapped);
          }
          catch (e) { log('Session discovery refused:', e.message); }
        }
        response.result = {...response.result, sessions:discovered};
      }
      send({...response,id:msg.id}); return;
    }
    if (msg.method === 'session/fork' || msg.method === 'session/delete') {
      const saved = owner(msg.params?.sessionId);
      if (saved.entry.kind === 'setup') throw Error('Local setup sessions cannot be forked or deleted through a profile');
      const backend = saved.backend || await checkedBackend(saved.entry.profile, saved.spec);
      requireSessionCapability(backend, msg.method.slice('session/'.length));
      if (msg.method === 'session/fork' && (typeof msg.params?.cwd !== 'string' || !path.isAbsolute(msg.params.cwd))) throw Error('An absolute project working directory is required for fork');
      const response = await request(backend, msg.method, msg.params);
      if (response.result && msg.method === 'session/fork') {
        validSid(response.result.sessionId);
        if (response.result.sessionId === saved.entry.sessionId) throw Error('Fork did not create a new sessionId');
        record(response.result.sessionId, backend, msg.params.cwd);
      }
      send({...response,id:msg.id}); return;
    }
    if (msg.method === 'session/load' || msg.method === 'session/resume') {
      if (!msg.params?.cwd) throw Error('A project working directory is required for restore');
      const saved = owner(msg.params?.sessionId,msg.params.cwd);
      if (saved.entry.kind === 'setup') {
        sessions.set(saved.entry.sessionId, saved);
        send({jsonrpc:'2.0', id:msg.id, result:msg.method === 'session/resume' ? {sessionId:saved.entry.sessionId} : {}});
        return;
      }
      const backend = saved.backend || await checkedBackend(saved.entry.profile,saved.spec);
      const response = await request(backend,msg.method,msg.params);
      if (response.result) {
        if (response.result.sessionId && response.result.sessionId !== saved.entry.sessionId) throw Error('Restored sessionId differs from its ownership record');
        sessions.set(saved.entry.sessionId,{entry:saved.entry,backend});
      }
      send({...response,id:msg.id}); return;
    }
    if (msg.method.startsWith('session/')) {
      // Persisted ownership is not enough: a session must first be loaded into this process.
      const saved = sessions.get(msg.params?.sessionId);
      if (!saved) throw Error('Unknown active session; restore it through its original profile first');
      if (saved.failure) throw Error(saved.failure);
      if (saved.entry.kind === 'setup') { setupRequest(msg); return; }
      checkAuthorization(saved.backend);
      if (saved.backend.failed) throw Error(saved.backend.failed);
      if (msg.id === undefined) saved.backend.proc.stdin.write(JSON.stringify(msg)+'\n');
      else {
        const response=await request(saved.backend,msg.method,msg.params,msg.method==='session/close');
        if(msg.method==='session/close' && !response.error) {
          sessions.delete(msg.params.sessionId);
          if(!saved.backend.sessionIds.size)await stopBackend(saved.backend,'native session closed',false);
        }
        send({...response,id:msg.id});
      }
      return;
    }
    // Agent extensions (including those advertised in _meta) retain their native
    // request/response shapes. Unscoped calls go only to the negotiated profile.
    const backend = await selectedBackend();
    if (msg.id === undefined) backend.proc.stdin.write(JSON.stringify(msg)+'\n');
    else send({...await request(backend,msg.method,msg.params,false),id:msg.id});
  } catch (e) { fail(msg,e.message,e.code || -32602,e.data); }
}
function fromBackend(backend,line) {
  if (!checkAuthorization(backend)) return;
  let msg;
  try { msg=JSON.parse(line); } catch (_) { log('Invalid ACP output for profile',backend.profile); return; }
  if (!msg || typeof msg !== 'object' || Array.isArray(msg)) return;
  if (connectionNotification(backend, msg)) return;
  if (msg.id !== undefined && !msg.method) {
    const item=pending.get(msg.id);
    if (!item || item.backend !== backend) { log('Unmatched backend response blocked'); return; }
    pending.delete(msg.id); clearTimeout(item.timer);
    if (msg.result && ['session/new','session/load','session/resume','session/fork'].includes(item.method)) {
      const sid=msg.result.sessionId || item.sessionId;
      if (typeof sid === 'string') { backend.sessionIds.add(sid); backend.sessionId = sid; lifecycle(backend,'session acquired',item.method); }
    }
    if (msg.result && item.method === 'session/close') backend.sessionIds.delete(item.sessionId);
    item.resolve(msg); return;
  }
  const wasVirtual = !!backend.virtual;
  if (virtual && virtual.fromBackend(backend,msg)) return;
  if (!wasVirtual && msg.params?.sessionId) {
    const active=sessions.get(msg.params.sessionId);
    const creating=[...pending.values()].some(p => p.backend === backend && ['session/new','session/load','session/resume','session/fork'].includes(p.method));
    if ((active && active.backend !== backend) || (!active && !creating)) { log('Cross-profile or unknown session event blocked'); return; }
  } else if (!wasVirtual && (!msg.method || ![...pending.values()].some(p => p.backend === backend))) {
    log('Unscoped backend event blocked'); return;
  }
  if (msg.id !== undefined && msg.method) {
    const id='aiaps-up:'+ ++counter;
    upstream.set(id,{backend,id:msg.id}); send({...msg,id});
  } else send(msg);
}
let shutdownPromise;
function shutdown() {
  if (shutdownPromise) return shutdownPromise; closed=true;
  clearInterval(authMonitor);
  fs.unwatchFile(configPath,refreshModelFilters);
  shutdownPromise = Promise.resolve().then(async () => {
    const stops=[...backends.values()].map(backend=>stopBackend(backend, 'router shutdown', false));
    await virtual?.close();
    await Promise.allSettled([...stops,...stoppingBackends]);
    for (const item of pending.values()) { clearTimeout(item.timer); item.reject(Error('ACP router closed')); }
    pending.clear(); upstream.clear(); authentication.clear(); sessions.clear();
    clientLines.close();
  });
  return shutdownPromise;
}
const clientLines=readline.createInterface({input:process.stdin}).on('line', line => { if (!closed) fromClient(line).catch(e => log(e.message)); });
process.stdin.on('end',() => { shutdown().catch(() => {}); });
process.stdin.on('error',() => { shutdown().catch(() => {}); });
process.stdout.on('error',() => { shutdown().catch(() => {}); });
process.stderr.on('error',() => {});
process.on('SIGTERM',() => { shutdown().finally(() => process.exit(0)); });
process.on('SIGINT',() => { shutdown().finally(() => process.exit(0)); });
}
