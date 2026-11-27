// 受限 JsonPath 求值：属性、多属性候选、数组下标、通配、递归下降。
// 支持示例：$[*]['title','name']、$..list[0]['url']

type Step =
  | { kind: 'key'; keys: string[] }
  | { kind: 'index'; index: number }
  | { kind: 'wildcard' }
  | { kind: 'recursive'; key: string };

function tokenize(path: string): Step[] {
  if (path.length > 2048) throw new Error('JsonPath 过长');
  const steps: Step[] = [];
  let i = path.startsWith('$') ? 1 : 0;
  let guard = 0;
  while (i < path.length) {
    if ((guard += 1) > 64) throw new Error('JsonPath 步数过多');
    if (path.startsWith('..', i)) {
      const m = /^\.\.([\w$-]+)/.exec(path.slice(i));
      if (!m) throw new Error('非法递归下降');
      steps.push({ kind: 'recursive', key: m[1] });
      i += m[0].length;
    } else if (path[i] === '.') {
      const m = /^\.([\w$-]+)/.exec(path.slice(i));
      if (!m) throw new Error('非法属性访问');
      steps.push({ kind: 'key', keys: [m[1]] });
      i += m[0].length;
    } else if (path[i] === '[') {
      const end = findBracketEnd(path, i);
      const inner = path.slice(i + 1, end).trim();
      if (inner === '*') {
        steps.push({ kind: 'wildcard' });
      } else if (/^-?\d+$/.test(inner)) {
        steps.push({ kind: 'index', index: Number(inner) });
      } else {
        const keys = [...inner.matchAll(/'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"/g)]
          .map((m) => (m[1] ?? m[2] ?? '').replace(/\\(['"\\])/g, '$1'));
        if (!keys.length) throw new Error('非法下标');
        steps.push({ kind: 'key', keys });
      }
      i = end + 1;
    } else {
      throw new Error('非法 JsonPath');
    }
  }
  return steps;
}

function findBracketEnd(path: string, start: number): number {
  let inQuote: string | null = null;
  for (let i = start + 1; i < path.length; i += 1) {
    const c = path[i];
    if (inQuote) {
      if (c === '\\') i += 1;
      else if (c === inQuote) inQuote = null;
    } else if (c === "'" || c === '"') {
      inQuote = c;
    } else if (c === ']') {
      return i;
    }
  }
  throw new Error('下标未闭合');
}

export function evalJsonPath(root: unknown, path: string): unknown[] {
  const steps = tokenize(path);
  let current: unknown[] = [root];
  let budget = 200000;
  for (const step of steps) {
    const next: unknown[] = [];
    for (const node of current) {
      if ((budget -= 1) < 0) throw new Error('JsonPath 计算预算耗尽');
      if (step.kind === 'key') {
        if (node && typeof node === 'object' && !Array.isArray(node)) {
          const record = node as Record<string, unknown>;
          for (const key of step.keys) {
            if (key in record) {
              next.push(record[key]);
              break; // 多属性候选取第一个命中
            }
          }
        }
      } else if (step.kind === 'index') {
        if (Array.isArray(node)) {
          const idx = step.index < 0 ? node.length + step.index : step.index;
          if (idx >= 0 && idx < node.length) next.push(node[idx]);
        }
      } else if (step.kind === 'wildcard') {
        if (Array.isArray(node)) next.push(...node);
        else if (node && typeof node === 'object') next.push(...Object.values(node));
      } else {
        const walk = (n: unknown, depth: number) => {
          if (depth > 128 || (budget -= 1) < 0) return;
          if (n && typeof n === 'object') {
            if (!Array.isArray(n) && step.key in (n as Record<string, unknown>)) {
              next.push((n as Record<string, unknown>)[step.key]);
            }
            for (const value of Object.values(n)) walk(value, depth + 1);
          }
        };
        walk(node, 0);
      }
      if (next.length > 20000) throw new Error('JsonPath 结果过多');
    }
    current = next;
  }
  return current;
}
