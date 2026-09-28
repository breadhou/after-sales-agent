# 阶段 3B 语料草案（审阅稿）

> 状态：预实施草案，供逐条事实审阅；不是最终 corpus JSON，也不表示阶段 3B 已实现。FAQ 依据按阶段 3A 已验收代码（Agent/MCP：`C:\Users\hou16\.codex\worktrees\after-sales-phase3a\after-sales-agent`，HEAD `0de77c7`；supermall：`D:\sourcecode\supermall-phase3a`，HEAD `878ac93`）核对。复核日期：2026-09-28。
>
> 只读核查发现：阶段 3A 已提供订单、物流、退款资格、动态政策和可信退款编排；阶段 3B 的 FAQ/商品问答、商品只读 MCP 工具尚未实现。以下 FAQ 是供资料问答采用的保守答复稿，不能代替实时订单查询或政策目录。FAQ-030/031 是阶段 3B Task 2/4 验收后的拟用答复；必须等相关代码实现并按计划验证通过后才可进入正式语料。

## 一、32 条原创 FAQ 草案

### FAQ-001
- **问题：** 我能查自己有哪些订单吗？
- **答复：** 可以按当前用户查询订单列表；现有工具每次请求第一页、最多 20 条。更早的订单是否能由现有工具翻页取得，需另行确认。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `OrderTools.listUserOrders` 固定 `pageNum=1&pageSize=20`；`mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `listOrders`。
- **复核日期：** 2026-09-28

### FAQ-002
- **问题：** 订单列表会显示哪些信息？
- **答复：** 当前列表工具只挑出订单 ID、订单号、金额、状态、创建时间和商品件数等字段。具体响应仍以当前订单查询结果为准。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `listUserOrders` 字段白名单；`OrderController.listOrders`。
- **复核日期：** 2026-09-28

### FAQ-003
- **问题：** 我给订单号后，能查这笔订单吗？
- **答复：** 当前只读工具可按订单 ID 查询订单详情，并返回经筛选的订单号、状态、实付总额和创建时间等信息。是否属于当前用户由后端接口的用户身份规则处理。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `getOrder` 调用 `GET /api/orders/{id}`；`mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `getOrderDetail`。
- **复核日期：** 2026-09-28

### FAQ-004
- **问题：** 查订单时能看到买了什么商品吗？
- **答复：** 当前 Agent 的 `getOrder` 工具会筛掉商品明细字段，因此不能据此列出商品名称或 SKU。该能力是否通过其他界面提供，不在这里推断。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `getOrder` 字段白名单仅含 `id`、`orderNo`、`status`、`totalAmount`、`createdAt`。
- **复核日期：** 2026-09-28

### FAQ-005
- **问题：** 能查订单现在是什么状态吗？
- **答复：** 可以读取订单接口返回的当前状态字段；应以本次查询结果为准，不能用旧对话内容替代实时状态。
- **依据：** `OrderTools.getOrder`；`mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `getOrderDetail` 返回 `OrderVO`。
- **复核日期：** 2026-09-28

### FAQ-006
- **问题：** 可以查物流进度吗？
- **答复：** 当前只读工具可按订单 ID 查询物流接口，并筛选承运方、运单号、物流状态和创建时间等字段。接口未返回或查询失败时，不能据此补猜物流进度。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `getLogistics` 调用 `GET /api/orders/{id}/logistics`；`mall-server/src/main/java/com/mall/module/logistics/controller/LogisticsController.java` — `getLogistics`。
- **复核日期：** 2026-09-28

### FAQ-007
- **问题：** 物流查询会告诉我快递公司和运单号吗？
- **答复：** 物流响应包含这些字段时，查询工具会返回承运方和运单号；字段缺失时不能补造。现有代码也不承诺物流信息的更新时间。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `getLogistics` 仅挑选 `company`、`trackingNo`、`status`、`createdAt`；`mall-server/src/main/java/com/mall/module/logistics/entity/vo/LogisticsVO.java`。
- **复核日期：** 2026-09-28

### FAQ-008
- **问题：** 物流查询报错了，能据此判断还没发货吗？
- **答复：** 不能；本次物流结果无法确认时，不推断订单进度。连接、HTTP、响应格式或业务错误会作为 MCP 工具错误返回，这表示本次查询失败，不表示订单尚未发货。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/OrderTools.java` — `getLogistics`；`mcp-server/src/main/java/com/mall/agent/mcp/SupermallClient.java` — `exchange` 将连接、HTTP、响应解析及业务错误转为 `SupermallException`；`mcp-server/src/main/java/com/mall/agent/mcp/McpServerMain.java` — `spec` 将异常作为 `isError=true` 工具结果；`mcp-server/src/main/java/com/mall/agent/mcp/ToolResults.java` — `failure`。
- **复核日期：** 2026-09-28
### FAQ-009
- **问题：** 我只想问这笔订单能不能退款，需要提供什么？
- **答复：** 当前可信入口要求一个明确的订单 ID，之后由后端重新查询资格；没有唯一订单 ID 时会请用户补充。查询资格本身不会提交退款。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `respondToEligibility`；`mall-server/src/main/java/com/mall/module/order/service/RefundEligibilityService.java` — `check` 为只读查询；阶段 3A 验证记录。
- **复核日期：** 2026-09-28

