/**
 * 限并发地跑一批任务。
 *
 * 并发必须设上限：浏览器对同一来源只有 6 条连接，全部数据源一起发会把它们占满，
 * 于是 /api/health、/api/data 这类小请求排在超时后面 —— 实测表现为重新打开标签页时
 * 整个应用卡在「正在连接服务器…」。
 *
 * 这里只管并发与「什么时候该停」，不管错误怎么处理：调用方对失败的表达方式各不相同
 * （搜索页要把错误累积成一张表，管理页要拼一行给人看的文案），把 try/catch 留在
 * 调用方的 run 里才不会把两种语义硬塞进同一个抽象。
 */
export async function mapLimit<T>(
  items: readonly T[],
  limit: number,
  shouldContinue: () => boolean,
  run: (item: T) => Promise<void>,
): Promise<void> {
  let cursor = 0
  const worker = async () => {
    while (shouldContinue() && cursor < items.length) {
      const item = items[cursor++]
      await run(item)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker))
}
