const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup() {
    let address;
    const list = {dataset:{pageSize:'12',category:'42',currentPage:'23',sort:'latest'}};
    const sort = {dataset:{sort:'latest'},classList:{remove(){},add(){}}};
    const link = {href:'https://tripbora.test/destinations?type=overseas&region=100&category=42&page=22&size=12&sort=latest'};
    const context = vm.createContext({URL,URLSearchParams,console,
        window:{location:{origin:'https://tripbora.test',search:''},matchMedia:()=>({matches:false,addEventListener(){}}),
            addEventListener(){},history:{state:null,replaceState:(_s,_t,url)=>{address=url;}}},
        document:{addEventListener(){},body:{dataset:{}},getElementById:()=>list,
            querySelector:selector=>({'.region-bar-wrapper':{dataset:{type:'overseas'}},
                '.sort-btn.active':sort,'.subregion-btn.selected':{dataset:{cityId:'100'}}})[selector],
            querySelectorAll:selector=>selector.includes('pagination') ? [link] : selector==='.sort-btn' ? [sort] : []}});
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/ajax-destination-switcher.js','utf8'),context);
    return {context,list,link,sort,address:()=>address};
}

test('sorting resets page while preserving selected country, category, type and size',()=>{
    const {context,sort}=setup();
    let requested;
    context.fetchListFragmentByUrl=url=>{requested=new URL(url,'https://tripbora.test');};
    context.bindSortButtons();
    sort.onclick({preventDefault(){}});
    assert.deepEqual(Object.fromEntries(requested.searchParams),
        {type:'overseas',region:'100',sort:'latest',page:'1',size:'12',category:'42'});
});

test('pagination uses AJAX endpoint while preserving complete link parameters',()=>{
    const {context,link}=setup();
    let requested;
    context.fetchListFragmentByUrl=url=>{requested=new URL(url);};
    context.bindFragmentPagination();
    link.onclick({preventDefault(){}});
    assert.equal(requested.pathname,'/destinations/list-fragment');
    assert.deepEqual(Object.fromEntries(requested.searchParams),
        {type:'overseas',region:'100',category:'42',page:'22',size:'12',sort:'latest'});
});

test('URL reflects server-clamped page, size and sort after fragment replacement',()=>{
    const {context,list,address}=setup();
    list.dataset.pageSize='100';list.dataset.sort='default';
    context.syncAddressBar('/destinations/list-fragment?type=overseas&region=100&category=42&page=99999&size=2147483647&sort=invalid');
    const params=new URL(address(),'https://tripbora.test').searchParams;
    assert.deepEqual(Object.fromEntries(params),{type:'overseas',region:'100',page:'23',size:'100',category:'42'});
});

test('country without child cities uses server-selected region instead of missing active buttons',()=>{
    const {context,list}=setup();
    list.dataset.regionId='200';
    const url=new URL(context.listFragmentUrl({sort:'views'}),'https://tripbora.test');
    assert.equal(url.searchParams.get('region'),'200');
    list.dataset.regionId='';
    assert.equal(new URL(context.listFragmentUrl({}),'https://tripbora.test').searchParams.has('region'),false);
});
