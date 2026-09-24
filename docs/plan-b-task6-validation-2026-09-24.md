# Plan B Task 6 真实环境验证

验证日期：2026-09-24。MCP Server 使用 `2e026da` + `0391378`；supermall 在 `feat/after-sales-capability` 分支，售后执行语义基线为 `bf2d59a`。运行环境为 JDK 22.0.2、MySQL80、WSL Redis/RabbitMQ、8081 端口的 `loadtest` profile。supermall 从当前分支执行 `mvn -pl mall-server -am clean package -DskipTests`，五模块均 `BUILD SUCCESS`；应用日志出现 `Started MallApplication`。

测试身份、商家身份和必需启动设置均由运行时环境变量提供。本文与 [JSON-RPC 响应记录](plan-b-task6-responses-2026-09-24.jsonl)不含口令、JWT 或密钥。响应记录保留各次 id=2 的 JSON-RPC 响应对象，中文采用 JSON 的 `\uXXXX` 转义，以防 Windows shell 捕获时发生字符集转换。

## 协议与只读工具

验证驱动为 [mcp_stdio_smoke.py](../scripts/mcp_stdio_smoke.py)。它发送 `initialize`、`notifications/initialized` 后保持 stdin 打开，等到指定 id=2 响应，再关闭 stdin；对 stdout 每行按 UTF-8 严格解码并解析 JSON，检查 stderr 不含用户令牌。与原计划的固定管道不同，此次确实读取到了工具响应。

`tools/list` 返回六个且名称不重复：`get_order`、`list_user_orders`、`get_logistics`、`get_refund_eligibility`、`list_policy_clauses`、`submit_refund`。`submit_refund` 的 schema 只有 `orderId` 和 `reason`，没有 `amount`。

`list_policy_clauses` 返回 `isError=false`、三条政策和目录指纹 `489160a3a592f001b71a3600676ef28d6dc29500244816aad18da1a288bfca72`。政策码为 `SEVEN_DAY_NO_REASON`、`SHIPPED_NOT_RECEIVED`、`QUALITY_ISSUE`，与 supermall 的 `AfterSalesPolicy` 枚举一致。驱动确认标题和条款文本中没有 Unicode 替换字符。

## 写工具与落库

旧测试用户已不存在，因此通过业务注册接口重建身份。测试订单经业务接口完成建地址、下单、支付、商家发货与送达、用户确认收货。订单 `2103052453124640768` 使用 SKU `930000000000000001`，写入前数据库只读查询为 `RECEIVED`、退款行数 0。

MCP `get_refund_eligibility` 返回 `isError=false`、`eligible=true`、`refundExists=false`、`refundableAmount=199.99`、`policyCode=SEVEN_DAY_NO_REASON`。随后对同一订单调用 `submit_refund` 一次，返回 `isError=false`、`eligible=true`、`refundExists=false`、金额 `199.99`。这里的 `refundExists=false` 是**调用前**没有退款记录，不能解释为执行后仍无记录。

写入后数据库只读查询得到：订单状态 `REFUNDED`；退款行数 **1**；退款状态 `REFUNDED`；订单实付金额与退款金额均为 `199.99`。这次使用新订单，未让先前退款的幂等结果代替首次执行验证。

## 业务错误与测试

数据库只读查询确认订单 `2100813807797936128` 属于另一用户。以本次测试用户令牌调用 MCP `get_order`，得到 `isError=true`、`code=50000`、中文消息“订单不存在”，响应中没有 Java 异常类名。

完成真实调用后，执行 `mvn -pl mcp-server test`：**34 个测试、0 失败、0 错误、0 跳过，BUILD SUCCESS**。打包 jar 由 `java -jar` 直接拉起；运行验证使用 JDK 22.0.2，尚未在 JDK 17 运行。K-13 的 Agent 指纹消费者仍待后续 RAG 阶段实现。
