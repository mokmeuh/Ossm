'use strict';

// Serveur PC OSSM : l'app (Reglages > Mise a jour) vient chercher ici le plus recent APK
// d'un dossier, sans ADB. Lecture seule ; ecoute sur 127.0.0.1 et l'IPv4 Tailscale du PC,
// jamais sur 0.0.0.0. Le nom des fichiers n'a pas d'importance : la version est lue dans l'APK.
//
//   node server.js [--dir <dossier>] [--port 8091] [--host <ip>]... [--minutes 10] [--devices 2]
//                  [--firmware <fichier ou dossier>]
//
// Le firmware de la machine est publie en plus des APK (/firmware/update.json), seulement s'il
// ressemble a une image ESP32 d'application. Il ne compte pas dans l'arret "tous les appareils".
//
// Le serveur n'est pas fait pour rester allume : il s'arrete tout seul apres --minutes
// (10 par defaut) ou des que --devices appareils differents (2 par defaut : S25 et Moto G)
// ont fini de telecharger la derniere version. --minutes 0 desactive la limite de duree.

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { execFile, execFileSync } = require('node:child_process');
const {
  parseBadging, pickNewest, manifestFor, route, isTailscaleIpv4,
  isEsp32AppImage, firmwareManifest, FIRMWARE_MIN_BYTES,
} = require('./lib');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const DEFAULT_DIR = path.join(REPO_ROOT, 'app', 'build', 'outputs', 'named-apk');
const DEFAULT_FIRMWARE = path.join(REPO_ROOT, 'firmware', 'Software', '.pio', 'build', 'development', 'firmware.bin');
const DEFAULT_PORT = 8091;

function parseArgs(argv) {
  const opts = { dir: process.env.OSSM_APK_DIR || DEFAULT_DIR, port: DEFAULT_PORT, hosts: [], minutes: 10, devices: 2, firmware: process.env.OSSM_FIRMWARE || DEFAULT_FIRMWARE };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--dir') opts.dir = next();
    else if (a === '--port') opts.port = Number.parseInt(next(), 10);
    else if (a === '--host') opts.hosts.push(next());
    else if (a === '--minutes') opts.minutes = Number.parseFloat(next());
    else if (a === '--devices') opts.devices = Number.parseInt(next(), 10);
    else if (a === '--firmware') opts.firmware = next();
    else throw new Error(`Option inconnue : ${a}`);
  }
  opts.dir = path.resolve(opts.dir);
  opts.firmware = path.resolve(opts.firmware);
  if (!Number.isInteger(opts.port) || opts.port < 1 || opts.port > 65535) throw new Error('Port invalide');
  if (!Number.isFinite(opts.minutes) || opts.minutes < 0) throw new Error('Duree invalide');
  if (!Number.isInteger(opts.devices) || opts.devices < 0) throw new Error('Nombre appareils invalide');
  return opts;
}

function findAapt2() {
  const exe = process.platform === 'win32' ? 'aapt2.exe' : 'aapt2';
  const roots = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
    path.join(os.homedir(), 'Android', 'Sdk'),
  ].filter(Boolean);
  for (const root of roots) {
    const bt = path.join(root, 'build-tools');
    let versions = [];
    try { versions = fs.readdirSync(bt); } catch { continue; }
    versions.sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    for (const v of versions) {
      const candidate = path.join(bt, v, exe);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return null;
}

function tailscaleIpv4() {
  for (const exe of ['tailscale', 'C:\\Program Files\\Tailscale\\tailscale.exe']) {
    try {
      const out = execFileSync(exe, ['ip', '-4'], { encoding: 'utf8', timeout: 5000, windowsHide: true });
      const ip = out.split(/\r?\n/).map((s) => s.trim()).find(isTailscaleIpv4);
      if (ip) return ip;
    } catch { /* essai suivant */ }
  }
  return null;
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('error', reject)
      .on('data', (c) => hash.update(c))
      .on('end', () => resolve(hash.digest('hex')));
  });
}

function badging(aapt2, file) {
  return new Promise((resolve) => {
    execFile(aapt2, ['dump', 'badging', file], { maxBuffer: 8 * 1024 * 1024, windowsHide: true, timeout: 30000 },
      (err, stdout) => resolve(err ? null : parseBadging(stdout)));
  });
}

