// 个人数据原子存储：ETag、整档 PUT、条目级 PATCH、备份恢复。
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { userDataFile } from './config';
import { badRequest, StorageError } from './gate';

const MAX_BYTES = 512 * 1024;

type Doc = Record<string, unknown>;

function fingerprint(buf: Buffer): string {
  return createHash('sha256').update(buf).digest('hex').slice(0, 32);
}

export function readUserData(): { doc: Doc; etag: string } {
  const file = userDataFile();
  try {
    const buf = fs.readFileSync(file);
    try {
      const doc = JSON.parse(buf.toString('utf8')) as Doc;
      if (typeof doc !== 'object' || doc === null || Array.isArray(doc)) throw new Error('bad doc');
      return { doc, etag: fingerprint(buf) };
    } catch {
      fs.renameSync(file, `${file}.bad`);
      throw new StorageError('corrupted');
    }
  } catch (err) {
    if (err instanceof StorageError) {
      // 尝试从备份恢复
      try {
        const buf = fs.readFileSync(`${file}.bak`);
        const doc = JSON.parse(buf.toString('utf8')) as Doc;
        return { doc, etag: fingerprint(buf) };
      } catch {
        throw err;
      }
    }
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return { doc: {}, etag: fingerprint(Buffer.from('{}')) };
    throw new StorageError('read failed');
  }
}

export function writeUserData(doc: Doc): { etag: string; bytes: number } {
  const file = userDataFile();
  const buf = Buffer.from(JSON.stringify(doc), 'utf8');
  if (buf.byteLength > MAX_BYTES) badRequest('个人数据超过 512 KiB 上限');
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = `${file}.tmp`;
    const fd = fs.openSync(tmp, 'w');
    fs.writeSync(fd, buf);
    fs.fsyncSync(fd);
    fs.closeSync(fd);
    if (fs.existsSync(file)) fs.copyFileSync(file, `${file}.bak`);
    fs.renameSync(tmp, file);
    return { etag: fingerprint(buf), bytes: buf.byteLength };
  } catch {
    throw new StorageError('write failed');
  }
}

function etagMatches(header: string | null, current: string): boolean {
  if (!header) return true; // 无条件写
  if (header.trim() === '*') return true;
  return header.split(',').some((tag) => {
    const t = tag.trim();
    if (t.startsWith('W/')) return false;
    return t.replace(/^"|"$/g, '') === current;
  });
}

export function conditionalWrite(doc: Doc, ifMatch: string | null): { etag: string; bytes: number } {
  const current = readUserData();
  if (!etagMatches(ifMatch, current.etag)) {
    const err = new Error('DATA_STALE') as Error & { status?: number };
    err.status = 409;
    throw err;
  }
  return writeUserData(doc);
}

/** 条目级增量写：progress / subjectProgress / favorites。 */
export function patchUserData(section: string, key: string, value: unknown, ifMatch: string | null): { etag: string } {
  const current = readUserData();
  if (!etagMatches(ifMatch, current.etag)) {
    const err = new Error('DATA_STALE') as Error & { status?: number };
    err.status = 409;
    throw err;
  }
  const doc = current.doc;
  if (section === 'progress' || section === 'subjectProgress') {
    const map = (typeof doc[section] === 'object' && doc[section] !== null && !Array.isArray(doc[section])
      ? { ...(doc[section] as Record<string, unknown>) }
      : {}) as Record<string, unknown>;
    if (value === null || value === undefined) delete map[key];
    else map[key] = value;
    doc[section] = map;
  } else if (section === 'favorites') {
    const id = Number(key);
    if (!Number.isInteger(id)) badRequest('收藏 key 必须为整数条目 id');
    const list = Array.isArray(doc.favorites) ? [...(doc.favorites as Array<Record<string, unknown>>)] : [];
    const idx = list.findIndex((f) => f && f.id === id);
    if (value === null || value === undefined) {
      if (idx >= 0) list.splice(idx, 1);
    } else if (idx >= 0) {
      list[idx] = value as Record<string, unknown>;
    } else {
      list.unshift(value as Record<string, unknown>);
    }
    doc.favorites = list;
  } else {
    badRequest('不支持的数据分区');
  }
  const { etag } = writeUserData(doc);
  return { etag };
}
