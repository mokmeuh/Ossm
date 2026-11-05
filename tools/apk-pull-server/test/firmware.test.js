'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { route, isEsp32AppImage, firmwareManifest } = require('../lib');
const { createFirmwareIndex } = require('../server');

const SHA = 'b'.repeat(64);

function image(size, { magic = 0xe9, segments = 5, chip = 0 } = {}) {
  const buf = Buffer.alloc(size, 0x11);
  buf[0] = magic;
  buf[1] = segments;
  buf[12] = chip & 0xff;
  buf[13] = (chip >> 8) & 0xff;
  return buf;
}

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'ossm-fw-'));
}

test('reconnait une image ESP32 d\'application', () => {
  assert.ok(isEsp32AppImage(image(64), 0x40000));
});

test('rejette un mauvais octet magique, un nombre de segments absurde, une autre puce', () => {
  assert.ok(!isEsp32AppImage(image(64, { magic: 0x7f }), 0x40000));
  assert.ok(!isEsp32AppImage(image(64, { segments: 0 }), 0x40000));
  assert.ok(!isEsp32AppImage(image(64, { segments: 17 }), 0x40000));
  assert.ok(!isEsp32AppImage(image(64, { chip: 9 }), 0x40000));
});

test('rejette une image trop petite ou plus grande que la partition', () => {
  assert.ok(!isEsp32AppImage(image(64), 0x7fff));
  assert.ok(!isEsp32AppImage(image(64), 0x1e0001));
  assert.ok(isEsp32AppImage(image(64), 0x1e0000));
});

test('routes firmware, par empreinte seulement', () => {
  assert.deepStrictEqual(route('/firmware/update.json'), { kind: 'firmwareManifest' });
  assert.deepStrictEqual(route(`/firmware/${SHA}.bin`), { kind: 'firmware', sha256: SHA });
  for (const bad of ['/firmware/firmware.bin', '/firmware/../secret.bin', `/firmware/${SHA.toUpperCase()}.bin`, '/firmware/']) {
    assert.deepStrictEqual(route(bad), { kind: 'notFound' }, bad);
  }
});

test('le manifeste firmware ne contient ni chemin ni nom de dossier', () => {
  const m = firmwareManifest({ sha256: SHA, size: 5, mtimeMs: 12.6, label: 'x.bin', file: 'C:\\secret\\x.bin' });
  assert.deepStrictEqual(m, { sha256: SHA, size: 5, modifiedMs: 13, label: 'x.bin' });
});

test('publie un fichier valide avec son empreinte', async () => {
  const dir = tempDir();
  const file = path.join(dir, 'whatever.bin');
  const data = image(0x9000);
  fs.writeFileSync(file, data);
  const scan = createFirmwareIndex(file, () => {});
  const fw = await scan();
  assert.strictEqual(fw.sha256, crypto.createHash('sha256').update(data).digest('hex'));
  assert.strictEqual(fw.size, data.length);
  assert.strictEqual(fw.label, 'whatever.bin');
});

test('ne publie pas un fichier qui n\'est pas une image ESP32', async () => {
  const dir = tempDir();
  const file = path.join(dir, 'notes.bin');
  fs.writeFileSync(file, image(0x9000, { magic: 0x00 }));
  assert.strictEqual(await createFirmwareIndex(file, () => {})(), null);
});

test('rien a publier si le fichier est absent', async () => {
  assert.strictEqual(await createFirmwareIndex(path.join(tempDir(), 'absent.bin'), () => {})(), null);
});

test('dans un dossier, ignore le bootloader et prend le plus recent .bin valide', async () => {
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, 'bootloader.bin'), image(22 * 1024));
  fs.writeFileSync(path.join(dir, 'partitions.bin'), Buffer.alloc(3 * 1024, 1));
  const older = path.join(dir, 'old.bin');
  const newer = path.join(dir, 'new.bin');
  fs.writeFileSync(older, image(0x9000, { segments: 3 }));
  fs.writeFileSync(newer, image(0xA000, { segments: 4 }));
  fs.utimesSync(older, new Date(1_000_000), new Date(1_000_000));
  fs.utimesSync(newer, new Date(2_000_000), new Date(2_000_000));
  const fw = await createFirmwareIndex(dir, () => {})();
  assert.strictEqual(fw.label, 'new.bin');
});

test('recalcule l\'empreinte quand le fichier change', async () => {
  const dir = tempDir();
  const file = path.join(dir, 'fw.bin');
  fs.writeFileSync(file, image(0x9000, { segments: 3 }));
  const scan = createFirmwareIndex(file, () => {});
  const first = await scan();
  fs.writeFileSync(file, image(0x9100, { segments: 4 }));
  const second = await scan();
  assert.notStrictEqual(first.sha256, second.sha256);
});