/** Index du dossier, recalcule a chaque demande ; seuls les fichiers nouveaux ou modifies sont relus. */
function createIndex(dir, aapt2, log) {
  const cache = new Map(); // chemin -> { key, entry|null }
  return async function scan() {
    let names = [];
    try { names = fs.readdirSync(dir); } catch { return []; }
    const entries = [];
    const seen = new Set();
    for (const name of names) {
      if (!name.toLowerCase().endsWith('.apk')) continue;
      const file = path.join(dir, name);
      let st;
      try { st = fs.statSync(file); } catch { continue; }
      if (!st.isFile()) continue;
      seen.add(file);
      const key = `${st.size}:${st.mtimeMs}`;
      const cached = cache.get(file);
      if (cached && cached.key === key) {
        if (cached.entry) entries.push(cached.entry);
        continue;
      }
      const meta = await badging(aapt2, file);
      let entry = null;
      if (meta) {
        entry = { ...meta, file, fileName: name, size: st.size, mtimeMs: st.mtimeMs, sha256: await sha256File(file) };
        log(`APK indexe : ${name} -> ${meta.packageName} ${meta.versionName} (${meta.versionCode})`);
      } else {
        log(`Ignore (APK illisible ou en cours d'ecriture) : ${name}`);
      }
      cache.set(file, { key, entry });
      if (entry) entries.push(entry);
    }
    for (const file of cache.keys()) if (!seen.has(file)) cache.delete(file);
    return entries;
  };
}

function readHead(file, length) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(length);
    const n = fs.readSync(fd, buf, 0, length, 0);
    return buf.subarray(0, n);
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * Image firmware a publier : le fichier donne, ou le plus recent .bin valide d'un dossier
 * (bootloader, partitions et ota_data sont trop petits pour passer pour une application).
 * Relu a chaque demande ; l'empreinte n'est recalculee que si le fichier a change.
 */
function createFirmwareIndex(target, log) {
  const cache = new Map(); // chemin -> { key, entry|null }
  return async function scanFirmware() {
    let candidates = [];
    try {
      const st = fs.statSync(target);
      candidates = st.isDirectory()
        ? fs.readdirSync(target).filter((n) => n.toLowerCase().endsWith('.bin')).map((n) => path.join(target, n))
        : [target];
    } catch { return null; }
    let best = null;
    for (const file of candidates) {
      let st;
      try { st = fs.statSync(file); } catch { continue; }
      if (!st.isFile() || st.size < FIRMWARE_MIN_BYTES) continue;
      const key = `${st.size}:${st.mtimeMs}`;
      let cached = cache.get(file);
      if (!cached || cached.key !== key) {
        let entry = null;
        if (isEsp32AppImage(readHead(file, 16), st.size)) {
          entry = { file, label: path.basename(file), size: st.size, mtimeMs: st.mtimeMs, sha256: await sha256File(file) };
          log(`Firmware indexe : ${entry.label} (${(st.size / 1024).toFixed(0)} Ko)`);
        } else {
          log(`Firmware ignore (pas une image ESP32 d'application) : ${path.basename(file)}`);
        }
        cached = { key, entry };
        cache.set(file, cached);
      }
      if (cached.entry && (!best || cached.entry.mtimeMs > best.mtimeMs)) best = cached.entry;
    }
    return best;
  };
}

function sendJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body, null, 2));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': data.length,
    'Cache-Control': 'no-store',
  });
  res.end(data);
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const log = (msg) => console.log(`[${new Date().toLocaleTimeString()}] ${msg}`);
  const aapt2 = findAapt2();
  if (!aapt2) {
    console.error('aapt2 introuvable (SDK Android > build-tools). Definis ANDROID_HOME.');
    process.exit(1);
  }
  const hosts = opts.hosts.length ? opts.hosts : ['127.0.0.1', tailscaleIpv4()].filter(Boolean);
  if (hosts.some((h) => h === '0.0.0.0' || h === '::')) {
    console.error('Refuse : ce serveur n\'ecoute jamais sur toutes les interfaces.');
    process.exit(1);
  }
  const scan = createIndex(opts.dir, aapt2, log);
  const scanFirmware = createFirmwareIndex(opts.firmware, log);

  // Arret automatique : duree maximale, ou tous les appareils attendus ont leur mise a jour.
  const servers = [];
  const delivered = new Set();
  const shutdown = (why) => {
    log(`Arret du serveur : ${why}`);
    servers.forEach((s) => s.close());
    setTimeout(() => process.exit(0), 1500).unref();
  };
  const onDownloaded = (client, entry) => {
    delivered.add(client);
    log(`${entry.fileName} livre a ${client} (${delivered.size}/${opts.devices || '?'} appareil(s))`);
    if (opts.devices > 0 && delivered.size >= opts.devices) shutdown('tous les appareils ont telecharge la mise a jour');
  };
  if (opts.minutes > 0) {
    setTimeout(() => shutdown(`${opts.minutes} min ecoulees`), opts.minutes * 60_000).unref();
    log(`Arret automatique dans ${opts.minutes} min, ou apres ${opts.devices} appareil(s) servi(s).`);
  }

  const handler = async (req, res) => {
    try {
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        res.writeHead(405, { Allow: 'GET, HEAD' });
        return res.end();
      }
      const { pathname } = new URL(req.url, 'http://localhost');
      const r = route(pathname);
      if (r.kind === 'notFound') return sendJson(res, 404, { error: 'Introuvable' });
      if (r.kind === 'firmwareManifest' || r.kind === 'firmware') {
        const fw = await scanFirmware();
        if (!fw) return sendJson(res, 404, { error: 'Aucune image firmware valide' });
        if (r.kind === 'firmwareManifest') return sendJson(res, 200, firmwareManifest(fw));
        if (fw.sha256 !== r.sha256) return sendJson(res, 404, { error: 'Firmware introuvable (fichier modifie ?)' });
        log(`${req.socket.remoteAddress} telecharge le firmware ${fw.label} (${(fw.size / 1024).toFixed(0)} Ko)`);
        res.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Content-Length': fw.size,
          'Cache-Control': 'no-store',
        });
        if (req.method === 'HEAD') return res.end();
        return fs.createReadStream(fw.file).on('error', () => res.destroy()).pipe(res);
      }
      const entries = await scan();
      if (r.kind === 'index') {
        const packages = [...new Set(entries.map((e) => e.packageName))].sort();
        return sendJson(res, 200, { folder: opts.dir, latest: packages.map((p) => manifestFor(pickNewest(entries, p))) });
      }
      if (r.kind === 'manifest') {
        const best = pickNewest(entries, r.packageName);
        log(`${req.socket.remoteAddress} demande ${r.packageName || '(tous)'} -> ${best ? best.versionName : 'aucun APK'}`);
        return best ? sendJson(res, 200, manifestFor(best)) : sendJson(res, 404, { error: 'Aucun APK pour ce package' });
      }
      const entry = entries.find((e) => e.sha256 === r.sha256);
      if (!entry) return sendJson(res, 404, { error: 'APK introuvable (dossier modifie ?)' });
      log(`${req.socket.remoteAddress} telecharge ${entry.fileName} (${(entry.size / 1048576).toFixed(1)} Mo)`);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': entry.size,
        'Content-Disposition': `attachment; filename="${entry.fileName.replace(/[^\w.-]/g, '_')}"`,
        'Cache-Control': 'no-store',
      });
      if (req.method === 'HEAD') return res.end();
      const client = req.socket.remoteAddress;
      res.on('finish', () => onDownloaded(client, entry));
      return fs.createReadStream(entry.file).on('error', () => res.destroy()).pipe(res);
    } catch (err) {
      log(`Erreur : ${err.message}`);
      if (!res.headersSent) sendJson(res, 500, { error: 'Erreur interne' });
      else res.destroy();
    }
  };

  log(`Dossier surveille : ${opts.dir}`);
  log(`aapt2 : ${aapt2}`);
  log(`Firmware : ${opts.firmware}`);
  for (const host of hosts) {
    const server = http.createServer(handler);
    servers.push(server);
    server.on('error', (err) => {
      console.error(`Ecoute impossible sur ${host}:${opts.port} : ${err.message}`);
      process.exit(2);
    });
    server.listen(opts.port, host, () => log(`En ecoute : http://${host}:${opts.port}/`));
  }
  if (!hosts.some(isTailscaleIpv4)) log('Tailscale introuvable : seul 127.0.0.1 est servi (le telephone ne pourra pas venir).');
}

if (require.main === module) {
  try { main(); } catch (err) { console.error(err.message); process.exit(1); }
}

module.exports = { parseArgs, createIndex, createFirmwareIndex };
