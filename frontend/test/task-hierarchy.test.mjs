import { check } from './assert.mjs'
import { childFirstBatches } from '../src/lib/task-hierarchy.ts'
const tasks = [
  { id: 'parent', parentTaskId: null },
  { id: 'child', parentTaskId: 'parent' },
  { id: 'other', parentTaskId: null },
]
check(
  '批量处理先子后父且保持层内顺序',
  JSON.stringify(childFirstBatches(['parent', 'other', 'child'], tasks)) ===
    JSON.stringify([['child'], ['parent', 'other']]),
)
check(
  '未知或无层级任务保留原序',
  JSON.stringify(childFirstBatches(['missing', 'parent'], tasks)) === JSON.stringify([['missing', 'parent']]),
)
check('空批量不发请求', childFirstBatches([], tasks).length === 0)
