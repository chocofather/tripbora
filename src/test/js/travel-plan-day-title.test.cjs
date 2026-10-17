const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// DOM fixture executes delegated handlers and request state; browser layout is separate.
class Node {
    constructor(selector = "", parent = null) {
        this.selector = selector; this.parent = parent; this.children = []; this.listeners = {};
        this.dataset = {}; this.hidden = false; this.value = ""; this.disabled = false;
        this.isConnected = true; this.textContent = ""; this.classes = new Set();
        this.classList = {add: name => this.classes.add(name), remove: name => this.classes.delete(name),
            toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name)};
        if (parent) parent.children.push(this);
    }
    matches(selector) { return selector === ".is-editing" ? this.classes.has("is-editing") : this.selector === selector; }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector); }
    querySelector(selector) {
        for (const child of this.children) {
            if (child.matches(selector)) return child;
            const result = child.querySelector(selector); if (result) return result;
        }
        return null;
    }
    addEventListener(type, callback) { (this.listeners[type] ||= []).push(callback); }
    async emit(type, target = this, extra = {}) {
        const event = {type, target, preventDefault() { this.prevented = true; }, ...extra};
        await Promise.all((this.listeners[type] || []).map(callback => callback(event)));
        return event;
    }
    focus() { this.focused = true; }
    reportValidity() { return true; }
}

function fixture() {
    const document = new Node("document"), planner = new Node("[data-plan-id]", document);
    const day = new Node("[data-travel-plan-day-id]", planner); day.dataset.travelPlanDayId = "2";
    const header = new Node(".travel-plan-day-heading", day);
    const button = new Node("[data-day-title-edit]", header); button.textContent = "original";
    const form = new Node("[data-day-title-form]", header); form.hidden = true;
    form.action = "/travel-plans/1/days/2/title";
    const input = new Node("input", form); input.name = "title"; input.value = "original";
    const version = new Node("input", form); version.name = "titleVersion"; version.value = "3";
    const csrf = new Node("input", form); csrf.name = "_csrf"; csrf.value = "test-token";
    const submit = new Node("button", form), cancel = new Node("[data-day-title-cancel]", form);
    form.elements = [input, version, csrf, submit, cancel];
    form.elements.namedItem = name => form.elements.find(node => node.name === name);
    const status = new Node("[data-day-title-status]", header);
    const idle = []; document.dispatchEvent = event => idle.push(event);
    const calls = [];
    const context = {document, window: {}, URLSearchParams,
        CustomEvent: class { constructor(type) { this.type = type; } },
        FormData: class { constructor(form) { this.form = form; } *[Symbol.iterator]() {
            for (const node of this.form.elements) if (node.name && !node.disabled) yield [node.name, node.value];
        } },
        fetch: (url, options) => new Promise((resolve, reject) => calls.push({url, options, resolve, reject}))};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/js/travel-plan-day-title.js"), "utf8"), context);
    document.emit("DOMContentLoaded");
    return {document, planner, header, button, form, input, version, cancel, status, calls, idle, context,
        open: () => planner.emit("click", button),
        save: () => planner.emit("submit", form),
        respond: (index, data, status = 200) => calls[index].resolve({ok: status < 400, status, json: async () => data})};
}

