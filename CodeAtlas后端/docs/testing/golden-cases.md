# V0.1 固定样本预期

样本位于 test-fixtures/spring-sample，作为只读导入数据使用。预期以源文件字面声明核对，开发前记录。

| 文件 | 预期 |
|---|---|
| PaymentService.java | sample.PaymentService，第 5 行类声明；pay(String) 含 Transactional 注解，范围从第 6 行开始；pay(String,int) 第 11 行；audit(String) 第 15 行；实现 Payment |
| Payment.java | sample.Payment 接口第 3 行；pay(String) 第 4 行 |
| BackupPayment.java | sample.BackupPayment 第 3 行；pay(String) 第 4 行；实现 Payment |
| Checkout.java | sample.Checkout 第 3 行；checkout(String) 第 6 行；第 7 行 payment.pay(orderId) 编译目标为 Payment.pay，运行时实现无法确定 |
| Broken.java | 第 3 行语法错误，其他文件仍可索引 |

按名称 pay 搜索应返回四个方法声明。事务注解依赖没有随样本提供，注解的语法名称可提取，依赖类型解析应保留未解析状态。继承、重载、Git 和目录边界的补充场景由测试临时目录构造。

真实业务项目的路径和版本仍待用户提供。V0.1 另使用官方 Spring PetClinic 固定 commit `500158f732419217507c7656904b8e6aa1bcc0d6` 的静态导入作为真实样本，结果见 docs/reports/v0.1-acceptance.md；该样本不代表用户业务项目，准确性与性能仍需另行记录。
