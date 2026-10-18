const {test} = require('node:test');
const assert = require('node:assert/strict');
const {normalize, searchAirports, airportLabel, mount} = require('../../main/resources/static/js/travel-plan-airport-autocomplete.js');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');

const airports = [
    {iata: 'ICN', name: {ko: '인천국제공항', en: 'Incheon International Airport'}, city: {ko: '서울', en: 'Seoul'}, country: {ko: '대한민국', en: 'South Korea'}, aliases: ['인천공', '인천 공항']},
    {iata: 'GMP', name: {ko: '김포국제공항', en: 'Gimpo International Airport'}, city: {ko: '서울', en: 'Seoul'}, country: {ko: '대한민국', en: 'South Korea'}, aliases: []},
    {iata: 'NRT', name: {ko: '나리타국제공항', en: 'Narita International Airport'}, city: {ko: '도쿄', en: 'Tokyo'}, country: {ko: '일본', en: 'Japan'}, aliases: ['나리타공항']}
];

test('search covers Korean/English name, city, country, aliases and normalized IATA', () => {
    for (const query of ['인천공', '인천 공항', 'INCheon', ' ｉｃｎ ', 'ICN']) {
        assert.equal(searchAirports(airports, query)[0].iata, 'ICN');
    }
    for (const query of ['서울', 'SEOUL', '대한민국', 'south korea']) assert.equal(searchAirports(airports, query).length, 2);
    assert.equal(searchAirports(airports, 'tokyo')[0].iata, 'NRT');
    assert.deepEqual(searchAirports(airports, '  '), []);
    assert.equal(searchAirports(airports, '공').length, 3);
    assert.equal(searchAirports(Array.from({length: 20}, () => airports[0]), 'i').length, 8);
    assert.equal(normalize('São-Paulo'), normalize('sao paulo'));
    assert.equal(airportLabel(airports[0]), '인천국제공항 (ICN)');
});

test('shipped airport catalogue parses, contains unique IATA records and ranks real airport queries', () => {
    const catalogue = JSON.parse(readFileSync(join(__dirname, '../../main/resources/static/data/airports.json'), 'utf8'));
    assert.ok(catalogue.source.startsWith('https://'));
    assert.ok(catalogue.airports.length > 4000);
    const seen = new Set();
    for (const airport of catalogue.airports) {
        assert.match(airport.iata, /^[A-Z]{3}$/);
        assert.equal(seen.has(airport.iata), false, airport.iata);
        seen.add(airport.iata);
        assert.ok(airport.name?.en?.trim(), `${airport.iata} English name`);
        assert.equal(typeof airport.city?.en, 'string', `${airport.iata} English city field`);
        assert.ok(airport.country?.en?.trim(), `${airport.iata} English country`);
        assert.ok(airport.country?.ko?.trim(), `${airport.iata} Korean country`);
        assert.ok(Array.isArray(airport.aliases));
        assert.ok(airport.aliases.every(alias => typeof alias === 'string' && alias.trim()));
        assert.ok(airportLabel(airport).includes(airport.iata));
    }
    for (const query of ['인', '인천', '인천공', 'ICN', 'Incheon']) {
        assert.equal(searchAirports(catalogue.airports, query)[0].iata, 'ICN', query);
    }
    assert.equal(searchAirports(catalogue.airports, 'KIX')[0].iata, 'KIX');
    for (const [query, iata] of [['김','GMP'],['간','KIX'],['GMP','GMP']]) assert.equal(searchAirports(catalogue.airports, query)[0].iata, iata);
});