### FAQ-010
- **问题：** 退款资格是谁判断的？
- **答复：** 资格来自 supermall 的售后资格接口和业务规则；模型或 FAQ 不能把后端的不可退结果改成可退。具体订单须按本次查询结果说明。
- **依据：** `mall-server/src/main/java/com/mall/module/order/service/RefundEligibilityEvaluator.java` — `assess`；`mall-server/src/main/java/com/mall/module/order/service/RefundEligibilityService.java` — `check`；`agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `apply` 硬门槛；阶段 3A 设计“确定性退款入口”。
- **复核日期：** 2026-09-28

### FAQ-011
- **问题：** 资格查询里的可退金额是不是退款承诺？
- **答复：** 不能只看金额字段；必须同时看 `eligible` 与 `refundExists`。资格响应即使显示金额，也不单独证明此刻可退或退款已完成。
- **依据：** `mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `refundEligibility` Javadoc；`mall-server/src/main/java/com/mall/module/order/entity/vo/RefundEligibilityVO.java` 字段契约。
- **复核日期：** 2026-09-28

### FAQ-012
- **问题：** 退款金额可以由客服或模型手动改吗？
- **答复：** 当前 Agent 的提交工具不接收金额参数，金额由 supermall 根据订单决定。不能通过 FAQ 或对话承诺另一个金额。
- **依据：** `mcp-server/src/main/java/com/mall/agent/mcp/tools/RefundTools.java` — `submitRefund(Long orderId, String reason, String expectedCatalogFingerprint, String expectedPolicyCode)`；`mall-server/src/main/java/com/mall/module/order/service/RefundExecutionService.java` — `execute`。
- **复核日期：** 2026-09-28

### FAQ-013
- **问题：** 看到 `refundExists=true`，是不是说明钱已经退回了？
- **答复：** 不一定；它只说明已有退款记录，可能仍在处理中。资格查询端点不能区分记录的完成状态，需要依据执行回执或人工核实。
- **依据：** `mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `refundEligibility` Javadoc 明确说明 `refundExists` 包含 `PENDING` 与 `REFUNDED`，资格端点不返回既有行状态；`mall-server/src/main/java/com/mall/module/order/entity/vo/RefundEligibilityVO.java` — `refundExists`。
- **复核日期：** 2026-09-28

### FAQ-014
- **问题：** 订单已经有退款记录，还能再申请一次吗？
- **答复：** 现有资格查询把已有退款记录标为 `refundExists=true`，可信退款流程会停止新的自动退款。已有记录目前处于什么状态，不能仅凭这个标记判断。
- **依据：** `mall-server/src/main/java/com/mall/module/order/entity/vo/RefundEligibilityVO.java` — `refundExists`；`agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `apply` 对已有记录的门槛；`mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `refundEligibility` Javadoc。
- **复核日期：** 2026-09-28

### FAQ-015
- **问题：** 怎么确认退款确实完成了？
- **答复：** 以可信执行回执或重新查到订单状态为 `REFUNDED` 为准；资格查询中的金额或“已有记录”都不能单独当作完成证明。查询无法确认时会说明需人工核实。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `verifiedHistoricalStatusReply` 仅在重新查询订单状态为 `REFUNDED` 时作此确认；`mall-server/src/main/java/com/mall/module/order/controller/OrderController.java` — `executeRefund` 回执契约。
- **复核日期：** 2026-09-28

### FAQ-016
- **问题：** 本次会话里复核没通过，我再提交同一订单会自动重审吗？
- **答复：** 同一会话、同一订单已记录为复核驳回后，当前流程不会再次自动复核或提交，会返回已记录人工升级的信息。这个结论只适用于该会话和订单组合。
- **依据：** `agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `Key(sessionId, orderId)`、`State.REJECTED` 与 `apply` 的重复请求分支；阶段 3A 验证记录“同会话重试不再复核”。
- **复核日期：** 2026-09-28
### FAQ-017
- **问题：** 退款查询出错时，能不能先告诉我已经退了？
- **答复：** 不能；查询失败不是退款成功的证据。当前可信回复会将结果标为无法确认并提示联系人工客服核实。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `verifiedHistoricalStatusReply`、`confirm` 异常回退；阶段 3A 验证记录中“结果无法确认”边界。
- **复核日期：** 2026-09-28

