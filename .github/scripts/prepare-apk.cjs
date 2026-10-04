const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const variant = process.env.APK_VARIANT;
if (!['release', 'debug'].includes(variant)) throw new Error('APK_VARIANT 必须为 release 或 debug');

const directory = path.join('app/build/outputs/apk', variant);
const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'output-metadata.json'), 'utf8'));
if (metadata.elements.length !== 1) throw new Error('直接上传 APK 仅支持一个构建文件');

const artifact = metadata.elements[0];
const source = path.join(directory, artifact.outputFile);
// 对最终已签名 APK 计算 MD5，短值仅用于命名和识别，不替代完整校验。
const shortMd5 = crypto.createHash('md5').update(fs.readFileSync(source)).digest('hex').slice(0, 8);
const suffix = variant === 'debug' ? '-debug' : '';
const filename = `HyperPods-${artifact.versionName}${suffix}-${shortMd5}.apk`;
const destination = path.join(directory, filename);
fs.renameSync(source, destination);
fs.appendFileSync(process.env.GITHUB_OUTPUT, `path=${destination}\n`);
