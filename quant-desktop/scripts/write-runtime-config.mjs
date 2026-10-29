import {execFileSync} from 'node:child_process'
import {createHash} from 'node:crypto'
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopRoot = path.resolve(scriptDirectory, '..')
const repositoryRoot = path.resolve(desktopRoot, '..')
const launcher = path.join(
  repositoryRoot,
  'quant-server', 'scripts', 'start-stock-quant-pro.ps1',
)
const artifact = path.join(
  repositoryRoot,
  'quant-server', 'target',
  'quant-server-1.3.1-research-production.jar',
)
const proof = `${artifact}.f1f-b2-proof.properties`

for (const required of [launcher, artifact, proof]) {
  if (!existsSync(required)) {
    throw new Error('DESKTOP_FORMAL_ARTIFACT_MISSING')
  }
}

const gitCommit = execFileSync(
  'git', ['-C', repositoryRoot, 'rev-parse', 'HEAD'],
  {encoding: 'utf8', windowsHide: true},
).trim()
if (!/^[0-9a-f]{40}$/.test(gitCommit)) {
  throw new Error('DESKTOP_RUNTIME_BINDING_INVALID')
}
const artifactSha256 = createHash('sha256')
  .update(readFileSync(artifact))
  .digest('hex')
const proofLines = readFileSync(proof, 'utf8').split(/\r?\n/)
if (!proofLines.includes(`git.commit=${gitCommit}`)
    || !proofLines.includes(`artifact.sha256=${artifactSha256}`)) {
  throw new Error('DESKTOP_FORMAL_ARTIFACT_PROOF_INVALID')
}

const outputDirectory = path.join(desktopRoot, 'dist-electron')
mkdirSync(outputDirectory, {recursive: true})
writeFileSync(
  path.join(outputDirectory, 'runtime-config.json'),
  `${JSON.stringify({
    schemaVersion: 'STOCK_QUANT_DESKTOP_RUNTIME_V1',
    repositoryRoot,
    gitCommit,
  }, null, 2)}\n`,
  {encoding: 'utf8'},
)

process.stdout.write('DESKTOP_RUNTIME_CONFIG=READY\n')
process.stdout.write(`DESKTOP_RUNTIME_COMMIT=${gitCommit}\n`)
process.stdout.write(`DESKTOP_ARTIFACT_SHA256=${artifactSha256}\n`)
