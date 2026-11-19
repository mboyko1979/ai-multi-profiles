'use strict';
// Grok 1.0.90 does not advertise an ACP session mode. Air therefore has no mode
// dropdown for it. This module supplies that dropdown and applies the choice by
// stopping the backend session and resuming it with Grok's session meta.
const fs = require('fs'), path = require('path');
const MODES = [
  {id:'ask', name:'Ask', description:'Ask before each action'},
  {id:'auto', name:'Auto-review', description:'Review routine actions and ask when unsure'},
  {id:'always-approve', name:'Always approve', description:'Run actions without asking'}
];
function preferred(spec, saved) {
  const chosen = (saved || []).find(item => item?.operation === 'mode' && IDS.has(item.value));
  return chosen ? chosen.value : savedMode(spec);
}
function owns(result) {
  const ids = new Set((result?.modes?.availableModes || []).map(item => item?.id));
  return ids.size === MODES.length && MODES.every(item => ids.has(item.id));
}
const IDS = new Set(MODES.map(mode => mode.id));
function isGrok(spec) {
  return `${spec?.agentId || ''} ${spec?.agentName || ''}`.toLowerCase().includes('grok');
}
function lacksModes(result) {
  return !Array.isArray(result?.modes?.availableModes) || result.modes.availableModes.length === 0;
}
function needsCustom(spec, result) {
  return isGrok(spec) && lacksModes(result);
}
function assertMode(mode) {
  if (!IDS.has(mode)) throw Error('Unknown Grok permission mode');
  return mode;
}
function home(spec) {
  const dir = spec?.env?.GROK_HOME;
  return typeof dir === 'string' && dir.trim() ? dir : null;
}
function readMode(text) {
  let section = '';
  for (const line of String(text || '').split(/\r?\n/)) {
    const table = line.match(/^\s*\[([^\]]+)\]\s*$/);
    if (table) { section = table[1].trim(); continue; }
    if (section !== 'ui') continue;
    const match = line.match(/^\s*permission_mode\s*=\s*["']?([A-Za-z-]+)["']?\s*$/);
    if (match && IDS.has(match[1])) return match[1];
  }
  return null;
}
function writeText(text, mode) {
  assertMode(mode);
  const lines = String(text || '').split(/\r?\n/);
  let section = '', ui = -1, key = -1;
  lines.forEach((line, index) => {
    const table = line.match(/^\s*\[([^\]]+)\]\s*$/);
    if (table) {
      if (section === 'ui' && key < 0 && ui >= 0) key = index;
      section = table[1].trim();
      if (section === 'ui' && ui < 0) ui = index;
      return;
    }
    if (section === 'ui' && /^\s*permission_mode\s*=/.test(line)) key = index;
  });
  const assignment = `permission_mode = "${mode}"`;
  if (key >= 0 && key < lines.length && /^\s*permission_mode\s*=/.test(lines[key])) lines[key] = assignment;
  else if (ui >= 0) lines.splice(key >= 0 ? key : ui + 1, 0, assignment);
  else lines.push((lines.length && lines[lines.length - 1] !== '' ? '' : ''), '[ui]', assignment);
  return lines.join('\n').replace(/\s*$/, '\n');
}
function savedMode(spec) {
  const dir = home(spec);
  if (!dir) return 'ask';
  try { return readMode(fs.readFileSync(path.join(dir, 'config.toml'), 'utf8')) || 'ask'; }
  catch (_) { return 'ask'; }
}
function writeMode(spec, mode) {
  const dir = home(spec);
  if (!dir) return false;
  const file = path.join(dir, 'config.toml');
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); } catch (_) {}
  fs.mkdirSync(dir, {recursive:true});
  const tmp = file + '.' + process.pid + '.tmp';
  fs.writeFileSync(tmp, writeText(text, mode));
  fs.renameSync(tmp, file);
  return true;
}
function metaFor(mode) {
  assertMode(mode);
  if (mode === 'auto') return {autoMode:true, yoloMode:false};
  if (mode === 'always-approve') return {yoloMode:true, autoMode:false};
  return {autoMode:false, yoloMode:false};
}
function withMode(params, mode) {
  return {...params, _meta:{...(params?._meta || {}), ...metaFor(mode)}};
}
function advertise(result, mode) {
  assertMode(mode);
  return {...result, modes:{currentModeId:mode, availableModes:MODES.map(item => ({...item})), name:'Permission mode'}};
}
async function handleSetMode(spec, request, session, mode) {
  // The file must be current before a restarted process reads it. autoMode:false
  // does not turn Auto-review off inside a process that already started in auto.
  writeMode(spec, mode);
  const applied = await reapply(request, session, mode);
  return {result:advertise({...applied, sessionId:session.sessionId}, mode)};
}
// Pause the live session and start it again in the selected mode.
// A caller that can replace the process sets session.restart. Grok reads
// permission_mode when the process starts, and resume meta can turn Auto-review
// or always-approve on, but cannot turn either off.
async function reapply(request, session, mode) {
  assertMode(mode);
  if (session.cancel) session.cancel();
  if (session.restart) await session.restart();
  else {
    const closed = await request('session/close', {sessionId:session.sessionId});
    if (closed?.error) throw Object.assign(Error(closed.error.message || 'Could not stop the Grok session'), {code:closed.error.code, data:closed.error.data});
  }
  const resumed = await request('session/resume', withMode({
    sessionId:session.sessionId, cwd:session.cwd, mcpServers:session.mcpServers || []
  }, mode));
  if (resumed?.error) throw Object.assign(Error(resumed.error.message || 'Could not resume the Grok session'), {code:resumed.error.code, data:resumed.error.data});
  if (resumed.result?.sessionId && resumed.result.sessionId !== session.sessionId) throw Error('Grok resume changed the session id');
  return resumed.result || {};
}
module.exports = {MODES, isGrok, lacksModes, needsCustom, owns, preferred, assertMode, readMode, writeText, savedMode, writeMode, metaFor, withMode, advertise, reapply, handleSetMode};
