'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { parseBadging, pickNewest, manifestFor, route, isTailscaleIpv4 } = require('../lib');

const SHA = 'a'.repeat(64);

test('lit package, versionCode et versionName dans la sortie aapt2', () => {
  const out = "package: name='com.ossm.remote.debug' versionCode='193' versionName='2.3.45' platformBuildVersionName='15'\nminSdkVersion:'26'";
  assert.deepStrictEqual(parseBadging(out), { packageName: 'com.ossm.remote.debug', versionCode: 193, versionName: '2.3.45' });
});

test('refuse une sortie aapt2 sans package lisible', () => {
  assert.strictEqual(parseBadging('ERROR: dump failed'), null);
  assert.strictEqual(parseBadging("package: name='x' versionCode='1'"), null);
  assert.strictEqual(parseBadging("package: name='a.b' versionCode='abc'"), null);
});

test('choisit le plus haut versionCode, peu importe le nom du fichier', () => {
  const entries = [
    { packageName: 'a.b', versionCode: 193, mtimeMs: 1, fileName: 'zzz.apk' },
    { packageName: 'a.b', versionCode: 200, mtimeMs: 0, fileName: 'old-name.apk' },
    { packageName: 'c.d', versionCode: 999, mtimeMs: 5, fileName: 'other.apk' },
  ];
  assert.strictEqual(pickNewest(entries, 'a.b').versionCode, 200);
  assert.strictEqual(pickNewest(entries, null).packageName, 'c.d');
  assert.strictEqual(pickNewest(entries, 'e.f'), null);
});

test('a version egale, le fichier le plus recent gagne', () => {
  const entries = [
    { packageName: 'a.b', versionCode: 5, mtimeMs: 10, fileName: 'first.apk' },
    { packageName: 'a.b', versionCode: 5, mtimeMs: 20, fileName: 'rebuilt.apk' },
  ];
  assert.strictEqual(pickNewest(entries, 'a.b').fileName, 'rebuilt.apk');
});

test('le manifeste pointe vers une route par empreinte, jamais par nom de fichier', () => {
  const m = manifestFor({ packageName: 'a.b', versionCode: 3, versionName: '1.0', sha256: SHA, size: 10, fileName: 'x y.apk' });
  assert.strictEqual(m.apkPath, `/apk/${SHA}.apk`);
  assert.strictEqual(m.sha256, SHA);
});

test('routes', () => {
  assert.deepStrictEqual(route('/'), { kind: 'index' });
  assert.deepStrictEqual(route('/update.json'), { kind: 'manifest', packageName: null });
  assert.deepStrictEqual(route('/p/com.ossm.remote.debug/update.json'), { kind: 'manifest', packageName: 'com.ossm.remote.debug' });
  assert.deepStrictEqual(route(`/apk/${SHA}.apk`), { kind: 'apk', sha256: SHA });
  for (const bad of ['/apk/../secret.apk', '/apk/ABC.apk', '/p/../update.json', '/p/x/update.json', '/p/%E0%A4%A/update.json', '/etc/passwd']) {
    assert.deepStrictEqual(route(bad), { kind: 'notFound' }, bad);
  }
});

test('reconnait seulement les IPv4 Tailscale', () => {
  assert.ok(isTailscaleIpv4('100.65.88.40'));
  assert.ok(!isTailscaleIpv4('100.128.0.1'));
  assert.ok(!isTailscaleIpv4('10.0.0.135'));
  assert.ok(!isTailscaleIpv4('0.0.0.0'));
});
