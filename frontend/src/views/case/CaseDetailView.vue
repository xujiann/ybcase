<template>
  <div v-loading="loading">
    <el-card class="mb">
      <div class="toolbar">
        <div>
          <h3 style="margin: 0">{{ c.caseNo }}　{{ c.name }}</h3>
          <div class="hint" style="margin-top: 6px">
            <el-tag :type="(CASE_STATUS_TAG[c.status] as any)" size="small">{{ CASE_STATUS[c.status] }}</el-tag>
            <span style="margin-left: 12px">{{ c.procedureType === 'SUMMARY' ? '简易程序' : '普通程序' }}</span>
            <span style="margin-left: 12px">承办人 {{ c.ownerUser || '-' }}
              <el-button v-if="isLeader" size="small" text type="primary" @click="onTransferOwner">移交</el-button></span>
            <span style="margin-left: 12px">立案 {{ c.filedAt }}</span>
            <span style="margin-left: 12px">办案期限 {{ detail.effectiveDeadline }}（含延长 {{ c.extensionDays }} 日与扣除期间）</span>
          </div>
        </div>
        <el-button @click="$router.push('/case/list')">返回列表</el-button>
      </div>
      <el-descriptions :column="3" border size="small" class="mb">
        <el-descriptions-item label="当事人">{{ c.partyName }}（{{ PARTY_TYPE[c.partyType] }}）</el-descriptions-item>
        <el-descriptions-item label="证照号">{{ c.partyCreditNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="法定代表人">{{ c.partyLegalRep || '-' }}</el-descriptions-item>
        <el-descriptions-item label="案由" :span="2">{{ cause.itemNo }}. {{ cause.category }}——{{ cause.description }}</el-descriptions-item>
        <el-descriptions-item label="涉案金额">{{ c.amountInvolved }}</el-descriptions-item>
        <el-descriptions-item label="案情摘要" :span="3">{{ c.summary || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'SUSPENDED'" label="中止原因" :span="3">{{ c.suspendReason }}（{{ c.suspendedAt }}）</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'TERMINATED'" label="终止原因" :span="3">{{ c.terminateReason }}（{{ c.terminatedAt }}）</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'CLOSED'" label="结案" :span="3">
          {{ CLOSE_REASON[c.closeReason] || c.closeReason }}（{{ c.closedAt }}）　案卷号：{{ c.archiveNo }}
        </el-descriptions-item>
      </el-descriptions>
      <!-- 流程操作：按状态提供合法动作 -->
      <div class="actions">
        <template v-if="c.status === 'INVESTIGATING'">
          <el-button v-if="c.procedureType === 'NORMAL'" type="primary" @click="dlg.report = true">调查终结报告</el-button>
          <el-button v-if="c.procedureType === 'SUMMARY' && isLeader" type="primary" @click="openDecide">当场处罚决定</el-button>
          <el-button v-if="isLeader" @click="onSuspend">中止调查</el-button>
          <el-button v-if="isLeader" @click="onExtendCase">延长期限</el-button>
          <el-button v-else @click="dlg.apply = true">申请延期/中止</el-button>
          <el-button v-if="isLeader" type="warning" @click="onTerminate">终止调查</el-button>
        </template>
        <template v-if="c.status === 'SUSPENDED'">
          <el-button v-if="isLeader" type="primary" @click="onResume">恢复调查</el-button>
        </template>
        <template v-if="c.status === 'REPORTED'">
          <el-button type="primary" @click="dlg.notice = true">处罚告知</el-button>
          <el-button @click="onSubmitReview">提交法制审核</el-button>
          <el-button v-if="isLeader" @click="onExtendCase">延长期限</el-button>
          <el-button v-else @click="dlg.apply = true">申请延期</el-button>
        </template>
        <template v-if="c.status === 'NOTIFIED'">
          <el-button v-if="isLeader" type="primary" @click="openDecide">作出决定</el-button>
          <el-button @click="dlg.statement = true">陈述申辩/听证</el-button>
          <el-button @click="onSubmitReview">提交法制审核</el-button>
          <el-button v-if="isLeader" @click="onExtendCase">延长期限</el-button>
          <el-button v-else @click="dlg.apply = true">申请延期</el-button>
        </template>
        <template v-if="c.status === 'DECIDED'">
          <el-button type="primary" @click="dlg.deliver = true">登记送达</el-button>
          <el-button v-if="c.procedureType === 'SUMMARY' && !c.summaryRecordAt" @click="onSummaryRecord">简易程序备案</el-button>
          <el-button v-if="!c.eDeliveryConsent" @click="onEConsent">电子送达确认书</el-button>
          <el-button @click="dlg.execution = true">登记执行</el-button>
          <el-button v-if="isLeader" @click="onCloseCase">结案</el-button>
        </template>
        <template v-if="c.status === 'DELIVERED'">
          <el-button type="primary" @click="dlg.execution = true">登记执行</el-button>
          <el-button @click="onLateFee">加处罚款测算</el-button>
          <el-button v-if="isLeader" @click="onApproveDefer">批准暂缓/分期</el-button>
          <el-button @click="onCourtEnforce">申请法院强制执行</el-button>
          <el-button v-if="isLeader" type="success" @click="onCloseCase">结案</el-button>
        </template>
        <el-button v-if="!['CLOSED', 'TERMINATED'].includes(c.status)" @click="dlg.meeting = true">集体讨论记录</el-button>
        <el-button v-if="!['CLOSED', 'TERMINATED'].includes(c.status)" @click="dlg.apply = true">提交审批申请</el-button>
      </div>
      <div v-if="pendingApprovals.length" class="hint" style="margin-top: 8px">
        <el-tag type="warning" size="small">本案有 {{ pendingApprovals.length }} 张审批单待负责人裁决（见"待办审批"）</el-tag>
      </div>
    </el-card>

    <el-card>
      <el-tabs>
        <el-tab-pane :label="`办案人员(${detail.officers?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.officer = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">添加人员</el-button>
          <el-table :data="detail.officers" border size="small">
            <el-table-column prop="name" label="姓名" width="120" />
            <el-table-column prop="cert_no" label="执法证号" width="160" />
            <el-table-column label="分工" width="80">
              <template #default="{ row }">{{ row.duty === 'LEAD' ? '主办' : '协办' }}</template>
            </el-table-column>
            <el-table-column label="回避" width="200">
              <template #default="{ row }">
                <span v-if="row.avoided" class="danger">已回避：{{ row.avoid_reason }}</span>
                <el-button v-else size="small" text type="warning" @click="onAvoid(row)">申请回避</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`证据(${detail.evidences?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.evidence = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">添加证据</el-button>
          <el-table :data="detail.evidences" border size="small">
            <el-table-column label="种类" width="110">
              <template #default="{ row }">{{ EVIDENCE_TYPE[row.type] }}</template>
            </el-table-column>
            <el-table-column prop="name" label="名称" show-overflow-tooltip />
            <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip />
            <el-table-column prop="obtained_at" label="取得日期" width="105" />
            <el-table-column label="质证" width="170">
              <template #default="{ row }">
                <span v-if="row.cross_exam_at" class="hint">已质证 {{ row.cross_exam_at }}</span>
                <el-button v-else size="small" text type="primary" @click="onCrossExam(row)">当事人质证</el-button>
              </template>
            </el-table-column>
            <el-table-column label="先行登记保存" width="200">
              <template #default="{ row }">
                <template v-if="row.register_hold">
                  <span class="danger">保存中(限{{ row.hold_expire_at }})</span>
                  <el-button size="small" text type="primary" @click="onHoldDispose(row)">处理</el-button>
                </template>
                <span v-else>{{ row.hold_disposal ? '已处理:' + row.hold_disposal : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="封存" width="220">
              <template #default="{ row }">
                <template v-if="row.sealed">
                  <span class="danger">封存至{{ row.seal_expire_at }}</span>
                  <el-button v-if="!row.seal_extended" size="small" text @click="onSeal(row, true)">延长</el-button>
                  <el-button size="small" text type="success" @click="onSeal(row, false)">解除</el-button>
                </template>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`文书(${detail.documents?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.document = true" :disabled="c.status === 'CLOSED'">制作文书</el-button>
          <el-button size="small" class="mb" type="primary" @click="onRenderTpl" :disabled="c.status === 'CLOSED'">模板生成</el-button>
          <el-button size="small" class="mb" @click="onCatalog">案卷目录</el-button>
          <el-button size="small" class="mb" type="success" @click="onPrintArchive">打印卷宗</el-button>
          <el-table :data="detail.documents" border size="small">
            <el-table-column label="类型" width="130">
              <template #default="{ row }">{{ DOC_TYPE[row.doc_type] || row.doc_type }}</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" show-overflow-tooltip />
            <el-table-column prop="made_at" label="日期" width="105" />
            <el-table-column prop="maker" label="制作人" width="100" />
            <el-table-column label="签名确认" width="90">
              <template #default="{ row }">{{ row.signed ? '已签' : '未签' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="viewDoc(row)">查看</el-button>
                <el-button size="small" text @click="onSignDoc(row)">签章</el-button>
                <el-button size="small" text @click="onVerifyDoc(row)">验签</el-button>
                <el-button size="small" text @click="onDeliverDoc(row)">送达</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`附件(${attachments.length})`">
          <div class="mb">
            <el-select v-model="uploadCategory" size="small" style="width: 140px; margin-right: 8px">
              <el-option label="文书扫描件" value="DOC_SCAN" />
              <el-option label="执法音像" value="AV_RECORD" />
              <el-option label="其他" value="OTHER" />
            </el-select>
            <input ref="fileInput" type="file" style="display: none" @change="onUpload" />
            <el-button size="small" type="primary" @click="(fileInput as any)?.click()">上传附件</el-button>
            <span class="hint" style="margin-left: 8px">扫描件 ≤10MB；执法音像走外置存储（FILE 模式）≤200MB</span>
          </div>
          <el-table :data="attachments" border size="small">
            <el-table-column label="分类" width="100">
              <template #default="{ row }">{{ ({ DOC_SCAN: '扫描件', AV_RECORD: '执法音像', OTHER: '其他' } as any)[row.category] || '-' }}</template>
            </el-table-column>
            <el-table-column prop="filename" label="文件名" show-overflow-tooltip />
            <el-table-column label="大小" width="100">
              <template #default="{ row }">{{ (row.size_bytes / 1024).toFixed(1) }} KB</template>
            </el-table-column>
            <el-table-column prop="uploaded_by" label="上传人" width="100" />
            <el-table-column label="下载" width="80">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="onDownload(row)">下载</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="大事记">
          <el-timeline style="padding: 8px 12px">
            <el-timeline-item v-for="(ev, i) in timeline" :key="i" :timestamp="ev.date" placement="top">
              <b>{{ ev.kind }}</b>　<span class="hint">{{ ev.detail }}</span>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>

        <el-tab-pane :label="`期限扣除(${detail.exclusions?.length || 0})`">
          <el-button size="small" class="mb" @click="dlg.exclusion = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">登记扣除期间</el-button>
          <p class="hint">检测检验、鉴定、听证、公告、专家评审时间不计入办案期限（第45条）</p>
          <el-table :data="detail.exclusions" border size="small" class="mb">
            <el-table-column label="事由" width="110">
              <template #default="{ row }">{{ EXCLUSION_REASON[row.reason] }}</template>
            </el-table-column>
            <el-table-column prop="start_at" label="开始" width="110" />
            <el-table-column prop="end_at" label="结束" width="110" />
            <el-table-column prop="note" label="说明" />
          </el-table>
          <h4>专家评审（第25条，结束自动登记期限扣除）　<el-button size="small" @click="onStartExpert" :disabled="['CLOSED','TERMINATED'].includes(c.status)">启动评审</el-button></h4>
          <el-table :data="detail.expertReviews" border size="small">
            <el-table-column prop="experts" label="专家" width="220" show-overflow-tooltip />
            <el-table-column prop="started_at" label="开始" width="110" />
            <el-table-column label="结束/意见">
              <template #default="{ row }">
                <span v-if="row.ended_at">{{ row.ended_at }}：{{ row.opinion }}</span>
                <el-button v-else size="small" text type="primary" @click="onEndExpert(row)">结束并出具意见</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`审核·告知·讨论(${(detail.reviews?.length || 0) + (detail.notices?.length || 0)})`">
          <h4>法制审核（第37-40条）</h4>
          <el-table :data="detail.reviews" border size="small" class="mb">
            <el-table-column prop="requiredReason" label="触发情形" show-overflow-tooltip />
            <el-table-column prop="submittedAt" label="提交" width="105" />
            <el-table-column prop="deadlineAt" label="审核期限" width="105" />
            <el-table-column label="意见" width="200">
              <template #default="{ row }">
                <span v-if="row.reviewedAt">{{ REVIEW_OPINION[row.opinionType] }}：{{ row.opinion }}（{{ row.reviewer }}）</span>
                <el-button v-else-if="isLegal" size="small" type="primary" @click="openReviewDialog(row)">办理审核</el-button>
                <span v-else class="hint">待法制机构审核</span>
              </template>
            </el-table-column>
          </el-table>
          <h4>处罚告知与陈述申辩（第41条）</h4>
          <el-table :data="detail.notices" border size="small" class="mb">
            <el-table-column prop="notifiedAt" label="告知日期" width="105" />
            <el-table-column prop="proposedFine" label="拟罚款" width="110" align="right" />
            <el-table-column prop="proposedRecoup" label="拟追回基金" width="110" align="right" />
            <el-table-column label="听证" width="180">
              <template #default="{ row }">
                {{ row.hearingEntitled ? (row.hearingHeldAt ? `已听证 ${row.hearingHeldAt}` : row.hearingRequested ? '已申请' : '有听证权利') : '未达听证标准' }}
              </template>
            </el-table-column>
            <el-table-column prop="statement" label="陈述申辩" show-overflow-tooltip />
            <el-table-column prop="statementReview" label="复核意见" show-overflow-tooltip />
          </el-table>
          <h4>听证（辽46-50条）　<el-button size="small" @click="dlg.hearing = true" :disabled="!['NOTIFIED','REPORTED'].includes(c.status)">安排听证</el-button></h4>
          <el-table :data="detail.hearings" border size="small" class="mb">
            <el-table-column prop="notice_sent_at" label="通知送达" width="105" />
            <el-table-column prop="scheduled_at" label="举行日期" width="105" />
            <el-table-column prop="host" label="主持人" width="100" />
            <el-table-column label="状态" width="230">
              <template #default="{ row }">
                <template v-if="row.status === 'SCHEDULED'">
                  待举行 <el-button size="small" text type="primary" @click="onHoldHearing(row)">举行并记录笔录</el-button>
                </template>
                <template v-else-if="row.status === 'HELD'">
                  已举行{{ row.held_at }} <el-button size="small" text type="primary" @click="onHearingOpinion(row)">出具听证意见</el-button>
                </template>
                <span v-else>已出意见（{{ row.opinion_at }}）</span>
              </template>
            </el-table-column>
            <el-table-column prop="opinion" label="听证意见" show-overflow-tooltip />
          </el-table>
          <h4>协查台账（第34条，15日）　<el-button size="small" @click="onAddAssist" :disabled="['CLOSED','TERMINATED'].includes(c.status)">发出协查函</el-button></h4>
          <el-table :data="detail.assists" border size="small" class="mb">
            <el-table-column label="方向" width="70">
              <template #default="{ row }">{{ row.direction === 'OUT' ? '发出' : '受托' }}</template>
            </el-table-column>
            <el-table-column prop="org" label="协查机关" width="160" show-overflow-tooltip />
            <el-table-column prop="content" label="事项" show-overflow-tooltip />
            <el-table-column prop="due_at" label="期限" width="105" />
            <el-table-column label="办理" width="170">
              <template #default="{ row }">
                <span v-if="row.replied_at">{{ row.refused ? '已拒绝' : '已复函' }} {{ row.replied_at }}</span>
                <el-button v-else size="small" text type="primary" @click="onReplyAssist(row)">复函/拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
          <h4>集体讨论（第44条）</h4>
          <el-table :data="detail.meetings" border size="small">
            <el-table-column prop="held_at" label="日期" width="105" />
            <el-table-column prop="attendees" label="参加人员" width="180" show-overflow-tooltip />
            <el-table-column prop="conclusion" label="结论" show-overflow-tooltip />
            <el-table-column label="签字确认" width="170">
              <template #default="{ row }">
                <span v-if="row.sign_confirmed">已确认：{{ row.sign_names }}</span>
                <el-button v-else size="small" text type="primary" @click="onSignMeeting(row)">签字确认</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="决定·送达·执行">
          <template v-if="detail.decision?.decisionType">
            <el-descriptions :column="3" border size="small" class="mb">
              <el-descriptions-item label="决定类型">{{ DECISION_TYPE[detail.decision.decisionType] }}</el-descriptions-item>
              <el-descriptions-item label="决定书文号">{{ detail.decision.decisionNo || '-' }}</el-descriptions-item>
              <el-descriptions-item label="决定日期">{{ detail.decision.decidedAt }}</el-descriptions-item>
              <el-descriptions-item label="罚款">{{ detail.decision.fineAmount }}</el-descriptions-item>
              <el-descriptions-item label="责令退回基金">{{ detail.decision.recoupAmount }}</el-descriptions-item>
              <el-descriptions-item label="没收违法所得">{{ detail.decision.confiscateAmount }}</el-descriptions-item>
              <el-descriptions-item label="从轻/减轻情形">{{ detail.decision.mitigation || '-' }}</el-descriptions-item>
              <el-descriptions-item label="裁量理由" :span="2">{{ detail.decision.discretionReason || '-' }}</el-descriptions-item>
              <el-descriptions-item label="公开">
                <span v-if="detail.decision.published">已公开 {{ detail.decision.publishedAt }}</span>
                <el-button v-else size="small" type="warning" @click="onPublish">公开（7日内）</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="政府备案" :span="2">
                <span v-if="detail.decision.govRecordNo">{{ detail.decision.govRecordNo }}（{{ detail.decision.govRecordAt }}）</span>
                <el-button v-else size="small" text type="primary" @click="onGovRecord">备案登记</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="决定内容" :span="3">{{ detail.decision.content }}</el-descriptions-item>
            </el-descriptions>
          </template>
          <p v-else class="hint">尚未作出处理决定</p>
          <h4>送达（第59条）</h4>
          <el-table :data="detail.deliveries" border size="small" class="mb">
            <el-table-column label="方式" width="110">
              <template #default="{ row }">{{ DELIVERY_METHOD[row.method] }}</template>
            </el-table-column>
            <el-table-column prop="delivered_at" label="送达日期" width="110" />
            <el-table-column prop="receiver" label="受送达人" width="140" />
            <el-table-column prop="note" label="备注" />
          </el-table>
          <h4>分期计划（第54条）　<el-button size="small" @click="openInstallment" :disabled="!c.deferApproved">添加分期</el-button></h4>
          <el-table :data="installments" border size="small" class="mb">
            <el-table-column prop="seq" label="期数" width="70" />
            <el-table-column prop="due_at" label="到期日" width="120" />
            <el-table-column prop="amount" label="金额" width="120" align="right" />
            <el-table-column label="缴纳" width="150">
              <template #default="{ row }">
                <span v-if="row.paid_at">已缴 {{ row.paid_at }}</span>
                <el-button v-else size="small" text type="primary" @click="onPayInstallment(row)">登记缴纳</el-button>
              </template>
            </el-table-column>
          </el-table>
          <h4>协议处理台账（联动经办）　<el-button size="small" @click="onAddAgreement" :disabled="!detail.decision?.decisionType">移交经办处理</el-button></h4>
          <el-table :data="detail.agreementActions" border size="small" class="mb">
            <el-table-column label="处理" width="110">
              <template #default="{ row }">{{ ({ INTERVIEW: '约谈', SUSPEND_AGREEMENT: '暂停协议', TERMINATE_AGREEMENT: '解除协议', REFUSE_PAY: '拒付', RECOVER: '追回' } as any)[row.action] }}</template>
            </el-table-column>
            <el-table-column prop="org" label="经办机构" width="160" show-overflow-tooltip />
            <el-table-column prop="sent_at" label="移交日期" width="110" />
            <el-table-column label="办理结果">
              <template #default="{ row }">
                <span v-if="row.replied_at">{{ row.replied_at }}：{{ row.result }}</span>
                <el-button v-else size="small" text type="primary" @click="onReplyAgreement(row)">登记结果</el-button>
              </template>
            </el-table-column>
          </el-table>
          <h4>执行（第52-56条）　<span class="hint">暂缓/分期：{{ c.deferApproved ? '已批准' : '未申请' }}；法院强执：{{ c.courtEnforceApplied ? '已申请' : '未申请' }}</span></h4>
          <el-table :data="detail.executions" border size="small">
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ EXEC_KIND[row.kind] }}</template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="120" align="right" />
            <el-table-column prop="paid_at" label="缴付日期" width="110" />
            <el-table-column label="方式" width="110">
              <template #default="{ row }">{{ EXEC_METHOD[row.method] }}</template>
            </el-table-column>
            <el-table-column prop="note" label="备注" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 操作弹窗 -->
    <el-dialog v-model="dlg.officer" title="添加办案人员" width="440px">
      <el-form label-width="90px">
        <el-form-item label="姓名"><el-input v-model="officerForm.name" /></el-form-item>
        <el-form-item label="执法证号"><el-input v-model="officerForm.certNo" /></el-form-item>
        <el-form-item label="分工">
          <el-select v-model="officerForm.duty"><el-option label="主办" value="LEAD" /><el-option label="协办" value="MEMBER" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.officer = false">取消</el-button>
        <el-button type="primary" @click="submit(`officers`, officerForm, 'officer')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.evidence" title="添加证据（法定八类）" width="520px">
      <el-form label-width="110px">
        <el-form-item label="种类">
          <el-select v-model="evidenceForm.type" style="width: 100%">
            <el-option v-for="(v, k) in EVIDENCE_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="evidenceForm.name" /></el-form-item>
        <el-form-item label="来源"><el-input v-model="evidenceForm.source" /></el-form-item>
        <el-form-item label="取得日期">
          <el-date-picker v-model="evidenceForm.obtainedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="保管人"><el-input v-model="evidenceForm.keeper" /></el-form-item>
        <el-form-item label="先行登记保存">
          <el-switch v-model="evidenceForm.registerHold" /><span class="hint" style="margin-left: 8px">7个工作日内作出处理决定（第26条）</span>
        </el-form-item>
        <el-form-item label="封存">
          <el-switch v-model="evidenceForm.sealed" /><span class="hint" style="margin-left: 8px">不超过30日，可延长一次（第31条）</span>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="evidenceForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.evidence = false">取消</el-button>
        <el-button type="primary" @click="submit(`evidences`, evidenceForm, 'evidence')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.document" title="制作文书" width="600px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="documentForm.docType" style="width: 100%">
            <el-option v-for="(v, k) in DOC_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="documentForm.title" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="documentForm.content" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="制作人"><el-input v-model="documentForm.maker" /></el-form-item>
        <el-form-item v-if="documentForm.docType === 'ORDER_CORRECT'" label="改正期限">
          <el-date-picker v-model="documentForm.dueAt" type="date" value-format="YYYY-MM-DD" style="width: 100%"
                          placeholder="责令改正的截止日期（督办按此预警逾期未改正）" />
        </el-form-item>
        <el-form-item label="已签名确认"><el-switch v-model="documentForm.signed" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.document = false">取消</el-button>
        <el-button type="primary" @click="submit(`documents`, documentForm, 'document')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.exclusion" title="登记不计入期限的期间" width="480px">
      <el-form label-width="90px">
        <el-form-item label="事由">
          <el-select v-model="exclusionForm.reason" style="width: 100%">
            <el-option v-for="(v, k) in EXCLUSION_REASON" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="exclusionForm.startAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="exclusionForm.endAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="exclusionForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.exclusion = false">取消</el-button>
        <el-button type="primary" @click="submit(`exclusions`, exclusionForm, 'exclusion')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.report" title="案件调查终结报告（第36条）" width="640px">
      <p class="hint">应包括：当事人基本情况；案件来源、调查经过及行政强制措施情况；调查认定的事实及主要证据；违法行为性质；处理意见及依据。</p>
      <el-input v-model="reportContent" type="textarea" :rows="10" />
      <template #footer>
        <el-button @click="dlg.report = false">取消</el-button>
        <el-button type="primary" @click="onReport">提交（调查终结）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.notice" title="行政处罚告知（第41条）" width="600px">
      <el-form label-width="110px">
        <el-form-item label="拟罚款金额"><el-input-number v-model="noticeForm.proposedFine" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="拟追回基金"><el-input-number v-model="noticeForm.proposedRecoup" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="变更理由">
          <el-input v-model="noticeForm.changeReason" type="textarea" :rows="2"
                    placeholder="仅再次告知且金额高于前次时必填：说明改变原认定事实、证据或依据的理由（辽52条）" />
        </el-form-item>
        <el-form-item label="事实理由依据"><el-input v-model="noticeForm.content" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <p class="hint">拟罚款达到听证标准将自动告知听证权利；当事人享有陈述权、申辩权。</p>
      <template #footer>
        <el-button @click="dlg.notice = false">取消</el-button>
        <el-button type="primary" @click="onNotify">送达告知书</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.statement" title="陈述申辩 / 听证" width="600px">
      <el-form label-width="110px">
        <el-form-item label="陈述申辩内容"><el-input v-model="statementForm.statement" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="复核意见"><el-input v-model="statementForm.statementReview" type="textarea" :rows="3" placeholder="成立的应当采纳；不得因陈述申辩加重处罚" /></el-form-item>
        <el-form-item label="明确放弃">
          <el-switch v-model="statementForm.statementWaived" />
          <div class="hint">当事人明确表示放弃陈述申辩的，勾选后可在期限届满前作出决定（须另有书面/笔录佐证）</div>
        </el-form-item>
        <el-form-item label="申请听证"><el-switch v-model="statementForm.hearingRequested" /></el-form-item>
        <el-form-item label="听证日期">
          <el-date-picker v-model="statementForm.hearingHeldAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.statement = false">取消</el-button>
        <el-button type="primary" @click="onStatement">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.meeting" title="负责人集体讨论记录（第44条）" width="600px">
      <el-form label-width="90px">
        <el-form-item label="讨论日期">
          <el-date-picker v-model="meetingForm.heldAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="参加人员"><el-input v-model="meetingForm.attendees" /></el-form-item>
        <el-form-item label="讨论记录"><el-input v-model="meetingForm.record" type="textarea" :rows="4" placeholder="不同意见应当如实记录" /></el-form-item>
        <el-form-item label="结论"><el-input v-model="meetingForm.conclusion" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.meeting = false">取消</el-button>
        <el-button type="primary" @click="submit(`meetings`, meetingForm, 'meeting')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.decide" title="作出处理决定（第43条）" width="640px">
      <el-form label-width="110px">
        <el-form-item label="决定类型">
          <el-select v-model="decisionForm.decisionType" style="width: 100%">
            <el-option v-for="(v, k) in DECISION_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
          <el-button size="small" text type="primary" @click="onDiscretionSuggest" style="margin-top: 4px">查看裁量基准建议</el-button>
        </el-form-item>
        <template v-if="decisionForm.decisionType === 'PUNISH'">
          <el-form-item label="罚款金额"><el-input-number v-model="decisionForm.fineAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="责令退回基金"><el-input-number v-model="decisionForm.recoupAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="没收违法所得"><el-input-number v-model="decisionForm.confiscateAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="其他措施"><el-input v-model="decisionForm.otherMeasures" placeholder="约谈、建议解除服务协议等" /></el-form-item>
          <el-form-item label="从轻/减轻"><el-input v-model="decisionForm.mitigation" placeholder="主动消除危害/受胁迫/立功等（辽55条），无则留空" /></el-form-item>
          <el-form-item label="裁量理由"><el-input v-model="decisionForm.discretionReason" type="textarea" :rows="2" placeholder="裁量性决定应说明考虑的主要因素（辽44条）" /></el-form-item>
        </template>
        <el-form-item label="决定内容"><el-input v-model="decisionForm.content" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.decide = false">取消</el-button>
        <el-button type="primary" @click="onDecide">作出决定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.deliver" title="登记送达（第59条：7个工作日内）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="送达方式">
          <el-select v-model="deliveryForm.method" style="width: 100%">
            <el-option v-for="(v, k) in DELIVERY_METHOD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="送达日期">
          <el-date-picker v-model="deliveryForm.deliveredAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="受送达人"><el-input v-model="deliveryForm.receiver" /></el-form-item>
        <el-form-item label="回证编号"><el-input v-model="deliveryForm.receiptNo" placeholder="送达回证编号（辽58条要求必须有回证）" /></el-form-item>
        <el-form-item label="回证签收日">
          <el-date-picker v-model="deliveryForm.receiptSignedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="签收日期即送达日期" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="deliveryForm.note" placeholder="电子送达须经当事人签订确认书" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.deliver = false">取消</el-button>
        <el-button type="primary" @click="onDeliver">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.execution" title="登记执行（第52-55条）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="executionForm.kind" style="width: 100%">
            <el-option v-for="(v, k) in EXEC_KIND" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额"><el-input-number v-model="executionForm.amount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="缴付日期">
          <el-date-picker v-model="executionForm.paidAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="方式">
          <el-select v-model="executionForm.method" style="width: 100%">
            <el-option v-for="(v, k) in EXEC_METHOD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="票据号"><el-input v-model="executionForm.receiptNo" placeholder="当场收缴必须登记财政统一票据号" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="executionForm.note" /></el-form-item>
      </el-form>
      <p class="hint">当场收缴限 100 元以下；退回基金退原财政专户，罚款/没收上缴国库；加处罚款每日 3% 且不超过罚款本金。</p>
      <template #footer>
        <el-button @click="dlg.execution = false">取消</el-button>
        <el-button type="primary" @click="submit(`executions`, executionForm, 'execution')">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.doc" :title="docView.title" width="640px">
      <p class="hint">{{ DOC_TYPE[docView.doc_type] }}　{{ docView.made_at }}　制作人：{{ docView.maker || '-' }}</p>
      <pre class="doc-content" id="doc-print-area">{{ docView.content }}</pre>
      <template #footer>
        <el-button type="primary" @click="printDoc">打印</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.apply" title="提交审批申请（负责人批准后自动执行）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="申请类型">
          <el-select v-model="applyForm.kind" style="width: 100%">
            <el-option label="延长办案期限" value="EXTEND" />
            <el-option label="中止调查" value="SUSPEND" />
            <el-option label="终止调查" value="TERMINATE" />
            <el-option label="暂缓/分期缴纳" value="DEFER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="applyForm.kind === 'EXTEND'" label="延长天数">
          <el-input-number v-model="applyForm.days" :min="1" :max="90" />
        </el-form-item>
        <el-form-item label="申请理由"><el-input v-model="applyForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.apply = false">取消</el-button>
        <el-button type="primary" @click="onApply">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.docDeliver" :title="`文书送达：${docDeliverForm.title}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="送达方式">
          <el-select v-model="docDeliverForm.method" style="width: 100%">
            <el-option v-for="(v, k) in DELIVERY_METHOD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="受送达人"><el-input v-model="docDeliverForm.receiver" /></el-form-item>
        <el-form-item label="回证编号"><el-input v-model="docDeliverForm.receiptNo" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.docDeliver = false">取消</el-button>
        <el-button type="primary" @click="onDocDeliverSubmit">登记送达</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.review" title="法制审核办理（审核人须具备法律职业资格，不得为本案办案人员）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="审核人"><el-input v-model="reviewForm.reviewer" /></el-form-item>
        <el-form-item label="意见类型">
          <el-select v-model="reviewForm.opinionType" style="width: 100%">
            <el-option v-for="(v, k) in REVIEW_OPINION" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核意见"><el-input v-model="reviewForm.opinion" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.review = false">取消</el-button>
        <el-button type="primary" @click="onDoReview">提交审核</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.installment" title="添加分期（第54条）" width="420px">
      <el-form label-width="80px">
        <el-form-item label="期数"><el-input-number v-model="installmentForm.seq" :min="1" /></el-form-item>
        <el-form-item label="到期日">
          <el-date-picker v-model="installmentForm.dueAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="金额"><el-input-number v-model="installmentForm.amount" :min="0.01" :precision="2" style="width: 180px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.installment = false">取消</el-button>
        <el-button type="primary" @click="onAddInstallment">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.hearing" title="安排听证（须在通知送达后≥7日举行；主持人不得为本案办案人员）" width="520px">
      <el-form :model="hearingForm" label-width="110px">
        <el-form-item label="公告日期">
          <el-date-picker v-model="hearingForm.announcedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="公开听证公告（涉密除外）" />
        </el-form-item>
        <el-form-item label="通知送达日">
          <el-date-picker v-model="hearingForm.noticeSentAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="举行日期">
          <el-date-picker v-model="hearingForm.scheduledAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="主持人"><el-input v-model="hearingForm.host" /></el-form-item>
        <el-form-item label="主持人部门"><el-input v-model="hearingForm.hostDept" placeholder="须与办案机构不同部门" /></el-form-item>
        <el-form-item label="记录员"><el-input v-model="hearingForm.recorder" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.hearing = false">取消</el-button>
        <el-button type="primary" @click="onScheduleHearing">安排</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.catalog" title="案卷目录（一案一卷，第57条）" width="640px">
      <el-alert v-if="catalog.missing?.length" type="warning" :closable="false" class="mb"
                :title="`必备文书缺失：${catalog.missing.map((m: string) => DOC_TYPE[m] || m).join('、')}`" />
      <el-alert v-else type="success" :closable="false" class="mb" title="必备文书齐全" />
      <p class="hint">案卷号：{{ catalog.archiveNo || '（结案时生成）' }}</p>
      <el-table :data="catalog.catalog" border size="small">
        <el-table-column prop="seq" label="序号" width="70" />
        <el-table-column label="文书类型" width="140">
          <template #default="{ row }">{{ DOC_TYPE[row.doc_type] || row.doc_type }}</template>
        </el-table-column>
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column prop="made_at" label="日期" width="110" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import client, { LARGE_TRANSFER } from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import {
  CASE_STATUS, CASE_STATUS_TAG, DECISION_TYPE, DELIVERY_METHOD, DOC_TYPE,
  EVIDENCE_TYPE, EXCLUSION_REASON, EXEC_KIND, EXEC_METHOD, PARTY_TYPE, REVIEW_OPINION,
} from './labels'

const auth = useAuthStore()

const CLOSE_REASON: Record<string, string> = {
  EXECUTED: '执行完毕', COURT: '法院受理强制执行', NO_NEED: '无须执行', OTHER: '其他',
}

/** 本地时区的今天：toISOString 取的是 UTC，中国时区 0-8 点会算成昨天 */
function todayLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const isLeader = computed(() => auth.user?.roles?.some((r: string) => ['LEADER', 'ADMIN'].includes(r)))
const isLegal = computed(() => auth.user?.roles?.some((r: string) => ['LEGAL', 'ADMIN'].includes(r)))

const route = useRoute()
// 同路由不同 id 会复用组件：必须响应式取值 + watch 重载，
// 否则从 A 详情页跳到 B 后页面仍是 A 的数据，而操作按钮打的是 A
const id = computed(() => route.params.id as string)
const detail = ref<any>({})
const loading = ref(false)
const c = computed<any>(() => detail.value.caseFile || {})
const cause = computed<any>(() => detail.value.cause || {})

const dlg = reactive<Record<string, boolean>>({
  officer: false, evidence: false, document: false, exclusion: false, report: false,
  notice: false, statement: false, meeting: false, decide: false, deliver: false,
  execution: false, doc: false, catalog: false, hearing: false, review: false, installment: false,
  apply: false, docDeliver: false,
})
const uploadCategory = ref('DOC_SCAN')
const applyForm = reactive<any>({ kind: 'EXTEND', days: 30, reason: '' })
const docDeliverForm = reactive<any>({ documentId: null, title: '', docKind: 'OTHER', method: 'DIRECT', receiver: '', receiptNo: '' })
const pendingApprovals = computed(() => (detail.value.approvals || []).filter((a: any) => a.status === 'PENDING'))
const reviewForm = reactive<any>({ reviewId: null, reviewer: '', opinionType: 'AGREE', opinion: '' })
const installmentForm = reactive<any>({ seq: 1, dueAt: '', amount: 0 })
const hearingForm = reactive<any>({ announcedAt: null, noticeSentAt: todayLocal(), scheduledAt: '', host: '', hostDept: '', recorder: '' })
const attachments = ref<any[]>([])
const timeline = ref<any[]>([])
const installments = ref<any[]>([])
const catalog = ref<any>({})
const fileInput = ref<HTMLInputElement>()

const today = todayLocal()
const officerForm = reactive({ name: '', certNo: '', duty: 'MEMBER' })
const evidenceForm = reactive({ type: 'DOCUMENT', name: '', source: '', obtainedAt: today, keeper: '', note: '', registerHold: false, sealed: false })
const documentForm = reactive<any>({ docType: 'INQUIRY_RECORD', title: '', content: '', maker: '', signed: false, dueAt: null })
const exclusionForm = reactive({ reason: 'APPRAISE', startAt: today, endAt: today, note: '' })
const noticeForm = reactive<any>({ content: '', proposedFine: 0, proposedRecoup: 0, changeReason: '' })
const statementForm = reactive<any>({ statement: '', statementReview: '', hearingRequested: false, hearingHeldAt: null, statementWaived: false })
const meetingForm = reactive({ heldAt: today, attendees: '', record: '', conclusion: '' })
const decisionForm = reactive({ decisionType: 'PUNISH', fineAmount: 0, recoupAmount: 0, confiscateAmount: 0, otherMeasures: '', content: '', mitigation: '', discretionReason: '' })
const deliveryForm = reactive({ method: 'DIRECT', deliveredAt: today, receiver: '', note: '', receiptNo: '', receiptSignedAt: null as string | null })
const executionForm = reactive({ kind: 'FINE', amount: 0, paidAt: today, method: 'BANK', note: '', receiptNo: '' })
const reportContent = ref('')
const docView = ref<any>({})

let loadSeq = 0
async function load() {
  const seq = ++loadSeq  // 快速切案时只有最后一次可写入，避免旧请求结果覆盖新案件
  const cid = id.value
  loading.value = true
  try {
    const resp = await client.get(`/bureau/cases/${cid}`)
    const att = (await client.get(`/bureau/cases/${cid}/attachments`)).data.data
    const tl = (await client.get(`/bureau/cases/${cid}/timeline`)).data.data
    const inst = (await client.get(`/bureau/cases/${cid}/installments`)).data.data
    if (seq !== loadSeq) return  // 已被更晚的 load 取代，丢弃
    detail.value = resp.data.data
    attachments.value = att
    timeline.value = tl
    installments.value = inst
  } catch {
    // 失败（无权限/已归档/网络）须清空，否则残留上一个案件的数据而按钮仍可点 → 误操作
    if (seq === loadSeq) {
      detail.value = {}
      attachments.value = []
      timeline.value = []
      installments.value = []
    }
    // 不重抛：现有大量 load() 调用点（含 onMounted/watch）都不 catch，拦截器已提示用户
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function onRenderTpl() {
  const { value: docType } = await ElMessageBox.prompt(
    '文书类型：NOTICE 告知书 / DECISION 决定书 / FINAL_REPORT 终结报告 / ORDER_CORRECT 责令改正 / CLOSE_REPORT 结案报告 / ASSIST_LETTER 协查函',
    '模板生成', { inputValue: 'NOTICE', inputPattern: /^(NOTICE|DECISION|FINAL_REPORT|ORDER_CORRECT|CLOSE_REPORT|ASSIST_LETTER)$/, inputErrorMessage: '类型无效' })
  const resp = await client.get(`/bureau/cases/${id.value}/documents/render`, { params: { docType } })
  const d = resp.data.data
  documentForm.docType = d.docType
  documentForm.title = d.title
  documentForm.content = d.content
  dlg.document = true
}

async function onCatalog() {
  catalog.value = (await client.get(`/bureau/cases/${id.value}/archive-catalog`)).data.data
  dlg.catalog = true
}

async function onDownload(row: any) {
  // 带 JWT 的 blob 下载（直链 <a href> 无 Authorization 会 401）
  const resp = await client.get(`/bureau/cases/attachments/${row.id}/download`,
    { responseType: 'blob', ...LARGE_TRANSFER })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(resp.data)
  a.download = row.filename
  a.click()
  URL.revokeObjectURL(a.href)
}

async function onUpload(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  fd.append('category', uploadCategory.value)
  const closeMsg = ElMessage({ message: `上传中：${file.name}`, type: 'info', duration: 0 })
  try {
    await client.post(`/bureau/cases/${id.value}/attachments`, fd,
      { headers: { 'Content-Type': 'multipart/form-data' }, ...LARGE_TRANSFER })
    ElMessage.success('已上传')
    load()
  } catch { /* 拦截器已提示 */ } finally {
    closeMsg.close()
    input.value = ''  // 无论成败都清空，否则重选同一文件不触发 change
  }
}

async function onSignDoc(row: any) {
  const { value } = await ElMessageBox.prompt('签章人（办案人员/当事人/负责人）', '电子签章', { inputValue: auth.user?.realName || '', inputPattern: /\S+/, inputErrorMessage: '必填' })
  const resp = await client.post(`/bureau/cases/${id.value}/documents/${row.id}/sign`, { signer: value })
  ElMessage.success(`已签章（${resp.data.data.provider}，摘要 ${resp.data.data.contentHash.slice(0, 12)}…）`)
  load()
}

async function onVerifyDoc(row: any) {
  const sigs = (await client.get(`/bureau/cases/${id.value}/documents/${row.id}/verify`)).data.data
  if (!sigs.length) {
    ElMessage.info('该文书尚无签章')
    return
  }
  const ok = sigs.every((s: any) => s.valid)
  ElMessageBox.alert(sigs.map((s: any) => `${s.signer}（${s.signer_role}）${s.signed_at}：${s.valid ? '有效' : '内容已被篡改！'}`).join('\n'),
    ok ? '验签通过' : '验签失败', { type: ok ? 'success' : 'error' })
}

function onDeliverDoc(row: any) {
  docDeliverForm.documentId = row.id
  docDeliverForm.title = row.title
  docDeliverForm.docKind = row.doc_type
  dlg.docDeliver = true
}

async function onDocDeliverSubmit() {
  await client.post(`/bureau/cases/${id.value}/doc-deliveries`, docDeliverForm)
  ElMessage.success('文书送达已登记')
  dlg.docDeliver = false
  load()
}

async function onApply() {
  if (!applyForm.reason.trim()) {
    ElMessage.warning('请填写理由')
    return
  }
  await client.post('/bureau/approvals', {
    kind: applyForm.kind, caseId: Number(id.value),
    payload: applyForm.kind === 'EXTEND' ? { days: applyForm.days } : null,
    reason: applyForm.reason,
  })
  ElMessage.success('已提交，待负责人在"待办审批"裁决')
  dlg.apply = false
  load()
}

async function onAddAgreement() {
  const { value: action } = await ElMessageBox.prompt(
    '处理类型：INTERVIEW 约谈 / SUSPEND_AGREEMENT 暂停协议 / TERMINATE_AGREEMENT 解除协议 / REFUSE_PAY 拒付 / RECOVER 追回',
    '移交经办处理', { inputValue: 'REFUSE_PAY', inputPattern: /^(INTERVIEW|SUSPEND_AGREEMENT|TERMINATE_AGREEMENT|REFUSE_PAY|RECOVER)$/, inputErrorMessage: '类型无效' })
  await client.post(`/bureau/cases/${id.value}/agreement-actions`, { action, org: '医保经办机构' })
  ElMessage.success('已移交')
  load()
}

async function onReplyAgreement(row: any) {
  const { value } = await ElMessageBox.prompt('经办办理结果', '登记结果', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/agreement-actions/${row.id}/reply`, { result: value })
  ElMessage.success('已登记')
  load()
}

async function onPrintArchive() {
  const d = (await client.get(`/bureau/cases/${id.value}/archive-full`)).data.data
  const cat = (await client.get(`/bureau/cases/${id.value}/archive-catalog`)).data.data
  const order: Record<number, number> = {}
  cat.catalog.forEach((x: any) => { order[x.id] = x.seq })
  const docs = [...d.documents].sort((a: any, b: any) => (order[a.id] || 99) - (order[b.id] || 99))
  const sigOf = (docId: number) => d.signatures.filter((s: any) => s.document_id === docId)
    .map((s: any) => `［电子签章］${esc(s.signer)}（${esc(s.signed_at?.slice(0, 10))}，${esc(s.provider)}）`).join('　')
  const w = window.open('', '_blank')
  if (!w) return
  const pages = docs.map((doc: any, i: number) => `
    <section class="page"><h2>${esc(doc.title)}</h2>
    <pre>${esc(doc.content)}</pre>
    <p class="sig">${sigOf(doc.id)}</p>
    <p class="foot">第 ${i + 2} 页</p></section>`).join('')
  const toc = docs.map((doc: any, i: number) => `<tr><td>${i + 1}</td><td>${esc(doc.title)}</td><td>${esc(doc.made_at)}</td><td>${i + 2}</td></tr>`).join('')
  w.document.write(`<html><head><title>${esc(d.caseFile.case_no)} 卷宗</title><style>
    body{font-family:SimSun,serif;line-height:1.9}
    .page{page-break-after:always;padding:40px 50px;min-height:90vh;position:relative}
    h1,h2{text-align:center} pre{white-space:pre-wrap;font-family:inherit;font-size:15px}
    table{width:100%;border-collapse:collapse}td,th{border:1px solid #333;padding:4px 8px;font-size:14px}
    .sig{margin-top:24px;font-size:13px;color:#333}
    .foot{position:absolute;bottom:16px;left:0;right:0;text-align:center;font-size:12px;color:#666}
    </style></head><body>
    <section class="page"><h1>${esc(d.orgName)}<br/>行政处罚案卷</h1>
      <p style="text-align:center;font-size:18px">${esc(d.caseFile.name)}</p>
      <p style="text-align:center">案号：${esc(d.caseFile.case_no)}　案卷号：${esc(d.caseFile.archive_no || '（未结案）')}</p>
      <h2>卷内目录</h2>
      <table><tr><th>序号</th><th>文书名称</th><th>日期</th><th>页码</th></tr>${toc}</table>
      <p class="foot">第 1 页</p></section>
    ${pages}</body></html>`)
  w.document.close()
  w.print()
}

/** 文书正文/标题由用户填写，拼进打印窗口前必须转义，否则可注入脚本读取本地令牌 */
function esc(v: any) {
  return String(v ?? '').replace(/[&<>"']/g, (ch) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch] as string))
}

function printDoc() {
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(`<html><head><title>${esc(docView.value.title)}</title><style>body{font-family:SimSun,serif;padding:40px;line-height:1.9}h2{text-align:center}pre{white-space:pre-wrap;font-family:inherit;font-size:15px}</style></head><body><h2>${esc(docView.value.title)}</h2><pre>${esc(docView.value.content)}</pre></body></html>`)
  w.document.close()
  w.print()
}

/** 通用子表提交：POST /bureau/cases/{id}/{path} 后关弹窗刷新 */
// 各复用录入弹窗的初值：提交成功后按 dlgKey 重置，避免下次打开预填上一条被误重复保存
const FORM_RESETS: Record<string, () => void> = {
  officer: () => Object.assign(officerForm, { name: '', certNo: '', duty: 'MEMBER' }),
  evidence: () => Object.assign(evidenceForm, { type: 'DOCUMENT', name: '', source: '', obtainedAt: today, keeper: '', note: '', registerHold: false, sealed: false }),
  document: () => Object.assign(documentForm, { docType: 'INQUIRY_RECORD', title: '', content: '', maker: '', signed: false, dueAt: null }),
  exclusion: () => Object.assign(exclusionForm, { reason: 'APPRAISE', startAt: today, endAt: today, note: '' }),
  meeting: () => Object.assign(meetingForm, { heldAt: today, attendees: '', record: '', conclusion: '' }),
  execution: () => Object.assign(executionForm, { kind: 'FINE', amount: 0, paidAt: today, method: 'BANK', note: '', receiptNo: '' }),
}

let submitting = false
async function submit(path: string, body: any, dlgKey: string) {
  if (submitting) return  // 防双击“保存”产生两条记录
  submitting = true
  try {
    await client.post(`/bureau/cases/${id.value}/${path}`, body)
    ElMessage.success('已保存')
    dlg[dlgKey] = false
    FORM_RESETS[dlgKey]?.()
    load()
  } finally {
    submitting = false
  }
}

async function onAvoid(row: any) {
  const { value } = await ElMessageBox.prompt('回避事由（第5条）', '申请回避', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/officers/${row.id}/avoid`, { reason: value })
  ElMessage.success('已回避')
  load()
}

async function onCrossExam(row: any) {
  const { value } = await ElMessageBox.prompt('当事人对该证据的意见（无异议也须注明，辽24条）', '证据质证', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/evidences/${row.id}/cross-exam`, { opinion: value })
  ElMessage.success('已记录质证')
  load()
}

async function onPublish() {
  await client.post(`/bureau/cases/${id.value}/publish`)
  ElMessage.success('处罚决定已公开')
  load()
}

async function onGovRecord() {
  const { value } = await ElMessageBox.prompt('政府备案文号（重大处罚决定报本级政府备案，辽54条）', '备案登记', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/gov-record`, { recordNo: value })
  ElMessage.success('已登记备案')
  load()
}

async function onHoldDispose(row: any) {
  const disposal = await ElMessageBox.confirm('先行登记保存证据处理（第28条）', '处理决定', {
    distinguishCancelAndClose: true,
    confirmButtonText: '证据保全', cancelButtonText: '转封存',
  }).then(() => 'PRESERVE').catch((a) => (a === 'cancel' ? 'SEAL' : null))
  if (!disposal) return
  await client.post(`/bureau/cases/${id.value}/evidences/${row.id}/hold-disposal`, { disposal })
  ElMessage.success('已处理')
  load()
}

async function onSeal(row: any, extend: boolean) {
  await client.post(`/bureau/cases/${id.value}/evidences/${row.id}/seal?extend=${extend}`)
  ElMessage.success(extend ? '已延长30日' : '已解除封存')
  load()
}

function viewDoc(row: any) {
  client.get(`/bureau/cases/${id.value}/documents/${row.id}`).then((resp) => {
    docView.value = resp.data.data
    dlg.doc = true
  })
}

async function onSuspend() {
  const { value } = await ElMessageBox.prompt('中止情形（第42条：需以裁判结果为依据/送请解释确认/不可抗力/当事人下落不明等）', '中止调查', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/suspend`, { reason: value })
  load()
}

async function onResume() {
  await client.post(`/bureau/cases/${id.value}/resume`)
  ElMessage.success('已恢复调查，办案期限已顺延中止天数')
  load()
}

async function onTerminate() {
  const { value } = await ElMessageBox.prompt('终止情形（第47条：当事人死亡或组织终止无承受人/移送司法/其他）', '终止调查', { inputPattern: /\S+/, inputErrorMessage: '必填', type: 'warning' })
  await client.post(`/bureau/cases/${id.value}/terminate`, { reason: value })
  load()
}

async function onExtendCase() {
  const { value: days } = await ElMessageBox.prompt('延长天数（首次≤30日；继续延期须先有集体讨论记录，累计≤90日）', '延长办案期限', { inputPattern: /^\d+$/, inputErrorMessage: '请输入天数' })
  const { value: reason } = await ElMessageBox.prompt('延期理由', '延长办案期限', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/extend`, { days: Number(days), reason })
  ElMessage.success('已延长')
  load()
}

async function onReport() {
  if (!reportContent.value.trim()) {
    ElMessage.warning('请填写报告内容')
    return
  }
  await client.post(`/bureau/cases/${id.value}/report`, { content: reportContent.value })
  ElMessage.success('调查终结')
  dlg.report = false
  load()
}

async function onSubmitReview() {
  const { value } = await ElMessageBox.prompt('审核触发情形（第37条：罚款数额较大/听证案件/疑难复杂/重大公共利益等）', '提交法制审核', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/reviews`, { requiredReason: value })
  ElMessage.success('已提交法制审核（10个工作日内审核完毕）')
  load()
}

function openReviewDialog(row: any) {
  // 不重置会串单：上一份审核单的审核人与意见残留到下一份
  Object.assign(reviewForm, { reviewId: row.id, reviewer: auth.user?.realName || '', opinionType: 'AGREE', opinion: '' })
  dlg.review = true
}

async function onDoReview() {
  if (!reviewForm.reviewer || !reviewForm.opinion) {
    ElMessage.warning('审核人与意见必填')
    return
  }
  await client.post(`/bureau/cases/reviews/${reviewForm.reviewId}`, reviewForm)
  ElMessage.success('审核完成')
  dlg.review = false
  load()
}

async function onNotify() {
  if (!noticeForm.content.trim()) {
    ElMessage.warning('请填写告知内容')
    return
  }
  await client.post(`/bureau/cases/${id.value}/notice`, noticeForm)
  ElMessage.success('已告知当事人')
  dlg.notice = false
  load()
}

async function onStatement() {
  await client.post(`/bureau/cases/${id.value}/statement`, statementForm)
  ElMessage.success('已保存')
  dlg.statement = false
  load()
}

function openDecide() {
  const n = detail.value.notices?.[0]
  if (n) {
    decisionForm.fineAmount = n.proposedFine
    decisionForm.recoupAmount = n.proposedRecoup
  }
  dlg.decide = true
}

async function onDecide() {
  if (!decisionForm.content.trim()) {
    ElMessage.warning('请填写决定内容')
    return
  }
  await client.post(`/bureau/cases/${id.value}/decide`, decisionForm)
  ElMessage.success('决定已作出')
  dlg.decide = false
  load()
}

async function onDeliver() {
  await client.post(`/bureau/cases/${id.value}/deliver`, deliveryForm)
  ElMessage.success('已送达')
  dlg.deliver = false
  load()
}

async function onLateFee() {
  const resp = await client.get(`/bureau/cases/${id.value}/late-fee-quote`)
  const q = resp.data.data
  ElMessageBox.alert(
    `缴款期限：${q.payDeadline}；逾期 ${q.overdueDays} 天；按日3%累计 ${q.accrued} 元；封顶后应加处 ${q.capped} 元（不超过罚款本金 ${q.fineAmount} 元）`,
    '加处罚款测算（第55条）',
  )
}

async function onApproveDefer() {
  await client.post(`/bureau/cases/${id.value}/approve-defer`)
  ElMessage.success('已批准暂缓/分期缴纳')
  load()
}

async function onCourtEnforce() {
  await client.post(`/bureau/cases/${id.value}/court-enforce`)
  ElMessage.success('已登记申请法院强制执行')
  load()
}

async function onScheduleHearing() {
  if (!hearingForm.scheduledAt || !hearingForm.host) {
    ElMessage.warning('举行日期与主持人必填')
    return
  }
  await client.post(`/bureau/cases/${id.value}/hearings`, hearingForm)
  ElMessage.success('已安排听证')
  dlg.hearing = false
  load()
}

async function onHoldHearing(row: any) {
  const { value } = await ElMessageBox.prompt('听证笔录（当场阅读并签字确认，辽50条）', '举行听证', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/hearings/${row.id}/hold`, { record: value })
  ElMessage.success('听证已举行')
  load()
}

async function onHearingOpinion(row: any) {
  const { value } = await ElMessageBox.prompt('听证意见（结束2日内提出，报负责人）', '听证意见', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/hearings/${row.id}/opinion`, { opinion: value })
  ElMessage.success('已出具听证意见')
  load()
}

async function onAddAssist() {
  const { value: org } = await ElMessageBox.prompt('协查机关', '发出协查函', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  const { value: content } = await ElMessageBox.prompt('协查事项', '发出协查函', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/assists`, { direction: 'OUT', org, content })
  ElMessage.success('已登记（15日内应复函）')
  load()
}

async function onReplyAssist(row: any) {
  const { value } = await ElMessageBox.prompt('复函结果（拒绝协助请以"拒绝："开头并说明理由）', '协查办理', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  const refused = value.startsWith('拒绝')
  await client.post(`/bureau/cases/${id.value}/assists/${row.id}/reply`,
    { result: value, refused, refuseReason: refused ? value : null })
  ElMessage.success('已办结')
  load()
}

async function onSignMeeting(row: any) {
  const { value } = await ElMessageBox.prompt('确认签字的参加人员名单（局令44条：讨论记录经参加人员确认签字存入案卷）', '签字确认', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/meetings/${row.id}/sign`, { signNames: value })
  ElMessage.success('已确认')
  load()
}

async function onDiscretionSuggest() {
  const resp = await client.get(`/bureau/cases/${id.value}/discretion-suggest`)
  const d = resp.data.data
  const lines = (d.tiers as any[]).map((t) =>
    `【${t.tier === 'LIGHT' ? '从轻' : t.tier === 'HEAVY' ? '从重' : '一般'}】${t.condition_desc}：${t.multiplier_min}-${t.multiplier_max}倍 → 建议 ${t.suggestMin}-${t.suggestMax} 元（${t.note}）`)
  ElMessageBox.alert(lines.join('\n\n') || '该案由暂无裁量基准，请按上位法幅度裁量', `裁量基准建议（涉案金额 ${d.amountInvolved} 元）`, { customStyle: { whiteSpace: 'pre-wrap' } as any })
}

function openInstallment() {
  installmentForm.dueAt = ''
  installmentForm.amount = 0
  dlg.installment = true
}

async function onAddInstallment() {
  if (!installmentForm.dueAt || !installmentForm.amount) {
    ElMessage.warning('请填写到期日与金额')
    return
  }
  await client.post(`/bureau/cases/${id.value}/installments`, installmentForm)
  ElMessage.success('已添加')
  dlg.installment = false
  installmentForm.seq += 1
  installmentForm.amount = 0
  load()
}

async function onStartExpert(row?: any) {
  const { value } = await ElMessageBox.prompt('评审专家（顿号分隔）', '启动专家评审', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/expert-reviews`, { experts: value })
  ElMessage.success('评审已启动')
  load()
}

async function onEndExpert(row: any) {
  const { value } = await ElMessageBox.prompt('评审意见', '结束专家评审', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/expert-reviews/${row.id}/end`, { opinion: value })
  ElMessage.success('评审结束，期间已计入期限扣除')
  load()
}

async function onPayInstallment(row: any) {
  await client.post(`/bureau/installments/${row.id}/pay`)
  ElMessage.success('已登记缴纳')
  load()
}

async function onSummaryRecord() {
  await client.post(`/bureau/cases/${id.value}/summary-record`)
  ElMessage.success('已备案（第51条）')
  load()
}

async function onEConsent() {
  await client.post(`/bureau/cases/${id.value}/e-delivery-consent`)
  ElMessage.success('已登记电子送达确认书')
  load()
}

async function onTransferOwner() {
  const { value } = await ElMessageBox.prompt('接收人登录账号（承办人变更，负责人权限）', '案件移交', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/transfer-owner`, { newOwner: value })
  ElMessage.success('已移交')
  load()
}

async function onCloseCase() {
  const { value } = await ElMessageBox.prompt('结案报告（经负责人批准后结案归档，一案一卷）', '结案', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/cases/${id.value}/close`, { closeReport: value })
  ElMessage.success('已结案归档')
  load()
}

onMounted(load)
/** 切换案件时把流程表单一并复位：A 案填了一半的告知/决定/送达/申辩若残留到 B 案，一次误提交就是错案 */
function resetCaseLocalForms() {
  Object.values(FORM_RESETS).forEach((reset) => reset())
  Object.assign(noticeForm, { content: '', proposedFine: 0, proposedRecoup: 0 })
  Object.assign(statementForm, { statement: '', statementReview: '', hearingRequested: false, hearingHeldAt: null })
  Object.assign(decisionForm, { decisionType: 'PUNISH', fineAmount: 0, recoupAmount: 0, confiscateAmount: 0, otherMeasures: '', content: '', mitigation: '', discretionReason: '' })
  Object.assign(deliveryForm, { method: 'DIRECT', deliveredAt: today, receiver: '', note: '', receiptNo: '', receiptSignedAt: null })
  Object.assign(reviewForm, { reviewId: null, reviewer: '', opinionType: 'AGREE', opinion: '' })
  Object.assign(installmentForm, { seq: 1, dueAt: '', amount: 0 })
  Object.assign(hearingForm, { announcedAt: null, noticeSentAt: todayLocal(), scheduledAt: '', host: '', hostDept: '', recorder: '' })
  Object.assign(applyForm, { kind: 'EXTEND', days: 30, reason: '' })
  Object.assign(docDeliverForm, { documentId: null, title: '', docKind: 'OTHER', method: 'DIRECT', receiver: '', receiptNo: '' })
  reportContent.value = ''
  Object.keys(dlg).forEach((k) => { dlg[k] = false })  // 打开着的弹窗一并关掉
}

watch(() => route.params.id, (nv, ov) => {
  if (nv && nv !== ov) {
    resetCaseLocalForms()
    load()
  }
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; }
.danger { color: #c0392b; font-size: 12px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.doc-content { white-space: pre-wrap; background: #f8f9fb; padding: 12px; border-radius: 4px; font-family: inherit; }
h4 { margin: 8px 0; }
</style>