class FakeEvent {
    constructor(type, options = {}) { this.type = type; Object.assign(this, options); }
    preventDefault() { this.defaultPrevented = true; }
    stopImmediatePropagation() { this.stopped = true; }
}
class Element {
    constructor(tag, doc) { this.tag = tag; this.doc = doc; this.children = []; this.attrs = {}; this.dataset = {}; this.style = {}; this.listeners = {}; this.value = ''; }
    setAttribute(name, value) { this.attrs[name] = String(value); }
    removeAttribute(name) { delete this.attrs[name]; }
    matches(selector) { return selector === '[data-airport-input]' && this.attrs['data-airport-input'] !== undefined; }
    closest(selector) { return selector === 'form' && this.tag === 'form' ? this : this.parentNode?.closest(selector); }
    append(...nodes) { nodes.forEach(node => { node.parentNode = this; this.children.push(node); }); }
    remove() { this.parentNode.children = this.parentNode.children.filter(node => node !== this); this.parentNode = null; }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
    get isConnected() { return this === this.doc || !!this.parentNode?.isConnected; }
    getBoundingClientRect() { return {width: 300, height: 36, left: 12, bottom: 100}; }
    addEventListener(type, listener, capture = false) { (this.listeners[type] ||= []).push({listener, capture}); }
    dispatchEvent(event) {
        event.target ||= this;
        const path = []; for (let node = this; node; node = node.parentNode) path.push(node);
        for (const node of [...path].reverse()) for (const {listener, capture} of node.listeners[event.type] || []) {
            if (capture && !event.stopped) listener(event);
        }
        for (const node of event.bubbles ? path : [this]) for (const {listener, capture} of node.listeners[event.type] || []) {
            if (!capture && !event.stopped) listener(event);
        }
        return event;
    }
}
function fixture(fetchOverride) {
    const doc = new Element('document'); doc.doc = doc;
    const timers = new Map(); let id = 0, fetchCount = 0;
    doc.defaultView = {Event: FakeEvent, innerWidth: 360, innerHeight: 700, addEventListener() {},
        setTimeout(fn, delay) { assert.equal(delay, 150); timers.set(++id, fn); return id; }, clearTimeout(id) { timers.delete(id); }};
    doc.createElement = tag => new Element(tag, doc);
    doc.body = doc.createElement('body'); doc.append(doc.body);
    function createForm(type = 'FLIGHT') {
        const form = doc.createElement('form'); form.dataset.editorType = type; doc.body.append(form);
        const input = doc.createElement('input'); input.setAttribute('data-airport-input', ''); form.append(input);
        return {form, input};
    }
    mount(doc, {fetch: async () => { fetchCount++; return fetchOverride ? fetchOverride() : {ok: true, json: async () => ({airports})}; }});
    const initial = createForm();
    const fire = (target, type, props = {}) => target.dispatchEvent(new FakeEvent(type, {bubbles: true, ...props}));
    function input(value, target = initial.input) { doc.activeElement = target; target.value = value; fire(target, 'input'); }
    async function flush() { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(fn => fn()); for (let i = 0; i < 10; i++) await Promise.resolve(); }
    return {doc, ...initial, createForm, fire, inputText: input, flush, list: () => doc.body.children.find(node => node.attrs.role === 'listbox'), fetchCount: () => fetchCount};
}

test('delegated dynamic forms share one request and search synchronously once loaded', async () => {
    const f = fixture(); f.inputText('i'); f.inputText('ic'); f.inputText('icn');
    assert.equal(f.fetchCount(), 1); await f.flush(); assert.equal(f.fetchCount(), 1); assert.equal(f.list().children.length, 1);
    const dynamic = f.createForm(); f.inputText('도쿄', dynamic.input); assert.equal(f.fetchCount(), 1);
    assert.match(f.list().children[0].children[0].textContent, /NRT/);
    f.fire(dynamic.input, 'focusout');
    const move = f.createForm('MOVE'); f.inputText('icn', move.input); await f.flush();
    assert.equal(f.list(), undefined); assert.equal(move.input.attrs.role, undefined); assert.equal(move.input.value, 'icn');
});

test('disabled and readOnly airport fields do not fetch or open suggestions', async () => {
    for (const property of ['disabled', 'readOnly']) {
        const f = fixture(); f.input[property] = true; f.inputText('icn'); await f.flush();
        assert.equal(f.fetchCount(), 0); assert.equal(f.list(), undefined); assert.equal(f.input.attrs.role, undefined);
    }
});

test('capture keyboard selection prevents scheduler Enter/save, Escape closes only popup', async () => {
    const f = fixture(); let saves = 0; const changes = [];
    f.input.addEventListener('keydown', () => saves++);
    f.form.addEventListener('change', event => changes.push(event.target.value));
    f.inputText('서울'); await f.flush();
    f.fire(f.input, 'keydown', {key: 'ArrowDown'}); f.fire(f.input, 'keydown', {key: 'ArrowDown'});
    const enter = f.fire(f.input, 'keydown', {key: 'Enter'});
    assert.equal(enter.defaultPrevented, true); assert.equal(saves, 0);
    assert.equal(f.input.value, '김포국제공항 (GMP)'); assert.deepEqual(changes, ['김포국제공항 (GMP)']);
    assert.equal(f.input.attrs['aria-expanded'], 'false'); assert.equal(f.list(), undefined);
    f.inputText('i'); await f.flush(); f.fire(f.input, 'keydown', {key: 'Escape'}); assert.equal(saves, 0);
    f.fire(f.input, 'keydown', {key: 'Escape'}); assert.equal(saves, 1);
});

