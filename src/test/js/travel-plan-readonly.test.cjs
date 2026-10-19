const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = name => fs.readFileSync(path.join(__dirname, '../../main/resources/static/js', name), 'utf8');

test('completed planner does not start ACTIVE-only realtime or its redirect timers', () => {
    let connected = 0;
    const planner = {hasAttribute: name => name === 'data-plan-readonly', getAttribute: () => '42'};
    const context = {
        window: {}, location: {protocol: 'http:', host: 'tripbora.test'},
        document: {querySelector: () => planner, addEventListener: (_, callback) => callback()},
        StompJs: {Client: class {constructor() {connected++; throw new Error('ACTIVE-only client started');}}}
    };
    vm.runInNewContext(source('travel-plan-realtime.js'), context);
    assert.equal(connected, 0);
});

test('ACTIVE planner keeps reconnect and genuine access-revocation redirect', () => {
    let client;
    const timers = [];
    const planner = {hasAttribute: () => false, getAttribute: () => '42'};
    const context = {
        document: {querySelector: () => planner, addEventListener() {},
            createElement: () => ({setAttribute() {}})},
        window: {addEventListener() {}, setTimeout: (callback, delay) => timers.push({callback, delay}), location: {}},
        location: {protocol: 'http:', host: 'tripbora.test'},
        StompJs: {Client: class {
            constructor(options) {this.options = options; client = this;}
            activate() {this.active = true;}
            deactivate() {this.active = false;}
        }}
    };
    planner.prepend = () => {};
    const callbacks = [];
    context.document.addEventListener = (event, callback) => {if (event === 'DOMContentLoaded') callbacks.push(callback);};
    vm.runInNewContext(source('travel-plan-realtime.js'), context);
    callbacks[0]();
    assert.equal(client.active, true);
    assert.equal(client.options.reconnectDelay, 5000);
    client.onStompError({headers: {message: '여행계획에 참여 중이 아닙니다.'}});
    assert.equal(client.active, false);
    assert.equal(timers[0].delay, 1500);
    timers[0].callback();
    assert.equal(context.window.location.href, '/travel-plans');
});
