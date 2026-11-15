# 源码证据契约

所有查询以 snapshotId 为边界，符号与关系再绑定 generationId。文件 ID 由规范相对路径生成，读取时必须结合快照 ID。符号 ID 由路径、限定名、签名与声明起点生成，重载和同名声明可区分。位置采用 1-based 行列，末端包含在范围内。

SourceSnapshot 清单包含每个文件的相对路径、字节长度及 SHA-256。源码响应返回 snapshotId、fileId、path、sha256、encoding、content 和 lineCount。文件字节不符合清单时返回 SNAPSHOT_CORRUPTED。无效 UTF-8 返回 UNSUPPORTED_ENCODING。

关系包含 sourceId、targetId、targetText、kind、status、reason、fileId、范围和 resolver。CALL 表示编译期目标，RUNTIME_CALL 表示运行时目标。继承和实现分别为 EXTENDS 与 IMPLEMENTS。

CONFIRMED 需要唯一源码声明及可复核证据。INFERRED 用于明确规则推断，V0.1 尚未实现提升为推断的框架规则。UNRESOLVED 表示目标位于索引外部、依赖缺失、类型重复、动态实现或解析能力限制。targetId 允许为空，targetText 与 reason 必须保留。接口调用的编译声明与运行时实现具有独立关系记录。

重建创建新代次，旧代次继续可查。默认查询已发布代次，指定 generationId 可以读取属于该快照的历史已发布代次。任务暂存结果通过 taskId 查询。失败和取消不会更新发布指针。
