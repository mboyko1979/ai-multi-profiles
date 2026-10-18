// Read-only report analysis. Download reports separately; this tool never reads credentials.
// node tools/summarize-marketplace-verifier.js <report262.json> <report263.json> <output-directory> [<new262-internal.txt> <new263-internal.txt>]
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const [first, second, output, ...developmentReports] = process.argv.slice(2);
if (!first || !second || !output) throw new Error('Expected two verifier JSON reports and an output directory');
const normalize = s => s.replace(/([\w.$\[\]]+) [\w$]+(?=[,)])/g, '$1');
const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e =>
  e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const sources = walk('src/main').filter(f => /\.(kt|java)$/.test(f));
function source(location) {
  const relative = location.replace(/^dev\.boyko\.aiagentswitcher\./, '').split('.');
  const name = relative[['air', 'compat'].includes(relative[0]) ? 1 : 0].replace(/Kt$/, '');
  // A bytecode location can describe a nested class, bridge, coroutine or delegated method.
  // Map it to its containing source file; retain the complete bytecode location separately.
  const file = sources.find(f => path.basename(f).replace(/\.(kt|java)$/, '') === name)
    || sources.find(f => new RegExp('\\b(class|object|interface)\\s+' + name + '\\b').test(fs.readFileSync(f, 'utf8')));
  if (!file) throw new Error('Unmapped plugin location: ' + location);
  return file.replaceAll('\\', '/');
}
function category(api) {
  if (api.startsWith('com.intellij.air.')) return 'Air native integration';
  if (api.startsWith('com.intellij.ml.llm.')) return 'AI Assistant native integration';
  if (api.startsWith('com.intellij.ui.split.') || api.startsWith('com.intellij.platform.rpc.')) return 'Assistant split UI / RPC';
  if (api.includes('DisabledPluginsState')) return 'Plugin enable/disable lifecycle';
  if (api.includes('ExtensionPointImpl')) return 'Assistant toolbar provider binding';
  if (api.includes('PluginManagerCore')) return 'Plugin/module classloader discovery';
  throw new Error('Uncategorized API: ' + api);
}
const reports = [first, second].map(file => {
  const bytes = fs.readFileSync(file), r = JSON.parse(bytes);
  const rows = [false, true].flatMap(ignored => (r[ignored ? 'ignoredInternalApiUsages' : 'internalApiUsages'] || []).map(u => ({
    ignored, api: normalize(u.apiElement), rawApi: u.apiElement, kind: u.identifier.detailedType,
    category: category(u.apiElement), source: source(u.usageLocation), location: u.usageLocation,
    description: normalize(u.fullDescription)
  })));
  return { ide: r.ideVersion.ideVersion, sha256: crypto.createHash('sha256').update(bytes).digest('hex'), rows };
});
const identity = r => JSON.stringify([r.ignored, r.api, r.kind, r.location]);
const keys = reports.map(r => new Set(r.rows.map(identity)));
const differences = reports.map((r, i) => r.rows.filter(row => !keys[1 - i].has(identity(row))));
const summary = reports.map(r => ({ ide: r.ide, sha256: r.sha256,
  active: r.rows.filter(x => !x.ignored).length, ignored: r.rows.filter(x => x.ignored).length,
  uniqueActiveApis: new Set(r.rows.filter(x => !x.ignored).map(x => x.api)).size,
  byCategory: Object.fromEntries([...new Set(r.rows.map(x => x.category))].sort().map(c => [c, {
    active: r.rows.filter(x => x.category === c && !x.ignored).length,
    ignored: r.rows.filter(x => x.category === c && x.ignored).length,
    uniqueActiveApis: new Set(r.rows.filter(x => x.category === c && !x.ignored).map(x => x.api)).size
  }])),
  bySource: Object.fromEntries([...new Set(r.rows.map(x => x.source))].sort().map(s => [s, r.rows.filter(x => x.source === s && !x.ignored).length]))
}));
fs.mkdirSync(output, { recursive: true });
const csv = value => '"' + String(value).replaceAll('"', '""') + '"';
fs.writeFileSync(path.join(output, 'internal-api-locations.csv'), '\uFEFF' +
  [['ide', 'ignored', 'category', 'api', 'source', 'bytecodeLocation', 'kind'],
   ...reports.flatMap(r => r.rows.map(u => [r.ide, u.ignored, u.category, u.api, u.source, u.location, u.kind]))]
    .map(row => row.map(csv).join(',')).join('\n') + '\n');
const grouped = new Map();
for (const row of reports[0].rows) {
  const key = JSON.stringify([row.ignored, row.api]);
  if (!grouped.has(key)) grouped.set(key, { ignored: row.ignored, api: row.api, category: row.category, usages: [] });
  grouped.get(key).usages.push({ source: row.source, location: row.location, kind: row.kind });
}
fs.writeFileSync(path.join(output, 'internal-api-grouped.json'), JSON.stringify([...grouped.values()].sort((a,b) => a.api.localeCompare(b.api)), null, 2) + '\n');
fs.writeFileSync(path.join(output, 'internal-api-summary.json'), JSON.stringify({ reports: summary, normalizedDifferences: differences }, null, 2) + '\n');
console.log(JSON.stringify({ reports: summary, normalizedDifferenceCounts: differences.map(x => x.length) }, null, 2));
if (developmentReports.length) {
  if (developmentReports.length !== 2) throw new Error('Provide both development Internal API text reports');
  const development = developmentReports.map((file, i) => {
    const lines = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).filter(Boolean).map(normalize);
    const old = new Map(reports[i].rows.map(row => [row.description, row]));
    const unknown = lines.filter(line => !old.has(line));
    if (unknown.length) throw new Error('New or changed findings require investigation: ' + JSON.stringify(unknown));
    const current = new Set(lines);
    const remaining = lines.map(line => old.get(line));
    const groups = new Map();
    for (const row of remaining) {
      if (!groups.has(row.api)) groups.set(row.api, { api: row.api, category: row.category, baselineIgnored: row.ignored, usages: [] });
      groups.get(row.api).usages.push({ source: row.source, location: row.location, kind: row.kind });
    }
    return { ide: reports[i].ide, reported: lines.length, uniqueApis: groups.size,
      removed: reports[i].rows.filter(row => !current.has(row.description)).map(({description, rawApi, ...row}) => row),
      added: [], remaining: [...groups.values()].sort((a,b) => a.api.localeCompare(b.api)) };
  });
  fs.writeFileSync(path.join(output, 'development-internal-api.json'), JSON.stringify(development, null, 2) + '\n');
  console.log(JSON.stringify(development.map(({remaining, ...r}) => r), null, 2));
}
