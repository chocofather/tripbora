const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {flush}=require('./fake-dom.cjs');
function fixture(response) {
    const button={dataset:{},disabled:false,textContent:''},content={dataset:{originalContent:'원문 리뷰'},textContent:'원문 리뷰'},status={hidden:true,textContent:''};
    const timers=[];let calls=0;
    const context=vm.createContext({console,document:{createElement:()=>({textContent:'',get innerHTML(){return this.textContent;}})},detailMessage:key=>key,
        fetchCommentTranslation:async()=>{calls++;if(response instanceof Error)throw response;return response;},window:{setTimeout:fn=>timers.push(fn)}});
    const code=fs.readFileSync('src/main/resources/static/js/comment/events.js','utf8').replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g,'').replaceAll('export function ','function ');
    vm.runInContext(code+'\nglobalThis.translate=handleTranslation;',context);
    const card={querySelector:selector=>selector==='.comment-content'?content:status},event={target:{closest:()=>button}};
    return {button,content,status,timers,get calls(){return calls;},click:()=>context.translate(event,card,'10')};
}
test('translation runs only on click and READY is shown safely with original toggle',async()=>{
    const f=fixture({status:'READY',translatedText:'<script>translated</script>'});assert.equal(f.calls,0);
    f.click();await flush();assert.equal(f.calls,1);assert.equal(f.content.textContent,'<script>translated</script>');assert.equal(f.button.dataset.translationState,'translated');
    f.click();assert.equal(f.content.innerHTML,'원문 리뷰');assert.equal(f.calls,1);
});
test('invalid READY and provider failure preserve original and show feedback',async()=>{
    for(const response of [{status:'READY',translatedText:''},{status:'FAILED'},new Error('provider unavailable')]){
        const f=fixture(response);f.click();await flush();assert.equal(f.content.textContent,'원문 리뷰');assert.notEqual(f.button.dataset.translationState,'translated');assert.equal(f.status.hidden,false);assert.equal(f.status.textContent,'commentTranslationFailed');assert.equal(f.timers.length,0);
    }
});
test('quota errors do not retry automatically',async()=>{
    const error=new Error('quota');error.status=429;const f=fixture(error);f.click();await flush();assert.equal(f.calls,1);assert.equal(f.timers.length,0);assert.equal(f.status.textContent,'commentTranslationRateLimited');
});
test('PROCESSING polling is bounded and never replaces the original with empty text',async()=>{
    const f=fixture({status:'PROCESSING',retryAfterSeconds:30});f.click();await flush();
    while(f.timers.length){f.timers.shift()();await flush();}
    assert.equal(f.calls,4);assert.equal(f.content.textContent,'원문 리뷰');assert.equal(f.button.disabled,false);assert.equal(f.status.textContent,'commentTranslationRetry');
});
