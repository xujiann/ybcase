<template>
  <div v-loading="loading">
    <WorkdayCoverageNotice />
    <el-card class="mb">
      <div class="toolbar">
        <div>
          <h3 style="margin: 0">{{ c.caseNo }}　{{ c.name }}</h3>
          <div class="hint" style="margin-top: 6px">
            <el-tag :type="(CASE_STATUS_TAG[c.status] as any)" size="small">{{ CASE_STATUS[c.status] }}</el-tag>
            <span style="margin-left: 12px">{{ c.procedureType === 'SUMMARY' ? '简易程序' : '普通程序' }}</span>
            <span style="margin-left: 12px">承办人 {{ c.ownerUser || '-' }}
              <el-button v-if="canWriteCase && (isLeader)" size="small" text type="primary" @click="onTransferOwner">移交</el-button></span>
            <span style="margin-left: 12px">立案 {{ c.filedAt }}</span>
            <span style="margin-left: 12px">办案期限 {{ detail.effectiveDeadline }}（含延长 {{ c.extensionDays }} 日与扣除期间）</span>
          </div>
        </div>
        <el-button @click="$router.push('/case/list')">返回列表</el-button>
      </div>
      <el-descriptions :column="3" border size="small" class="mb">
        <template #extra>
          <el-button v-if="canCorrect" size="small" @click="openCorrection">更正要素</el-button>
        </template>
        <el-descriptions-item label="当事人">{{ c.partyName }}（{{ PARTY_TYPE[c.partyType] }}）</el-descriptions-item>
        <el-descriptions-item v-if="auth.user?.roles.includes('ADMIN')" label="授权核对案件记录号">{{ c.id }}</el-descriptions-item>
        <el-descriptions-item label="证照号">{{ c.partyCreditNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="法定代表人">{{ c.partyLegalRep || '-' }}</el-descriptions-item>
        <el-descriptions-item label="案由" :span="2">{{ cause.itemNo }}. {{ cause.category }}——{{ cause.description }}</el-descriptions-item>
        <el-descriptions-item v-if="c.institutionCode" :label="c.decidedAt ? '原卷机构信息' : '机构信息'">
          {{ c.institutionCode }} / {{ c.institutionOwnership === 'PUBLIC' ? '公立' : '社会办' }} /
          {{ INSTITUTION_GRADE[c.institutionGrade] }}
        </el-descriptions-item>
        <template v-if="c.confirmedViolationStart">
          <el-descriptions-item :label="c.decidedAt ? '原卷违法期间' : '违法发生期间'">{{ c.confirmedViolationStart }} 至 {{ c.confirmedViolationEnd }}</el-descriptions-item>
          <el-descriptions-item :label="c.decidedAt ? '原卷认定违法金额' : '认定违法金额'">{{ c.confirmedViolationAmount }}</el-descriptions-item>
          <el-descriptions-item :label="c.decidedAt ? '原卷认定违法事实' : '认定违法事实'" :span="3">{{ c.confirmedViolationFacts }}</el-descriptions-item>
        </template>
        <el-descriptions-item label="涉案金额">{{ c.amountInvolved }}</el-descriptions-item>
        <el-descriptions-item label="案情摘要" :span="3">{{ c.summary || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'SUSPENDED'" label="中止原因" :span="3">{{ c.suspendReason }}（{{ c.suspendedAt }}）</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'TERMINATED'" label="终止原因" :span="3">{{ c.terminateReason }}（{{ c.terminatedAt }}）</el-descriptions-item>
        <el-descriptions-item v-if="c.status === 'CLOSED'" label="结案" :span="3">
          {{ CLOSE_REASON[c.closeReason] || c.closeReason }}（{{ c.closedAt }}）　案卷号：{{ c.archiveNo }}
        </el-descriptions-item>
      </el-descriptions>
      <div class="mb">
        <h4>管辖事实与材料 <el-button v-if="canUpdateJurisdiction" size="small" @click="openJurisdiction">补录/更正</el-button></h4>
        <JurisdictionSummary :value="detail.jurisdiction" />
        <p class="hint">人工事实记录；管辖认定仍须由有权人员依据材料审查。</p>
        <el-table v-if="detail.jurisdictionMaterials?.length" :data="detail.jurisdictionMaterials" size="small" border>
          <el-table-column prop="label" label="材料项目" width="160" />
          <el-table-column label="登记情况" width="100"><template #default="{ row }">{{ row.present ? '已登记' : '待补充' }}</template></el-table-column>
          <el-table-column prop="message" label="说明" />
        </el-table>
        <el-collapse v-if="detail.jurisdictionHistory?.length" style="margin-top: 8px">
          <el-collapse-item title="管辖事实补录/更正记录">
            <el-table :data="detail.jurisdictionHistory" size="small" border>
              <el-table-column label="字段" width="130"><template #default="{ row }">{{ CORRECTION_FIELD[row.field] || row.field }}</template></el-table-column>
              <el-table-column label="原值" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.old_value == null || row.old_value === '' ? '—' : row.old_value }}</template></el-table-column>
              <el-table-column label="新值" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.new_value == null || row.new_value === '' ? '—' : row.new_value }}</template></el-table-column>
              <el-table-column prop="operator" label="操作人" width="100" />
              <el-table-column prop="reason" label="补录/更正理由" />
              <el-table-column label="时间" width="150"><template #default="{ row }">{{ fmtTime(row.created_at) }}</template></el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
      <el-dialog v-model="dlg.jurisdiction" title="补录/更正管辖事实（留痕）" width="760px" style="max-width: 95vw">
        <el-form label-width="120px">
          <JurisdictionForm v-model="jurisdictionForm.jurisdiction" />
          <el-form-item label="补录/更正理由" required><el-input v-model="jurisdictionForm.reason" type="textarea" :maxlength="1000" :rows="2" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dlg.jurisdiction = false">取消</el-button>
          <el-button v-if="canUpdateJurisdiction" type="primary" :loading="savingJurisdiction" @click="saveJurisdiction">保存记录</el-button>
        </template>
      </el-dialog>
      <!-- 要素更正记录（有记录才显示；蛇形列来自详情接口 json_agg） -->
      <el-collapse v-if="corrections.length" class="mb">
        <el-collapse-item :title="`更正记录（${corrections.length}）`" name="corrections">
          <el-table :data="corrections" size="small" border>
            <el-table-column label="时间" width="140">
              <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
            </el-table-column>
            <el-table-column label="字段" width="100">
              <template #default="{ row }">{{ CORRECTION_FIELD[row.field] || row.field }}</template>
            </el-table-column>
            <el-table-column label="原值" show-overflow-tooltip><template #default="{ row }">{{ correctionValue(row.field, row.old_value) }}</template></el-table-column>
            <el-table-column label="新值" show-overflow-tooltip><template #default="{ row }">{{ correctionValue(row.field, row.new_value) }}</template></el-table-column>
            <el-table-column prop="reason" label="更正理由" show-overflow-tooltip />
            <el-table-column prop="operator" label="操作人" width="100" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
      <el-dialog v-model="dlg.correction" title="更正案件要素（留痕）" width="640px">
        <el-form :model="correctionForm" label-width="110px">
          <el-form-item label="当事人名称">
            <el-input v-model="correctionForm.partyName" />
          </el-form-item>
          <el-form-item label="案由">
            <el-select v-model="correctionForm.causeId" style="width: 100%" filterable placeholder="按当事人类别筛选">
              <el-option v-for="x in causes.filter((y) => y.subjectType === c.partyType)" :key="x.id"
                         :label="`${x.itemNo}. ${x.category}——${x.description}`" :value="x.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="证照/证件号">
            <el-input v-model="correctionForm.partyCreditNo" />
          </el-form-item>
          <el-form-item label="地址">
            <el-input v-model="correctionForm.partyAddress" />
          </el-form-item>
          <el-form-item label="法定代表人">
            <el-input v-model="correctionForm.partyLegalRep" />
          </el-form-item>
          <el-form-item label="联系方式">
            <el-input v-model="correctionForm.partyContact" />
          </el-form-item>
          <el-form-item label="涉案金额">
            <el-input-number v-model="correctionForm.amountInvolved" :min="0" :precision="2" style="width: 200px" />
          </el-form-item>
          <el-form-item label="案情摘要">
            <el-input v-model="correctionForm.summary" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="更正理由" required>
            <el-input v-model="correctionForm.reason" type="textarea" :rows="2" placeholder="必填：说明录错原因（全过程记录，第4/35条）" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dlg.correction = false">取消</el-button>
          <el-button v-if="canWriteCase" type="primary" @click="onCorrect" :loading="correcting">提交更正</el-button>
        </template>
      </el-dialog>
      <!-- 流程操作：按状态提供合法动作 -->
      <div class="actions">
        <template v-if="c.status === 'INVESTIGATING'">
          <el-button v-if="canWriteCase && (c.procedureType === 'NORMAL')" type="primary" @click="dlg.report = true">调查终结报告</el-button>
          <el-button v-if="c.procedureType === 'SUMMARY' && isLeader" type="primary" @click="openDecide">当场处罚决定</el-button>
          <el-button v-if="canConvertProcedure" :loading="convertingProcedure" @click="onConvertProcedure">转普通程序</el-button>
          <el-button v-if="canWriteCase && (isLeader)" @click="onSuspend">中止调查</el-button>
          <el-button v-if="canWriteCase && (isLeader)" @click="onExtendCase">延长期限</el-button>
          <el-button v-else-if="canWriteCase" @click="dlg.apply = true">申请延期/中止</el-button>
          <el-button v-if="canWriteCase && (isLeader)" type="warning" @click="onTerminate">终止调查</el-button>
        </template>
        <template v-if="c.status === 'SUSPENDED'">
          <el-button v-if="canWriteCase && (isLeader)" type="primary" @click="onResume">恢复调查</el-button>
        </template>
        <template v-if="c.status === 'REPORTED'">
          <el-button v-if="canWriteCase" type="primary" @click="openNotice">处罚告知</el-button>
          <el-button v-if="canWriteCase" :loading="submittingReview" :disabled="submittingReview" @click="onSubmitReview">提交法制审核</el-button>
          <el-button v-if="canWriteCase && (isLeader)" @click="onExtendCase">延长期限</el-button>
          <el-button v-else-if="canWriteCase" @click="dlg.apply = true">申请延期</el-button>
        </template>
        <template v-if="c.status === 'NOTIFIED'">
          <el-button v-if="canWriteCase" type="warning" @click="openNotice">再次告知</el-button>
          <el-button v-if="canWriteCase && currentNotice?.deliveryRequired && !currentNoticeReceipt" type="primary" @click="onDeliverNotice">登记告知回证</el-button>
          <el-button v-if="isLeader" type="primary" @click="openDecide">作出决定</el-button>
          <el-button v-if="canWriteCase" :disabled="currentNotice?.deliveryRequired && !currentNoticeReceipt" @click="openStatement">陈述申辩/听证</el-button>
          <el-button v-if="canWriteCase" :loading="submittingReview" :disabled="submittingReview" @click="onSubmitReview">提交法制审核</el-button>
          <el-button v-if="canWriteCase && (isLeader)" @click="onExtendCase">延长期限</el-button>
          <el-button v-else-if="canWriteCase" @click="dlg.apply = true">申请延期</el-button>
        </template>
        <template v-if="c.status === 'DECIDED'">
          <el-button v-if="canWriteCase && !detail.executionDeadline?.decisionVersionId" type="primary" @click="dlg.deliver = true">登记送达</el-button>
          <el-button v-if="canWriteCase && !detail.executionDeadline?.decisionVersionId" @click="dlg.execution = true">登记执行</el-button>
          <el-button v-if="isLeader" @click="onCloseCase">结案</el-button>
        </template>
        <!-- 终止调查/移送司法同样要立卷归档（第57条一案一卷），此前这批案件拿不到案卷号。
             独立成块：不能嵌在按状态分支的 template 里，否则对 TERMINATED 永远不渲染 -->
        <template v-if="c.status === 'TERMINATED' && !c.archiveNo">
          <el-button v-if="isLeader" type="primary" @click="onArchiveTerminated">立卷归档</el-button>
        </template>
        <!-- 下面两项的有效期跨越 DECIDED 与 DELIVERED，不能嵌在按状态分支的 template 里：
             简易备案是"决定后7个工作日内"（第51条），登记送达后仍须办；
             电子送达确认书在决定作出前的文书送达同样要用。此前都只在 DECIDED 分支出现，
             一旦登记送达入口就永久消失，而督办看板的"简易备案超期"会一直常亮无解。 -->
        <el-button v-if="canWriteCase && (c.procedureType === 'SUMMARY' && c.decidedAt && !c.summaryRecordAt)"
                   @click="onSummaryRecord">简易程序备案</el-button>
        <!-- 实体字段名 eDeliveryConsent，Jackson 对 getEDeliveryConsent 生成的 JSON 键是 edeliveryConsent
             （首字母连续大写被整体小写），读驼峰恒 undefined → 登记后按钮不消失，每点一次多插一份确认书 -->
        <el-button v-if="canWriteCase && !c.archiveNo && !['CLOSED', 'TERMINATED'].includes(c.status)"
                   @click="dlg.eConsent = true">{{ c.edeliveryConsent ? '核实或变更同意地址' : '电子送达确认书' }}</el-button>
        <template v-if="c.status === 'DELIVERED'">
          <template v-if="!detail.executionDeadline?.decisionVersionId">
            <el-button v-if="canWriteCase" type="primary" @click="dlg.execution = true">登记执行</el-button>
            <el-button @click="onLateFee">加处罚款测算</el-button>
            <el-button v-if="canWriteCase && isLeader" @click="onApproveDefer">批准暂缓/分期</el-button>
            <el-button v-if="(isLeader || isLegal) && !c.courtEnforceApplied" @click="openRelief">登记救济/暂缓期限</el-button>
            <el-button v-if="canWriteCase && isLeader" @click="openUrgeDelivery">登记催告送达</el-button>
            <el-button v-if="canWriteCase && isLeader" :disabled="c.courtEnforceApplied || !!detail.executionDeadline?.courtStatus" @click="onCourtEnforce">申请法院强制执行</el-button>
          </template>
          <el-button v-if="canWriteCase && isLeader && c.courtEnforceApplied && (!c.courtEnforceStatus || c.courtEnforceStatus === 'APPLIED')" @click="dlg.courtResult = true">登记法院结果</el-button>
          <el-button v-if="isLeader" type="success" @click="onCloseCase">结案</el-button>
        </template>
        <span v-if="detail.executionDeadline?.decisionVersionId && ['DECIDED', 'DELIVERED'].includes(c.status)" class="hint">
          当前决定已有独立版本，请在归档补充卷中登记送达和催告，在执行续办中登记期限、款项和法院申请。
        </span>
        <el-button v-if="!c.archiveNo && ['INVESTIGATING', 'SUSPENDED', 'REPORTED', 'NOTIFIED'].includes(c.status) && (!isLeader || (c.procedureType === 'NORMAL' && c.status === 'INVESTIGATING') || c.status === 'SUSPENDED' || c.status === 'REPORTED')" @click="openDecide">决定条件清单</el-button>
        <el-button v-if="!isLeader && !c.archiveNo && ['DECIDED', 'DELIVERED', 'TERMINATED'].includes(c.status)" @click="onCloseCase">结案条件清单</el-button>
        <el-button v-if="canWriteCase && (!['CLOSED', 'TERMINATED'].includes(c.status))" @click="dlg.meeting = true">集体讨论记录</el-button>
        <el-button v-if="canWriteCase && (!['CLOSED', 'TERMINATED'].includes(c.status))" @click="dlg.apply = true">提交审批申请</el-button>
      </div>
      <div v-if="pendingApprovals.length" class="hint" style="margin-top: 8px">
        <el-tag type="warning" size="small">本案有 {{ pendingApprovals.length }} 张审批单待负责人裁决（见"待办审批"）</el-tag>
      </div>
    </el-card>

    <div v-if="caseReady && ['DECIDED', 'DELIVERED', 'CLOSED'].includes(c.status)
               && ['PROVIDER', 'INDIVIDUAL'].includes(c.partyType)" id="report-supplement">
      <HistoricalReportSupplement :key="id" :case-id="Number(id)" />
    </div>
    <SupplementCorrectionPanel v-if="caseReady && ['DECIDED','DELIVERED','CLOSED'].includes(c.status)" :key="`correction-${id}`" :case-id="Number(id)" />
    <template v-if="caseReady && ['DECIDED','DELIVERED','CLOSED'].includes(c.status)">
      <div id="execution-work">
        <CaseContinuationPanel :key="`continuation-${id}`" :case-id="Number(id)" />
      </div>
      <div id="supplement-work">
        <SupplementBundlesPanel :case-id="Number(id)" :writable="canOperateCase" />
      </div>
    </template>
    <FactRevisionPanel v-if="caseReady" :key="`facts-${id}`" :case-id="Number(id)" @changed="load" />
    <DeliveryReceiptsPanel v-if="caseReady" :case-id="Number(id)" :writable="canWriteCase" :refresh-key="detail" @changed="load" />
    <el-card>
      <el-tabs>
        <el-tab-pane :label="`办案人员(${detail.officers?.length || 0})`">
          <el-button v-if="canWriteCase" size="small" class="mb" @click="dlg.officer = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">添加人员</el-button>
          <el-table :data="detail.officers" border size="small">
            <el-table-column v-if="auth.user?.roles.includes('ADMIN')" prop="id" label="授权核对参办记录号" width="160" />
            <el-table-column prop="name" label="姓名" width="120" />
            <el-table-column prop="cert_no" label="执法证号" width="160" />
            <el-table-column label="分工" width="80">
              <template #default="{ row }">{{ row.duty === 'LEAD' ? '主办' : '协办' }}</template>
            </el-table-column>
            <el-table-column label="回避" width="200">
              <template #default="{ row }">
                <span v-if="row.avoided" class="danger">已回避：{{ row.avoid_reason }}</span>
                <el-button v-else-if="canWriteCase" size="small" text type="warning" @click="onAvoid(row)">申请回避</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`证据(${detail.evidences?.length || 0})`">
          <el-button v-if="canEditActive" size="small" class="mb" @click="dlg.evidence = true" :disabled="['CLOSED','TERMINATED'].includes(c.status)">添加证据</el-button>
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
                <el-button v-else-if="canWriteCase" size="small" text type="primary" @click="onCrossExam(row)">当事人质证</el-button>
              </template>
            </el-table-column>
            <el-table-column label="先行登记保存" width="200">
              <template #default="{ row }">
                <template v-if="row.register_hold">
                  <span class="danger">保存中(限{{ row.hold_expire_at }})</span>
                  <el-button v-if="canWriteCase" size="small" text type="primary" @click="onHoldDispose(row)">处理</el-button>
                </template>
                <span v-else>{{ row.hold_disposal ? '已处理:' + row.hold_disposal : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="封存" width="220">
              <template #default="{ row }">
                <template v-if="row.sealed">
                  <span class="danger">封存至{{ row.seal_expire_at }}</span>
                  <el-button v-if="canWriteCase && (!row.seal_extended)" size="small" text @click="onSeal(row, true)">延长</el-button>
                  <el-button v-if="canWriteCase" size="small" text type="success" @click="onSeal(row, false)">解除</el-button>
                </template>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`文书(${detail.documents?.length || 0})`">
          <el-button v-if="canWriteCase" size="small" class="mb" @click="dlg.document = true" :disabled="c.status === 'CLOSED'">制作文书</el-button>
          <el-button v-if="canWriteCase" size="small" class="mb" type="primary" @click="onRenderTpl" :disabled="c.status === 'CLOSED'">模板生成</el-button>
          <el-button size="small" class="mb" @click="onCatalog">案卷目录</el-button>
          <el-button size="small" class="mb" @click="dlg.evidenceCatalog = true">证据卷目录</el-button>
          <el-button size="small" class="mb" type="success" :loading="printingArchive" :disabled="printingArchive" @click="onPrintArchive">打印卷宗</el-button>
          <SignaturePolicyBanner />
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
                <el-button v-if="canWriteCase" :disabled="signingDocument" size="small" text @click="onSignDoc(row)">签章</el-button>
                <el-button size="small" text @click="onVerifyDoc(row)">验签</el-button>
                <el-button v-if="canWriteCase && (row.doc_type !== 'NOTICE' || !currentNotice?.deliveryRequired)" size="small" text @click="onDeliverDoc(row)">送达</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`附件(${attachments.length})`">
          <div v-if="canWriteCase" class="mb">
            <el-select v-model="uploadCategory" :disabled="uploadingAttachment" size="small" style="width: 140px; margin-right: 8px">
              <el-option label="文书扫描件" value="DOC_SCAN" />
              <el-option label="执法音像" value="AV_RECORD" />
              <el-option label="其他" value="OTHER" />
            </el-select>
            <input ref="fileInput" type="file" style="display: none" @change="onUpload" />
            <el-button size="small" type="primary" :loading="uploadingAttachment" :disabled="uploadingAttachment" @click="openAttachmentUpload">上传附件</el-button>
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
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="onDownload(row)">下载</el-button>
                <el-button v-if="canVoidCaseAttachment(row)" :disabled="voidingAttachment" size="small" text type="danger" @click="onVoidAttachment(row)">作废</el-button>
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
          <el-button v-if="canStartPeriods" size="small" class="mb" @click="dlg.exclusion = true">登记扣除期间</el-button>
          <p class="hint">检测检验、鉴定、听证、公告、专家评审时间不计入办案期限（第45条）。作出决定、中止、终止或归档前，请先补登未结束期间及评审的实际结束日期。</p>
          <el-table :data="detail.exclusions" border size="small" class="mb">
            <el-table-column label="事由" width="110">
              <template #default="{ row }">{{ EXCLUSION_REASON[row.reason] }}</template>
            </el-table-column>
            <el-table-column prop="start_at" label="开始" width="110" />
            <el-table-column prop="end_at" label="结束" width="110" />
            <el-table-column prop="note" label="说明" />
            <el-table-column label="操作" width="145"><template #default="{ row }"><el-button v-if="!row.end_at && canCompletePeriods" size="small" text type="primary" @click="openPeriodEnd(row, 'EXCLUSION')">补登结束日期</el-button></template></el-table-column>
          </el-table>
          <h4>专家评审（第25条，结束自动登记期限扣除）　<el-button v-if="canStartPeriods" size="small" @click="onStartExpert">启动评审</el-button></h4>
          <el-table :data="detail.expertReviews" border size="small">
            <el-table-column prop="experts" label="专家" width="220" show-overflow-tooltip />
            <el-table-column prop="started_at" label="开始" width="110" />
            <el-table-column label="结束/意见">
              <template #default="{ row }">
                <span v-if="row.ended_at">{{ row.ended_at }}：{{ row.opinion }}</span>
                <el-button v-else-if="canCompletePeriods" size="small" text type="primary" @click="onEndExpert(row)">结束并出具意见</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`审核·告知·讨论(${(detail.reviews?.length || 0) + (detail.notices?.length || 0)})`">
          <h4>法制审核（第37-40条）</h4>
          <el-alert type="info" :closable="false" class="mb" title="建议事实、报告、证据附件及告知听证材料齐备后再送审。首次告知、再告知、陈述申辩、听证或其他材料变更后，原审核不再适用于当前材料，应重新送审。" />
          <p v-if="detail.reviewMaterialStatus?.reason" class="hint">最新申请材料核验：{{ detail.reviewMaterialStatus.reason }}</p>
          <el-table :data="detail.reviews" border size="small" class="mb">
            <el-table-column prop="requiredReason" label="触发情形" show-overflow-tooltip />
            <el-table-column prop="submittedAt" label="提交" width="105" />
            <el-table-column prop="deadlineAt" label="审核期限" width="105" />
            <el-table-column label="材料版本" width="165"><template #default="{ row }"><el-tooltip :content="reviewVersion(row, detail.reviewMaterialStatus).reason"><el-tag :type="reviewVersion(row, detail.reviewMaterialStatus).type" size="small">{{ reviewVersion(row, detail.reviewMaterialStatus).label }}</el-tag></el-tooltip></template></el-table-column>
            <el-table-column label="申请快照" width="115"><template #default="{ row }"><el-button size="small" text @click="openReviewSnapshot(row)">查看送审材料</el-button></template></el-table-column>
            <el-table-column label="意见" width="240">
              <template #default="{ row }">
                <span v-if="row.reviewedAt">{{ REVIEW_OPINION[row.opinionType] }}：{{ row.opinion }}（{{ row.reviewer }}）</span>
                <template v-else>
                  <el-button v-if="isLegal" size="small" type="primary" :disabled="c.status === 'CLOSED' || !!c.archiveNo || !reviewVersion(row, detail.reviewMaterialStatus).actionable" @click="openReviewDialog(row)">办理审核</el-button>
                  <span v-if="!reviewVersion(row, detail.reviewMaterialStatus).actionable" class="hint">请由办案人员重新送审</span>
                  <span v-else-if="!isLegal" class="hint">待法制机构审核</span>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <h4>处罚告知与陈述申辩（第41条）</h4>
          <el-table :data="detail.notices" border size="small" class="mb">
            <el-table-column prop="id" label="告知版本" width="92" />
            <el-table-column prop="notifiedAt" label="告知登记日" width="112" />
            <el-table-column label="实际送达" min-width="145">
              <template #default="{ row }">
                <span v-if="noticeReceipt(row.id)">{{ noticeReceipt(row.id)?.delivered_at }} · {{ noticeReceipt(row.id)?.receipt_no }}</span>
                <span v-else>{{ row.deliveryRequired ? '待登记实际回证' : '沿用历史登记口径' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="statementDeadline" label="陈述申辩截止日" width="140" />
            <el-table-column prop="hearingRequestDeadline" label="听证申请截止日" width="140" />
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
          <h4>听证（辽46-50条）　
            <el-button v-if="canWriteCase && currentNotice?.hearingRequested && c.status === 'NOTIFIED'" size="small" @click="onDeliverHearingNotice()"
              :disabled="currentNotice.deliveryRequired && !currentNoticeReceipt">登记听证通知回证</el-button>
            <el-button v-if="canWriteCase" size="small" @click="openHearing"
              :disabled="c.status !== 'NOTIFIED' || !currentNotice?.hearingRequested || currentNotice.deliveryRequired && !currentNoticeReceipt">安排听证</el-button>
          </h4>
          <el-table :data="detail.hearings" border size="small" class="mb">
            <el-table-column label="告知版本" width="100">
              <template #default="{ row }">{{ row.notice_id ?? '历史未关联' }}</template>
            </el-table-column>
            <el-table-column prop="notice_sent_at" label="通知送达" width="105" />
            <el-table-column label="通知回证" width="110"><template #default="{ row }">{{ row.hearing_notice_delivery_id ? '#' + row.hearing_notice_delivery_id : '历史人工录入' }}</template></el-table-column>
            <el-table-column prop="scheduled_at" label="举行日期" width="105" />
            <el-table-column prop="host" label="主持人" width="100" />
            <el-table-column label="状态" width="230">
              <template #default="{ row }">
                <template v-if="row.status === 'SCHEDULED'">
                  待举行 <el-button v-if="canWriteCase && (row.notice_id == null || String(row.notice_id) === String(currentNotice?.id))" size="small" text type="primary" @click="onHoldHearing(row)">举行并记录笔录</el-button>
                  <el-button v-if="canWriteCase" size="small" text type="danger" @click="onCancelHearing(row)">撤销排期</el-button>
                </template>
                <span v-else-if="row.status === 'CANCELLED'">已撤销</span>
                <template v-else-if="row.status === 'HELD'">
                  已举行{{ row.held_at }} <el-button v-if="canWriteCase" size="small" text type="primary" @click="onHearingOpinion(row)">出具听证意见</el-button>
                </template>
                <span v-else>已出意见（{{ row.opinion_at }}）</span>
              </template>
            </el-table-column>
            <el-table-column prop="opinion" label="听证意见" show-overflow-tooltip />
          </el-table>
          <h4>协查台账（第34条，15日）　<el-button v-if="canWriteCase" size="small" @click="onAddAssist" :disabled="['CLOSED','TERMINATED'].includes(c.status)">发出协查函</el-button></h4>
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
                <el-button v-else-if="canWriteCase" size="small" text type="primary" @click="onReplyAssist(row)">复函/拒绝</el-button>
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
                <el-button v-else-if="canWriteCase" size="small" text type="primary" @click="onSignMeeting(row)">签字确认</el-button>
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
                <template v-if="detail.decision.published">
                  <span v-if="detail.decision.publishWithdrawn" class="danger">已撤回公开（{{ fmtDate(detail.decision.withdrawnAt) }}）：{{ detail.decision.withdrawReason }}</span>
                  <template v-else>
                    <span>已公开 {{ detail.decision.publishedAt }}</span>
                    <el-button v-if="canOperateCase && isLeader" size="small" text type="danger" @click="onPublishWithdraw">撤回公开</el-button>
                  </template>
                </template>
                <el-button v-else-if="canOperateCase" size="small" type="warning" @click="onPublish">公开（7日内）</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="政府备案" :span="2">
                <span v-if="detail.decision.govRecordNo || detail.decision.govRecordAt">{{ detail.decision.govRecordNo || '文号未记录' }}（{{ detail.decision.govRecordAt || '日期未记录' }}）</span>
                <el-button v-else-if="canGovRecord" size="small" text type="primary" @click="onGovRecord">备案登记</el-button>
              </el-descriptions-item>
              <el-descriptions-item v-if="detail.decision.penaltyBasis" label="处罚依据" :span="3">{{ detail.decision.penaltyBasis }}</el-descriptions-item>
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
          <h4>分期计划（第54条）　<el-button v-if="canWriteCase" size="small" @click="openInstallment" :disabled="!c.deferApproved">添加分期</el-button></h4>
          <el-table :data="installments" border size="small" class="mb">
            <el-table-column prop="seq" label="期数" width="70" />
            <el-table-column label="款项" width="110">
              <template #default="{ row }">{{ EXEC_KIND[row.kind] || row.kind || '罚款' }}</template>
            </el-table-column>
            <el-table-column prop="due_at" label="到期日" width="120" />
            <el-table-column prop="amount" label="金额" width="120" align="right" />
            <el-table-column label="缴纳" width="150">
              <template #default="{ row }">
                <span v-if="row.paid_at">已缴 {{ row.paid_at }}</span>
                <el-button v-else-if="canWriteCase" size="small" text type="primary" @click="onPayInstallment(row)">登记缴纳</el-button>
              </template>
            </el-table-column>
          </el-table>
          <h4>协议处理台账（联动经办）　<el-button v-if="canWriteCase" size="small" @click="openAgreement()" :disabled="!detail.decision?.decisionType">移交经办处理</el-button></h4>
          <el-table :data="detail.agreementActions" border size="small" class="mb">
            <el-table-column label="处理" width="110">
              <template #default="{ row }">{{ ({ INTERVIEW: '约谈', SUSPEND_AGREEMENT: '暂停协议', TERMINATE_AGREEMENT: '解除协议', REFUSE_PAY: '拒付', RECOVER: '追回' } as any)[row.action] }}</template>
            </el-table-column>
            <el-table-column prop="org" label="经办机构" width="160" show-overflow-tooltip />
            <el-table-column prop="sent_at" label="移交日期" width="110" />
            <el-table-column label="经办进度" width="180">
              <template #default="{ row }">
                <template v-if="row.agency_dept_id">
                  <div>{{ AGREEMENT_STATUS[row.agency_status] || '待接收' }} · 第 {{ row.agency_cycle }} 次分派</div>
                  <div class="hint">期限 {{ row.agency_due_at || '未登记' }}</div>
                  <div v-if="row.agency_received_at" class="hint">接收 {{ row.agency_received_at }}</div>
                  <div v-if="row.agency_reminded_at" class="hint">催办 {{ row.agency_reminded_at }}</div>
                </template>
                <span v-else>线下登记</span>
              </template>
            </el-table-column>
            <el-table-column label="办理结果" min-width="180">
              <template #default="{ row }">
                <div v-if="row.replied_at">
                  {{ row.replied_at }} · {{ row.result }}
                  <div v-if="row.agency_result_type" class="hint">
                    {{ row.agency_result_type === 'COMPLETED' ? '已落实' : '未落实' }}
                    <span v-if="row.agency_recovered_amount != null"> · 追回 {{ row.agency_recovered_amount }} 元</span>
                    <span v-if="row.agency_result_effective_at"> · 生效 {{ row.agency_result_effective_at }}</span>
                    <span v-if="row.agency_result_end_at"> · 至 {{ row.agency_result_end_at }}</span>
                    <span v-if="row.agency_result_reference"> · {{ row.agency_result_reference }}</span>
                  </div>
                </div>
                <span v-else-if="row.agency_return_reason">退回原因 {{ row.agency_return_reason }}</span>
                <span v-else-if="row.agency_dept_id" class="hint">待经办部门反馈</span>
                <el-button v-else-if="canWriteCase" size="small" text type="primary" @click="onReplyAgreement(row)">登记结果</el-button>
              </template>
            </el-table-column>
            <el-table-column label="协同操作" min-width="190">
              <template #default="{ row }">
                <template v-if="row.agency_dept_id">
                  <el-button v-if="canOperateCase && ['PENDING','ACCEPTED'].includes(row.agency_status)"
                             :disabled="agreementSaving || row.agency_reminded_at === todayLocal()" size="small" text
                             @click="remindAgreement(row)">催办</el-button>
                  <el-button v-if="canOperateCase && ['PENDING','RETURNED'].includes(row.agency_status)"
                             :disabled="agreementSaving" size="small" text @click="openAgreement(row)">重派</el-button>
                  <el-button size="small" text @click="openAgreementHistory(row.id)">流转记录</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <h4>执行（第52-56条）　<span class="hint">暂缓/分期：{{ c.deferApproved ? '已批准' : '未申请' }}；当前决定法院强执：{{ COURT_STATUS[detail.executionDeadline?.courtStatus] || '未申请' }}</span></h4>
          <p v-if="detail.executionDeadline" class="hint">
            缴款期限：{{ detail.executionDeadline.paymentDeadline || '未记录' }}；
            救济期限：{{ detail.executionDeadline.reliefDeadline || '待确认' }}；
            强执申请期限：{{ detail.executionDeadline.applicationStart || '待确认' }} 至 {{ detail.executionDeadline.applicationDeadline || '待确认' }}；
            {{ EXECUTION_DEADLINE_STATE[detail.executionDeadline.state] || detail.executionDeadline.state }}
          </p>
          <p v-if="detail.executionDeadline?.courtReceiptNo" class="hint">当前有效法院回执：{{ detail.executionDeadline.courtReceiptNo }}（{{ detail.executionDeadline.courtReceivedAt }}）</p>
          <p v-if="c.courtReceiptNo && (c.courtReceiptNo !== detail.executionDeadline?.courtReceiptNo || c.courtResultNote)" class="hint">原决定首轮回执：{{ c.courtReceiptNo }}（{{ c.courtReceivedAt }}） {{ c.courtResultNote }}</p>
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
            <el-table-column label="财政票据" width="122">
              <template #default="{ row }">
                <el-button v-if="row.receipt_no" link type="primary" :loading="receiptChecking === row.id"
                           :disabled="receiptChecking !== null" @click="checkTreasuryReceipt(row.id)">核对票据</el-button>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="dlg.relief" title="登记救济与暂缓期限" width="520px">
      <el-form label-width="110px">
        <el-form-item label="救济期限届满"><el-date-picker v-model="reliefForm.deadline" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="救济进行中"><el-switch v-model="reliefForm.pending" /></el-form-item>
        <el-form-item label="期限依据"><el-input v-model="reliefForm.basis" type="textarea" /></el-form-item>
        <el-form-item v-if="c.deferApproved" label="暂缓至"><el-date-picker v-model="reliefForm.deferUntil" value-format="YYYY-MM-DD" clearable /></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" @click="saveRelief">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="dlg.courtResult" title="登记法院办理结果" width="520px">
      <el-form label-width="100px">
        <el-form-item label="结果"><el-select v-model="courtResultForm.status"><el-option v-for="(label, value) in COURT_RESULT" :key="value" :value="value" :label="label" /></el-select></el-form-item>
        <el-form-item label="回执编号"><el-input v-model="courtResultForm.receiptNo" /></el-form-item>
        <el-form-item label="结果日期"><el-date-picker v-model="courtResultForm.receivedAt" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="courtResultForm.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button v-if="canWriteCase" type="primary" @click="saveCourtResult">保存</el-button></template>
    </el-dialog>
    <EvidenceCatalog v-model:visible="dlg.evidenceCatalog" :case-id="id" />
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
        <el-button v-if="canWriteCase" type="primary" @click="submit(`officers`, officerForm, 'officer')">保存</el-button>
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
        <el-button v-if="canWriteCase" type="primary" @click="submit(`evidences`, evidenceForm, 'evidence')">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模板生成：库里有 14 种模板，此前用 prompt 让用户手打英文枚举码且只放行 6 种——
         催告书/中止决定书/先行登记保存决定书等 8 种"系统自己指的路自己堵死"（强执前置报错让做 URGE_LETTER，入口却拒收） -->
    <el-dialog v-model="dlg.tplPick" title="按模板生成文书" width="460px">
      <el-select v-model="tplPickType" :disabled="renderingTemplate" filterable style="width: 100%" placeholder="选择文书类型（仅列出已配置模板的类型）">
        <el-option v-for="t in tplOptions" :key="t.doc_type" :label="DOC_TYPE[t.doc_type] || t.doc_type" :value="t.doc_type" />
      </el-select>
      <div class="hint" style="margin-top: 8px">生成后进入"制作文书"窗口，可修改正文后保存；模板正文由管理员在「文书模板」页维护。</div>
      <template #footer>
        <el-button @click="dlg.tplPick = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" :loading="renderingTemplate" :disabled="!tplPickType || renderingTemplate" @click="onRenderTplConfirm">生成</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="dlg.document" title="制作文书" width="600px">
      <el-form label-width="90px" :disabled="submitting">
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
        <el-button v-if="canWriteCase" :loading="submitting" :disabled="submitting" type="primary" @click="submit(`documents`, documentForm, 'document')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.periodEnd" :title="periodEndForm.kind === 'EXPERT' ? '结束专家评审并登记意见' : '补登扣除期间结束日期'" width="560px" style="max-width: 95vw">
      <el-alert type="info" :closable="false" :title="`请按实际材料补录。开始日：${periodEndForm.startAt}；最晚结束日：${periodCutoff(c, todayLocal())}`" />
      <el-form label-width="100px" style="margin-top: 12px">
        <el-form-item label="结束日期" required><el-date-picker v-model="periodEndForm.endAt" type="date" value-format="YYYY-MM-DD" :disabled-date="periodDateDisabled" /></el-form-item>
        <el-form-item v-if="periodEndForm.kind === 'EXPERT'" label="评审意见" required><el-input v-model="periodEndForm.opinion" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg.periodEnd = false">取消</el-button><el-button v-if="canCompletePeriods" type="primary" :loading="endingPeriod" @click="savePeriodEnd">保存实际结束信息</el-button></template>
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
        <el-button v-if="canWriteCase" type="primary" @click="submit(`exclusions`, exclusionForm, 'exclusion')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.agreement" :title="agreementReassignment ? '重派经办事项' : '移交经办处理'" width="540px"
               :close-on-click-modal="!agreementSaving" :close-on-press-escape="!agreementSaving"
               :show-close="!agreementSaving">
      <el-form label-width="100px">
        <el-form-item v-if="!agreementReassignment" label="处理类型">
          <el-select v-model="agreementForm.action">
            <el-option v-for="(label, action) in AGREEMENT_ACTION" :key="action" :label="label" :value="action" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!agreementReassignment && c.partyType === 'PROVIDER'" label="办理方式">
          <el-radio-group v-model="agreementForm.mode">
            <el-radio value="ONLINE">平台移交</el-radio>
            <el-radio value="OFFLINE">线下移交登记</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="agreementForm.mode === 'ONLINE'" label="经办部门" required>
          <el-select v-model="agreementForm.agencyDeptId" :loading="agreementLoading" filterable
                     placeholder="请选择接收部门">
            <el-option v-for="dept in agreementDepartments" :key="dept.id" :label="dept.name" :value="dept.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="经办机构">
          <el-input v-model="agreementForm.org" maxlength="128" />
        </el-form-item>
        <el-form-item v-if="agreementForm.mode === 'ONLINE'" label="办理期限" required>
          <el-date-picker v-model="agreementForm.dueAt" value-format="YYYY-MM-DD"
                          placeholder="选择最迟办理日期" />
        </el-form-item>
        <el-form-item v-if="agreementReassignment" label="重派原因" required>
          <el-input v-model="agreementForm.reason" type="textarea" maxlength="1000" />
        </el-form-item>
        <el-form-item v-else label="移交说明">
          <el-input v-model="agreementForm.note" type="textarea" maxlength="255"
                    placeholder="填写协议处理所需事项" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="agreementSaving" @click="dlg.agreement = false">取消</el-button>
        <el-button type="primary" :loading="agreementSaving"
                   :disabled="agreementReassignment ? !canOperateCase : !canWriteCase"
                   @click="submitAgreement">{{ agreementReassignment ? '确认重派' : '确认移交' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.agreementHistory" title="经办流转记录" width="820px" style="max-width: 95vw">
      <el-alert v-if="agreementHistoryFailed" title="流转记录加载失败，请关闭后重试。" type="warning" :closable="false" />
      <el-table v-loading="agreementHistoryLoading" :data="agreementHistory" border>
        <el-table-column prop="cycle" label="轮次" width="62" />
        <el-table-column label="操作" width="95">
          <template #default="{ row }">{{ AGREEMENT_EVENT[row.type] || row.type }}</template>
        </el-table-column>
        <el-table-column prop="department" label="经办部门" min-width="115" />
        <el-table-column prop="actor" label="操作账号" min-width="110" />
        <el-table-column prop="dueAt" label="办理期限" width="110" />
        <el-table-column label="时间" width="168">
          <template #default="{ row }">{{ formatLocalDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="detail" label="原因" min-width="110" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <el-dialog v-model="dlg.report" title="案件调查终结报告（第36条）" width="640px">
      <p class="hint">报告正文填写当事人情况、案件来源、调查经过、主要证据、违法行为性质、处理意见及依据。下列认定内容会自动写入报告和统计表。</p>
      <el-form label-width="135px">
        <ViolationFindingForm v-model="violationFinding" />
        <el-form-item label="报告正文" required>
          <el-input v-model="reportContent" type="textarea" :rows="6" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.report = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" :loading="submitting" @click="onReport">提交（调查终结）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.notice" :title="c.status==='NOTIFIED' ? '再次行政处罚告知' : '行政处罚告知（第41条）'" width="600px">
      <el-alert v-if="detail.notices?.length" title="再次告知保留历次记录；变更后须核对申辩、听证及重新送审。" type="warning" :closable="false" />
      <p v-if="noticeAmountLimit" class="hint">历次最低告知金额为罚款 {{ noticeAmountLimit.fine }} 元、追回 {{ noticeAmountLimit.recoup }} 元、没收 {{ noticeAmountLimit.confiscate }} 元。再次告知和决定均按此预检。</p>
      <el-form label-width="110px">
        <el-form-item label="拟罚款金额"><el-input-number v-model="noticeForm.proposedFine" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="拟追回基金"><el-input-number v-model="noticeForm.proposedRecoup" :min="0" :precision="2" style="width: 200px" /></el-form-item>
        <el-form-item label="拟没收违法所得">
          <el-input-number v-model="noticeForm.proposedConfiscate" :min="0" :precision="2" style="width: 200px" />
          <div class="hint">与罚款合计参与"数额较大"判定（听证告知/法制审核/集体讨论）</div>
        </el-form-item>
        <el-form-item label="变更理由">
          <el-input v-model="noticeForm.changeReason" type="textarea" :rows="2"
                    placeholder="再次告知可填写事实、证据或依据发生变化的理由" />
        </el-form-item>
        <el-form-item label="事实理由依据"><el-input v-model="noticeForm.content" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <p class="hint">拟罚款达到听证标准将自动告知听证权利；当事人享有陈述权、申辩权。</p>
      <p class="hint">保存后请登记本版本实际送达回证，系统据此计算申辩与听证申请截止日。</p>
      <template #footer>
        <el-button @click="dlg.notice = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" :loading="notifying" :disabled="notifying" @click="onNotify">保存本次告知</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.statement" title="陈述申辩 / 听证" width="600px">
      <el-form label-width="110px">
        <el-form-item label="陈述申辩内容"><el-input v-model="statementForm.statement" type="textarea" :rows="4" /></el-form-item>
        <el-form-item v-if="statementForm.deliveryRequired && statementForm.statement.trim() && statementForm.statement !== statementSnapshot.statement" label="申辩收到日期">
          <el-date-picker v-model="statementForm.statementReceivedAt" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="复核意见"><el-input v-model="statementForm.statementReview" type="textarea" :rows="3" placeholder="成立的应当采纳；不得因陈述申辩加重处罚" /></el-form-item>
        <el-form-item label="明确放弃">
          <el-switch v-model="statementForm.statementWaived" :disabled="statementSnapshot.statementWaived" />
          <div class="hint">当事人明确表示放弃陈述申辩的，勾选后可在期限届满前作出决定（须另有书面/笔录佐证）</div>
        </el-form-item>
        <el-form-item v-if="statementForm.deliveryRequired && statementForm.statementWaived && !statementSnapshot.statementWaived" label="放弃凭据">
          <el-input v-model="statementForm.statementWaiverReference" maxlength="500" />
        </el-form-item>
        <el-form-item label="申请听证"><el-switch v-model="statementForm.hearingRequested" /></el-form-item>
        <el-form-item v-if="statementForm.deliveryRequired && statementForm.hearingRequested && !statementSnapshot.hearingRequested" label="申请收到日期">
          <el-date-picker v-model="statementForm.hearingRequestedAt" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item v-if="statementForm.deliveryRequired && !statementForm.hearingRequested && statementSnapshot.hearingRequested" label="撤回凭据">
          <el-input v-model="statementForm.hearingWithdrawalReference" maxlength="500" />
        </el-form-item>
        <el-form-item label="听证日期">
          <span>{{ statementForm.hearingHeldAt || '尚未举行' }}（由听证举行记录生成）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.statement = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" @click="onStatement">保存</el-button>
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
        <el-button v-if="canWriteCase" type="primary" @click="submit(`meetings`, meetingForm, 'meeting')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.decide" :title="isLeader ? '作出处理决定（第43条）' : '决定条件清单（仅检查，不作出决定）'" width="760px" style="max-width: 95vw">
      <el-form label-width="110px">
        <el-form-item label="决定类型">
          <el-select v-model="decisionForm.decisionType" style="width: 100%">
            <el-option v-for="(v, k) in DECISION_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
          <el-button size="small" text type="primary" @click="onDiscretionSuggest" style="margin-top: 4px">查看裁量基准建议</el-button>
        </el-form-item>
        <template v-if="decisionForm.decisionType === 'PUNISH'">
          <ViolationFindingForm v-if="c.procedureType === 'SUMMARY'" v-model="violationFinding" />
          <el-form-item label="处罚依据" required>
            <el-input v-model="decisionForm.penaltyBasis" type="textarea" :rows="3" maxlength="4000"
                      placeholder="填写适用的规范名称和条款" />
          </el-form-item>
          <el-form-item label="罚款金额"><el-input-number v-model="decisionForm.fineAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="责令退回基金"><el-input-number v-model="decisionForm.recoupAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="没收违法所得"><el-input-number v-model="decisionForm.confiscateAmount" :min="0" :precision="2" style="width: 200px" /></el-form-item>
          <el-form-item label="其他措施"><el-input v-model="decisionForm.otherMeasures" placeholder="约谈、建议解除服务协议等" /></el-form-item>
          <el-form-item label="从轻/减轻"><el-input v-model="decisionForm.mitigation" placeholder="主动消除危害/受胁迫/立功等（辽55条），无则留空" /></el-form-item>
          <el-form-item label="裁量理由"><el-input v-model="decisionForm.discretionReason" type="textarea" :rows="2" placeholder="裁量性决定应说明考虑的主要因素（辽44条）" /></el-form-item>
        </template>
        <el-form-item label="决定内容"><el-input v-model="decisionForm.content" type="textarea" :rows="5" /></el-form-item>
      </el-form>
      <PreflightChecklist :result="decisionCheck.result" />
      <template #footer>
        <el-button @click="dlg.decide = false">取消</el-button>
        <el-button :loading="decisionCheck.checking" :disabled="deciding" @click="decisionGuard.check">检查办理条件</el-button>
        <el-button v-if="canWriteCase && (isLeader)" type="primary" :loading="deciding" :disabled="decisionCheck.checking" @click="onDecide">作出决定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.close" :title="!isLeader ? '结案条件清单（仅检查，不结案归档）' : c.status === 'TERMINATED' ? '终止案件立卷归档' : '结案归档'" width="760px" style="max-width: 95vw">
      <el-form label-width="90px">
        <el-form-item label="结案报告"><el-input v-model="closeForm.closeReport" type="textarea" :rows="5" placeholder="说明办理、执行及归档情况" /></el-form-item>
      </el-form>
      <PreflightChecklist :result="closeCheck.result" />
      <template #footer>
        <el-button @click="dlg.close = false">取消</el-button>
        <el-button :loading="closeCheck.checking" :disabled="closing" @click="closeGuard.check">检查办理条件</el-button>
        <el-button v-if="canWriteCase && (isLeader)" type="primary" :loading="closing" :disabled="closeCheck.checking" @click="submitClose">结案归档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.deliver" title="登记送达（第59条：7个工作日内）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="送达方式">
          <el-select v-model="deliveryForm.method" style="width: 100%">
            <el-option v-for="(v, k) in DELIVERY_METHOD" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item :label="deliveryForm.method === 'ANNOUNCE' ? '公告日期' : '送达日期'">
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
        <el-button v-if="canWriteCase" type="primary" :loading="deliveringCase" :disabled="deliveringCase" @click="onDeliver">登记</el-button>
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
          <el-date-picker v-model="executionForm.paidAt" :disabled-date="executionDateDisabled" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
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
        <el-button v-if="canWriteCase" type="primary" @click="submit(`executions`, executionForm, 'execution')">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.doc" :title="docView.title || '查看文书'" width="640px">
      <p class="hint">{{ DOC_TYPE[docView.doc_type] }}　{{ docView.made_at }}　制作人：{{ docView.maker || '-' }}</p>
      <pre v-loading="docReading" class="doc-content" id="doc-print-area">{{ docView.content }}</pre>
      <template #footer>
        <el-button type="primary" :disabled="docReading || !docReady" @click="printDoc">打印</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.apply" title="提交审批申请（负责人批准后自动执行）" width="480px"
               :close-on-click-modal="!applying" :close-on-press-escape="!applying" :show-close="!applying">
      <el-form label-width="90px" :disabled="applying">
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
        <el-button :disabled="applying" @click="dlg.apply = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" :loading="applying" @click="onApply">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.docDeliver" :title="`文书送达：${docDeliverForm.title}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="送达方式">
          <el-select v-model="docDeliverForm.method" style="width: 100%">
            <el-option v-for="(v, k) in DELIVERY_METHOD" :disabled="!!docDeliverForm.noticeId && k === 'ELECTRONIC'" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="送达日期"><el-date-picker v-model="docDeliverForm.deliveredAt" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="受送达人"><el-input v-model="docDeliverForm.receiver" /></el-form-item>
        <el-form-item label="回证编号"><el-input v-model="docDeliverForm.receiptNo" /></el-form-item>
        <p v-if="docDeliverForm.noticeId || docDeliverForm.hearingNoticeVersionId" class="hint">请填写本版本已经实际生效的送达日期。采用公告方式时，填写实际期满生效日。</p>
      </el-form>
      <template #footer>
        <el-button @click="dlg.docDeliver = false">取消</el-button>
        <el-button v-if="canWriteCase" :loading="deliveringDocument" :disabled="deliveringDocument" type="primary" @click="onDocDeliverSubmit">登记送达</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.reviewSnapshot" title="审核申请材料快照（只读）" width="850px" style="max-width: 95vw"><ReviewMaterialSnapshot :review="snapshotReview" /></el-dialog>
    <el-dialog v-model="dlg.review" title="法制审核办理（审核人须具备法律职业资格，不得为本案办案人员）" width="850px" style="max-width: 95vw">
      <ReviewMaterialSnapshot :review="selectedReview" />
      <el-alert v-if="!canReviewSelected" type="warning" :closable="false" title="本申请已不适用于当前材料或已有后续申请，请重新送审后办理。" style="margin: 12px 0" />
      <el-form label-width="90px">
        <el-form-item label="审核人"><el-input :model-value="auth.user?.realName || auth.user?.username" readonly /></el-form-item>
        <el-form-item label="意见类型">
          <el-select v-model="reviewForm.opinionType" style="width: 100%">
            <el-option v-for="(v, k) in REVIEW_OPINION" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核意见"><el-input v-model="reviewForm.opinion" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.review = false">取消</el-button>
        <el-button type="primary" :disabled="!canReviewSelected" @click="onDoReview">提交审核</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.installment" title="添加分期（第54条）" width="420px">
      <el-form label-width="80px">
        <el-form-item label="款项类型">
          <el-select v-model="installmentForm.kind" style="width: 180px">
            <el-option label="罚款" value="FINE" />
            <el-option label="退回基金" value="RECOUP" />
            <el-option label="没收违法所得" value="CONFISCATE" />
          </el-select>
          <span class="hint">须与决定书确定的款项对应，缴纳时按此类型入账</span>
        </el-form-item>
        <el-form-item label="期数"><el-input-number v-model="installmentForm.seq" :min="1" /></el-form-item>
        <el-form-item label="到期日">
          <el-date-picker v-model="installmentForm.dueAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="金额"><el-input-number v-model="installmentForm.amount" :min="0.01" :precision="2" style="width: 180px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.installment = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" @click="onAddInstallment">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.eConsent" title="电子送达确认书（第59条：须当事人签署并确认电子送达地址）" width="520px">
      <el-form :model="eConsentForm" label-width="150px">
        <el-form-item label="受送达人" required>
          <el-input v-model="eConsentForm.receiver" placeholder="签署确认书的当事人/受送达人" />
        </el-form-item>
        <el-form-item label="确认的电子送达地址" required>
          <el-input v-model="eConsentForm.channel" placeholder="当事人确认的手机号/邮箱等" />
        </el-form-item>
        <el-form-item label="确认书编号">
          <el-input v-model="eConsentForm.docNo" placeholder="可留空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.eConsent = false">取消</el-button>
        <el-button v-if="canWriteCase" type="primary" @click="onEConsent">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.hearing" title="安排听证（须在通知送达后≥7日举行；主持人不得为本案办案人员）" width="520px">
      <el-form :model="hearingForm" label-width="110px">
        <el-form-item label="公告日期">
          <el-date-picker v-model="hearingForm.announcedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="公开听证公告（涉密除外）" />
        </el-form-item>
        <el-form-item v-if="hearingForm.requireReceipt || hearingNoticeReceipts.length" label="通知实际回证">
          <el-select v-model="hearingForm.hearingNoticeDeliveryId" placeholder="选择已登记的听证通知回证" :clearable="!hearingForm.requireReceipt">
            <el-option v-for="receipt in hearingNoticeReceipts" :key="receipt.id" :value="receipt.id"
              :label="`#${receipt.id}　${receipt.delivered_at}　${receipt.receipt_no}`" />
          </el-select>
          <p v-if="!hearingNoticeReceipts.length" class="hint">请先在听证区域登记当前版本的通知回证。</p>
        </el-form-item>
        <el-form-item v-if="!hearingForm.requireReceipt && !hearingForm.hearingNoticeDeliveryId" label="通知送达日">
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
        <el-button v-if="canWriteCase" type="primary" @click="onScheduleHearing">安排</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dlg.catalog" title="案卷目录（一案一卷，第57条）" width="640px">
      <el-alert v-if="catalogReady && catalog.missing?.length" type="warning" :closable="false" class="mb"
                :title="`必备文书缺失：${catalog.missing.map((m: string) => DOC_TYPE[m] || m).join('、')}`" />
      <el-alert v-else-if="catalogReady" type="success" :closable="false" class="mb" title="必备文书齐全" />
      <p class="hint">案卷号：{{ catalog.archiveNo || '（结案时生成）' }}</p>
      <el-table :data="catalog.catalog" v-loading="catalogReading" border size="small">
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
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { canCompletePeriod, canStartPeriod, periodCutoff, periodEndError } from '../../api/periodCompletion'
import { localDate as todayLocal, createTodayReset, formatLocalDateTime } from '../../api/dates'
import { executionDateError, lateFeeTimingError } from '../../api/executionForm'
import { caseWriteAccess, caseOperationAccess, governmentRecordAvailable, attachmentVoidAccess } from '../../api/caseWriteAccess'
import { createApprovalRequestKey } from '../../api/approvalRequestKey'
import ReviewMaterialSnapshot from '../../components/ReviewMaterialSnapshot.vue'
import { reviewVersion, cloneReview, type MaterialReview } from '../../api/reviewMaterials'
import EvidenceCatalog from '../../components/EvidenceCatalog.vue'
import { evidenceCatalogHtml } from '../../api/evidenceCatalog'
import JurisdictionForm from '../../components/JurisdictionForm.vue'
import HistoricalReportSupplement from './HistoricalReportSupplement.vue'
import CaseContinuationPanel from '../../components/CaseContinuationPanel.vue'
import FactRevisionPanel from '../../components/FactRevisionPanel.vue'
import SupplementBundlesPanel from '../../components/SupplementBundlesPanel.vue'
import SignaturePolicyBanner from '../../components/SignaturePolicyBanner.vue'
import SupplementCorrectionPanel from '../../components/SupplementCorrectionPanel.vue'
import DeliveryReceiptsPanel from '../../components/DeliveryReceiptsPanel.vue'
import JurisdictionSummary from '../../components/JurisdictionSummary.vue'
import { emptyJurisdiction, serializeJurisdiction, jurisdictionError, canEditJurisdiction } from '../../api/jurisdiction'
import PreflightChecklist from '../../components/PreflightChecklist.vue'
import WorkdayCoverageNotice from '../../components/WorkdayCoverageNotice.vue'
import { createPreflightCheck, type PreflightState } from '../../api/preflight'
import client, { LARGE_TRANSFER } from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import ViolationFindingForm from '../../components/ViolationFindingForm.vue'
import { INSTITUTION_GRADE } from '../../api/violationFinding'
import type { ViolationFinding } from '../../api/violationFinding'
import {
  CASE_STATUS, CASE_STATUS_TAG, DECISION_TYPE, DELIVERY_METHOD, DOC_TYPE,
  EXECUTION_DEADLINE_STATE, EVIDENCE_TYPE, EXCLUSION_REASON, EXEC_KIND, EXEC_METHOD, PARTY_TYPE, REVIEW_OPINION, fmtTime, fmtDate,
} from './labels'

const auth = useAuthStore()

const COURT_STATUS: Record<string, string> = { APPLIED: '已申请，待法院结果', ACCEPTED: '法院已受理', RETURNED: '法院退回', REJECTED: '法院驳回' }
const COURT_RESULT: Record<string, string> = { ACCEPTED: '法院已受理', RETURNED: '法院退回', REJECTED: '法院驳回' }
const reliefForm = reactive<any>({ deadline: '', pending: false, basis: '', deferUntil: null })
const courtResultForm = reactive({ status: 'ACCEPTED', receiptNo: '', receivedAt: '', note: '' })

const CLOSE_REASON: Record<string, string> = {
  EXECUTED: '执行完毕', COURT: '法院受理强制执行', NO_NEED: '无须执行', OTHER: '其他',
}


const isLeader = computed(() => auth.user?.roles?.some((r: string) => ['LEADER', 'ADMIN'].includes(r)))
const isLegal = computed(() => auth.user?.roles?.some((r: string) => ['LEGAL', 'ADMIN'].includes(r)))

const route = useRoute()
// 同路由不同 id 会复用组件：必须响应式取值 + watch 重载，
// 否则从 A 详情页跳到 B 后页面仍是 A 的数据，而操作按钮打的是 A
const id = computed(() => route.params.id as string)
const detail = ref<any>({})
const loading = ref(false)
const caseReady = ref(false)
watch(() => [caseReady.value, route.query.executionVersion, route.query.deliveryVersion, route.query.courtRound, route.hash], () => {
  if (!caseReady.value) return
  const target = typeof route.query.deliveryVersion === 'string' && /^\d+$/.test(route.query.deliveryVersion) ? 'supplement-work'
    : typeof route.query.executionVersion === 'string' && /^\d+$/.test(route.query.executionVersion) ? 'execution-work'
    : route.hash === '#report-supplement' ? 'report-supplement' : null
  if (target) document.getElementById(target)?.scrollIntoView({ block: 'start' })
}, { flush: 'post' })
const c = computed<any>(() => detail.value.caseFile || {})
const cause = computed<any>(() => detail.value.cause || {})
const canOperateCase = computed(() => caseOperationAccess(auth.user?.roles))
const canGovRecord = computed(() => governmentRecordAvailable(auth.user?.roles, detail.value.decision))
const canWriteCase = computed(() => caseReady.value && !loading.value && caseWriteAccess(auth.user?.roles, c.value))
const canEditActive = computed(() => canWriteCase.value && ['INVESTIGATING','REPORTED','NOTIFIED'].includes(c.value.status))
function canVoidCaseAttachment(row: any) { return attachmentVoidAccess(auth.user?.roles, auth.user?.username, c.value, row) }
let materialDisposed = false, materialContextGeneration = 0
type MaterialContext = { caseId: string; generation: number }
function captureMaterialContext(): MaterialContext { return { caseId: id.value, generation: materialContextGeneration } }
function materialContextCurrent(context: MaterialContext) {
  return !materialDisposed && context.caseId === id.value && context.generation === materialContextGeneration
}
watch(() => [id.value, auth.user?.username, auth.user?.roles?.join('|'), c.value.status, c.value.archiveNo], () => {
  materialContextGeneration++
}, { flush: 'sync' })
onUnmounted(() => {
  materialDisposed = true; materialContextGeneration++; loadSeq++
  caseReady.value = false; loading.value = false; detail.value = {}; attachments.value = []
  docView.value = {}; docReady.value = false; catalog.value = {}; catalogReady.value = false
})

const canCompletePeriods = computed(() => canCompletePeriod(auth.user?.roles, c.value))
const canStartPeriods = computed(() => canStartPeriod(auth.user?.roles, c.value))
const periodEndForm = reactive({ kind: 'EXCLUSION', rowId: 0, startAt: '', endAt: '', opinion: '' })
const endingPeriod = ref(false)
function openPeriodEnd(row: any, kind: string) {
  if (!canCompletePeriods.value) return
  Object.assign(periodEndForm, { kind, rowId: row.id, startAt: kind === 'EXPERT' ? row.started_at : row.start_at, endAt: todayLocal(), opinion: '' })
  dlg.periodEnd = true
}
function periodDateDisabled(day: Date) {
  const date = todayLocal(day)
  return date < periodEndForm.startAt || date > periodCutoff(c.value, todayLocal())
}
async function savePeriodEnd() {
  if (!canCompletePeriods.value || endingPeriod.value) return
  const error = periodEndError(periodEndForm.startAt, periodEndForm.endAt, c.value, todayLocal())
  if (error) { ElMessage.warning(error); return }
  if (periodEndForm.kind === 'EXPERT' && !periodEndForm.opinion.trim()) { ElMessage.warning('请填写评审意见'); return }
  const caseId = id.value, rowId = periodEndForm.rowId, expert = periodEndForm.kind === 'EXPERT'
  endingPeriod.value = true
  try {
    await client.post(`/bureau/cases/${caseId}/${expert ? 'expert-reviews' : 'exclusions'}/${rowId}/end`,
      expert ? { endedAt: periodEndForm.endAt, opinion: periodEndForm.opinion.trim() } : { endAt: periodEndForm.endAt })
    if (caseId !== id.value) return
    ElMessage.success('实际结束信息已登记')
    dlg.periodEnd = false
    load()
  } finally { endingPeriod.value = false }
}

const canUpdateJurisdiction = computed(() => canEditJurisdiction(auth.user?.roles, c.value))
const jurisdictionForm = reactive({ jurisdiction: emptyJurisdiction(), reason: '' })
const savingJurisdiction = ref(false)
function openJurisdiction() {
  if (!canUpdateJurisdiction.value) return
  jurisdictionForm.jurisdiction = serializeJurisdiction(detail.value.jurisdiction)
  jurisdictionForm.reason = ''
  dlg.jurisdiction = true
}
async function saveJurisdiction() {
  if (!canUpdateJurisdiction.value || savingJurisdiction.value) return
  const error = jurisdictionError(jurisdictionForm.jurisdiction)
  if (error) { ElMessage.warning(error); return }
  if (!jurisdictionForm.reason.trim()) { ElMessage.warning('请填写补录/更正理由'); return }
  const caseId = id.value
  savingJurisdiction.value = true
  try {
    await client.put(`/bureau/cases/${caseId}/jurisdiction`, {
      jurisdiction: serializeJurisdiction(jurisdictionForm.jurisdiction), reason: jurisdictionForm.reason.trim(),
    })
    if (caseId !== id.value) return
    ElMessage.success('管辖事实已记录')
    dlg.jurisdiction = false
    load()
  } finally { savingJurisdiction.value = false }
}


const dlg = reactive<Record<string, boolean>>({
  reviewSnapshot: false, periodEnd: false, officer: false, evidence: false, document: false, exclusion: false, report: false,
  notice: false, statement: false, meeting: false, decide: false, deliver: false,
  execution: false, doc: false, catalog: false, hearing: false, review: false, installment: false,
  evidenceCatalog: false, jurisdiction: false, close: false, relief: false, courtResult: false, apply: false, docDeliver: false, eConsent: false, tplPick: false,
  agreement: false, agreementHistory: false,
  correction: false,   // 更正弹窗必须随切案复位：否则 A 案的更正值会提交到 B 案
})
const tplOptions = ref<any[]>([])
const tplPickType = ref('')
const eConsentForm = reactive<any>({ receiver: '', channel: '', docNo: '' })
const uploadCategory = ref('DOC_SCAN')
const uploadingAttachment = ref(false), voidingAttachment = ref(false), signingDocument = ref(false)
let attachmentPicker: MaterialContext | null = null
const downloadingAttachments = new Set<number>()
const renderingTemplate = ref(false)
let templateSequence = 0, documentDraftGeneration = 0
watch(() => [dlg.document, dlg.evidence], () => { documentDraftGeneration++ }, { flush: 'sync' })
watch(() => dlg.tplPick, visible => { if (!visible) templateSequence++ }, { flush: 'sync' })
/** 附件上限（MB）：与服务端 AttachmentController 的 MAX_SIZE/MAX_AV_SIZE 同口径 */
const fileStorage = ref(false)
function uploadLimitMb(category: string) {
  return category === 'AV_RECORD' && fileStorage.value ? 200 : 10
}
client.get('/config/public').then((r) => { fileStorage.value = r.data.data?.attachment_storage === 'FILE' }).catch(() => {})
const applyForm = reactive<any>({ kind: 'EXTEND', days: 30, reason: '' })
const applying = ref(false)
const caseApprovalRequestKey = createApprovalRequestKey()
let applyContextGeneration = 0
let applyDisposed = false
function invalidateApplyContext() { applyContextGeneration++ }
watch(() => dlg.apply, visible => { if (!visible) invalidateApplyContext() }, { flush: 'sync' })
onUnmounted(() => { applyDisposed = true; invalidateApplyContext() })
const docDeliverForm = reactive<any>({ documentId: null, noticeId: null, hearingNoticeVersionId: null, title: '', docKind: 'OTHER', method: 'DIRECT', deliveredAt: todayLocal(), receiver: '', receiptNo: '' })
const deliveringDocument = ref(false)
let deliveryDraftGeneration = 0
watch(() => dlg.docDeliver, () => { deliveryDraftGeneration++ }, { flush: 'sync' })
const pendingApprovals = computed(() => (detail.value.approvals || []).filter((a: any) => a.status === 'PENDING'))
const reviewForm = reactive<any>({ reviewId: null, reviewer: '', opinionType: 'AGREE', opinion: '' })
const selectedReview = ref<MaterialReview | null>(null)
const snapshotReview = ref<MaterialReview | null>(null)
const canReviewSelected = computed(() => {
  const row = detail.value.reviews?.find((r: any) => String(r.id) === String(reviewForm.reviewId))
  return c.value.status !== 'CLOSED' && !c.value.archiveNo && !!isLegal.value && !!row && reviewVersion(row, detail.value.reviewMaterialStatus).actionable
})
function openReviewSnapshot(row: MaterialReview) {
  snapshotReview.value = cloneReview(row)
  dlg.reviewSnapshot = true
}

const installmentForm = reactive<any>({ seq: 1, dueAt: '', amount: 0, kind: 'FINE' })
const hearingForm = reactive<any>({ noticeId: null, hearingNoticeDeliveryId: null, requireReceipt: false, announcedAt: null, noticeSentAt: todayLocal(), scheduledAt: '', host: '', hostDept: '', recorder: '' })
const attachments = ref<any[]>([])
const timeline = ref<any[]>([])
const installments = ref<any[]>([])
const catalog = ref<any>({})
const catalogReading = ref(false), catalogReady = ref(false)
let catalogSequence = 0
watch(() => dlg.catalog, visible => { if (!visible) { catalogSequence++; catalog.value = {}; catalogReading.value = false; catalogReady.value = false } }, { flush: 'sync' })
const fileInput = ref<HTMLInputElement>()

const officerForm = reactive({ name: '', certNo: '', duty: 'MEMBER' })
const evidenceForm = reactive({ type: 'DOCUMENT', name: '', source: '', obtainedAt: todayLocal(), keeper: '', note: '', registerHold: false, sealed: false })
const documentForm = reactive<any>({ docType: 'INQUIRY_RECORD', title: '', content: '', maker: '', signed: false, dueAt: null })
const exclusionForm = reactive({ reason: 'APPRAISE', startAt: todayLocal(), endAt: todayLocal(), note: '' })
const currentNotice = computed(() => detail.value.notices?.[0])
function noticeReceipt(noticeId: number | string) {
  return detail.value.deliveries?.find((receipt: any) => receipt.notice_id != null && String(receipt.notice_id) === String(noticeId))
}
const currentNoticeReceipt = computed(() => noticeReceipt(currentNotice.value?.id))
const hearingNoticeReceipts = computed(() => {
  const version = currentNotice.value?.id
  if (version == null) return []
  const used = new Set((detail.value.hearings || []).map((row: any) => String(row.hearing_notice_delivery_id)))
  return (detail.value.deliveries || []).filter((row: any) => row.doc_kind === 'HEARING_NOTICE'
    && String(row.hearing_notice_version_id) === String(version) && !used.has(String(row.id)))
})
const noticeAmountLimit = computed(() => {
  const notices = detail.value.notices || []
  if (!notices.length) return null
  const minimum = (field: string, includeMissing = false) => {
    const values = notices.filter((n: any) => includeMissing || n[field] != null).map((n: any) => Number(n[field] ?? 0))
    return values.length ? Math.min(...values) : 0
  }
  return { fine: minimum('proposedFine'), recoup: minimum('proposedRecoup'), confiscate: minimum('proposedConfiscate', true) }
})
const noticeForm = reactive({ content: '', proposedFine: 0, proposedRecoup: 0, proposedConfiscate: 0, changeReason: '', requireDelivery: true, previousNoticeId: null as number | null })
const notifying=ref(false)
function openNotice() {
  if(!canWriteCase.value || notifying.value || !['REPORTED','NOTIFIED'].includes(c.value.status))return
  const previous = currentNotice.value
  Object.assign(noticeForm, {
    content: previous?.content || '', proposedFine: Number(previous?.proposedFine || 0),
    proposedRecoup: Number(previous?.proposedRecoup || 0), proposedConfiscate: Number(previous?.proposedConfiscate || 0),
    changeReason: '', previousNoticeId: previous?.id ?? null,
  })
  dlg.notice=true
}
function emptyStatementForm() {
  return {
    noticeId: null, deliveryRequired: false, statement: '', statementReview: '', hearingRequested: false,
    hearingHeldAt: null, statementWaived: false, statementReceivedAt: null, hearingRequestedAt: null,
    hearingWithdrawalReference: '', statementWaiverReference: '',
  }
}
const statementForm = reactive<any>(emptyStatementForm())
/** 打开弹窗时的回填快照，提交时据此只发改动过的字段 */
let statementSnapshot: any = { ...statementForm }
const meetingForm = reactive({ heldAt: todayLocal(), attendees: '', record: '', conclusion: '' })
const decisionForm = reactive({ decisionType: 'PUNISH', fineAmount: 0, recoupAmount: 0, confiscateAmount: 0, otherMeasures: '', content: '', mitigation: '', discretionReason: '', penaltyBasis: '' })
const closeForm = reactive({ closeReport: '' })
const decisionCheck = reactive<PreflightState>({ result: null, checking: false })
const closeCheck = reactive<PreflightState>({ result: null, checking: false })
const deciding = ref(false)
const closing = ref(false)
function emptyViolationFinding(): ViolationFinding {
  return { start: null, end: null, facts: '', amount: null }
}

function findingComplete(finding: ViolationFinding): boolean {
  return !!finding.start && !!finding.end && finding.start <= finding.end
    && !!finding.facts.trim() && finding.amount !== null
    && Number.isFinite(finding.amount) && finding.amount >= 0
}

const violationFinding = ref(emptyViolationFinding())
function decisionBody() {
  const body = { ...decisionForm, finding: c.value.procedureType === 'SUMMARY' && decisionForm.decisionType === 'PUNISH'
    ? { ...violationFinding.value } : null }
  if (body.decisionType !== 'PUNISH') {
    body.fineAmount = 0; body.recoupAmount = 0; body.confiscateAmount = 0
  }
  return body
}
async function requestPreflight(caseId: string, body: Record<string, unknown>) {
  return (await client.post(`/bureau/cases/${caseId}/preflight`, body)).data.data
}
const decisionGuard = createPreflightCheck(decisionCheck, () => ({ caseId: id.value, body: { action: 'DECIDE', decision: decisionBody() } }), requestPreflight)
const closeGuard = createPreflightCheck(closeCheck, () => ({ caseId: id.value, body: { action: 'CLOSE', close: { ...closeForm } } }), requestPreflight)
watch([decisionForm, violationFinding], decisionGuard.invalidate, { deep: true, flush: 'sync' })
watch(closeForm, closeGuard.invalidate, { deep: true, flush: 'sync' })
watch(id, () => { decisionGuard.invalidate(); closeGuard.invalidate() }, { flush: 'sync' })
watch(() => dlg.decide, open => { if (!open) decisionGuard.invalidate() }, { flush: 'sync' })
watch(() => dlg.close, open => { if (!open) closeGuard.invalidate() }, { flush: 'sync' })

const deliveryForm = reactive({ method: 'DIRECT', deliveredAt: todayLocal(), receiver: '', note: '', receiptNo: '', receiptSignedAt: null as string | null })
const executionForm = reactive({ kind: 'FINE', amount: 0, paidAt: todayLocal(), method: 'BANK', note: '', receiptNo: '' })
function executionDateDisabled(day: Date) {
  return !!executionDateError(todayLocal(day), detail.value.decision?.decidedAt || c.value.decidedAt, todayLocal())
}

const reportContent = ref('')
const docView = ref<any>({})
const docReading = ref(false), docReady = ref(false)
let documentSequence = 0
watch(() => dlg.doc, visible => { if (!visible) { documentSequence++; docView.value = {}; docReading.value = false; docReady.value = false } }, { flush: 'sync' })

let loadSeq = 0
async function load() {
  if (materialDisposed) return
  const seq = ++loadSeq  // 快速切案时只有最后一次可写入，避免旧请求结果覆盖新案件
  const cid = id.value
  materialContextGeneration++; attachmentPicker = null
  loading.value = true; caseReady.value = false
  detail.value = {}; attachments.value = []; timeline.value = []; installments.value = []
  dlg.doc = false; dlg.catalog = false; dlg.evidenceCatalog = false
  docView.value = {}; docReady.value = false; catalog.value = {}; catalogReady.value = false
  try {
    const [resp, att, tl, inst] = await Promise.all([
      client.get(`/bureau/cases/${cid}`), client.get(`/bureau/cases/${cid}/attachments`),
      client.get(`/bureau/cases/${cid}/timeline`), client.get(`/bureau/cases/${cid}/installments`),
    ])
    if (materialDisposed || seq !== loadSeq || cid !== id.value) return
    detail.value = resp.data.data
    attachments.value = att.data.data
    timeline.value = tl.data.data
    installments.value = inst.data.data
    caseReady.value = true
  } catch {
    // 失败（无权限/已归档/网络）须清空，否则残留上一个案件的数据而按钮仍可点 → 误操作
    if (!materialDisposed && seq === loadSeq) {
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
  if (!canWriteCase.value) return
  if (materialDisposed || renderingTemplate.value) return
  const context = captureMaterialContext(), draft = documentDraftGeneration
  renderingTemplate.value = true
  try {
    // Template options are shared, but opening a draft still belongs to the original case.
    if (!tplOptions.value.length) {
      const options = (await client.get('/bureau/doc-templates')).data.data
      if (!materialContextCurrent(context) || draft !== documentDraftGeneration || !canWriteCase.value) return
      tplOptions.value = Array.isArray(options) ? options : []
    }
    if (!materialContextCurrent(context) || draft !== documentDraftGeneration || !canWriteCase.value) return
    if (!tplPickType.value) tplPickType.value = tplOptions.value.some((t: any) => t.doc_type === 'NOTICE') ? 'NOTICE' : (tplOptions.value[0]?.doc_type || '')
    dlg.tplPick = true
  } catch { /* The interceptor has already reported the failed read. */ }
  finally { renderingTemplate.value = false }
}

async function onRenderTplConfirm() {
  if (!canWriteCase.value) return
  if (materialDisposed || renderingTemplate.value || !dlg.tplPick) return
  const context = captureMaterialContext(), sequence = ++templateSequence, draft = documentDraftGeneration, docType = tplPickType.value
  renderingTemplate.value = true
  try {
    const resp = await client.get(`/bureau/cases/${context.caseId}/documents/render`, { params: { docType } })
    if (!materialContextCurrent(context) || sequence !== templateSequence || draft !== documentDraftGeneration || !dlg.tplPick || !canWriteCase.value) return
    const d = resp.data.data
    Object.assign(documentForm, { docType: d.docType, title: d.title, content: d.content, maker: '', signed: false, dueAt: null })
    dlg.tplPick = false
    dlg.document = true
  } catch { /* Keep the selection and draft on failure. */ }
  finally { renderingTemplate.value = false }
}

async function onCatalog() {
  if (materialDisposed || !caseReady.value || loading.value) return
  const context = captureMaterialContext(), sequence = ++catalogSequence
  catalog.value = {}; catalogReady.value = false; catalogReading.value = true
  dlg.catalog = true
  try {
    const value = (await client.get(`/bureau/cases/${context.caseId}/archive-catalog`)).data.data
    if (!value || !Array.isArray(value.catalog)) throw new Error('Invalid archive catalog')
    if (!materialContextCurrent(context) || sequence !== catalogSequence || !dlg.catalog) return
    catalog.value = value; catalogReady.value = true
  } catch {
    if (sequence === catalogSequence) { catalog.value = {}; catalogReady.value = false }
  } finally { if (sequence === catalogSequence) catalogReading.value = false }
}

async function onDownload(row: any) {
  if (materialDisposed || !caseReady.value || loading.value || row.voided || downloadingAttachments.has(row.id) || !attachments.value.some(a => a.id === row.id)) return
  const context = captureMaterialContext(), attachmentId = row.id, filename = row.filename
  downloadingAttachments.add(attachmentId)
  // 带 JWT 的 blob 下载（直链 <a href> 无 Authorization 会 401）
  try {
    const resp = await client.get(`/bureau/cases/attachments/${attachmentId}/download`, { responseType: 'blob', ...LARGE_TRANSFER })
    if (!materialContextCurrent(context)) return
    const a = document.createElement('a')
    a.href = URL.createObjectURL(resp.data); a.download = filename; a.click(); URL.revokeObjectURL(a.href)
  } catch { /* Do not download an error response or an obsolete case's file. */ }
  finally { downloadingAttachments.delete(attachmentId) }
}

function openAttachmentUpload() {
  if (!canWriteCase.value || materialDisposed || uploadingAttachment.value) return
  attachmentPicker = captureMaterialContext()
  if (fileInput.value) fileInput.value.value = ''
  fileInput.value?.click()
}
async function onUpload(e: Event) {
  if (!canWriteCase.value) return
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  const context = attachmentPicker; attachmentPicker = null
  if (!file || !context || !materialContextCurrent(context) || uploadingAttachment.value) { input.value = ''; return }
  // 客户端预检：超限的执法音像此前要整段传完才被服务端拒，且超过网关上限时友好提示到不了浏览器
  const limitMb = uploadLimitMb(uploadCategory.value)
  if (file.size > limitMb * 1024 * 1024) {
    ElMessage.error(`文件 ${(file.size / 1024 / 1024).toFixed(1)}MB 超过「${uploadCategory.value === 'AV_RECORD' ? '执法音像' : '扫描件/其他'}」上限 ${limitMb}MB`
      + (uploadCategory.value === 'AV_RECORD' && !fileStorage.value ? '（当前为库内存储，执法音像需管理员切换外置存储后方可上传大文件）' : ''))
    input.value = ''
    return
  }
  const fd = new FormData()
  fd.append('file', file)
  fd.append('category', uploadCategory.value)
  uploadingAttachment.value = true
  const closeMsg = ElMessage({ message: `上传中：${file.name}`, type: 'info', duration: 0 })
  try {
    await client.post(`/bureau/cases/${context.caseId}/attachments`, fd,
      { headers: { 'Content-Type': 'multipart/form-data' }, ...LARGE_TRANSFER })
    if (!materialContextCurrent(context)) return
    ElMessage.success('已上传')
    await load()
  } catch { /* 拦截器已提示 */ } finally {
    closeMsg.close()
    uploadingAttachment.value = false
    input.value = ''  // 无论成败都清空，否则重选同一文件不触发 change
  }
}

async function onSignDoc(row: any) {
  if (!canWriteCase.value) return
  if (materialDisposed || signingDocument.value) return
  const context = captureMaterialContext(), documentId = row.id
  signingDocument.value = true
  try {
    const { value } = await ElMessageBox.prompt('签章人（办案人员/当事人/负责人）', '电子签章', { inputValue: auth.user?.realName || '', inputPattern: /\S+/, inputErrorMessage: '必填' })
    if (!materialContextCurrent(context) || !canWriteCase.value) return
    const resp = await client.post(`/bureau/cases/${context.caseId}/documents/${documentId}/sign`, { signer: value })
    if (!materialContextCurrent(context)) return
    if(resp.data.data.provider==='MOCK') ElMessage.warning('已保存模拟签章，仅摘要留痕；不作为正式 CA 签章')
    else ElMessage.success(`已签章（${resp.data.data.provider}，摘要 ${resp.data.data.contentHash.slice(0, 12)}…）`)
    await load()
  } catch { /* Includes a dismissed signature prompt. */ }
  finally { signingDocument.value = false }
}

let verificationSequence = 0
async function onVerifyDoc(row: any) {
  if (materialDisposed || !caseReady.value || loading.value) return
  const context = captureMaterialContext(), sequence = ++verificationSequence, documentId = row.id
  try {
    const sigs = (await client.get(`/bureau/cases/${context.caseId}/documents/${documentId}/verify`)).data.data
    if (!materialContextCurrent(context) || sequence !== verificationSequence) return
    if (!sigs.length) { ElMessage.info('该文书尚无签章'); return }
    const ok = sigs.every((s: any) => s.valid)
    await ElMessageBox.alert(sigs.map((s: any) => `${s.signer}（${s.signer_role}）${s.signed_at}：${s.valid ? '摘要及签章校验通过' : '校验未通过'}；${s.simulation || s.provider==='MOCK' ? '模拟记录' : '外部签章'}；${s.formalAccepted===true ? '符合当前正式策略' : '未作为正式签章接受'}`).join('\n'),
      ok ? '验签通过' : '验签失败', { type: ok ? 'success' : 'error' })
  } catch { /* Do not show verification results for an obsolete document or case. */ }
}

function onDeliverDoc(row: any) {
  if (!canWriteCase.value) return
  if (materialDisposed || deliveringDocument.value) return
  if (row.doc_type === 'NOTICE' && currentNotice.value?.deliveryRequired) return
  if (row.doc_type === 'HEARING_NOTICE' && currentNotice.value?.hearingRequested && c.value.status === 'NOTIFIED') {
    onDeliverHearingNotice(row.id)
    return
  }
  Object.assign(docDeliverForm, { documentId: row.id, noticeId: null, hearingNoticeVersionId: null, title: row.title, docKind: row.doc_type, method: 'DIRECT', deliveredAt: todayLocal(), receiver: '', receiptNo: '' })
  dlg.docDeliver = true
}

function onDeliverNotice() {
  const notice = currentNotice.value
  if (!canWriteCase.value || deliveringDocument.value || !notice?.deliveryRequired || currentNoticeReceipt.value) return
  Object.assign(docDeliverForm, {
    documentId: null, noticeId: notice.id, hearingNoticeVersionId: null, title: `处罚告知 #${notice.id}`, docKind: 'NOTICE',
    method: 'DIRECT', deliveredAt: '', receiver: '', receiptNo: '',
  })
  dlg.docDeliver = true
}

function onDeliverHearingNotice(documentId: number | null = null) {
  const notice = currentNotice.value
  if (!canWriteCase.value || deliveringDocument.value || !notice?.hearingRequested
      || c.value.status !== 'NOTIFIED' || (notice.deliveryRequired && !currentNoticeReceipt.value)) return
  Object.assign(docDeliverForm, {
    documentId, noticeId: null, hearingNoticeVersionId: notice.id, title: `告知 #${notice.id} 的听证通知`,
    docKind: 'HEARING_NOTICE', method: 'DIRECT', deliveredAt: '', receiver: '', receiptNo: '',
  })
  dlg.docDeliver = true
}

async function onDocDeliverSubmit() {
  if (!canWriteCase.value) return
  if (materialDisposed || deliveringDocument.value || !dlg.docDeliver) return
  const context = captureMaterialContext(), draft = deliveryDraftGeneration, payload = { ...docDeliverForm }
  if ((payload.noticeId || payload.hearingNoticeVersionId) && (!payload.deliveredAt || !payload.receiver.trim() || !payload.receiptNo.trim())) {
    ElMessage.warning('请填写实际送达日期、受送达人和回证编号')
    return
  }
  deliveringDocument.value = true
  try {
    await client.post(`/bureau/cases/${context.caseId}/doc-deliveries`, payload)
    if (!materialContextCurrent(context) || draft !== deliveryDraftGeneration || !dlg.docDeliver) return
    ElMessage.success('文书送达已登记')
    dlg.docDeliver = false
    await load()
  } catch { /* Preserve the unsaved delivery draft on rejection. */ }
  finally { deliveringDocument.value = false }
}

async function onApply() {
  if (!canWriteCase.value || applyDisposed || applying.value || !dlg.apply) return
  if (!applyForm.reason.trim()) {
    ElMessage.warning('请填写理由')
    return
  }
  const caseId = id.value, generation = applyContextGeneration
  const payload = {
    kind: applyForm.kind, caseId: Number(caseId),
    payload: applyForm.kind === 'EXTEND' ? { days: applyForm.days } : null,
    reason: applyForm.reason,
  }
  applying.value = true
  try {
    await client.post('/bureau/approvals', { ...payload, requestKey: caseApprovalRequestKey.get(payload, auth.user?.id) })
    if (applyDisposed || caseId !== id.value || generation !== applyContextGeneration) return
    caseApprovalRequestKey.clear()
    ElMessage.success('已提交，待负责人在"待办审批"裁决')
    dlg.apply = false
    await load()
  } catch {
    // Keep the draft on failure; the API interceptor has already shown the reason.
  } finally {
    applying.value = false
  }
}

const AGREEMENT_ACTION = { INTERVIEW: '约谈', SUSPEND_AGREEMENT: '暂停协议', TERMINATE_AGREEMENT: '解除协议',
  REFUSE_PAY: '拒付', RECOVER: '追回' }
const AGREEMENT_STATUS: Record<string, string> = {
  PENDING: '待接收', ACCEPTED: '已接收', RETURNED: '已退回', COMPLETED: '已回复',
}
const AGREEMENT_EVENT: Record<string, string> = {
  DISPATCHED: '移交', ACCEPTED: '接收', RETURNED: '退回', REASSIGNED: '重派', REMINDED: '催办',
  AUTO_REMINDED: '系统到期提醒', COMPLETED: '回复',
}
interface OnlineAgreement { id: number; agency_cycle: number; agency_status: string; agency_due_at?: string }
const agreementForm = reactive({
  action: 'REFUSE_PAY', mode: 'OFFLINE', org: '医保经办机构', note: '', reason: '', dueAt: '',
  agencyDeptId: null as number | null,
})
const agreementReassignment = ref<OnlineAgreement | null>(null)
const agreementDepartments = ref<{ id: number; name: string }[]>([])
const agreementLoading = ref(false)
const agreementSaving = ref(false)
const agreementHistory = ref([])
const agreementHistoryLoading = ref(false)
const agreementHistoryFailed = ref(false)
let agreementSequence = 0, agreementHistorySequence = 0

async function openAgreement(row?: OnlineAgreement) {
  if (agreementSaving.value || (row ? !canOperateCase.value : !canWriteCase.value)) return
  const context = captureMaterialContext(), sequence = ++agreementSequence
  const canAssign = c.value.partyType === 'PROVIDER'
  agreementReassignment.value = row ?? null
  Object.assign(agreementForm, { action: 'REFUSE_PAY', mode: canAssign ? 'ONLINE' : 'OFFLINE',
    org: '医保经办机构', note: '', reason: '', dueAt: row?.agency_due_at || '', agencyDeptId: null })
  agreementDepartments.value = []
  agreementLoading.value = false
  dlg.agreement = true
  if (!canAssign) return
  agreementLoading.value = true
  try {
    const response = await client.get('/bureau/agency-departments')
    if (materialContextCurrent(context) && sequence === agreementSequence && dlg.agreement)
      agreementDepartments.value = response.data.data
  } catch { /* The shared client displays the directory error. */ }
  finally {
    if (sequence === agreementSequence) agreementLoading.value = false
  }
}

async function submitAgreement() {
  const reassignment = agreementReassignment.value
  if (agreementSaving.value || !dlg.agreement || (reassignment ? !canOperateCase.value : !canWriteCase.value)) return
  const context = captureMaterialContext(), online = agreementForm.mode === 'ONLINE'
  if (online && (!agreementForm.agencyDeptId || !agreementForm.dueAt || agreementForm.dueAt < todayLocal())) {
    ElMessage.warning('请选择经办部门和办理期限，期限不得早于今天')
    return
  }
  if ((!online && !agreementForm.org.trim()) || (reassignment && !agreementForm.reason.trim())) {
    ElMessage.warning('请填写经办机构或重派原因')
    return
  }
  agreementSaving.value = true
  try {
    if (reassignment) {
      await client.post(`/bureau/agreement-actions/${reassignment.id}/reassign`, {
        cycle: reassignment.agency_cycle, agencyDeptId: agreementForm.agencyDeptId,
        dueAt: agreementForm.dueAt, reason: agreementForm.reason,
      })
    } else {
      await client.post(`/bureau/cases/${context.caseId}/agreement-actions`, {
        action: agreementForm.action, note: agreementForm.note,
        ...(online ? { agencyDeptId: String(agreementForm.agencyDeptId), dueAt: agreementForm.dueAt }
          : { org: agreementForm.org.trim() }),
      })
    }
    if (!materialContextCurrent(context) || !dlg.agreement) return
    ElMessage.success(reassignment ? '已重派' : '已移交')
    dlg.agreement = false
    await load()
  } catch { /* The shared client displays the request error. */ }
  finally { agreementSaving.value = false }
}

async function remindAgreement(row: OnlineAgreement) {
  if (!caseReady.value || loading.value || !canOperateCase.value || agreementSaving.value) return
  const context = captureMaterialContext()
  agreementSaving.value = true
  try {
    await client.post(`/bureau/agreement-actions/${row.id}/remind`, { cycle: row.agency_cycle })
    if (!materialContextCurrent(context)) return
    ElMessage.success('已催办，经办部门可在待办查看')
    await load()
  } catch { /* The shared client displays the request error. */ }
  finally { agreementSaving.value = false }
}

async function openAgreementHistory(rowId: number) {
  const context = captureMaterialContext(), sequence = ++agreementHistorySequence
  const current = () => materialContextCurrent(context) && sequence === agreementHistorySequence && dlg.agreementHistory
  agreementHistory.value = []
  agreementHistoryFailed.value = false
  agreementHistoryLoading.value = true
  dlg.agreementHistory = true
  try {
    const response = await client.get(`/bureau/agreement-actions/${rowId}/history`)
    if (current()) agreementHistory.value = response.data.data
  } catch {
    if (current()) agreementHistoryFailed.value = true
  } finally {
    if (current()) agreementHistoryLoading.value = false
  }
}

async function onReplyAgreement(row: any) {
  if (!canWriteCase.value || row.agency_dept_id) return
  const context = captureMaterialContext()
  const { value } = await ElMessageBox.prompt('经办办理结果', '登记结果', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value || !materialContextCurrent(context)) return
  await client.post(`/bureau/agreement-actions/${row.id}/reply`, { result: value })
  if (!materialContextCurrent(context)) return
  ElMessage.success('已登记')
  await load()
}

const printingArchive = ref(false)
async function onPrintArchive() {
  if (materialDisposed || !caseReady.value || loading.value || printingArchive.value) return
  printingArchive.value = true
  try {
    const context = captureMaterialContext()
    let d: any
    try { d = (await client.get(`/bureau/cases/${context.caseId}/archive-full`)).data.data }
    catch { return }
    if (!materialContextCurrent(context)) return
    // archive-full 返回的已是"卷内件全集"（含由结构化记录渲染的立案审批表/法制审核意见/
    // 集体讨论/听证笔录/送达回证），且服务端已按法定装订顺序排好 seq——此前按 id 二次排序，
    // 而虚拟卷内件无 id，会被全部挤到卷末
    const docs = d.documents
    const sigOf = (docId: number) => d.signatures.filter((s: any) => s.document_id === docId)
      .map((s: any) => `［电子签章］${esc(s.signer)}（${esc(fmtDate(s.signed_at))}，${esc(s.provider)}）`).join('　')
    const w = window.open('', '_blank')
    if (!w) return
    const pages = docs.map((doc: any, i: number) => `
    <section class="page"><h2>${esc(doc.title)}</h2>
    <pre>${esc(doc.content)}</pre>
    <p class="sig">${doc.id ? sigOf(doc.id) : ''}</p>
    </section>`).join('')
    // 页码交给浏览器（@page counter）：此前目录与页脚都按"一件一页"硬编 i+2，
    // 任一件正文超过一页、或目录本身超过一页，之后所有页码就整体错位
    const toc = docs.map((doc: any, i: number) => `<tr><td>${i + 1}</td><td>${esc(doc.title)}</td><td>${esc(doc.made_at)}</td><td>${doc.source === 'RECORD' ? '系统记录' : '文书'}</td></tr>`).join('')
    w.document.write(`<html><head><title>${esc(d.caseFile.case_no)} 卷宗</title><style>
    body{font-family:SimSun,serif;line-height:1.9}
    .page{page-break-after:always;padding:40px 50px;min-height:90vh;position:relative}
    h1,h2{text-align:center} pre{white-space:pre-wrap;font-family:inherit;font-size:15px}
    table{width:100%;border-collapse:collapse}td,th{border:1px solid #333;padding:4px 8px;font-size:14px}
    .sig{margin-top:24px;font-size:13px;color:#333}
    @page{margin:14mm;@bottom-center{content:counter(page);font-size:12px;color:#666}}
    </style></head><body>
    <section class="page"><h1>${esc(d.orgName)}<br/>行政处罚案卷</h1>
      <p style="text-align:center;font-size:18px">${esc(d.caseFile.name)}</p>
      <p style="text-align:center">案号：${esc(d.caseFile.case_no)}　案卷号：${esc(d.caseFile.archive_no || '（未结案）')}</p>
      <h2>卷内目录</h2>
      <table><tr><th>序号</th><th>文书名称</th><th>日期</th><th>来源</th></tr>${toc}</table>
      </section>
    ${pages}${evidenceCatalogHtml(d.evidenceCatalog?.items || [])}</body></html>`)
    w.document.close()
    w.print()
  } finally { printingArchive.value = false }
}

/** 文书正文/标题由用户填写，拼进打印窗口前必须转义，否则可注入脚本读取本地令牌 */
function esc(v: any) {
  return String(v ?? '').replace(/[&<>"']/g, (ch) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch] as string))
}

function printDoc() {
  if (materialDisposed || !dlg.doc || docReading.value || !docReady.value) return
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(`<html><head><title>${esc(docView.value.title)}</title><style>body{font-family:SimSun,serif;padding:40px;line-height:1.9}h2{text-align:center}pre{white-space:pre-wrap;font-family:inherit;font-size:15px}</style></head><body><h2>${esc(docView.value.title)}</h2><pre>${esc(docView.value.content)}</pre></body></html>`)
  w.document.close()
  w.print()
}

/** 通用子表提交：POST /bureau/cases/{id}/{path} 后关弹窗刷新 */
// 各复用录入弹窗的初值：提交成功后按 dlgKey 重置，避免下次打开预填上一条被误重复保存
const FORM_RESETS: Record<string, () => void> = {
  report: () => { reportContent.value = ''; violationFinding.value = emptyViolationFinding() },
  officer: () => Object.assign(officerForm, { name: '', certNo: '', duty: 'MEMBER' }),
  evidence: createTodayReset(evidenceForm, today => ({ type: 'DOCUMENT', name: '', source: '', obtainedAt: today, keeper: '', note: '', registerHold: false, sealed: false })),
  document: () => Object.assign(documentForm, { docType: 'INQUIRY_RECORD', title: '', content: '', maker: '', signed: false, dueAt: null }),
  exclusion: createTodayReset(exclusionForm, today => ({ reason: 'APPRAISE', startAt: today, endAt: today, note: '' })),
  meeting: createTodayReset(meetingForm, today => ({ heldAt: today, attendees: '', record: '', conclusion: '' })),
  execution: createTodayReset(executionForm, today => ({ kind: 'FINE', amount: 0, paidAt: today, method: 'BANK', note: '', receiptNo: '' })),
}

const receiptChecking = ref<number | null>(null)
async function checkTreasuryReceipt(executionId: number) {
  if (receiptChecking.value !== null) return
  const context = captureMaterialContext()
  receiptChecking.value = executionId
  try {
    const { data } = await client.post(`/bureau/cases/${context.caseId}/executions/${executionId}/receipt-check`)
    if (!materialContextCurrent(context)) return
    const result = data.data
    if (result.status === 'MATCH') ElMessage.success('票据金额、缴款日期和状态一致')
    else if (result.status === 'DIFFERENT') {
      const mismatches = [!result.amountMatches && '金额', !result.dateMatches && '缴款日期', !result.paid && '缴款状态'].filter(Boolean)
      ElMessage.warning(`待核对 ${mismatches.join('、')}`)
    } else {
      const labels: Record<string, string> = {
        MISSING_RECEIPT: '该记录尚未登记票据号', UNCONFIGURED: '财政票据接口尚未启用', NOT_FOUND: '财政端未找到票据',
      }
      ElMessage.warning(labels[result.status] || '财政票据状态待核对')
    }
  } catch {
    // The shared client reports the request failure.
  } finally {
    receiptChecking.value = null
  }
}

const submitting = ref(false)
const submittingReview = ref(false)
const deliveringCase = ref(false)
async function submit(path: string, body: any, dlgKey: string) {
  if (!canWriteCase.value) return
  if (path === 'evidences' && !canEditActive.value) return
  if (path === 'exclusions' && !canStartPeriods.value) return
  if (path === 'executions') {
    const error = executionDateError(body.paidAt, detail.value.decision?.decidedAt || c.value.decidedAt, todayLocal())
      || (body.kind === 'LATE_FEE' ? lateFeeTimingError(body.paidAt, detail.value.decision?.decisionType, c.value.status, c.value.deliveredAt, detail.value.executionDeadline?.paymentDeadline) : '')
    if (error) { ElMessage.warning(error); return }
  }
  if (materialDisposed || submitting.value || !dlg[dlgKey]) return
  const context = captureMaterialContext(), draft = documentDraftGeneration
  const payload = JSON.parse(JSON.stringify(body))
  submitting.value = true
  try {
    await client.post(`/bureau/cases/${context.caseId}/${path}`, payload)
    if (!materialContextCurrent(context) || draft !== documentDraftGeneration || !dlg[dlgKey]) return
    ElMessage.success('已保存')
    dlg[dlgKey] = false
    FORM_RESETS[dlgKey]?.()
    await load()
  } catch {
    // Preserve the current draft on failure. Never retry material writes automatically.
  } finally {
    submitting.value = false
  }
}

async function onAvoid(row: any) {
  if (!canWriteCase.value) return
  // 后端按"申请主体 + 是否主办"分档要求批准人（第5条）：当事人申请、或主办人员回避，
  // 都须记录负责人批准。此前只发 reason，这两种情形 100% 被 2061 拒，
  // 而本人主动回避又被默认记成 SELF，当事人申请回避这一法定情形在系统里留不下痕迹。
  const applicant = await ElMessageBox.confirm(
    '本次回避是当事人申请的，还是办案人员主动申请？', '申请回避（第5条）',
    { confirmButtonText: '当事人申请', cancelButtonText: '本人主动', distinguishCancelAndClose: true })
    .then(() => 'PARTY').catch((a) => { if (a === 'close') throw a; return 'SELF' })
  const { value: reason } = await ElMessageBox.prompt('回避事由（第5条）', '申请回避',
    { inputPattern: /\S+/, inputErrorMessage: '必填' })
  let decidedBy: string | undefined
  if (applicant === 'PARTY' || row.duty === 'LEAD') {
    const r = await ElMessageBox.prompt(
      applicant === 'PARTY' ? '当事人申请回避须经负责人审查决定，请填写批准人'
                            : '主办人员回避须经负责人批准，请填写批准人',
      '批准人', { inputPattern: /\S+/, inputErrorMessage: '必填' })
    decidedBy = r.value
  }
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/officers/${row.id}/avoid`,
    { reason, applicant, decidedBy })
  ElMessage.success('已回避')
  load()
}

async function onCrossExam(row: any) {
  if (!canWriteCase.value) return
  const { value } = await ElMessageBox.prompt('当事人对该证据的意见（无异议也须注明，辽24条）', '证据质证', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/evidences/${row.id}/cross-exam`, { opinion: value })
  ElMessage.success('已记录质证')
  load()
}

async function onPublish() {
  if (!canOperateCase.value) return
  await client.post(`/bureau/cases/${id.value}/publish`)
  ElMessage.success('处罚决定已公开')
  load()
}

async function onGovRecord() {
  if (!canGovRecord.value) return
  const caseId = id.value
  const { value } = await ElMessageBox.prompt('政府备案文号（重大处罚决定报本级政府备案，辽54条）', '备案登记', { inputValidator: value => !!value.trim() && value.trim().length <= 64 || '文号必填且最多64字' })
  if (caseId !== id.value || !canGovRecord.value) return
  const recordNo = value.trim()
  if (!recordNo || recordNo.length > 64) { ElMessage.warning('文号必填且最多64字'); return }
  await client.post(`/bureau/cases/${caseId}/gov-record`, { recordNo })
  ElMessage.success('已登记备案')
  load()
}

async function onHoldDispose(row: any) {
  if (!canWriteCase.value) return
  const disposal = await ElMessageBox.confirm('先行登记保存证据处理（第28条）', '处理决定', {
    distinguishCancelAndClose: true,
    confirmButtonText: '证据保全', cancelButtonText: '转封存',
  }).then(() => 'PRESERVE').catch((a) => (a === 'cancel' ? 'SEAL' : null))
  if (!disposal) return
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/evidences/${row.id}/hold-disposal`, { disposal })
  ElMessage.success('已处理')
  load()
}

async function onSeal(row: any, extend: boolean) {
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/evidences/${row.id}/seal?extend=${extend}`)
  ElMessage.success(extend ? '已延长30日' : '已解除封存')
  load()
}

async function viewDoc(row: any) {
  if (materialDisposed || !caseReady.value || loading.value) return
  const context = captureMaterialContext(), sequence = ++documentSequence, documentId = row.id
  docView.value = {}; docReady.value = false; docReading.value = true; dlg.doc = true
  try {
    const resp = await client.get(`/bureau/cases/${context.caseId}/documents/${documentId}`)
    if (!materialContextCurrent(context) || sequence !== documentSequence || !dlg.doc) return
    docView.value = resp.data.data; docReady.value = true
  } catch {
    if (sequence === documentSequence) { docView.value = {}; docReady.value = false }
  } finally { if (sequence === documentSequence) docReading.value = false }
}

async function onSuspend() {
  if (!canWriteCase.value || !isLeader.value) return
  const { value } = await ElMessageBox.prompt('中止情形（第42条：需以裁判结果为依据/送请解释确认/不可抗力/当事人下落不明等）', '中止调查', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/suspend`, { reason: value })
  load()
}

async function onResume() {
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/resume`)
  ElMessage.success('已恢复调查，办案期限已顺延中止天数')
  load()
}

const convertingProcedure = ref(false)
const canConvertProcedure = computed(() => canWriteCase.value && isLeader.value
  && c.value.status === 'INVESTIGATING' && c.value.procedureType === 'SUMMARY'
  && !c.value.decidedAt && !c.value.archiveNo)

async function onConvertProcedure() {
  if (!canConvertProcedure.value || convertingProcedure.value) return
  const context = captureMaterialContext()
  convertingProcedure.value = true
  try {
    const { value } = await ElMessageBox.prompt(
      '请填写转换原因。确认后在本案继续办理调查终结报告。',
      '转普通程序',
      { inputType: 'textarea', inputValidator: value => {
        const reason = value?.trim() || ''
        return reason && Array.from(reason).length <= 512 ? true : '请填写 512 字以内的原因'
      } },
    )
    if (!materialContextCurrent(context) || !canConvertProcedure.value) return
    await client.post(`/bureau/cases/${context.caseId}/convert-to-ordinary`, { reason: value })
    if (!materialContextCurrent(context)) return
    ElMessage.success('已转为普通程序')
    await load()
  } catch {
    // Closing the prompt leaves the case unchanged. The API client reports request failures.
  } finally {
    convertingProcedure.value = false
  }
}

async function onTerminate() {
  if (!canWriteCase.value || !isLeader.value) return
  const { value } = await ElMessageBox.prompt('终止情形（第47条：当事人死亡或组织终止无承受人/移送司法/其他）', '终止调查', { inputPattern: /\S+/, inputErrorMessage: '必填', type: 'warning' })
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/terminate`, { reason: value })
  load()
}

async function onExtendCase() {
  if (!canWriteCase.value || !isLeader.value) return
  const { value: days } = await ElMessageBox.prompt('延长天数（首次≤30日；继续延期须先有集体讨论记录，累计≤90日）', '延长办案期限', { inputPattern: /^\d+$/, inputErrorMessage: '请输入天数' })
  const { value: reason } = await ElMessageBox.prompt('延期理由', '延长办案期限', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/extend`, { days: Number(days), reason })
  ElMessage.success('已延长')
  load()
}

async function onReport() {
  if (!canEditActive.value) return
  if (!reportContent.value.trim()) {
    ElMessage.warning('请填写报告内容')
    return
  }
  if (!findingComplete(violationFinding.value)) {
    ElMessage.warning('请填写有效的违法发生起止日期、认定事实和金额')
    return
  }
  await submit('report', { content: reportContent.value, finding: { ...violationFinding.value } }, 'report')
}

async function onSubmitReview() {
  if (!canWriteCase.value || submittingReview.value) return
  const context = captureMaterialContext(), token = auth.token
  submittingReview.value = true
  try {
    const { value } = await ElMessageBox.prompt('审核触发情形（第37条：罚款数额较大/听证案件/疑难复杂/重大公共利益等）', '提交法制审核', { inputPattern: /\S+/, inputErrorMessage: '必填' })
    if (!materialContextCurrent(context) || token !== auth.token || !canWriteCase.value) return
    await client.post(`/bureau/cases/${context.caseId}/reviews`, { requiredReason: value })
    if (!materialContextCurrent(context) || token !== auth.token) return
    ElMessage.success('已提交法制审核（10个工作日内审核完毕）')
    await load()
  } catch {
    // Cancelled prompts and request failures leave the existing application available.
  } finally { submittingReview.value = false }
}

function openReviewDialog(row: any) {
  if (c.value.status === 'CLOSED' || c.value.archiveNo || !isLegal.value || !reviewVersion(row, detail.value.reviewMaterialStatus).actionable) return
  selectedReview.value = cloneReview(row)
  // 不重置会串单：上一份审核单的审核人与意见残留到下一份
  Object.assign(reviewForm, { reviewId: row.id, reviewer: auth.user?.realName || '', opinionType: 'AGREE', opinion: '' })
  dlg.review = true
}

async function onDoReview() {
  if (!canReviewSelected.value) { ElMessage.warning('申请材料版本已失效，请重新送审'); return }
  if (!reviewForm.opinion.trim()) {
    ElMessage.warning('审核意见必填')
    return
  }
  const caseId = id.value, reviewId = reviewForm.reviewId
  await client.post(`/bureau/cases/reviews/${reviewId}`, { opinionType: reviewForm.opinionType, opinion: reviewForm.opinion })
  if (caseId !== id.value || String(reviewForm.reviewId) !== String(reviewId)) return
  ElMessage.success('审核完成')
  dlg.review = false
  load()
}

async function onNotify() {
  if (!canWriteCase.value || notifying.value || !dlg.notice || !['REPORTED','NOTIFIED'].includes(c.value.status)) return
  if (!noticeForm.content.trim()) {
    ElMessage.warning('请填写告知内容')
    return
  }
  const limit = noticeAmountLimit.value
  if (limit && (noticeForm.proposedFine > limit.fine || noticeForm.proposedRecoup > limit.recoup || noticeForm.proposedConfiscate > limit.confiscate)) {
    ElMessage.warning('再次告知金额须在历次最低告知金额以内')
    return
  }
  const context=captureMaterialContext(), token=auth.token, request=JSON.parse(JSON.stringify(noticeForm))
  notifying.value=true
  try {
    await client.post(`/bureau/cases/${context.caseId}/notice`, request)
    if(!materialContextCurrent(context)||auth.token!==token)return
    ElMessage.success('本次告知已保存，历次告知保留')
    dlg.notice = false
    load()
  } finally {notifying.value=false}
}

function openStatement() {
  if (!canWriteCase.value) return
  const n = currentNotice.value
  if (!n || n.deliveryRequired && !currentNoticeReceipt.value) return
  Object.assign(statementForm, emptyStatementForm(), {
    noticeId: n.id,
    deliveryRequired: n.deliveryRequired,
    statement: n.statement ?? '',
    statementReview: n.statementReview ?? '',
    hearingRequested: n.hearingRequested ?? false,
    hearingHeldAt: n.hearingHeldAt ?? null,
    statementWaived: n.statementWaived ?? false,
    statementReceivedAt: n.statementReceivedAt ?? null,
    hearingRequestedAt: n.hearingRequestedAt ?? null,
  })
  statementSnapshot = { ...statementForm }
  dlg.statement = true
}

async function onStatement() {
  if (!canWriteCase.value) return
  const caseId = id.value, noticeId = statementForm.noticeId
  // 只发真正被改动的字段，未改动的一律发 null（后端语义＝不修改）。
  // 不能简单地"只发真值"：那样取消勾选听证申请就永远提交不上去。
  const body: any = {}
  for (const k of ['statement', 'statementReview', 'hearingRequested', 'statementWaived']) {
    const v = (statementForm as any)[k]
    if (v === (statementSnapshot as any)[k]) { body[k] = null; continue }
    // 空串归一为 null：否则等于向后端声明"当事人已陈述申辩"
    body[k] = typeof v === 'string' && !v.trim() ? null : v
  }
  if (Object.values(body).every((v) => v === null)) {
    ElMessage.info('没有需要保存的改动')
    return
  }
  if (noticeId != null) body.noticeId = noticeId
  if (statementForm.deliveryRequired) {
    if (body.statement) body.statementReceivedAt = statementForm.statementReceivedAt
    if (body.hearingRequested === true) body.hearingRequestedAt = statementForm.hearingRequestedAt
    if (body.hearingRequested === false) body.hearingWithdrawalReference = statementForm.hearingWithdrawalReference
    if (body.statementWaived === true) body.statementWaiverReference = statementForm.statementWaiverReference
  }
  await client.post(`/bureau/cases/${caseId}/statement`, body)
  if (caseId !== id.value || noticeId !== statementForm.noticeId) return
  ElMessage.success('已保存')
  dlg.statement = false
  load()
}

function openDecide() {
  decisionGuard.invalidate()
  const n = detail.value.notices?.[0]
  if (n) {
    decisionForm.fineAmount = n.proposedFine
    decisionForm.recoupAmount = n.proposedRecoup
    decisionForm.confiscateAmount = n.proposedConfiscate ?? 0
  }
  dlg.decide = true
}

async function onDecide() {
  if (!canWriteCase.value) return
  if (!isLeader.value || deciding.value || decisionCheck.checking) return
  deciding.value = true
  const caseId = id.value
  try {
    if (!await decisionGuard.check()) return
    await client.post(`/bureau/cases/${caseId}/decide`, decisionBody())
    if (caseId !== id.value) return
    ElMessage.success('决定已作出')
    dlg.decide = false
    load()
  } finally { deciding.value = false }
}

async function onDeliver() {
  if (!canWriteCase.value || deliveringCase.value || !dlg.deliver) return
  const payload = { ...deliveryForm }
  const actualDate = payload.method === 'ANNOUNCE' ? payload.deliveredAt : payload.receiptSignedAt || payload.deliveredAt
  if (!actualDate || !payload.receiver.trim() || !payload.receiptNo.trim()) {
    ElMessage.warning('请填写实际送达或公告日期、受送达人和回证编号')
    return
  }
  const decidedAt = detail.value.decision?.decidedAt || c.value.decidedAt
  if (!decidedAt || actualDate < decidedAt || actualDate > todayLocal()) {
    ElMessage.warning('实际送达或公告日期须在处理决定日与今天之间')
    return
  }
  const context = captureMaterialContext(), token = auth.token
  deliveringCase.value = true
  try {
    await client.post(`/bureau/cases/${context.caseId}/deliver`, payload)
    if (!materialContextCurrent(context) || token !== auth.token) return
    ElMessage.success('已送达')
    dlg.deliver = false
    await load()
  } catch {
    // Preserve the entered receipt for correction or an explicit retry.
  } finally { deliveringCase.value = false }
}

async function onLateFee() {
  const resp = await client.get(`/bureau/cases/${id.value}/late-fee-quote`)
  const q = resp.data.data
  if (!q.payDeadline) { ElMessage.warning('缴款到期日尚未明确，请先登记已批准的暂缓期限或分期计划'); return }
  ElMessageBox.alert(
    `缴款期限：${q.payDeadline}；逾期 ${q.overdueDays} 天；按日3%累计 ${q.accrued} 元；封顶后应加处 ${q.capped} 元（不超过罚款本金 ${q.fineAmount} 元）`,
    '加处罚款测算（第55条）',
  )
}

async function onApproveDefer() {
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/approve-defer`)
  ElMessage.success('已批准暂缓/分期缴纳')
  load()
}

function openRelief() {
  if (!(isLeader.value || isLegal.value) || c.value.status === 'CLOSED' || c.value.archiveNo) return
  Object.assign(reliefForm, { deadline: c.value.reliefDeadline || '', pending: !!c.value.reliefPending, basis: c.value.reliefBasis || '', deferUntil: c.value.deferUntil || null })
  dlg.relief = true
}
async function saveRelief() {
  if (!(isLeader.value || isLegal.value) || c.value.status === 'CLOSED' || c.value.archiveNo) return
  if (!reliefForm.deadline || !reliefForm.basis.trim()) { ElMessage.warning('请填写期限与依据'); return }
  await client.post(`/bureau/cases/${id.value}/relief-deadline`, reliefForm)
  dlg.relief = false
  load()
}
async function saveCourtResult() {
  if (!canWriteCase.value || !isLeader.value) return
  if (!courtResultForm.receiptNo.trim() || !courtResultForm.receivedAt) { ElMessage.warning('请填写回执与日期'); return }
  await client.post(`/bureau/cases/${id.value}/court-result`, courtResultForm)
  dlg.courtResult = false
  load()
}
function openUrgeDelivery() {
  if (!canWriteCase.value) return
  Object.assign(docDeliverForm, { documentId: null, noticeId: null, hearingNoticeVersionId: null, title: '催告书', docKind: 'URGE_LETTER', method: 'DIRECT', deliveredAt: todayLocal(), receiver: '', receiptNo: '' })
  dlg.docDeliver = true
}
async function onCourtEnforce() {
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/court-enforce`)
  ElMessage.success('已登记申请法院强制执行')
  load()
}

function openHearing() {
  const notice = currentNotice.value
  if (!canWriteCase.value || !notice?.hearingRequested || notice.deliveryRequired && !currentNoticeReceipt.value) return
  Object.assign(hearingForm, {
    noticeId: notice.id, hearingNoticeDeliveryId: null, requireReceipt: !!notice.deliveryRequired,
    announcedAt: null, noticeSentAt: notice.deliveryRequired ? null : todayLocal(),
    scheduledAt: '', host: '', hostDept: '', recorder: '',
  })
  dlg.hearing = true
}

async function onScheduleHearing() {
  if (!canWriteCase.value) return
  if (!hearingForm.scheduledAt || !hearingForm.host) {
    ElMessage.warning('举行日期与主持人必填')
    return
  }
  if (hearingForm.requireReceipt && !hearingForm.hearingNoticeDeliveryId) {
    ElMessage.warning('请选择实际送达的听证通知回证')
    return
  }
  await client.post(`/bureau/cases/${id.value}/hearings`, hearingForm)
  ElMessage.success('已安排听证')
  dlg.hearing = false
  load()
}

async function onHoldHearing(row: any) {
  if (!canWriteCase.value) return
  const { value } = await ElMessageBox.prompt('听证笔录（当场阅读并签字确认，辽50条）', '举行听证', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/hearings/${row.id}/hold`, { record: value })
  ElMessage.success('听证已举行')
  load()
}

async function onHearingOpinion(row: any) {
  if (!canWriteCase.value) return
  const { value } = await ElMessageBox.prompt('听证意见（结束2日内提出，报负责人）', '听证意见', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/hearings/${row.id}/opinion`, { opinion: value })
  ElMessage.success('已出具听证意见')
  load()
}

async function onAddAssist() {
  if (!canWriteCase.value) return
  const { value: org } = await ElMessageBox.prompt('协查机关', '发出协查函', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  const { value: content } = await ElMessageBox.prompt('协查事项', '发出协查函', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/assists`, { direction: 'OUT', org, content })
  ElMessage.success('已登记（15日内应复函）')
  load()
}

async function onReplyAssist(row: any) {
  if (!canWriteCase.value) return
  const { value } = await ElMessageBox.prompt('复函结果（拒绝协助请以"拒绝："开头并说明理由）', '协查办理', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  const refused = value.startsWith('拒绝')
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/assists/${row.id}/reply`,
    { result: value, refused, refuseReason: refused ? value : null })
  ElMessage.success('已办结')
  load()
}

async function onSignMeeting(row: any) {
  if (!canWriteCase.value) return
  const { value } = await ElMessageBox.prompt('确认签字的参加人员名单（局令44条：讨论记录经参加人员确认签字存入案卷）', '签字确认', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value) return
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
  if (!canWriteCase.value) return
  installmentForm.dueAt = ''
  installmentForm.amount = 0
  dlg.installment = true
}

async function onAddInstallment() {
  if (!canWriteCase.value) return
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

async function onStartExpert() {
  if (!canStartPeriods.value) return
  const caseId = id.value
  const { value } = await ElMessageBox.prompt('评审专家（顿号分隔）', '启动专家评审', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (caseId !== id.value || !canStartPeriods.value) return
  await client.post(`/bureau/cases/${caseId}/expert-reviews`, { experts: value })
  ElMessage.success('评审已启动')
  if (caseId === id.value) load()
}
function onEndExpert(row: any) {
  openPeriodEnd(row, 'EXPERT')
}

async function onPayInstallment(row: any) {
  if (!canWriteCase.value) return
  await client.post(`/bureau/installments/${row.id}/pay`)
  ElMessage.success('已登记缴纳')
  load()
}

async function onSummaryRecord() {
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/summary-record`)
  ElMessage.success('已备案（第51条）')
  load()
}

async function onEConsent() {
  if (!canWriteCase.value) return
  // 后端要求 receiver/channel（第59条：电子送达须当事人签署确认书并确认送达地址），
  // 不收集就发空体等于按钮 100% 失败
  if (!eConsentForm.receiver || !eConsentForm.channel) {
    ElMessage.warning('请填写受送达人与当事人确认的电子送达地址')
    return
  }
  await client.post(`/bureau/cases/${id.value}/e-delivery-consent`, { ...eConsentForm })
  ElMessage.success('已登记电子送达确认书')
  dlg.eConsent = false
  load()
}

/** 撤销未举行的听证排期：填错日期的排期若无法撤销，decide() 会永久拦住本案 */
async function onCancelHearing(row: any) {
  if (!canWriteCase.value) return
  const { value } = await ElMessageBox.prompt('撤销理由（如：排期日期填写有误需改期／当事人撤回听证申请）',
    '撤销听证排期', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value) return
  await client.post(`/bureau/cases/${id.value}/hearings/${row.id}/cancel`, { reason: value })
  ElMessage.success('排期已撤销，可重新安排听证')
  load()
}

async function onTransferOwner() {
  if (!canWriteCase.value || !isLeader.value) return
  const { value } = await ElMessageBox.prompt('接收人登录账号（承办人变更，负责人权限）', '案件移交', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canWriteCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/transfer-owner`, { newOwner: value })
  ElMessage.success('已移交')
  load()
}

function onArchiveTerminated() {
  onCloseCase()
}
function onCloseCase() {
  closeGuard.invalidate()
  dlg.close = true
}
async function submitClose() {
  if (!canWriteCase.value) return
  if (!isLeader.value || closing.value || closeCheck.checking) return
  closing.value = true
  const caseId = id.value
  try {
    if (!await closeGuard.check()) return
    await client.post(`/bureau/cases/${caseId}/close`, { ...closeForm })
    if (caseId !== id.value) return
    ElMessage.success('已结案归档')
    dlg.close = false
    load()
  } finally { closing.value = false }
}

onMounted(load)
/** 切换案件时把流程表单一并复位：A 案填了一半的告知/决定/送达/申辩若残留到 B 案，一次误提交就是错案 */
function resetCaseLocalForms() {
  attachmentPicker = null
  uploadCategory.value = 'DOC_SCAN'
  if (fileInput.value) fileInput.value.value = ''
  docView.value = {}; docReady.value = false; catalog.value = {}; catalogReady.value = false
  invalidateApplyContext()
  selectedReview.value = null
  snapshotReview.value = null
  Object.assign(periodEndForm, { kind: 'EXCLUSION', rowId: 0, startAt: '', endAt: '', opinion: '' })
  jurisdictionForm.jurisdiction = emptyJurisdiction()
  jurisdictionForm.reason = ''
  decisionGuard.invalidate()
  closeGuard.invalidate()
  closeForm.closeReport = ''
  Object.values(FORM_RESETS).forEach((reset) => reset())
  // 复位对象必须与 759-764 行的初值逐字段对齐：漏掉 statementWaived 会把 A 案的"明确放弃陈述申辩"
  // 带进 B 案，一次误提交就在 B 案案卷里写下当事人放弃陈述申辩的假事实并解除 2076 守卫
  Object.assign(noticeForm, { content: '', proposedFine: 0, proposedRecoup: 0, proposedConfiscate: 0, changeReason: '', previousNoticeId: null })
  Object.assign(statementForm, emptyStatementForm())
  Object.assign(decisionForm, { decisionType: 'PUNISH', fineAmount: 0, recoupAmount: 0, confiscateAmount: 0, otherMeasures: '', content: '', mitigation: '', discretionReason: '', penaltyBasis: '' })
  Object.assign(deliveryForm, { method: 'DIRECT', deliveredAt: todayLocal(), receiver: '', note: '', receiptNo: '', receiptSignedAt: null })
  Object.assign(reliefForm, { deadline: '', pending: false, basis: '', deferUntil: null })
  Object.assign(courtResultForm, { status: 'ACCEPTED', receiptNo: '', receivedAt: '', note: '' })
  Object.assign(reviewForm, { reviewId: null, reviewer: '', opinionType: 'AGREE', opinion: '' })
  Object.assign(installmentForm, { seq: 1, dueAt: '', amount: 0, kind: 'FINE' })
  Object.assign(hearingForm, { noticeId: null, hearingNoticeDeliveryId: null, requireReceipt: false, announcedAt: null, noticeSentAt: todayLocal(), scheduledAt: '', host: '', hostDept: '', recorder: '' })
  Object.assign(applyForm, { kind: 'EXTEND', days: 30, reason: '' })
  Object.assign(docDeliverForm, { documentId: null, noticeId: null, hearingNoticeVersionId: null, title: '', docKind: 'OTHER', method: 'DIRECT', deliveredAt: todayLocal(), receiver: '', receiptNo: '' })
  Object.assign(eConsentForm, { receiver: '', channel: '', docNo: '' })
  reportContent.value = ''
  violationFinding.value = emptyViolationFinding()
  Object.keys(dlg).forEach((k) => { dlg[k] = false })  // 打开着的弹窗一并关掉
}

watch(() => route.params.id, (nv, ov) => {
  if (nv && nv !== ov) {
    resetCaseLocalForms()
    load()
  }
}, { flush: 'sync' })
watch(() => [auth.user?.username, auth.user?.roles?.join('|')], () => {
  if (materialDisposed) return
  resetCaseLocalForms()
  loadSeq++; loading.value = false
  caseReady.value = false; detail.value = {}; attachments.value = []; timeline.value = []; installments.value = []
  if (auth.user?.username) load()
}, { flush: 'sync' })

// ---------- 案件要素更正（留痕；仅调查阶段，法制不参与要素认定） ----------
const CORRECTION_FIELD: Record<string, string> = {
  jurisdictionType: '管辖记录类型', jurisdictionBasis: '管辖依据', violationPlace: '违法发生地',
  treatmentPlace: '就医地', insuredPlace: '参保地', designatedNo: '指定管辖文号', jurisdictionDisputeNote: '争议/协商记录',
  partyName: '当事人名称', partyCreditNo: '证照号', partyAddress: '地址', partyLegalRep: '法定代表人',
  partyContact: '联系方式', summary: '案情摘要', amountInvolved: '涉案金额', causeId: '案由', name: '案件名称',
  procedureType: '办理程序',
}
function correctionValue(field: string, value: string | null) {
  if (field === 'procedureType') return value === 'SUMMARY' ? '简易程序' : value === 'NORMAL' ? '普通程序' : value
  return value
}
const corrections = computed<any[]>(() => detail.value.corrections || [])
const canCorrect = computed(() => canWriteCase.value && ['INVESTIGATING', 'REPORTED'].includes(c.value.status)
  && !!auth.user?.roles?.some((r: string) => ['HANDLER', 'LEADER', 'ADMIN'].includes(r)))
const correcting = ref(false)
const causes = ref<any[]>([])
const correctionForm = reactive<any>({})
/** 打开弹窗时的回填快照，提交只发有变化的字段（后端无变化拒 2122） */
let correctionSnapshot: any = {}

async function openCorrection() {
  if (!canWriteCase.value) return
  if (!causes.value.length) causes.value = (await client.get('/bureau/causes')).data.data
  correctionSnapshot = {
    partyName: c.value.partyName || '', partyCreditNo: c.value.partyCreditNo || '',
    partyAddress: c.value.partyAddress || '', partyLegalRep: c.value.partyLegalRep || '',
    partyContact: c.value.partyContact || '', summary: c.value.summary || '',
    amountInvolved: Number(c.value.amountInvolved || 0), causeId: c.value.causeId,
  }
  Object.assign(correctionForm, correctionSnapshot, { reason: '' })
  dlg.correction = true
}

async function onCorrect() {
  if (!canWriteCase.value) return
  if (correcting.value) return
  const body: any = {}
  for (const k of Object.keys(correctionSnapshot)) {
    if (correctionForm[k] !== correctionSnapshot[k]) body[k] = correctionForm[k]
  }
  if (!Object.keys(body).length) {
    ElMessage.warning('没有任何要素发生变化')
    return
  }
  if (!String(correctionForm.reason || '').trim()) {
    ElMessage.warning('请填写更正理由')
    return
  }
  body.reason = correctionForm.reason
  correcting.value = true
  try {
    await client.put(`/bureau/cases/${id.value}/party-info`, body)
    ElMessage.success('已更正并留痕')
    dlg.correction = false
    load()
  } finally {
    correcting.value = false
  }
}

/** 附件作废：只打标不删文件（执法留痕），列表默认不再显示；限上传人本人或负责人，已归档案卷不可 */
async function onVoidAttachment(row: any) {
  if (!canVoidCaseAttachment(row)) return
  if (materialDisposed || voidingAttachment.value || !caseReady.value || loading.value) return
  const context = captureMaterialContext(), attachmentId = row.id, filename = row.filename
  voidingAttachment.value = true
  try {
    const { value } = await ElMessageBox.prompt(`作废理由（如：误传他案材料／版本错误重传）— ${filename}`, '附件作废',
      { inputPattern: /\S+/, inputErrorMessage: '必填', type: 'warning' })
    if (!materialContextCurrent(context) || !canWriteCase.value || !canVoidCaseAttachment(row)) return
    await client.post(`/bureau/cases/${context.caseId}/attachments/${attachmentId}/void`, { reason: value })
    if (!materialContextCurrent(context)) return
    ElMessage.success('附件已作废（记录保留，列表不再显示）')
    await load()
  } catch { /* Includes cancelled prompts and failures already reported by the interceptor. */ }
  finally { voidingAttachment.value = false }
}

/** 撤回公开：决定被复议/诉讼撤销、变更或确认违法后三日内撤回（行政处罚法第48条第2款），公示导出即不再包含 */
async function onPublishWithdraw() {
  if (!canOperateCase.value || !isLeader.value) return
  const { value } = await ElMessageBox.prompt('撤回公开的理由（撤销/变更/确认违法的复议决定或判决文号）', '撤回公开',
    { inputPattern: /\S+/, inputErrorMessage: '必填', type: 'warning' })
  if (!canOperateCase.value || !isLeader.value) return
  await client.post(`/bureau/cases/${id.value}/publish-withdraw`, { reason: value })
  ElMessage.success('已撤回公开')
  load()
}
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