test("delegated save serializes title/version/CSRF before disabling and closes with new baseline", async () => {
    const f = fixture(); await f.open();
    assert.equal(f.context.window.travelPlanDayTitle.isEditing(), true);
    assert.equal(f.header.classes.has("is-editing"), true);
    f.input.value = "my trip";
    const pending = f.save();
    assert.equal(f.calls[0].options.body.get("title"), "my trip");
    assert.equal(f.calls[0].options.body.get("titleVersion"), "3");
    assert.equal(f.calls[0].options.body.get("_csrf"), "test-token");
    assert.equal(f.input.disabled, true);
    await f.save(); assert.equal(f.calls.length, 1);
    f.respond(0, {title: "my trip", titleVersion: 4}); await pending;
    assert.equal(f.button.textContent, "my trip"); assert.equal(f.form.hidden, true);
    assert.equal(f.input.value, "my trip"); assert.equal(f.version.value, "4");
    assert.equal(f.input.defaultValue, "my trip"); assert.equal(f.version.defaultValue, "4");
    assert.equal(f.input.disabled, false); assert.equal(f.idle.length, 1);
    assert.equal(f.context.window.travelPlanDayTitle.isEditing(), false);
    await f.open(); f.input.value = "discard"; await f.planner.emit("click", f.cancel);
    assert.equal(f.input.value, "my trip"); assert.equal(f.calls.length, 1);
});

test("cancel and Escape restore draft; IME Escape and pending cancellation are ignored", async () => {
    const f = fixture(); await f.open(); f.input.value = "draft";
    await f.planner.emit("keydown", f.input, {key: "Escape", isComposing: true});
    assert.equal(f.form.hidden, false);
    await f.planner.emit("keydown", f.input, {key: "Escape"});
    assert.equal(f.form.hidden, true); assert.equal(f.input.value, "original"); assert.equal(f.calls.length, 0);
    await f.open(); const pending = f.save(); await f.planner.emit("click", f.cancel);
    assert.equal(f.form.hidden, false); f.respond(0, {title: "original", titleVersion: 4}); await pending;
});

test("409 and network errors retain text and original version without emitting idle", async () => {
    const f = fixture(); await f.open(); f.input.value = "unsaved draft";
    let pending = f.save(); f.respond(0, {title: "remote", titleVersion: 9}, 409); await pending;
    assert.equal(f.input.value, "unsaved draft"); assert.equal(f.version.value, "3");
    assert.equal(f.form.hidden, false); assert.match(f.status.textContent, /다른 참여자/);
    assert.equal(f.idle.length, 0);
    pending = f.save(); f.calls[1].reject(new Error("offline")); await pending;
    assert.equal(f.input.value, "unsaved draft"); assert.equal(f.version.value, "3");
    assert.equal(f.input.disabled, false); assert.match(f.status.textContent, /연결/);
});

test("other editors and editor opening prevent title edit", async () => {
    const f = fixture(); f.planner.dataset.editorOpening = "true"; await f.open();
    assert.equal(f.form.hidden, true);
    f.planner.dataset.editorOpening = "false";
    const other = new Node("div", f.planner); other.classList.add("is-editing"); await f.open();
    assert.equal(f.form.hidden, true); assert.equal(f.calls.length, 0);
});

test("cancel after conflict refreshes latest title only after releasing the editor", async () => {
    const f = fixture(), refreshes = [];
    f.context.window.travelPlanRealtime = {refreshDay(dayId) {
        refreshes.push(dayId);
        assert.equal(f.context.window.travelPlanDayTitle.isEditing(), false);
        assert.equal(f.header.classes.has("is-editing"), false);
        assert.equal(f.idle.length, 1);
        return Promise.resolve();
    }};
    await f.open(); f.input.value = "my draft";
    const pending = f.save(); f.respond(0, {}, 409); await pending;
    assert.equal(refreshes.length, 0); assert.equal(f.input.value, "my draft");
    await f.planner.emit("click", f.cancel);
    assert.deepEqual(refreshes, ["2"]); assert.equal(f.form.hidden, true);
});

test("late response for detached fragment never applies title or emits editor idle", async () => {
    const f = fixture(); await f.open(); f.input.value = "draft"; const pending = f.save();
    f.form.isConnected = false;
    f.respond(0, {title: "stale response", titleVersion: 8}); await pending;
    assert.equal(f.button.textContent, "original"); assert.equal(f.version.value, "3");
    assert.equal(f.idle.length, 0);
    assert.equal(f.context.window.travelPlanDayTitle.isEditing(), false);
});
