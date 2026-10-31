/**
 * Node-only regression seam: compile the actual production callbacks, with explicit
 * browser/media/API doubles supplied by each test. This is not a browser renderer.
 * Tests assert corrected outcomes, never the old audit probe's defective outcome.
 */
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

export const PLAYER = new URL('../src/pages/PlayerPage.tsx', import.meta.url)
export const VIDEO = new URL('../src/components/VideoPlayer.tsx', import.meta.url)
export const SUBJECT = new URL('../src/pages/SubjectPage.tsx', import.meta.url)
export const DETAIL = new URL('../src/pages/SourceDetailPage.tsx', import.meta.url)

function source(file: URL): ts.SourceFile {
  return ts.createSourceFile(file.pathname, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
}

function unique(root: ts.SourceFile, predicate: (node: ts.Node) => boolean): ts.Node {
  const matches: ts.Node[] = []
  const visit = (node: ts.Node) => {
    if (predicate(node)) matches.push(node)
    ts.forEachChild(node, visit)
  }
  visit(root)
  if (matches.length !== 1) throw new Error(`Expected one production callback in ${root.fileName}, found ${matches.length}`)
  return matches[0]
}

function variableNode(root: ts.SourceFile, name: string): ts.Expression | ts.FunctionDeclaration {
  const node = unique(root, (n) =>
    (ts.isVariableDeclaration(n) && n.name.getText(root) === name) ||
    (ts.isFunctionDeclaration(n) && n.name?.text === name),
  ) as ts.VariableDeclaration | ts.FunctionDeclaration
  if (ts.isFunctionDeclaration(node)) return node
  if (!node.initializer) throw new Error(`Missing initializer: ${name}`)
  return ts.isCallExpression(node.initializer) && node.initializer.expression.getText(root) === 'useCallback'
    ? node.initializer.arguments[0]
    : node.initializer
}

function compile<T>(node: ts.Node, scope: Record<string, unknown>): T {
  const output = ts.transpileModule(`(${node.getText()})`, {
    compilerOptions: { target: ts.ScriptTarget.ES2021, module: ts.ModuleKind.None },
  }).outputText
  return vm.runInNewContext(output, {
    URL, URLSearchParams, Event, AbortController, Error, Promise, console, setTimeout, clearTimeout,
    ...scope,
  }) as T
}

export function callback<T = (...args: never[]) => unknown>(file: URL, name: string, scope: Record<string, unknown>): T {
  return compile<T>(variableNode(source(file), name), scope)
}

export function jsxCallback<T>(file: URL, attribute: string, scope: Record<string, unknown>, marker = ''): T {
  const root = source(file)
  const node = unique(root, (n) => ts.isJsxAttribute(n) && n.name.getText(root) === attribute &&
    !!n.initializer?.getText(root).includes(marker)) as ts.JsxAttribute
  const expression = (node.initializer as ts.JsxExpression).expression!
  return compile<T>(ts.isIdentifier(expression) ? variableNode(root, expression.text) : expression, scope)
}

export function effect(file: URL, marker: string, scope: Record<string, unknown>, kind = 'useEffect'): () => void | (() => void) {
  const root = source(file)
  const node = unique(root, (n) => ts.isCallExpression(n) && n.expression.getText(root) === kind &&
    !!n.arguments[0]?.getText(root).includes(marker)) as ts.CallExpression
  return compile(node.arguments[0], scope)
}

/** Evaluate the real React dependency array; no substring assertion stands in for a reload. */
export function effectDependencies(file: URL, marker: string, scope: Record<string, unknown>): unknown[] {
  const root = source(file)
  const node = unique(root, (n) => ts.isCallExpression(n) && n.expression.getText(root) === 'useEffect' &&
    !!n.arguments[0]?.getText(root).includes(marker)) as ts.CallExpression
  return compile<unknown[]>(node.arguments[1], scope)
}

export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
