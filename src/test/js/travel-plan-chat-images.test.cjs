const {test} = require('node:test');
const assert = require('node:assert/strict');
const {validate,safeImages} = require('../../main/resources/static/js/travel-plan-chat-images.js');

test('photo selection accepts five supported images and rejects size, count and unsupported input',()=>{
    const file={name:'숙소.png',type:'image/png',size:10*1024*1024};
    assert.equal(validate(Array(5).fill(file)),'');
    assert.match(validate(Array(6).fill(file)),/5장/);
    assert.match(validate([{...file,size:file.size+1}]),/10MB/);
    assert.match(validate([{...file,size:0}]),/10MB/);
    assert.match(validate([{...file,name:'page.svg',type:'image/svg+xml'}]),/JPG/);
    assert.match(validate([{...file,type:'application/pdf'}]),/JPG/);
});

test('message images accept only controlled same-room same-message paths',()=>{
    const base={id:7,url:'/travel-plans/42/chat/messages/9/images/7',width:100,height:200};
    const message={messageId:9,images:[base,
        {...base,url:'https://example.test/image.png'},
        {...base,url:'/travel-plans/43/chat/messages/9/images/7'},
        {...base,url:'/travel-plans/42/chat/messages/10/images/7'},
        {...base,url:'javascript:alert(1)'}]};
    assert.deepEqual(safeImages(message,42),[base]);
    assert.deepEqual(safeImages({id:9},42),[]);
    assert.deepEqual(safeImages(message,'../42'),[]);
});