test('IME, stale response, reset, schedule updates and failures preserve manual input', async () => {
    const f = fixture(); f.fire(f.input, 'compositionstart'); f.inputText('인'); await f.flush(); assert.equal(f.fetchCount(), 1); assert.ok(f.list());
    f.fire(f.input, 'keydown', {key:'Enter',isComposing:true}); assert.equal(f.input.value,'인');
    let escaped=0;f.input.addEventListener('keydown',()=>escaped++);
    const imeEscape=f.fire(f.input,'keydown',{key:'Escape',isComposing:true});
    assert.equal(imeEscape.stopped,true);assert.equal(imeEscape.defaultPrevented,undefined);assert.equal(escaped,0);
    f.fire(f.input, 'compositionend'); assert.ok(f.list());
    const composing = f.fire(f.input, 'keydown', {key: 'Enter', isComposing: true}); assert.equal(composing.defaultPrevented, undefined);
    for (const type of ['reset', 'travelplan:editor-opened', 'travelplan:schedule-updated']) {
        f.inputText('i'); await f.flush(); assert.ok(f.list()); f.fire(f.doc, type); assert.equal(f.list(), undefined);
    }
    let resolve;
    const pending = fixture(() => new Promise(done => { resolve = done; }));
    pending.inputText('i'); await pending.flush(); pending.fire(pending.doc, 'travelplan:schedule-updated');
    resolve({ok: true, json: async () => ({airports})}); await pending.flush(); assert.equal(pending.list(), undefined);
    const disconnected = fixture(); disconnected.inputText('i'); disconnected.input.remove(); await disconnected.flush(); assert.equal(disconnected.list(), undefined);
    const failure = fixture(() => Promise.reject(new Error('offline'))); failure.inputText('직접 입력'); await failure.flush();
    assert.equal(failure.input.value, '직접 입력'); assert.equal(failure.list(), undefined);
});

test('focus warms the catalogue before typing, and warm input has no timer delay',async()=>{
    const f=fixture(); f.doc.activeElement=f.input; f.fire(f.input,'focusin');
    assert.equal(f.fetchCount(),1); await f.flush(); assert.equal(f.list(),undefined);
    f.inputText('인'); assert.match(f.list().children[0].children[0].textContent,/ICN/);
    f.inputText('김'); assert.match(f.list().children[0].children[0].textContent,/GMP/); assert.equal(f.fetchCount(),1);
});

test('normalized search fields are indexed once, with exact names ahead of prefix matches',()=>{
    let reads=0;const exact={...airports[0],name:{get ko(){reads++;return '인천';},en:'Incheon'}};
    const rows=[airports[0],exact];assert.equal(searchAirports(rows,'인천')[0],exact);
    searchAirports(rows,'ICN');searchAirports(rows,'인');assert.equal(reads,1);
});

test('a failed catalogue request can be retried without losing manual text',async()=>{
    let attempts=0;const f=fixture(async()=>{if(++attempts===1)throw new Error('offline');return {ok:true,json:async()=>({airports})};});
    f.inputText('인');await f.flush();assert.equal(f.list(),undefined);assert.equal(f.input.value,'인');
    f.inputText('인천');await f.flush();assert.match(f.list().children[0].children[0].textContent,/ICN/);assert.equal(f.fetchCount(),2);
});

test('pointer selection prevents blur and dispatches normal string input/change', async () => {
    const f = fixture(); let selectedInput = 0;
    f.form.addEventListener('input', () => selectedInput++);
    f.inputText('인천공'); await f.flush();
    const pointer = f.fire(f.list().children[0], 'pointerdown');
    assert.equal(pointer.defaultPrevented, true); assert.equal(f.input.value, '인천국제공항 (ICN)');
    assert.equal(selectedInput, 2); assert.equal(f.list(), undefined);
});

test('scrolling the airport input outside the viewport closes its popup', async () => {
    const f = fixture(); f.inputText('ICN'); await f.flush();
    assert.ok(f.list());
    f.input.getBoundingClientRect = () => ({width: 300, height: 36, left: 12, top: -50, bottom: -14});
    f.fire(f.doc, 'scroll');
    assert.equal(f.list(), undefined);
    assert.equal(f.input.attrs['aria-expanded'], 'false');
});

test('outside pointer closes a pending search before the network response arrives', async () => {
    let resolve;
    const f = fixture(() => new Promise(done => { resolve = done; }));
    f.inputText('i'); await f.flush(); f.fire(f.doc.body, 'pointerdown');
    resolve({ok: true, json: async () => ({airports})}); await f.flush();
    assert.equal(f.list(), undefined);
});
