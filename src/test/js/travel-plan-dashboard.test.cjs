const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// Dependency-free DOM fixtures exercise the actual module's requests and editor state.
// These tests do not represent browser layout or native focus/keyboard verification.
class Node {
    constructor(tag = "div", attrs = {}) {
        this.tagName = tag.toUpperCase(); this.children = []; this.listeners = {};
        this.attrs = {}; this.dataset = {}; this.value = ""; this.hidden = false;
        this.checked = false; this.disabled = false; this.textContent = "";
        this.elements = {}; this.classes = new Set();
        this.classList = {toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name)};
        for (const [key, value] of Object.entries(attrs)) this.setAttribute(key, value);
    }
    setAttribute(key, value) {
        this.attrs[key] = String(value);
        if (key.startsWith("data-")) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
        if (key === "name" || key === "type") this[key] = value;
    }
    hasAttribute(key) {
        return key in this.attrs || key.startsWith("data-") && key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase()) in this.dataset;
    }
    matches(selector) {
        return selector.split(",").some(part => {
            const match = part.trim().match(/^(\w+)?(?:\[([^=\]]+)(?:=["']?([^\]"']+)["']?)?\])?$/);
            if (!match) return false;
            const [, tag, attr, value] = match;
            return (!tag || this.tagName === tag.toUpperCase()) && (!attr || (this.hasAttribute(attr) || attr === "type" && this.type) && (value === undefined || String(this.attrs[attr] ?? this[attr]) === value));
        });
    }
    querySelectorAll(selector) {
        return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]);
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector); }
    append(...children) {
        for (const child of children) {
            child.parent = this; this.children.push(child);
            if (child.name) this.elements[child.name] = child;
        }
    }
    remove() { this.parent.children = this.parent.children.filter(child => child !== this); }
    replaceChildren(...children) { this.children = []; this.append(...children); }
    addEventListener(type, handler) { (this.listeners[type] ||= []).push(handler); }
    emit(type, extra = {}) {
        const event = {preventDefault() {}, ...extra};
        for (const handler of this.listeners[type] || []) handler(event);
    }
    click() { if (!this.disabled) this.emit("click"); }
    focus() { this.focused = true; }
    reset() { for (const field of Object.values(this.elements)) field.value = ""; }
    reportValidity() { return true; }
    requestSubmit() { this.emit("submit"); }
}

function fixture(readonly = false) {
    const document = new Node("document");
    document.createElement = tag => new Node(tag);
    const planner = new Node("article", {[readonly ? "data-dashboard-plan-id" : "data-plan-id"]: "12", ...(readonly ? {"data-dashboard-readonly": ""} : {})});
    document.append(planner);
    const create = (parent, tag, attr, extra = {}) => {
        const node = new Node(tag, {[attr]: "", ...extra}); parent.append(node); return node;
    };
    const preparation = create(planner, "section", "data-dashboard-preparation");
    create(preparation, "p", "data-dashboard-status");
    create(preparation, "button", "data-dashboard-refresh");
    const checklist = create(preparation, "ul", "data-checklist-list");
    create(preparation, "span", "data-checklist-count"); create(preparation, "p", "data-checklist-empty");
    create(preparation, "progress", "data-checklist-progress");
    const checklistAdd = create(preparation, "form", "data-checklist-add");
    create(checklistAdd, "input", "data-title", {name: "title"});
    create(checklistAdd, "button", "data-checklist-add-submit", {type: "submit"});
    const noticeForm = create(preparation, "form", "data-notice-form"); noticeForm.hidden = true;
    const noticeInput = create(noticeForm, "textarea", "data-content", {name: "content"});
    create(preparation, "p", "data-notice-view"); create(preparation, "button", "data-notice-edit");
    create(noticeForm, "span", "data-notice-state"); create(noticeForm, "details", "data-notice-current");
    create(noticeForm, "p", "data-notice-current-content"); create(noticeForm, "button", "data-notice-use-latest");
    create(noticeForm, "button", "data-submit", {type: "submit"}); create(noticeForm, "button", "data-notice-cancel");
    const expenses = create(planner, "section", "data-dashboard-expenses");
    create(expenses, "ul", "data-expense-list"); create(expenses, "p", "data-expense-empty"); create(expenses, "strong", "data-expense-total");
    create(expenses, "button", "data-expense-add-open");
    const expenseAdd = create(expenses, "form", "data-expense-add"); expenseAdd.hidden = true;
    for (const name of ["title", "amount", "memo"]) create(expenseAdd, "input", `data-${name}`, {name});
    create(expenseAdd, "button", "data-expense-add-cancel");
    document.append(new Node("meta", {name: "_csrf"}), new Node("meta", {name: "_csrf_header"}));
    document.querySelector('meta[name="_csrf"]').content = "test-token";
    document.querySelector('meta[name="_csrf_header"]').content = "X-CSRF-TOKEN";
    const calls = [], timers = [];
    const context = {document, window: {setTimeout: callback => {timers.push(callback); return timers.length;}}, fetch: (url, options) => new Promise(resolve => calls.push({url, options, resolve}))};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/js/travel-plan-dashboard.js"), "utf8"), context);
    document.emit("DOMContentLoaded");
    const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
    const respond = async (index, data, status = 200) => {
        calls[index].resolve({ok: status < 400, status, headers: {get: () => "application/json"}, json: async () => data});
        await flush();
    };
    return {document, planner, preparation, checklist, checklistAdd, noticeForm, noticeInput, expenseAdd, calls, respond, flush,
        timers: () => { const pending = timers.splice(0); pending.forEach(callback => callback()); }};
}

