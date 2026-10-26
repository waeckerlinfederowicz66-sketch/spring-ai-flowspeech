import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repo = process.env.MAVEN_REPO ?? path.join(root, '.tools/m2');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
const dependencies = [];
function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const location = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(location);
    else if (entry.name.endsWith('.jar') && !entry.name.endsWith('-sources.jar')) dependencies.push(location);
  }
}
walk(repo);
const artifacts = ['model','autoconfigure','starter'].map(module =>
  path.join(root, 'spring-ai-flowspeech-' + module, 'target', 'spring-ai-flowspeech-' + module + '-0.2.0.jar'));
for (const artifact of artifacts) if (!fs.existsSync(artifact)) throw new Error('Run mvn verify first: ' + artifact);
const result = spawnSync(java, ['-cp', [...artifacts, ...dependencies].join(path.delimiter),
  path.join(root, 'verification/ArtifactSmoke.java')], { stdio: 'inherit', cwd: root });
process.exit(result.status ?? 1);
