'use strict';
// Public ACP configuration only. Authentication payloads and opaque metadata never enter this cache.
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const sensitive=id=>/access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|secret|password|api[_-]?key|credential|^token$/i.test(id || '');
const short=value=>typeof value==='string' && value.length<=4096?value:null;
function choices(items) {
  return (Array.isArray(items)?items:[]).flatMap(x=>Array.isArray(x?.options)?choices(x.options):
    short(x?.value)!==null && short(x?.name)!==null?[{value:x.value,name:x.name}]:[]);
}
function descriptors(result={}) {
  const options=[];
  for(const o of Array.isArray(result.configOptions)?result.configOptions:[]) {
    if(!o || !short(o.id) || sensitive(o.id))continue;
    const item={id:o.id,name:short(o.name)||o.id,description:short(o.description)||'',category:short(o.category)||'',operation:'config'};
    if(o.type==='select') {item.type='select';item.options=choices(o.options);if(!item.options.length)continue;}
    else if(o.type==='boolean')item.type='boolean';
    else continue; // Future types remain provider-owned; never guess secret/free-text semantics.
    if(typeof o.currentValue==='boolean' || short(o.currentValue)!==null)item.currentValue=o.currentValue;
    options.push(item);
  }
  if(!options.some(x=>x.category==='model') && Array.isArray(result.models?.availableModels))options.push({
    id:'model',operation:'model',type:'select',category:'model',name:'Model',description:'',currentValue:result.models.currentModelId,
    options:result.models.availableModels.filter(x=>short(x.modelId)!==null).map(x=>({value:x.modelId,name:short(x.name)||x.modelId}))});
  if(!options.some(x=>x.category==='mode') && Array.isArray(result.modes?.availableModes))options.push({
    id:'mode',operation:'mode',type:'select',category:'mode',name:'Mode',description:'',currentValue:result.modes.currentModeId,
    options:result.modes.availableModes.filter(x=>short(x.id)!==null).map(x=>({value:x.id,name:short(x.name)||x.id}))});
  return options;
}
function cache(directory,profile,spec,result,initialize,warnings=[]) {
  if(!spec.configurationKey)return;
  try {
    const dir=path.join(directory,'capabilities');fs.mkdirSync(dir,{recursive:true});
    const file=path.join(dir,crypto.createHash('sha256').update(profile).digest('hex')+'.json');
    const data={version:1,configurationKey:spec.configurationKey,observedAt:Date.now(),agentVersion:short(initialize?.agentInfo?.version)||'',options:descriptors(result),warnings};
    const tmp=file+'.'+crypto.randomUUID()+'.tmp';
    try {fs.writeFileSync(tmp,JSON.stringify(data),{mode:0o600});fs.renameSync(tmp,file);}finally{try{fs.unlinkSync(tmp);}catch(_) {}}
  } catch(_) { /* A settings preview cache must not fail a live conversation. */ }
}
function catalog(directory,profile,spec,result,defaults=false) {
  if(!spec.catalogKey)return;
  try {
    const dir=path.join(directory,'catalogs');fs.mkdirSync(dir,{recursive:true});
    const file=path.join(dir,crypto.createHash('sha256').update(profile).digest('hex')+'.json');
    let previous=null;try{previous=JSON.parse(fs.readFileSync(file,'utf8'));}catch(_){}
    if(previous?.catalogKey!==spec.catalogKey)previous=null;
    const options=descriptors(result).map(o=>defaults?o:{...o,currentValue:previous?.options?.find(x=>x.id===o.id&&x.operation===o.operation)?.currentValue??null});
    const tmp=file+'.'+crypto.randomUUID()+'.tmp';
    try{fs.writeFileSync(tmp,JSON.stringify({version:1,catalogKey:spec.catalogKey,observedAt:Date.now(),options,warnings:[]}),{mode:0o600});fs.renameSync(tmp,file);}
    finally{try{fs.unlinkSync(tmp);}catch(_){}}
  }catch(_){/* Catalog failure never interrupts a live chat. */}
}
function forClient(spec,initialize) {
  const name=initialize?.clientInfo?.name || '';
  const host=name.startsWith('air-plugin:')?'air':(/^JetBrains[ .]/.test(name) || initialize?.clientInfo?._meta?.jbai===true)?'assistant':'';
  const select=items=>(items || []).filter(x=>!x.host || x.host===host);
  return {...spec,settingsHost:host,clientDefaults:spec.clientDefaults?.filter(x=>x.host===host && x.agentId===(spec.agentId || '')),
    sessionSettings:select(spec.sessionSettings),agentDefaults:select(spec.agentDefaults)};
}
function concrete(spec) {return spec.clientDefaults?.find(x=>x.agentId===(spec.agentId || ''));}
function preferences(spec,modelId) {
  if(Array.isArray(spec.clientDefaults)) {
    const d=concrete(spec);if(!d)return {saved:[],frozen:new Set()};
    const model=d.models.find(x=>x.modelId===(modelId ?? d.defaultModelId));
    const settings=(model?.settings || []).filter(x=>!sensitive(x.id));
    const selected=d.modelOptionId?[{agentId:spec.agentId,operation:d.modelOperation,id:d.modelOptionId,value:modelId ?? d.defaultModelId}]:[];
    return {saved:[...selected,...settings],frozen:new Set()};
  }
  if(spec.frozenDefaultsRequired && spec.defaultsAgentId!==(spec.agentId || ''))
    throw Error('Refresh agent defaults in AI Multi Profiles and apply the profile settings before starting a new session.');
  const explicit=(spec.sessionSettings || []).filter(x=>x.agentId===(spec.agentId || '') && !sensitive(x.id));
  const overrides=new Set(explicit.map(x=>JSON.stringify([x.operation,x.id])));
  const frozen=(spec.agentDefaults || []).filter(x=>x.agentId===(spec.agentId || '') && !sensitive(x.id)
    && !overrides.has(JSON.stringify([x.operation,x.id])));
  return {saved:[...frozen,...explicit],frozen:new Set(frozen)};
}
async function apply(spec,result,request) {
  if(Array.isArray(spec.clientDefaults))return applyConcrete(spec,result,request);
  let current={...result};const warnings=[];
  const agentDecides=spec.automaticDefaults===true;
  const {saved,frozen}=preferences(spec);
  // Models first: their accepted response may change the remaining option catalogs.
  const rank=x=>x.operation==='model' || descriptors(current).find(o=>o.id===x.id && o.operation===x.operation)?.category==='model'?0:1;
  const ordered=saved.map((x,i)=>({x,i,rank:rank(x)})).sort((a,b)=>a.rank-b.rank || a.i-b.i).map(x=>x.x);
  const blocked=new Map();
  const match=setting=>descriptors(current).find(o=>o.id===setting.id && o.operation===setting.operation);
  const valueFor=(option,setting)=>option.type==='boolean' && ['true','false'].includes(setting.value)?setting.value==='true':setting.value;
  const allowed=(option,setting)=>option.type==='boolean'?['true','false'].includes(setting.value):option.options.some(o=>o.value===setting.value);
  // A provider change can reveal an option that was unavailable earlier in this pass.
  // Reconsider the catalog until stable, with a strict bound for mutually dependent/cyclic options.
  for(let pass=0;pass<=ordered.length;pass++) {
    let changed=false;
    for(const setting of ordered) {
      if(blocked.has(setting))continue;
      const option=match(setting) || (agentDecides?{id:setting.id,operation:setting.operation}:null);
      if(!option || (!agentDecides && !allowed(option,setting)))continue;
      const value=valueFor(option,setting);if(option.currentValue===value)continue;
      const method=option.operation==='config'?'session/set_config_option':option.operation==='model'?'session/set_model':'session/set_mode';
      const params={sessionId:result.sessionId,...(option.operation==='config'?{configId:option.id,value,...(option.type==='boolean'?{type:'boolean'}:{})}:option.operation==='model'?{modelId:value}:{modeId:value})};
      const response=await request(method,params);
      if(response.error){
        if(agentDecides)throw Object.assign(Error(response.error.message || 'Agent rejected saved option: '+setting.id),
          {code:response.error.code,data:response.error.data});
        blocked.set(setting,'Agent rejected saved option: '+setting.id);continue;
      }
      if(response.result?.sessionId && response.result.sessionId!==result.sessionId)
        throw Error('Agent attempted to replace the session while applying a preset. Adjust the preset and open a new session.');
      current={...current,...response.result,sessionId:result.sessionId};changed=true;
      // Legacy setters acknowledge the selected ID with an empty response.
      if(option.operation==='model')current.models={...current.models,currentModelId:value};
      if(option.operation==='mode')current.modes={...current.modes,currentModeId:value};
      if(option.operation==='config' && !Array.isArray(response.result?.configOptions))
        throw Error('Agent did not confirm the preset configuration. Choose the agent default in AI Multi Profiles and open a new session.');
      if(match(setting)?.currentValue!==value)
        blocked.set(setting,'Agent selected a different effective value: '+setting.id);
    }
    if(!changed)break;
  }
  for(const setting of ordered) {
    const before=warnings.length;
    const option=match(setting);
    if(blocked.has(setting))warnings.push(blocked.get(setting));
    else if(!option)warnings.push('Saved option unavailable: '+setting.id);
    else if(!agentDecides && !allowed(option,setting))warnings.push('Saved value unavailable: '+setting.id);
    else if(option.currentValue!==valueFor(option,setting))warnings.push('Agent changed the effective preset value: '+setting.id);
    if(agentDecides && warnings.length!==before)
      throw Error(warnings.at(-1)+'. Review this profile in AI Multi Profiles.');
    // Discovery and the client can expose different representations of the same
    // provider option. Keep unavailable observations as warnings, without guessing
    // a conversion or preventing creation of a session using the real agent value.
    if(frozen.has(setting) && option && allowed(option,setting) && warnings.length!==before)
      throw Error('The agent could not apply the saved profile default for '+setting.id+'. Refresh agent defaults in AI Multi Profiles.');
  }
  return {result:current,warnings};
}
function selectedModel(result) {const m=descriptors(result).find(o=>o.category==='model' || o.operation==='model');return m?.currentValue;}
// One precedence/application policy for every native client. Stores expose exact
// ACP option IDs/values; missing and obsolete preferences never become defaults.
async function applyRemembered(spec,result,settings,request) {
  let current=result;
  const configured=new Set(preferences(spec).saved.map(x=>JSON.stringify([x.operation,x.id])));
  const saved=(settings || []).filter(x=>!sensitive(x.id) && !configured.has(JSON.stringify([x.operation,x.id])));
  const rank=x=>x.operation==='model' || descriptors(current).find(o=>o.id===x.id && o.operation===x.operation)?.category==='model'?0:1;
  const ordered=saved.map((x,i)=>({x,i,rank:rank(x)})).sort((a,b)=>a.rank-b.rank || a.i-b.i).map(x=>x.x);
  for(const setting of ordered) {
    const option=descriptors(current).find(o=>o.id===setting.id && o.operation===setting.operation);
    if(!option || !(option.type==='boolean'?['true','false'].includes(String(setting.value)):option.options.some(x=>x.value===setting.value)))continue;
    if(option.category==='model' && concrete(spec)?.models.some(x=>x.modelId===setting.value && !x.enabled))continue;
    const value=option.type==='boolean'?String(setting.value)==='true':setting.value;
    if(option.currentValue===value)continue;
    const method=option.operation==='config'?'session/set_config_option':option.operation==='model'?'session/set_model':'session/set_mode';
    const params={sessionId:result.sessionId,...(option.operation==='config'?{configId:option.id,value,...(option.type==='boolean'?{type:'boolean'}:{})}:option.operation==='model'?{modelId:value}:{modeId:value})};
    const response=await request(method,params);
    if(response.error)throw Object.assign(Error(response.error.message),{code:response.error.code,data:response.error.data});
    if(response.result?.sessionId && response.result.sessionId!==result.sessionId)throw Error('Agent replaced the session while applying client preferences');
    current={...current,...response.result};
    if(option.operation==='model')current.models={...current.models,currentModelId:value};
    if(option.operation==='mode')current.modes={...current.modes,currentModeId:value};
  }
  // A remembered model can reset dependent parameters. Reassert only explicit
  // profile settings after that change; never let a dependency reverse precedence.
  return (await apply(spec,current,request)).result;
}
function presetFailure(spec,current,settings,reason) {
  const d=concrete(spec),modelId=d?.defaultModelId || '';
  const issues=settings.map(({setting,option,reason:detail})=>({modelId,configId:setting.id,value:setting.value,
    reason:detail || `${option?.name || setting.id}: ${setting.value} - Deprecated. Available: ${option?.type==='boolean'?'true, false':option?.options?.map(x=>x.value).join(', ') || 'option unavailable'}`}));
  return Object.assign(Error(reason || issues.map(x=>x.reason).join('; ')),{code:-32602,presetIssues:issues,current,modelId,host:spec.settingsHost || d?.host || ''});
}
async function applyConcrete(spec,result,request) {
  const d=concrete(spec);if(!d)return {result,warnings:[]};
  let current={...result};const model=d.models.find(x=>x.modelId===d.defaultModelId);
  const saved=preferences(spec).saved;
  const modelSetting=saved.find(x=>x.id===d.modelOptionId && x.operation===d.modelOperation);
  const inspect=setting=>{
    const option=descriptors(current).find(o=>o.id===setting.id && o.operation===setting.operation);
    const valid=option && (option.type==='boolean'?['true','false'].includes(setting.value):option.options.some(x=>x.value===setting.value));
    return {setting,option,valid};
  };
  if(!model?.enabled)throw presetFailure(spec,current,[{setting:{id:d.modelOptionId || 'model',value:d.defaultModelId},reason:'The default model is disabled or unavailable - Deprecated'}]);
  async function set(setting) {
    const checked=inspect(setting),option=checked.option;
    if(!checked.valid)throw presetFailure(spec,current,[checked]);
    const value=option.type==='boolean'?setting.value==='true':setting.value;
    if(option.currentValue===value)return;
    const method=option.operation==='config'?'session/set_config_option':option.operation==='model'?'session/set_model':'session/set_mode';
    const params={sessionId:result.sessionId,...(option.operation==='config'?{configId:option.id,value,...(option.type==='boolean'?{type:'boolean'}:{})}:option.operation==='model'?{modelId:value}:{modeId:value})};
    const response=await request(method,params);
    if(response.error) {
      // Authentication, process and transport errors are not deprecated settings.
      if(![-32602,-32070].includes(response.error.code))throw Object.assign(Error(response.error.message),response.error);
      const failure=presetFailure(spec,current,[{setting,option,reason:`${option.name}: ${setting.value}. Agent rejected the saved value: ${response.error.message}`}]);
      failure.code=response.error.code;failure.data=response.error.data;throw failure;
    }
    if(response.result?.sessionId && response.result.sessionId!==result.sessionId)throw Error('Agent attempted to replace the session while applying profile settings');
    current={...current,...Object.fromEntries(['configOptions','models','modes'].filter(k=>response.result?.[k]!==undefined).map(k=>[k,response.result[k]])),sessionId:result.sessionId};
    if(option.operation==='model')current.models={...current.models,currentModelId:value};
    if(option.operation==='mode')current.modes={...current.modes,currentModeId:value};
    if(option.operation==='config' && !Array.isArray(response.result?.configOptions))throw Error('Agent did not confirm the applied profile setting: '+option.id);
    if(inspect(setting).option?.currentValue!==value)throw presetFailure(spec,current,[{setting,option,reason:`${option.name}: agent did not confirm ${setting.value}`}]);
  }
  if(modelSetting)await set(modelSetting);
  const remaining=saved.filter(x=>x!==modelSetting);
  const invalid=remaining.map(inspect).filter(x=>!x.valid);
  if(invalid.length)throw presetFailure(spec,current,invalid);
  for(const setting of remaining)await set(setting);
  return {result:current,warnings:[]};
}
// Apply only an explicitly configured list policy. Preserve provider fields and group structure.
function filterModels(spec,result) {
  const d=concrete(spec);if(!d)return result;
  const current=selectedModel(result),disabled=new Set(d.models.filter(x=>!x.enabled).map(x=>x.modelId));
  const order=new Map(d.models.map((m,i)=>[m.modelId,i]));
  const keep=id=>id===current || !disabled.has(id);
  const sort=items=>items.map((x,i)=>({x,i})).sort((a,b)=>(order.get(a.x.value ?? a.x.modelId) ?? -1)-(order.get(b.x.value ?? b.x.modelId) ?? -1) || a.i-b.i).map(x=>x.x);
  const choices=items=>sort((items || []).flatMap(x=>Array.isArray(x.options)?[{...x,options:choices(x.options)}].filter(g=>g.options.length):keep(x.value)?[x]:[]));
  const filtered={...result};
  if(Array.isArray(result.configOptions))filtered.configOptions=result.configOptions.map(o=>o.category==='model' || (o.id===d.modelOptionId && d.modelOperation==='config')?{...o,options:choices(o.options)}:o);
  if(result.models?.availableModels)filtered.models={...result.models,availableModels:sort(result.models.availableModels.filter(x=>keep(x.modelId)))};
  return filtered;
}
function instructions(spec) {
  if(spec.instructionsSource==='file') {
    try {
      const file=spec.instructionsFile;
      if(typeof file!=='string' || !path.isAbsolute(file) || !/\.(md|markdown)$/i.test(file))throw Error();
      const stat=fs.statSync(file);if(!stat.isFile() || stat.size>65536)throw Error();
      // Codex already discovers its native home rules. Do not also inject the same file.
      const normalized=x=>process.platform==='win32'?path.resolve(x).toLowerCase():path.resolve(x);
      if(spec.agentId==='acp.registry.codex-acp' && spec.env?.CODEX_HOME &&
        ['AGENTS.md','AGENTS.override.md'].some(name=>normalized(file)===normalized(path.join(spec.env.CODEX_HOME,name))))return '';
      return fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'');
    } catch(_) {throw Error('Profile instructions file is unavailable or exceeds 64 KiB. Check it in AI Multi Profiles and start a new session.');}
  }
  if(spec.instructionsSource==='text') {
    if(typeof spec.instructionsText!=='string' || Buffer.byteLength(spec.instructionsText)>65536)throw Error('Profile instructions exceed 64 KiB');
    return spec.instructionsText;
  }
  return '';
}
const accountProviders=[require('./codex-account'),require('./local-account')];
function accountIdentity(spec) {
  return accountProviders.find(provider=>provider.supports(spec))?.discover(spec) || {status:'Account unknown',verified:false};
}

