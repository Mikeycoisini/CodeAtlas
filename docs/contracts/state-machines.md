# 导入与索引状态契约

导入和索引采用独立任务记录，状态为 QUEUED、RUNNING、SUCCEEDED、PARTIAL_SUCCESS、FAILED、CANCELLED。

| 起始状态 | 目标状态 | 条件 |
|---|---|---|
| QUEUED | RUNNING | 工作线程取得任务 |
| QUEUED / RUNNING | CANCELLED | 检查到取消请求 |
| RUNNING | SUCCEEDED | 导入成功，或索引全部成功 |
| RUNNING | PARTIAL_SUCCESS | 索引存在成功文件和错误文件 |
| QUEUED / RUNNING | FAILED | 处理失败、容量不足或进程中断 |

全部 Java 文件解析失败时索引为 FAILED。零 Java 文件成功发布空索引，message 提示未发现 Java 文件。导入任务整体成功才发布快照。

取消和最终发布锁定同一任务行，终态取消请求返回终态。重试生成新任务，保留原任务和诊断。相同路径的活动导入任务复用，同快照的活动索引任务通过快照行锁去重。成功结果的普通重复索引复用，rebuild=true 创建新代次。

启动恢复把未完成任务标记 FAILED，错误码 PROCESS_INTERRUPTED。用户可以重试。每个任务有 progress、total、createdAt、updatedAt、errorCode 和 message。发布失败时旧代次仍然有效，暂存结果可按任务 ID 查询。
