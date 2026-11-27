/**
 * 个人数据层公共入口。
 *
 * 拆分后的结构：
 *  - sync.ts：共享文档、版本控制、冲突合并与回写调度（同步引擎）
 *  - favorites.ts / progress.ts / recent.ts / history.ts：各业务集合的读写 API
 * 页面仍然只从 './storage' 导入，不需要关心内部拆成了几个文件。
 */
export * from './sync'
export * from './favorites'
export * from './progress'
export * from './recent'
export * from './history'