// Settings-only metadata collection. currentValue is always retained as session state;
// modelDefault is populated exclusively from a validated agent recommendation.
function modelDefaults(result) {
  return descriptors(result).map(option=>{
    if(option.category!=='thought_level')return option;
    const raw=result.configOptions?.find(x=>x.id===option.id);
    const recommended=raw?._meta?.jetbrains?.air?.recommendedValue;
    const valid=typeof recommended==='string' && option.options?.some(x=>x.value===recommended);
    return {...option,modelDefault:valid?recommended:null,defaultSource:valid?'ACP recommendedValue':'unknown'};
  });
}
async function discoverModels(spec,initial,request,diagnose=()=>{}) {
  const model=descriptors(initial).find(o=>o.category==='model' && o.operation==='config');
  if(!model)return [];
  if(model.options.length>256)throw Error('The agent reported too many models for defaults discovery');
  const models=[];
  for(const choice of model.options) {
    let result=initial;
    if(choice.value!==model.currentValue) {
      const response=await request('session/set_config_option',{sessionId:initial.sessionId,configId:model.id,type:'id',value:choice.value});
      if(response.error || !Array.isArray(response.result?.configOptions)) {
        models.push({modelId:choice.value,options:[],warnings:['Model metadata unavailable']});continue;
      }
      result=response.result;
      const reported=descriptors(result).find(x=>x.id===model.id && x.operation===model.operation);
      if(reported?.currentValue!==choice.value) {
        models.push({modelId:choice.value,options:[],warnings:['Agent did not confirm this model']});continue;
      }
    }
    const options=modelDefaults(result),warnings=[];
    for(const option of options.filter(x=>x.category==='thought_level')) {
      const recommended=result.configOptions?.find(x=>x.id===option.id)?._meta?.jetbrains?.air?.recommendedValue;
      diagnose({modelId:choice.value,optionId:option.id,
        currentValue:option.options?.some(x=>x.value===option.currentValue)?option.currentValue:null,
        recommendedValue:option.modelDefault,recommendationPresent:recommended!==undefined,
        modelDefault:option.modelDefault,source:option.defaultSource});
      if(option.modelDefault===null)warnings.push('Model default unavailable for '+option.name);
    }
    models.push({modelId:choice.value,options,warnings});
  }
  return models;
}

module.exports={concrete,selectedModel,filterModels,applyConcrete,applyRemembered,forClient,descriptors,preferences,apply,cache,catalog,instructions,accountIdentity,modelDefaults,discoverModels};
