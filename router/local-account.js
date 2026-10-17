'use strict';
// Offline identity hints shared by ACP providers. Never return credentials or treat local data as verified login.
const fs=require('fs'),path=require('path');
const safe=x=>typeof x==='string' && x.length>0 && x.length<=320 && !/[\x00-\x1f\x7f]/.test(x)?x:undefined;
function discover(spec) {
  const unknown={status:'Agent does not expose local account identity',provider:spec.agentId||'',verified:false};
  const env={...(spec.inheritEnvironment===false?{}:process.env),...spec.env};
  if(spec.agentId==='acp.registry.grok-build' && env.XAI_API_KEY)
    return {...unknown,status:'API key environment · account identity unavailable'};
  try {
    // Only explicitly selected profile directories; never search the user's global homes.
    const roots=[...new Set(Object.entries(spec.env||{}).filter(([k,v])=>/(?:_HOME|_CONFIG_DIR)$|^(?:HOME|USERPROFILE)$/i.test(k) && typeof v==='string' && path.isAbsolute(v)).map(([,v])=>v))];
    const identities=[];
    function visit(record,depth=0) {
      if(!record || typeof record!=='object' || Array.isArray(record) || depth>4)return;
      const email=safe(record.email||record.account_email||record.emailAddress);
      const accountId=safe(record.user_id||record.account_id||record.accountId||record.userId);
      if((email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) || accountId) {
        identities.push({...unknown,email:email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)?email:undefined,accountId,
          status:''});
      }
      for(const [key,value] of Object.entries(record))if(['user','account','profile','oauthAccount'].includes(key) || key.includes('::'))visit(value,depth+1);
    }
    for(const root of roots)for(const name of ['auth.json','.credentials.json']) {
      const file=path.join(root,name);
      try {const stat=fs.statSync(file);if(stat.isFile() && stat.size<=1048576)visit(JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'')));}catch(_){}
    }
    const unique=[...new Map(identities.map(x=>[JSON.stringify([x.email||null,x.accountId||null]),x])).values()];
    return unique.length===1?unique[0]:unique.length>1?{...unknown,status:'Multiple local accounts · identity ambiguous'}:unknown;
  }catch(_){return unknown;}
}
module.exports={supports:spec=>spec.agentId!=='acp.registry.codex-acp',discover};