### FAQ-018
- **问题：** 申请退款会立刻成功吗？
- **答复：** 申请请求先进入订单确认与后端资格检查，再进行独立政策复核；只有各项门槛通过才会提交。申请或等待确认本身不代表退款已执行。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `handleTurn`、`confirm`；`agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `apply`；阶段 3A 验证记录 `normal --confirm` 链路。
- **复核日期：** 2026-09-28

### FAQ-019
- **问题：** 申请退款时为什么要我确认订单和理由？
- **答复：** 可信入口会展示候选订单与理由，要求用户显式确认；确认只绑定该订单、理由和当前会话。未确认时不会调用退款提交。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `respondToHandoff`、`confirmationReply`、`confirm`；阶段 3A 验证记录的 `unconfirmed` 用例。
- **复核日期：** 2026-09-28

### FAQ-020
- **问题：** 没写订单 ID 或理由，能直接帮我提交吗？
- **答复：** 当前入口要求明确订单 ID 和有实质内容的理由；缺少时会要求补充，本次不提交。多个订单或目标不清楚时也会先请用户选择。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `respondToHandoff`、`groundedReason`、`trustedOrderList`；`agent/src/main/java/com/mall/agent/tools/RefundHandoffTools.java` 候选信号；阶段 3A 设计的确认边界。
- **复核日期：** 2026-09-28

### FAQ-021
- **问题：** 我选错了退款订单，或者不想继续了怎么办？
- **答复：** 待确认期间可用 `/cancel-refund` 取消；改变诉求会清除旧候选。取消后该候选不会被提交。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `handleTurn` 取消分支和新话题清除 `pendingBySession`；阶段 3A 入口实现。
- **复核日期：** 2026-09-28

### FAQ-022
- **问题：** 退款申请在什么情况下会转人工？
- **答复：** 后端明确判定当前不可退时，流程会直接说明不可退并停止提交。资格事实无法核实、政策目录无法核实，或复核未通过/无法核实时，流程会停止自动退款并记录人工升级；具体原因按本次可信查询说明。
- **依据：** `agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `apply` 对 `RefundNotEligibleException` 直接返回不可退答复；事实查询异常、目录读取/匹配异常及复核异常/驳回分支调用 `rejectAndEscalate`；`agent/src/main/java/com/mall/agent/tools/EscalationTools.java` — `escalateToHuman`。
- **复核日期：** 2026-09-28
### FAQ-023
- **问题：** 转人工以后会有人主动联系我吗？
- **答复：** 当前实现记录升级并提示“已记录，请联系人工客服”；代码没有承诺客服会主动联系或在某个时限内处理。
- **依据：** `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` — `ESCALATION_REPLY`；`agent/src/main/java/com/mall/agent/tools/EscalationTools.java` — `escalateToHuman`；阶段 3A 设计“人工升级只写入现有会话记录和日志”。
- **复核日期：** 2026-09-28

