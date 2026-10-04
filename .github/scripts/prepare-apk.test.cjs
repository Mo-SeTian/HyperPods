const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const script = path.join(__dirname, 'prepare-apk.cjs');

function fixture(t, variant, content = Buffer.from('APK test fixture')) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hyperpods-apk-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const directory = path.join(root, 'app/build/outputs/apk', variant);
  fs.mkdirSync(directory, { recursive: true });
  const metadata = { version: 3, elements: [{ versionName: '9.2.0-test', outputFile: `app-${variant}.apk` }] };
  fs.writeFileSync(path.join(directory, 'output-metadata.json'), JSON.stringify(metadata));
  fs.writeFileSync(path.join(directory, metadata.elements[0].outputFile), content);
  const output = path.join(root, 'step-output');
  const run = () => spawnSync(process.execPath, [script], {
    cwd: root, env: { ...process.env, APK_VARIANT: variant, GITHUB_OUTPUT: output }, encoding: 'utf8',
  });
  return { directory, metadata, output, run, content };
}

for (const variant of ['release', 'debug']) {
  test(`${variant}：版本名和最终文件 MD5 决定文件名，重命名不改变 APK`, t => {
    const f = fixture(t, variant);
    assert.equal(f.run().status, 0);
    const digest = crypto.createHash('md5').update(f.content).digest('hex').slice(0, 8);
    const filename = `HyperPods-9.2.0-test${variant === 'debug' ? '-debug' : ''}-${digest}.apk`;
    assert.deepEqual(fs.readdirSync(f.directory).sort(), [filename, 'output-metadata.json'].sort());
    assert.deepEqual(fs.readFileSync(path.join(f.directory, filename)), f.content);
    assert.equal(fs.readFileSync(f.output, 'utf8'), `path=app/build/outputs/apk/${variant}/${filename}\n`);
  });
}

test('APK 内容改变后，文件名中的 MD5 同步改变', t => {
  const first = fixture(t, 'release', Buffer.from('first APK'));
  const second = fixture(t, 'release', Buffer.from('second APK'));
  assert.equal(first.run().status, 0);
  assert.equal(second.run().status, 0);
  assert.notEqual(fs.readFileSync(first.output, 'utf8'), fs.readFileSync(second.output, 'utf8'));
});

test('多个 APK 时明确失败，不上传错误文件', t => {
  const f = fixture(t, 'release');
  f.metadata.elements.push({ outputFile: 'second.apk' });
  fs.writeFileSync(path.join(f.directory, 'output-metadata.json'), JSON.stringify(f.metadata));
  assert.notEqual(f.run().status, 0);
  assert.ok(fs.existsSync(path.join(f.directory, 'app-release.apk')));
  assert.ok(!fs.existsSync(f.output));
});

test('APK 缺失时明确失败，不生成可上传路径', t => {
  const f = fixture(t, 'release');
  fs.unlinkSync(path.join(f.directory, 'app-release.apk'));
  assert.notEqual(f.run().status, 0);
  assert.ok(!fs.existsSync(f.output));
});