const data = (version = 1) => ({checklist: [{id: 7, title: `item-${version}`, completed: false, version}], notice: {content: `notice-${version}`, version}, expenses: [], totalAmount: "0.00"});

test("read view is default and editors cancel without requests", async () => {
    const f = fixture(); await f.respond(0, data());
    const row = f.checklist.children[0];
    assert.equal(row.querySelector("form").hidden, true);
    assert.equal(f.checklistAdd.hidden, false); assert.equal(f.noticeForm.hidden, true); assert.equal(f.expenseAdd.hidden, true);
    row.children[0].click();
    const form = row.querySelector("form"), input = form.querySelector("input");
    assert.equal(form.hidden, false); input.value = "draft"; input.emit("input");
    form.querySelectorAll("button")[1].click();
    assert.equal(input.value, "item-1"); assert.equal(form.hidden, true); assert.equal(f.calls.length, 1);
});

test("background refresh preserves dirty editor and its version until explicit latest selection", async () => {
    const f = fixture(); await f.respond(0, data());
    const row = f.checklist.children[0]; row.children[0].click();
    const form = row.querySelector("form"), input = form.querySelector("input");
    input.value = "my draft"; input.emit("input");
    f.document.emit("travelplan:dashboard-updated"); f.document.emit("travelplan:dashboard-updated"); f.timers();
    assert.equal(f.calls.length, 2); await f.respond(1, data(2));
    assert.equal(input.value, "my draft"); assert.equal(form.hidden, false);
    form.requestSubmit(); assert.equal(JSON.parse(f.calls[2].options.body).version, 1);
    await f.respond(2, {}, 409); assert.equal(input.value, "my draft");
    f.preparation.querySelector("[data-dashboard-refresh]").click(); await f.respond(3, data(2));
    const latest = row.children.at(-1); assert.equal(latest.hidden, false); latest.click();
    assert.equal(input.value, "item-2"); input.value = "new draft"; input.emit("input"); form.requestSubmit();
    assert.equal(JSON.parse(f.calls[4].options.body).version, 2);
});

test("events during mutation queue one refresh after save without losing new notice input", async () => {
    const f = fixture(); await f.respond(0, data());
    f.preparation.querySelector("[data-notice-edit]").click();
    f.noticeInput.value = "saved content"; f.noticeInput.emit("input"); f.noticeForm.requestSubmit();
    assert.equal(f.calls[1].options.headers["X-CSRF-TOKEN"], "test-token");
    f.noticeInput.value = "newer draft"; f.noticeInput.emit("input");
    f.document.emit("travelplan:dashboard-updated"); f.document.emit("travelplan:dashboard-updated");
    assert.equal(f.calls.length, 2);
    const saved = data(2); saved.notice.content = "saved content"; await f.respond(1, saved);
    assert.equal(f.noticeInput.value, "newer draft"); assert.equal(f.noticeForm.hidden, false);
    f.timers(); assert.equal(f.calls.length, 3); await f.respond(2, saved); f.timers();
    assert.equal(f.calls.length, 3); assert.equal(f.noticeInput.value, "newer draft");
    f.noticeForm.requestSubmit(); assert.equal(JSON.parse(f.calls[3].options.body).version, 2);
});

