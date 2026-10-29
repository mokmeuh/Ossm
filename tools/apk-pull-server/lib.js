'use strict';

// Fonctions pures du serveur : lecture de la sortie aapt2, choix de la version, routes.

const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

/** Premiere ligne de `aapt2 dump badging` : package, versionCode, versionName. */
function parseBadging(output) {
  const line = String(output).split(/\r?\n/).find((l) => l.startsWith('package:'));
  if (!line) return null;
  const field = (name) => {
    const m = line.match(new RegExp(`\\b${name}='([^']*)'`));
    return m ? m[1] : null;
  };
  const packageName = field('name');
  const rawCode = field('versionCode') ?? '';
  const versionCode = /^\d+$/.test(rawCode) ? Number(rawCode) : NaN;
  const versionName = field('versionName') || String(versionCode);
  if (!packageName || !PACKAGE_RE.test(packageName) || !Number.isSafeInteger(versionCode)) return null;
  return { packageName, versionCode, versionName };
}

/**
 * Le plus recent APK pour un package (ou tous si packageName est vide) :
 * versionCode le plus haut, puis fichier le plus recemment modifie.
 */
function pickNewest(entries, packageName) {
  let best = null;
  for (const e of entries) {
    if (packageName && e.packageName !== packageName) continue;
    if (!best || e.versionCode > best.versionCode ||
        (e.versionCode === best.versionCode && e.mtimeMs > best.mtimeMs)) {
      best = e;
    }
  }
  return best;
}

/** Manifeste lu par l'app (UpdateManifestParser) : apkPath relatif a la racine du serveur. */
function manifestFor(entry) {
  return {
    packageName: entry.packageName,
    versionCode: entry.versionCode,
    versionName: entry.versionName,
    apkPath: `/apk/${entry.sha256}.apk`,
    sha256: entry.sha256,
    size: entry.size,
    fileName: entry.fileName,
  };
}

/** Route d'une requete GET. Aucune route ne prend un nom de fichier venant du client. */
function route(pathname) {
  if (pathname === '/' || pathname === '/index.json') return { kind: 'index' };
  if (pathname === '/update.json') return { kind: 'manifest', packageName: null };
  let m = pathname.match(/^\/p\/([^/]+)\/update\.json$/);
  if (m) {
    let packageName = '';
    try { packageName = decodeURIComponent(m[1]); } catch { return { kind: 'notFound' }; }
    return PACKAGE_RE.test(packageName) ? { kind: 'manifest', packageName } : { kind: 'notFound' };
  }
  m = pathname.match(/^\/apk\/([0-9a-f]{64})\.apk$/);
  if (m) return { kind: 'apk', sha256: m[1] };
  return { kind: 'notFound' };
}

/** Adresse IPv4 Tailscale (plage CGNAT 100.64.0.0/10). */
function isTailscaleIpv4(ip) {
  return /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$/.test(String(ip).trim());
}

module.exports = { parseBadging, pickNewest, manifestFor, route, isTailscaleIpv4 };
