'use strict';
const fs=require('fs'),path=require('path'),crypto=require('crypto');

// The IDE owns the dialog; the router owns validation and the actual session change.
// No transcript or credentials enter this rendezvous directory.
async function requestRecovery(directory,details,isClosed=()=>false) {
  const dir=path.join(directory,'agent-recovery');fs.mkdirSync(dir,{recursive:true});
  const nonce=crypto.randomUUID(),base=path.join(dir,nonce),request=base+'.request.json',response=base+'.response.json';
  fs.writeFileSync(request,JSON.stringify({...details,nonce,pid:process.pid,createdAt:Date.now()}),{flag:'wx',mode:0o600});
  let retained=false;
  try {
    const deadline=Date.now()+10*60*1000;
    while(Date.now()<deadline && !isClosed()) {
      if(fs.existsSync(response)) {
        const answer=JSON.parse(fs.readFileSync(response,'utf8').replace(/^\uFEFF/,''));
        if(answer.nonce!==nonce)throw Error('Invalid agent recovery response');
        if(answer.approved!==true)throw Object.assign(Error('Restoration was cancelled. The original chat is intact.'),{code:-32001});
        retained=true;
        return {selection:answer,ready:result=>fs.writeFileSync(base+'.ready.json',JSON.stringify({nonce,pid:process.pid,...result}),{flag:'wx',mode:0o600})};
      }
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    throw Object.assign(Error('Agent changed; explicit recovery was not completed. The original session is intact.'),{code:-32001});
  }finally{
    for(const file of [request,response])if(!retained || file===response)try{fs.unlinkSync(file);}catch(_){}
  }
}
async function requestConfiguration(directory,details,isClosed=()=>false) {
  const dir=path.join(directory,'agent-recovery');fs.mkdirSync(dir,{recursive:true});
  const nonce=crypto.randomUUID(),base=path.join(dir,nonce),request=base+'.request.json',response=base+'.response.json';
  fs.writeFileSync(request,JSON.stringify({...details,nonce,pid:process.pid,createdAt:Date.now()}),{flag:'wx',mode:0o600});
  try {
    const deadline=Date.now()+5*60*1000;
    while(Date.now()<deadline && !isClosed()) {
      if(fs.existsSync(response)) {
        const answer=JSON.parse(fs.readFileSync(response,'utf8').replace(/^\uFEFF/,''));
        if(answer.nonce!==nonce)throw Error('Invalid profile configuration response');
        if(!answer.approved)throw Error(answer.error || 'Profile configuration could not be completed');
        return;
      }
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    throw Error('Profile configuration did not complete');
  }finally{for(const file of [request,response])try{fs.unlinkSync(file);}catch(_) {}}
}
// Short-lived metadata only; the IDE adapter reads/writes its native client store.
async function requestPreferences(directory,details,isClosed=()=>false) {
  const dir=path.join(directory,'client-preferences');fs.mkdirSync(dir,{recursive:true});
  const nonce=crypto.randomUUID(),base=path.join(dir,nonce),request=base+'.request.json',response=base+'.response.json';
  fs.writeFileSync(request,JSON.stringify({...details,nonce,pid:process.pid,createdAt:Date.now()}),{flag:'wx',mode:0o600});
  try {
    const deadline=Date.now()+15000;
    while(Date.now()<deadline && !isClosed()) {
      if(fs.existsSync(response)) {
        const answer=JSON.parse(fs.readFileSync(response,'utf8').replace(/^\uFEFF/,''));
        if(answer.nonce!==nonce)throw Error('Invalid client preference response');
        if(answer.error)throw Error(answer.error);
        return answer.settings || [];
      }
      await new Promise(resolve=>setTimeout(resolve,50));
    }
    throw Error('Native client preference adapter did not respond');
  }finally{for(const file of [request,response])try{fs.unlinkSync(file);}catch(_) {}}
}
module.exports={requestRecovery,requestConfiguration,requestPreferences};
