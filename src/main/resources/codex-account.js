'use strict';
// Codex-specific, offline identity discovery. Raw tokens and parser errors never leave this provider.
// Local token expiry is not a login failure: the agent owns credential renewal.
const fs=require('fs'),path=require('path');
function discover(spec) {
  const unknown={status:'Account unknown',verified:false};
  if(spec.agentId!=='acp.registry.codex-acp')return unknown;
  try {
    const env={...(spec.inheritEnvironment===false?{}:process.env),...spec.env};
    if(env.OPENAI_API_KEY)return {...unknown,status:'API key environment · account identity unavailable'};
    if(!env.CODEX_HOME || !path.isAbsolute(env.CODEX_HOME))return unknown;
    const file=path.join(env.CODEX_HOME,'auth.json');if(fs.statSync(file).size>1048576)return unknown;
    const auth=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
    if(auth.auth_mode==='apikey' || auth.OPENAI_API_KEY)return {...unknown,status:'API key · account identity unavailable'};
    const parts=auth.tokens?.id_token?.split('.');if(parts?.length!==3 || parts[1].length>131072)return unknown;
    const claims=JSON.parse(Buffer.from(parts[1],'base64url').toString('utf8'));
    const safe=x=>typeof x==='string' && x.length<=320 && !/[\x00-\x1f\x7f]/.test(x)?x:undefined;
    const email=safe(claims.email),claimed=safe(claims['https://api.openai.com/auth']?.chatgpt_account_id),recorded=safe(auth.tokens.account_id);
    if(claimed && recorded && claimed!==recorded)return unknown;
    const accountId=claimed||recorded;
    return {...unknown,...(email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)?{email}:{}),...(accountId?{accountId}:{}),provider:'Codex',
      status:''};
  } catch(_) {return unknown;}
}
module.exports={supports:spec=>spec.agentId==='acp.registry.codex-acp',discover};
