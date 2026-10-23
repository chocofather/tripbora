const test = require('node:test');
const assert = require('node:assert/strict');
const {selection, fieldsOf, countsOf, invalidate, canApply, groupOf, fieldLabel, validateDocument} = require('../../main/resources/static/js/admin-destination-json-update.js');
const {requestJson} = require('../../main/resources/static/js/admin-destination-json-update.js');

test('응답 없는 미리보기는 유한 시간에 종료하며 요청을 자동 재시도하지 않는다', async () => {
  let calls = 0;
  await assert.rejects(requestJson('preview', {}, {timeoutMs: 10, fetcher: () => { calls++; return new Promise(() => {}); }}), error => {
    assert.match(error.message, /지연|응답/); assert.equal(error.uncertain, false); return true;
  });
  assert.equal(calls, 1);
});

test('저장 네트워크 오류와 응답 유실은 실패 확정 대신 결과 확인 대상으로 분류한다', async () => {
  for (const fetcher of [() => Promise.reject(new TypeError('offline')), () => Promise.resolve(new Response('<html>error</html>', {status: 500}))]) {
    await assert.rejects(requestJson('apply', {}, {timeoutMs: 50, fetcher}), error => {
      assert.equal(error.uncertain, true); assert.match(error.message, /확인/); return true;
    });
  }
});

test('서버 오류 및 인증 오류는 명확히 표시하고 정상 저장 응답은 그대로 반환한다', async () => {
  await assert.rejects(requestJson('preview', {}, {fetcher: async () => new Response('', {status: 403})}), /권한|로그인|CSRF/);
  await assert.rejects(requestJson('preview', {}, {fetcher: async () => new Response('', {status: 500})}), /서버|500/);
  const payload = {status: 'UPDATED', destinationId: 17, changedFields: 1};
  assert.deepEqual(await requestJson('apply', {}, {fetcher: async () => Response.json(payload)}), payload);
});

function row() {
  return {preview: {index: 2, status: 'MATCHED', changes: [
    {path: 'translations.ko.name'}, {path: 'translations.en.description'}, {path: 'categories'}
  ]}, fields: new Set(['translations.ko.name']), destinationId: 17, confirmedMatch: false, replaceRelations: false, token: 'preview-token', result: null};
}

test('선택한 필드와 대상만 전송하며 한국어와 외국어를 독립 선택한다', () => {
  const current = row();
  current.fields.add('unknown');
  assert.deepEqual(selection(current), {index: 2, destinationId: 17, confirmedMatch: false, fields: ['translations.ko.name'], replaceRelations: false});
  current.fields.add('translations.en.description');
  assert.deepEqual(fieldsOf(current), ['translations.ko.name', 'translations.en.description']);
});

test('선택을 바꾸면 기존 token으로 저장할 수 없다', () => {
  const current = row();
  assert.equal(canApply(current), true);
  invalidate(current);
  assert.equal(canApply(current), false);
  assert.equal(current.token, null);
});

test('저장 대상은 MATCHED와 갱신된 token 및 선택 항목이 모두 필요하다', () => {
  for (const status of ['NEEDS_MATCH', 'NOT_FOUND', 'INVALID', 'CONFLICT']) {
    const current = row(); current.preview.status = status;
    assert.equal(canApply(current), false);
  }
  const empty = row(); empty.fields.clear(); assert.equal(canApply(empty), false);
  const missing = row(); missing.destinationId = null; assert.equal(canApply(missing), false);
  const completed = row(); completed.result = {status: 'UPDATED'}; assert.equal(canApply(completed), false);
});

test('확인된 캐시 비교는 항목 변경 후 최종 클릭에서 자동 검증할 수 있다', () => {
  const current = row(); current.previewReady = true; current.dirty = true; current.token = null;
  assert.equal(canApply(current), true);
  current.preview.status = 'NEEDS_MATCH'; assert.equal(canApply(current), false);
  current.preview.status = 'MATCHED'; current.pendingSave = true; assert.equal(canApply(current), false);
});

test('목록 교체와 후보 확인은 명시한 값만 전송한다', () => {
  const current = row();
  assert.equal(selection(current).replaceRelations, false);
  current.replaceRelations = true; current.confirmedMatch = true;
  assert.equal(selection(current).replaceRelations, true);
  assert.equal(selection(current).confirmedMatch, true);
});

test('원문·4개 번역과 사실정보 번역을 올바르게 구분한다', () => {
  assert.equal(groupOf('translations.ko.description'), '한국어 원문');
  for (const lang of ['en', 'ja', 'zh-CN', 'zh-TW']) assert.match(groupOf(`translations.${lang}.name`), /번역$/);
  assert.equal(groupOf('info.translations.en.openingHours'), '사실정보 번역');
  assert.equal(groupOf('info.openingHours'), '사실정보');
  assert.equal(fieldLabel({path: 'translations.ko.shortDescription'}), '한국어 원문 · 한 줄 소개');
});

test('JSON 문법과 1~30건 및 UTF-8 1MB 제한을 확인한다', () => {
  assert.equal(validateDocument('{"destinations":[{}]}').destinations.length, 1);
  assert.throws(() => validateDocument('{'), /문법/);
  assert.throws(() => validateDocument('{"destinations":[]}'), /1~30/);
  assert.throws(() => validateDocument(JSON.stringify({destinations: Array(31).fill({})})), /1~30/);
  assert.throws(() => validateDocument(JSON.stringify({destinations: [{description: '가'.repeat(350000)}]})), /1MB/);
});

test('좌표는 변경된 한쪽을 선택해도 쌍으로 전송하며 동일한 값은 별도로 선택하지 않는다', () => {
  const current = row();
  current.preview.changes = [{path:'latitude',kind:'REPLACE'}, {path:'longitude',kind:'UNCHANGED'}, {path:'season',kind:'UNCHANGED'}];
  current.fields = new Set(['latitude','season']);
  assert.deepEqual(fieldsOf(current), ['latitude','longitude']);
});

test('자동 보강·교체·유지를 구분해 집계하며 같은 값은 선택되어도 유지한다', () => {
  const current = row();
  current.preview.changes = [{path:'translations.en.name',kind:'AUTO_ADD'}, {path:'season',kind:'REPLACE'}, {path:'latitude',kind:'UNCHANGED'}, {path:'amenities',kind:'REPLACE'}];
  current.fields = new Set(['translations.en.name','season','latitude']);
  assert.deepEqual(countsOf([current]), {auto:1,replace:1,retained:2});
});

test('최초 후보 확정은 자동 선택 기본값을 요청하고 기존 매칭 후 전체해제는 명시적으로 유지한다', () => {
  const current = row(); current.preview.status = 'NEEDS_MATCH'; current.preview.changes = []; current.fields.clear();
  assert.equal(selection(current).fields, null);
  current.hasMatchedPreview = true;
  assert.deepEqual(selection(current).fields, []);
});
