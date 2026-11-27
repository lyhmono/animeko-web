// 将 JASSUB 的 worker / wasm 资源复制到 public，供 ASS/SSA 字幕渲染使用。
import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(import.meta.url);
const dest = path.join(process.cwd(), 'public', 'assets', 'jassub');

try {
  const pkgJson = require.resolve('jassub/package.json');
  const dist = path.join(path.dirname(pkgJson), 'dist');
  if (existsSync(dist)) {
    mkdirSync(dest, { recursive: true });
    cpSync(dist, dest, { recursive: true });
    console.log('[copy-jassub] copied', dist, '->', dest);
  } else {
    console.warn('[copy-jassub] dist not found, skipped');
  }
} catch (err) {
  console.warn('[copy-jassub] jassub not installed yet, skipped');
}
