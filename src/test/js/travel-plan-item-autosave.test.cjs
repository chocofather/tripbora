const {test} = require('node:test');
const assert = require('node:assert/strict');
const {create} = require('../../main/resources/static/js/travel-plan-item-autosave.js');
const deferred = () => { let resolve, reject; const promise = new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject}; };
const tick = async () => { for(let i=0;i<12;i++) await Promise.resolve(); };
function fixture(write) {
    let text='initial', version=0, timer;
    const requests=[], errors=[], statuses=[];
    const auto=create({read:()=>({key:text,version}),write:async snapshot=>{requests.push(snapshot);return write ? write(snapshot) : snapshot.version+1;},
        accept:next=>{version=next;},error:error=>errors.push(error),status:message=>statuses.push(message)},
        {setTimeout:fn=>{timer=fn;return fn;},clearTimeout:id=>{if(timer===id)timer=null;}});
    auto.start();return {auto,requests,errors,statuses,set:value=>{text=value;auto.changed();},value:()=>text,version:()=>version,fire:()=>timer?.()};
}
test('typing coalesces and unchanged/untouched forms do not issue updates',async()=>{
    const f=fixture();await f.auto.flush();assert.equal(f.requests.length,0);
    f.set('a');f.set('ab');f.set('abc');assert.equal(f.requests.length,0);f.fire();await tick();
    assert.deepEqual(f.requests,[{key:'abc',version:0}]);assert.equal(f.auto.isDirty(),false);
});
test('in-flight edits retain latest text and serialize with the acknowledged version',async()=>{
    const first=deferred();const f=fixture(snapshot=>snapshot.version===0?first.promise:snapshot.version+1);
    f.set('a');const flush=f.auto.flush();await tick();f.set('latest');f.auto.changed(true);
    assert.equal(f.requests.length,1);first.resolve(1);assert.equal(await flush,true);
    assert.deepEqual(f.requests,[{key:'a',version:0},{key:'latest',version:1}]);assert.equal(f.value(),'latest');assert.equal(f.version(),2);
});
test('failed or conflicting writes preserve draft and baseline until explicit retry',async()=>{
    let failed=true;const f=fixture(snapshot=>{if(failed)throw new Error('409');return snapshot.version+1;});
    f.set('keep');assert.equal(await f.auto.flush(),false);assert.equal(f.value(),'keep');assert.equal(f.version(),0);assert.equal(f.auto.isDirty(),true);
    assert.equal(f.statuses.at(-1),'저장 실패');failed=false;assert.equal(await f.auto.flush(),true);assert.equal(f.version(),1);
});
test('stop invalidates late responses without accepting or overwriting a new editor',async()=>{
    const reply=deferred();const f=fixture(()=>reply.promise);f.set('old');const saving=f.auto.flush();f.auto.stop();reply.resolve(1);
    assert.equal(await saving,false);assert.equal(f.version(),0);
});
test('readback recovery acknowledges the previous request, then persists newer input',async()=>{
    let call=0;const f=fixture(()=>{call++;if(call===1)throw new Error('readback failed');return call===2?{key:'first'}:{key:'latest'};});
    f.set('first');assert.equal(await f.auto.flush(),false);f.set('latest');
    assert.equal(await f.auto.flush(),true);assert.deepEqual(f.requests.map(row=>row.key),['first','latest','latest']);
    assert.equal(f.auto.isDirty(),false);
});
test('delete pause drains only the issued write and keeps newer/invalid drafts available on failure',async()=>{
    const first=deferred();const f=fixture(()=>first.promise);
    f.set('sent');void f.auto.flush();f.set('');const drained=f.auto.pause();first.resolve(1);await drained;
    assert.equal(f.requests.length,1);assert.equal(f.value(),'');assert.equal(f.auto.isDirty(),true);
    assert.equal(await f.auto.flush(),false);f.auto.resume();assert.equal(f.value(),'');
});