### FAQ-024
- **问题：** 自动退款复核是谁做的？
- **答复：** 退款流程由可信代码编排，独立复核 Agent 检查原始诉求、订单事实和政策条款；复核只能增加拒绝条件，不能推翻后端不可退结论或自行决定金额。
- **依据：** `agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `apply`、`rejectAndEscalate`；阶段 3A 设计“RAG 政策复核”。
- **复核日期：** 2026-09-28

### FAQ-025
- **问题：** 退款政策条款从哪里来？
- **答复：** 当前政策由 supermall 的政策目录接口提供，目录与资格规则共用后端目录构造入口。FAQ 内容不是退款政策来源。
- **依据：** `mall-server/src/main/java/com/mall/module/order/controller/AfterSalesPolicyController.java` — `listPolicies`；`mall-server/src/main/java/com/mall/module/order/service/AfterSalesPolicyCatalog.java` — `currentSnapshot`；`mcp-server/src/main/java/com/mall/agent/mcp/tools/PolicyTools.java` — `listPolicyClauses`。
- **复核日期：** 2026-09-28

### FAQ-026
- **问题：** 你们怎么知道政策目录是不是更新过？
- **答复：** 每次目录快照带有由条款内容计算的指纹，可用于比较目录版本；指纹本身不证明条款文字与执行规则在语义上一定一致。
- **依据：** `mall-server/src/main/java/com/mall/module/order/entity/vo/PolicyCatalogVO.java`；`mall-server/src/main/java/com/mall/module/order/service/AfterSalesPolicyCatalog.java` — `currentSnapshot`；`agent/src/main/java/com/mall/agent/policy/PolicyCatalogConsumer.java` — `refresh`；阶段 3A 验证记录中的同进程版本切换。
- **复核日期：** 2026-09-28

### FAQ-027
- **问题：** 我在网上看到一段旧政策，能用它判断我这笔订单吗？
- **答复：** 不能用旧截图或网上转载直接判定当前订单；政策说明应读取后端当前目录，具体订单适用哪条仍由当次后端资格响应决定。
- **依据：** `mall-server/src/main/java/com/mall/module/order/service/AfterSalesPolicyCatalog.java` — `currentSnapshot`；`mall-server/src/main/java/com/mall/module/order/entity/vo/RefundEligibilityVO.java` — `policyCode`、`catalogFingerprint`；阶段 3A 设计“同源政策检索”。
- **复核日期：** 2026-09-28
### FAQ-028
- **问题：** 目录读取失败时，可以用旧政策回答退款吗？
- **答复：** 自动退款复核不能在目录读取失败时沿用旧证据继续；流程会停止并转人工。一般政策答复也只能依据成功刷新得到的当前目录。
- **依据：** `agent/src/main/java/com/mall/agent/policy/PolicyCatalogConsumer.java` — `refresh`；`agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` — `apply` 对目录刷新失败的异常处理；阶段 3A 验证记录“failed refresh never returns old evidence”。
- **复核日期：** 2026-09-28

### FAQ-029
- **问题：** 商品问答能判断我下单时看到的描述吗？
- **答复：** 不能仅凭当前商品目录还原历史下单描述；当前描述只代表当前目录。若要核对历史页面或订单承诺，需人工查证。
- **依据：** 阶段 3A 设计“回复与资料问答”明确当前商品描述不能作历史承诺；3B 计划 Task 5 历史描述误述验收项。商品资料功能尚未实现。
- **复核日期：** 2026-09-28

### FAQ-030
- **问题：** 能查当前在售的演示商品吗？
- **答复：** 可以查询清单内当前处于上架状态的演示商品资料；回答引用商品前会重新读取详情并核对上架状态，无法核实的商品不作为答复依据。
- **依据：** `docs/plans/2026-09-26-phase3b-knowledge-qa.md` — Task 2 的 `list_on_shelf_products`、`get_product_detail` 与 Task 4 的 `CurrentProductIndex.refresh`、`verifyCitation` 验收契约。此条须待 Task 2/4 实现并验证通过后才能收入正式语料。
- **复核日期：** 2026-09-28

### FAQ-031
- **问题：** 商品的价格和库存以哪里显示的信息为准？
- **答复：** 以当前商品详情返回的 SKU 价格和库存为准；仅凭商品列表摘要不推断这些数值。若详情无法核实，就不报价格或库存。
- **依据：** `docs/plans/2026-09-26-phase3b-knowledge-qa.md` — Task 2 Step 3 要求不从列表推断 SKU/价格、Task 4 `verifyCitation`；`mall-server/src/main/java/com/mall/module/product/controller/ProductController.java` — `showDetails`；`mall-server/src/main/java/com/mall/module/product/entity/vo/ProductSkuVO.java` — `price`、`stock`。此条须待 Task 2/4 实现并验证通过后才能收入正式语料。
- **复核日期：** 2026-09-28
### FAQ-032
- **问题：** 当前商品介绍能当作退款政策或质量核验结果吗？
- **答复：** 不能；FAQ 和商品说明只用于资料解释，不是退款审批依据，也不证明质量问题。退款资格和可退金额须以当前后端查询及可信流程为准。
- **依据：** 阶段 3A 设计“回复与资料问答”“不在阶段 3 内”；`mall-server/src/main/java/com/mall/module/order/service/RefundEligibilityEvaluator.java` — `assess` 只以订单状态与创建时间解析政策；`mall-server/src/main/java/com/mall/module/order/enums/AfterSalesPolicy.java` — `resolve` 不读取理由、商品使用状态或凭证。
- **复核日期：** 2026-09-28

## 二、虚构演示商品候选（21 件）

> 全部名称、描述、规格、价格和库存均为**虚构演示数据**，不是现有商品、真实报价或库存。拟采用“纸品与桌面文具”连贯主题；目前未能从已提供的只读代码/验证记录确认运行环境中存在可用类目。**上架前须选现有类目 ID**，并确认该类目可容纳下列纸品/文具。每项仅一个 SKU；描述只陈述拟创建记录中可直接兑现的张数、尺寸、颜色、材质或件数。21 件高于计划单页 20 件，用来展示商品分页读取；SKU 字段按 `MerchantProductDTO.SkuDTO` 的价格、库存和规格契约拟定。

| Stable logical key | 中文名称 | 原创描述（创建记录需与其一致） | SKU 规格 | 正价（虚构） | 库存（虚构） |
|---|---|---|---|---:|---:|
| paper_grid_a5 | A5 方格线圈本 | 一本含 80 张 A5 方格内页，封面为雾蓝色纸卡。 | A5／方格／80张／雾蓝 | 18.80 | 40 |
| paper_plain_a5 | A5 空白线圈本 | 一本含 80 张 A5 空白内页，封面为浅灰色纸卡。 | A5／空白／80张／浅灰 | 17.80 | 36 |
| paper_dot_a5 | A5 点阵线圈本 | 一本含 80 张 A5 点阵内页，封面为米白色纸卡。 | A5／点阵／80张／米白 | 19.80 | 32 |
| paper_weekpad | 桌面周计划纸 | 一册含 52 张单周计划页，页面尺寸为 210×148 毫米。 | 210×148mm／52张 | 16.00 | 28 |
| paper_daily_pad | 桌面日记纸 | 一册含 60 张空白日期记录页，页面尺寸为 210×148 毫米。 | 210×148mm／60张 | 15.00 | 30 |
| paper_todo_pad | 横线待办纸 | 一册含 70 张横线待办页，页面尺寸为 148×105 毫米。 | 148×105mm／70张 | 12.50 | 34 |
| paper_index_cards | 横线索引卡 | 一包含 50 张 90×55 毫米横线纸卡。 | 90×55mm／50张 | 9.90 | 45 |
| paper_blank_cards | 空白索引卡 | 一包含 50 张 90×55 毫米空白纸卡。 | 90×55mm／50张 | 9.50 | 42 |
| paper_bookmarks | 彩边书签纸卡 | 一包含 12 张纸质书签，每张为单色边框设计。 | 12张／纸质 | 8.80 | 50 |
| paper_message_cards | 方形留言卡 | 一包含 20 张 90×90 毫米空白留言卡。 | 90×90mm／20张 | 11.00 | 38 |
| paper_envelopes | A6 素色信封 | 一包含 10 只适配 A6 卡片的浅米色纸信封。 | A6／浅米／10只 | 10.80 | 35 |
| paper_label_sheets | 圆角标签纸 | 一包含 8 张 A4 标签纸，每张分为 24 个圆角标签。 | A4／8张／192枚 | 13.80 | 26 |
| paper_clipboards | A5 纸板夹 | 一件 A5 尺寸硬纸板夹，配一枚金属夹扣。 | A5／纸板／1件 | 14.80 | 22 |
| desk_pencil_case | 帆布笔袋 | 一个长 200 毫米的灰绿色布面笔袋，带拉链开口。 | 200mm／灰绿／1个 | 22.00 | 18 |
| desk_pen_holder | 圆筒笔筒 | 一个高 90 毫米、直径 80 毫米的白色圆筒笔筒。 | 90×80mm／白色／1个 | 18.00 | 24 |
| desk_clip_set | 金属长尾夹组合 | 一盒含 12 枚黑色金属长尾夹，分为三种尺寸各 4 枚。 | 12枚／黑色／3尺寸 | 12.80 | 33 |
| desk_binder_clips | 小号装订夹 | 一盒含 20 枚黑色小号金属装订夹。 | 小号／20枚／黑色 | 9.80 | 40 |
| desk_ruler | 透明刻度尺 | 一把 20 厘米透明塑料刻度尺，刻度以毫米标示。 | 20cm／透明／1把 | 6.80 | 31 |
| desk_scissors | 圆头纸剪 | 一把总长 140 毫米的圆头剪刀，手柄为蓝色塑料。 | 140mm／蓝色／1把 | 12.00 | 20 |
| desk_washi_tape | 几何纹纸胶带 | 一卷宽 15 毫米、长 5 米的纸胶带，印有蓝灰几何图样。 | 15mm×5m／1卷 | 7.80 | 44 |
| desk_page_flags | 纯色索引贴 | 一组含 5 色索引贴，每色 20 枚，共 100 枚。 | 5色×20枚／100枚 | 8.50 | 37 |

## 三、事实审查清单

- [ ] **依据路径存在性：** FAQ 的阶段 3A 依据应在指定已验收 Agent/MCP 与 supermall checkout 中逐条定位；设计和 3B 计划在主仓核对，阶段 3A 验证记录 `docs/phase3a-rag-refund-validation-2026-09-28.md` 在 Agent 工作树中核对。FAQ-008 仅描述 MCP 已有的异常包装契约，不声称已实测物流故障。
- [ ] **逐条答复受代码支持：** 当前订单/物流功能仅限代码实际暴露字段；退款资格、金额和退款状态不得由静态 FAQ 断言。FAQ-030、FAQ-031 是 Task 2/4 验收后的拟用稳定答复；相关功能实现与验证完成前不得加入正式语料或宣称已交付。
- [ ] **政策保持动态：** FAQ 没有写入固定退款政策条款或固定窗口；后续政策问答只能从后端当前目录读取，具体订单以当次资格响应为准。
- [ ] **商品描述兑现：** 创建脚本提交的商品名称、描述、SKU 规格、价格、库存须与表格一致；当前只有字段契约可核对，尚无实际商品记录。颜色/材质等值需在真实创建请求中明确写入规格或描述，否则删去相应描述。
- [ ] **类目和凭据前提：** 需由运行环境提供现有类目 ID、独立商家 JWT、supermall 运行地址；这份只读检查没有查询业务 API，也未确认类目存在。不得编造类目 ID；按计划使用商家业务 API 创建、上架，Agent/MCP 只持 C 端用户令牌。
- [ ] **真实商品数据：** 21 件均是人工作出的演示候选，不是上架状态、实际库存、真实售价或商家目录的事实；只有业务 API 成功创建并由 C 端分页/详情接口核对后才能称为当前商品。
- [ ] **数量与分页：** FAQ 32 条，ID 连续且唯一；候选商品 21 件，Stable logical key 唯一，SKU 数量每件恰为一个，价格为正、库存非负。21 件是为了覆盖计划规定的 `pageSize=20` 分页边界。
- [ ] **范围纪律：** 本文件是审阅稿，不是最终 corpus JSON；未创建商品、未调用写 API、未改动计划或实现，也未验证线上问答功能。