test("readonly view keeps text visible and ignores active dashboard events", async () => {
    const f = fixture(true); await f.respond(0, data());
    assert.equal(f.checklist.children[0].children[0].textContent, "item-1");
    assert.equal(f.preparation.querySelector("[data-notice-edit]").hidden, true);
    assert.equal(f.checklistAdd.hidden, true);
    f.document.emit("travelplan:dashboard-updated"); f.timers(); assert.equal(f.calls.length, 1);
});

test("instant checkbox save does not submit a title draft and preserves the editor", async () => {
    const f = fixture(); await f.respond(0, data());
    const row = f.checklist.children[0]; row.children[0].click();
    const form = row.querySelector("form"), input = form.querySelector("input"), check = row.querySelector("input");
    input.value = "unfinished title"; input.emit("input");
    check.checked = true; check.emit("change");
    const body = JSON.parse(f.calls[1].options.body);
    assert.equal(body.title, "item-1"); assert.equal(body.completed, true); assert.equal(body.version, 1);
    const saved = data(2); saved.checklist[0].title = "item-1"; saved.checklist[0].completed = true;
    await f.respond(1, saved);
    assert.equal(check.checked, true); assert.equal(input.value, "unfinished title"); assert.equal(form.hidden, false);
    form.requestSubmit(); assert.equal(JSON.parse(f.calls[2].options.body).version, 2);
});

test("notice background refresh never promotes the version of a dirty draft", async () => {
    const f = fixture(); await f.respond(0, data());
    f.preparation.querySelector("[data-notice-edit]").click();
    f.noticeInput.value = "my notice draft"; f.noticeInput.emit("input");
    f.document.emit("travelplan:dashboard-updated"); f.timers(); await f.respond(1, data(2));
    assert.equal(f.noticeInput.value, "my notice draft"); assert.equal(f.noticeForm.hidden, false);
    f.noticeForm.requestSubmit(); assert.equal(JSON.parse(f.calls[2].options.body).version, 1);
    await f.respond(2, {}, 409);
    f.preparation.querySelector("[data-dashboard-refresh]").click(); await f.respond(3, data(2));
    f.preparation.querySelector("[data-notice-use-latest]").click();
    assert.equal(f.noticeInput.value, "notice-2");
    f.noticeInput.value = "revised notice"; f.noticeInput.emit("input"); f.noticeForm.requestSubmit();
    assert.equal(JSON.parse(f.calls[4].options.body).version, 2);
});

test("compact checklist keeps add input visible and retains text typed during save", async () => {
    const f = fixture(); await f.respond(0, data());
    const input = f.checklistAdd.elements.title;
    assert.equal(f.checklistAdd.hidden, false);
    input.value = "passport"; f.checklistAdd.requestSubmit();
    assert.equal(f.calls[1].url, "/travel-plans/12/dashboard/checklist");
    assert.equal(JSON.parse(f.calls[1].options.body).title, "passport");
    input.value = "charger";
    await f.respond(1, data());
    assert.equal(input.value, "charger"); assert.equal(f.checklistAdd.hidden, false);
    f.checklistAdd.requestSubmit(); await f.respond(2, data());
    assert.equal(input.value, ""); assert.equal(f.checklistAdd.hidden, false);
});

test("compact checklist shows progress and direct delete sends the current version", async () => {
    const f = fixture();
    const saved = data(3); saved.checklist[0].completed = true;
    await f.respond(0, saved);
    const progress = f.preparation.querySelector("[data-checklist-progress]");
    assert.equal(progress.value, 1); assert.equal(progress.max, 1);
    const row = f.checklist.children[0];
    assert.equal(row.children[0].textContent, "item-3");
    assert.equal(row.children[1].type, "checkbox");
    row.children[3].click();
    assert.equal(f.calls[1].url, "/travel-plans/12/dashboard/checklist/7/delete");
    assert.equal(JSON.parse(f.calls[1].options.body).version, 3);
    const empty = data(); empty.checklist = [];
    await f.respond(1, empty);
    assert.equal(f.checklist.children.length, 0);
    assert.equal(progress.value, 0); assert.equal(progress.max, 1);
    assert.equal(f.preparation.querySelector("[data-checklist-count]").textContent, "0 / 0 완료");
});
