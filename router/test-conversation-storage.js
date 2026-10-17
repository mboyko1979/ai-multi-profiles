'use strict';
const fs=require('fs'),os=require('os'),path=require('path'),assert=require('assert');
const {VirtualSessions}=require('./virtual-sessions');
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ai-multi-storage-'));
const store=new VirtualSessions({directory:dir}),rename=fs.renameSync;
const s={sessionId:'aiaps-chat-storage',revision:0,exchanges:[]};
try {
 store.save(s);const file=store.file(s.sessionId),before=fs.readFileSync(file,'utf8');
 let attempts=0;
 fs.renameSync=(a,b)=>{if(++attempts<4)throw Object.assign(Error('locked'),{code:'EPERM'});return rename(a,b);};
 s.exchanges.push({user:'new output'});store.save(s);
 assert.equal(attempts,4);assert.equal(s.revision,2);assert.equal(JSON.parse(fs.readFileSync(file,'utf8')).exchanges.length,1);
 const committed=fs.readFileSync(file,'utf8');attempts=0;
 fs.renameSync=()=>{attempts++;throw Object.assign(Error('locked'),{code:'EPERM'});};
 s.exchanges.push({user:'retained output'});
 assert.throws(()=>store.save(s),/latest snapshot is retained/);
 assert.equal(attempts,7);assert.equal(s.revision,2);assert.equal(fs.readFileSync(file,'utf8'),committed);
 const pending=store.pendingWrites.get(s.sessionId);assert.equal(JSON.parse(fs.readFileSync(pending,'utf8')).exchanges.length,2);
 fs.renameSync=rename;store.save(s);assert.equal(s.revision,3);assert(!fs.existsSync(pending));
 attempts=0;fs.renameSync=()=>{attempts++;throw Object.assign(Error('disk error'),{code:'EIO'});};
 assert.throws(()=>store.save(s),/EIO/);assert.equal(attempts,1);assert.equal(s.revision,3);
 assert.notEqual(before,committed);
 fs.renameSync=rename;
 s.segments=[{id:'configuration',profileInstructions:'pending-profile-instruction'}];store.save(s);
 assert.equal(JSON.parse(fs.readFileSync(file,'utf8')).segments[0].profileInstructions,'pending-profile-instruction');
 s.segments[0].instructionsDelivered=true;store.save(s);
 assert(!fs.readFileSync(file,'utf8').includes('pending-profile-instruction'));
 // Even a legacy, persisted transfer body is not an alternative history source after reopening.
 s.profile='target';s.currentSegment='target-segment';s.switches=[{}];
 s.pendingHandoff={token:'old',prompt:[{type:'text',text:'legacy-copied-history'}],previousProfile:'original',previousSegment:'original-segment',previousOptions:{}};
 store.cancelExpiredTransfer(s);
 assert.equal(s.profile,'original');assert.equal(s.currentSegment,'original-segment');assert(!s.pendingHandoff);
 assert(!fs.readFileSync(file,'utf8').includes('legacy-copied-history'));
 // No complete or partial snapshot may become a second message archive.
 const contents=fs.readdirSync(store.dir).filter(x=>x.endsWith('.json')||x.endsWith('.tmp')).map(x=>fs.readFileSync(path.join(store.dir,x),'utf8')).join('\n');
 assert(!contents.includes('new output'));assert(!contents.includes('retained output'));
 const metadata=JSON.parse(committed);assert.equal(metadata.version,2);assert(metadata.exchanges.every(x=>!x.user&&!x.updates));
 console.log('PASS: transient sharing-violation retries, permanent failure preserves committed JSON and pending snapshot, revision commits only on success, recovery, non-transient failures do not retry');
} finally {fs.renameSync=rename;store.close();fs.rmSync(dir,{recursive:true,force:true});}
