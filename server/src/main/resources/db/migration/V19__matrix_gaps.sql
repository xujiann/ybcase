-- 第五轮矩阵清点整改：菜单授权与后端角色闸门对齐、补法定文书模板骨架、死参数接线

-- ① 督办看板(14)与举报奖励(19)后端限监督岗(LEADER/LEGAL/ADMIN)，菜单却授予 HANDLER，
--    办案员菜单可见、点开必 403。收回办案员这两项菜单，与后端闸门一致。
delete from sys_role_menu
where menu_id in (14, 19)
  and role_id in (select id from sys_role where code = 'HANDLER');

-- ② 补齐法定文书模板骨架（正文须由局方按正式文本核定后在“文书模板”页维护）。
--    此前 render 这些类型会抛 2053，办案员只能手工录入且不进齐全性检查。
insert into doc_template (doc_type, title_tpl, content_tpl) values
    ('SUSPEND_DECISION', '{{caseNo}} 中止调查决定书',
     '当事人：{{partyName}}（统一社会信用代码/身份证号：{{partyCreditNo}}）' || chr(10) ||
     '本机关办理的{{caseName}}（案号：{{caseNo}}），因下列法定情形，决定中止调查：' || chr(10) ||
     '中止事由：（请填写第42条规定的中止情形）' || chr(10) ||
     '中止期间不计入办案期限。中止原因消除后，本机关将恢复调查。' || chr(10) ||
     '（本模板为骨架，须按本地正式文本核定后使用）' || chr(10) ||
     '{{orgName}}' || chr(10) || '{{today}}'),
    ('RESUME_NOTICE', '{{caseNo}} 恢复调查通知书',
     '当事人：{{partyName}}' || chr(10) ||
     '本机关办理的{{caseName}}（案号：{{caseNo}}）中止事由已消除，自即日起恢复调查，' ||
     '办案期限相应顺延。' || chr(10) ||
     '（本模板为骨架，须按本地正式文本核定后使用）' || chr(10) ||
     '{{orgName}}' || chr(10) || '{{today}}'),
    ('TERMINATE_DECISION', '{{caseNo}} 终止调查决定书',
     '当事人：{{partyName}}' || chr(10) ||
     '本机关办理的{{caseName}}（案号：{{caseNo}}），因下列法定情形，决定终止调查：' || chr(10) ||
     '终止事由：（请填写第47条规定的终止情形）' || chr(10) ||
     '已采取的先行登记保存、封存等行政强制措施同时解除。' || chr(10) ||
     '（本模板为骨架，须按本地正式文本核定后使用）' || chr(10) ||
     '{{orgName}}' || chr(10) || '{{today}}'),
    ('PRESERVE_DECISION', '{{caseNo}} 先行登记保存决定书',
     '当事人：{{partyName}}' || chr(10) ||
     '在办理{{caseName}}过程中，因证据可能灭失或者以后难以取得，经本机关负责人批准，' ||
     '决定对下列证据先行登记保存（清单附后）。' || chr(10) ||
     '本机关将在七日内作出处理决定。' || chr(10) ||
     '（本模板为骨架，须按本地正式文本核定后使用）' || chr(10) ||
     '{{orgName}}' || chr(10) || '{{today}}'),
    ('SEAL_DECISION', '{{caseNo}} 封存决定书',
     '当事人：{{partyName}}' || chr(10) ||
     '在办理{{caseName}}过程中，经本机关负责人批准，决定对下列物品/资料予以封存（清单附后）。' || chr(10) ||
     '封存期限自本决定书送达之日起计算，期满前本机关将作出处理决定。' || chr(10) ||
     '（本模板为骨架，须按本地正式文本核定后使用）' || chr(10) ||
     '{{orgName}}' || chr(10) || '{{today}}')
on conflict (doc_type) do nothing;

-- ③ 听证意见期限参数此前无任何代码读取（死参数），现由 BureauStatsController 读取生效
update sys_config set remark = '听证意见提出期限（日，辽50条）：督办看板据此预警'
where cfg_key = 'hearing_opinion_days';

-- ④ 新增守卫所需参数（矩阵整改：法院强执前置、时效必填、送达工作日）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('court_urge_days',            '10',   '申请法院强制执行前的催告期（日，行政强制法54条）'),
    ('payment_days',               '15',   '决定书送达后的缴款期（日）：加处罚款与强执申请起算'),
    ('delivery_days',              '7',    '决定书送达期限（工作日，第59条）：督办看板按节假日表精算'),
    ('violation_end_date_required','false','立案必填违法行为终了日（第6条时效判定）。默认关闭：开启后不填终了日即无法立案，'
                                          || '属破坏性变更，须待存量案件补齐终了日、且与办案人员达成一致后由管理员开启')
on conflict (cfg_key) do nothing;

-- ⑤ 催告书文书类型（法院强执前置要件，行政强制法54条）
insert into doc_template (doc_type, title_tpl, content_tpl) values
    ('URGE_LETTER', '{{caseNo}} 履行行政处罚决定催告书',
     '当事人：{{partyName}}' || chr(10) ||
     '本机关于{{today}}作出的{{decisionNo}}行政处罚决定书已送达，你（单位）逾期未履行缴纳义务。' || chr(10) ||
     '现依法催告你（单位）自收到本催告书之日起十日内履行；逾期仍不履行的，' ||
     '本机关将依法申请人民法院强制执行。' || chr(10) ||
     '你（单位）有权在收到本催告书之日起十日内进行陈述和申辩。' || chr(10) ||
     '（本模板为骨架，须按本地正式文本核定后使用）' || chr(10) ||
     '{{orgName}}' || chr(10) || '{{today}}')
on conflict (doc_type) do nothing;
