-- 二期B：文书与案卷（执法事项目录/法律依据库/文书模板/附件/节假日/追责时效/责令改正跟踪）

-- 执法事项目录（辽宁省医疗保障局行政执法事项清单 2024 年版，13 项种子）
create table law_enforce_item (
    id          bigserial primary key,
    seq_no      numeric(6,1) not null,
    name        varchar(255) not null,
    category    varchar(16)  not null,  -- PENALTY 行政处罚/COERCION 行政强制/INSPECTION 行政检查/REWARD 行政奖励
    subject_org varchar(128) not null,  -- 执法主体
    undertaker  varchar(64)  not null,  -- 承办机构
    basis_refs  varchar(512) not null,  -- 依据条文引用（法律/行政法规/规章）
    target      varchar(128),           -- 实施对象
    time_limit  varchar(255),           -- 办理时限
    enabled     boolean not null default true
);

-- 法律依据库（文书引用条文全文；实施期按正式文本核对维护）
create table law_basis (
    id        bigserial primary key,
    code      varchar(64)  not null unique,  -- 如 TL38（条例38条）
    law_name  varchar(128) not null,
    article   varchar(32)  not null,
    content   text         not null
);

-- 文书模板（要素+模板分离：实施只改模板不改代码；{{占位符}} 渲染）
create table doc_template (
    doc_type varchar(32) primary key,
    title_tpl   varchar(255) not null,
    content_tpl text         not null,
    updated_at  timestamptz  not null default now()
);

-- 案件附件（笔录扫描件/影像证据等；一期用库内存储，大体量后续外置对象存储）
create table case_attachment (
    id          bigserial primary key,
    case_id     bigint not null references case_file (id),
    document_id bigint references case_document (id),
    evidence_id bigint references case_evidence (id),
    filename    varchar(255) not null,
    content_type varchar(128),
    size_bytes  bigint not null,
    data        bytea not null,
    uploaded_by varchar(64),
    uploaded_at timestamptz not null default now()
);

-- 法定节假日/调休表（第58条：期间届满遇节假日顺延；每年按国办通知维护）
create table sys_holiday (
    day  date primary key,
    kind varchar(16) not null,  -- HOLIDAY 放假 / SHIFT_WORK 调休上班（周末补班）
    name varchar(64)
);

-- 案件：执法事项关联 + 追责时效（第6条）
alter table case_file add column enforce_item_id bigint references law_enforce_item (id);
alter table case_file add column violation_end_date date;   -- 违法行为终了日
alter table case_file add column health_harm boolean not null default false;  -- 涉及公民生命健康且有危害后果（时效5年）

-- 文书：限期跟踪（责令改正通知书等的履行期限）
alter table case_document add column due_at date;

-- 参数
insert into sys_config (cfg_key, cfg_value, remark) values
    ('liability_years',        '2', '追责时效（年，第6条：违法行为2年内未被发现不再处罚）'),
    ('liability_years_health', '5', '追责时效-涉生命健康且有危害后果（年）'),
    ('archive_completeness_required', 'false', '结案时强制校验案卷必备文书齐全（第57条）');

-- 菜单：执法事项目录 + 节假日维护
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (16, 10, '执法事项', 'MENU', '/case/enforce-items', 'case:item:list', 'List', 6),
    (6,  1,  '节假日表', 'MENU', '/system/holidays', 'sys:holiday:list', 'Calendar', 5);
insert into sys_role_menu (role_id, menu_id) select r.id, 16 from sys_role r where r.code in ('ADMIN','HANDLER','LEGAL','LEADER');
insert into sys_role_menu (role_id, menu_id) select r.id, 6 from sys_role r where r.code = 'ADMIN';

-- ============ 种子：执法事项 13 项（2024年版清单） ============
insert into law_enforce_item (seq_no, name, category, subject_org, undertaker, basis_refs, target, time_limit) values
(1, '对违反《医疗保障基金使用监督管理条例》的处罚——对造成医疗保障基金损失的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《医疗保障基金使用监督管理条例》第38条', '定点医药机构、公民', '普通程序自立案之日起90日内作出处理决定'),
(2, '对违反《医疗保障基金使用监督管理条例》的处罚——对定点医药机构未按规定进行管理和不配合检查等的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《医疗保障基金使用监督管理条例》第39条', '定点医药机构', '普通程序自立案之日起90日内作出处理决定'),
(3, '对经办机构及医药服务机构以欺诈、伪造证明材料或其他手段骗取医保、生育保险基金的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《社会保险法》第87条；《条例》第38/40条', '定点医药机构、经办机构', '普通程序自立案之日起90日内作出处理决定'),
(4, '对以欺诈、伪造证明材料或其他手段骗取医疗保险、生育保险待遇的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《社会保险法》第88条；《条例》第41条', '公民', '普通程序自立案之日起90日内作出处理决定'),
(5, '对采取虚报、隐瞒、伪造等手段骗取医疗救助资金的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《社会救助暂行办法》第68条；《条例》第2条', '公民、法人或其他组织', '普通程序自立案之日起90日内作出处理决定'),
(6, '对用人单位不办理医保登记及伪造、变造登记证明的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《社会保险法》第84条；《社会保险费征缴暂行条例》第23条', '用人单位', '普通程序自立案之日起90日内作出处理决定'),
(7, '对药品采购投标人低于成本报价、欺诈串通投标等违法行为的处罚', 'PENALTY', '市医疗保障局', '基金监管科', '《基本医疗卫生与健康促进法》第103条', '公民、法人或其他组织', '普通程序自立案之日起90日内作出处理决定'),
(8, '对可能灭失或以后难以取得的医保基金相关资料先行登记保存', 'COERCION', '市医疗保障局', '基金监管科', '《行政处罚法》第56条；《医疗保障行政处罚程序暂行规定》第26条', '公民、法人或其他组织', '7个工作日内作出处理决定'),
(9, '对可能被转移、隐匿或灭失的医保基金相关资料进行封存', 'COERCION', '市医疗保障局', '基金监管科', '《社会保险法》第79条；《医疗保障行政处罚程序暂行规定》第29条', '公民、法人或其他组织', '封存期限不得超过30日'),
(10, '对用人单位和个人遵守医疗保险法律法规情况进行监督检查', 'INSPECTION', '市医疗保障局', '基金监管科', '《社会保险法》第77条', '公民、法人或其他组织', null),
(11, '对基本医疗保险基金的收支、管理情况进行监督检查', 'INSPECTION', '市医疗保障局', '基金监管科', '《社会保险法》第79条；《条例》第6条', '公民、法人或其他组织', null),
(12, '对医疗救助的监督检查', 'INSPECTION', '市医疗保障局', '基金监管科', '《社会救助暂行办法》第57条', '公民、法人或其他组织', null),
(13, '对欺诈骗取医疗保障基金行为的举报奖励', 'REWARD', '市医疗保障局', '基金监管科', '《社会保险法》第82条；《条例》第35条', '公民、法人或其他组织', null);

-- ============ 种子：常用法律依据条文（实施期按正式文本校核） ============
insert into law_basis (code, law_name, article, content) values
('TL38', '医疗保障基金使用监督管理条例', '第三十八条', '定点医药机构有分解住院、挂床住院，违反诊疗规范过度诊疗、过度检查、分解处方、超量开药、重复开药，重复收费、超标准收费、分解项目收费，串换药品、医用耗材、诊疗项目和服务设施等情形之一的，由医疗保障行政部门责令改正，并可以约谈有关负责人；造成医疗保障基金损失的，责令退回，处造成损失金额1倍以上2倍以下的罚款；拒不改正或者造成严重后果的，责令定点医药机构暂停相关责任部门6个月以上1年以下涉及医疗保障基金使用的医药服务；违反其他法律、行政法规的，由有关主管部门依法处理。'),
('TL39', '医疗保障基金使用监督管理条例', '第三十九条', '定点医药机构有未建立医疗保障基金使用内部管理制度、未按照规定保管资料、未按照规定通过医疗保障信息系统传送数据、拒绝医疗保障等行政部门监督检查或者提供虚假情况等情形之一的，由医疗保障行政部门责令改正，并可以约谈有关负责人；拒不改正的，处1万元以上5万元以下的罚款；违反其他法律、行政法规的，由有关主管部门依法处理。'),
('TL40', '医疗保障基金使用监督管理条例', '第四十条', '定点医药机构通过诱导、协助他人冒名或者虚假就医、购药，伪造、变造、隐匿、涂改、销毁医学文书等有关资料，虚构医药服务项目等方式骗取医疗保障基金支出的，由医疗保障行政部门责令退回，处骗取金额2倍以上5倍以下的罚款；责令定点医药机构暂停相关责任部门6个月以上1年以下涉及医疗保障基金使用的医药服务，直至由医疗保障经办机构解除服务协议；有执业资格的，由有关主管部门依法吊销执业资格。'),
('TL41', '医疗保障基金使用监督管理条例', '第四十一条', '个人有将本人的医疗保障凭证交由他人冒名使用、重复享受医疗保障待遇、利用享受医疗保障待遇的机会转卖药品等情形之一的，由医疗保障行政部门责令改正；造成医疗保障基金损失的，责令退回；属于参保人员的，暂停其医疗费用联网结算3个月至12个月。个人以骗取医疗保障基金为目的，实施了前款规定行为之一，造成医疗保障基金损失的，或者使用他人医疗保障凭证冒名就医、购药的，或者通过伪造、变造、隐匿、涂改、销毁医学文书等有关资料或者虚构医药服务项目等方式骗取医疗保障基金支出的，除依照前款规定处理外，还应当由医疗保障行政部门处骗取金额2倍以上5倍以下的罚款。'),
('SB87', '中华人民共和国社会保险法', '第八十七条', '社会保险经办机构以及医疗机构、药品经营单位等社会保险服务机构以欺诈、伪造证明材料或者其他手段骗取社会保险基金支出的，由社会保险行政部门责令退回骗取的社会保险金，处骗取金额2倍以上5倍以下的罚款；属于社会保险服务机构的，解除服务协议；直接负责的主管人员和其他直接责任人员有执业资格的，依法吊销其执业资格。'),
('SB88', '中华人民共和国社会保险法', '第八十八条', '以欺诈、伪造证明材料或者其他手段骗取社会保险待遇的，由社会保险行政部门责令退回骗取的社会保险金，处骗取金额2倍以上5倍以下的罚款。'),
('SB84', '中华人民共和国社会保险法', '第八十四条', '用人单位不办理社会保险登记的，由社会保险行政部门责令限期改正；逾期不改正的，对用人单位处应缴社会保险费数额1倍以上3倍以下的罚款，对其直接负责的主管人员和其他直接责任人员处500元以上3000元以下的罚款。'),
('JZ68', '社会救助暂行办法', '第六十八条', '采取虚报、隐瞒、伪造等手段，骗取社会救助资金、物资或者服务的，由有关部门决定停止社会救助，责令退回非法获取的救助资金、物资，可以处非法获取的救助款额1倍以上3倍以下的罚款；构成违反治安管理行为的，依法给予治安管理处罚。'),
('WC103', '基本医疗卫生与健康促进法', '第一百零三条', '违反本法规定，参加药品采购投标的投标人以低于成本的报价竞标，或者以欺诈、串通投标、滥用市场支配地位等方式竞标的，由县级以上人民政府医疗保障主管部门责令改正，没收违法所得；情节严重的，责令停业整顿，并处违法所得2倍以上10倍以下的罚款，两年内禁止参加药品采购投标。');

-- ============ 种子：文书模板（{{占位符}}） ============
insert into doc_template (doc_type, title_tpl, content_tpl) values
('NOTICE', '行政处罚事先告知书（{{caseNo}}）',
'{{partyName}}：

经查，你（单位）{{summary}}，涉案金额{{amountInvolved}}元。上述行为违反了相关规定。依据：
{{basisText}}

本机关拟对你（单位）作出如下行政处罚：责令退回医疗保障基金{{proposedRecoup}}元，并处罚款{{proposedFine}}元。

根据《中华人民共和国行政处罚法》及《医疗保障行政处罚程序暂行规定》，你（单位）有权自收到本告知书之日起{{statementDays}}日内进行陈述和申辩；符合听证条件的，可在告知之日起{{hearingDays}}日内申请听证。逾期未行使视为放弃上述权利。

{{orgName}}
{{today}}'),
('DECISION', '行政处罚决定书（{{decisionNo}}）',
'当事人：{{partyName}}　地址：{{partyAddress}}　统一社会信用代码/证件号：{{partyCreditNo}}

一、违法事实和证据
{{summary}}

二、处罚依据
{{basisText}}

三、处罚决定
{{decisionContent}}

四、履行方式和期限
罚款自收到本决定书之日起15日内缴至指定账户；退回基金退至医疗保障基金财政专户。逾期不缴纳罚款的，每日按罚款数额的3%加处罚款。

如不服本决定，可自收到本决定书之日起60日内向本级人民政府或上一级医疗保障行政部门申请行政复议，或者6个月内依法向人民法院提起行政诉讼。复议诉讼期间本决定不停止执行。

{{orgName}}
{{today}}'),
('FINAL_REPORT', '案件调查终结报告（{{caseNo}}）',
'一、当事人基本情况
{{partyName}}（{{partyTypeText}}），地址：{{partyAddress}}，法定代表人：{{partyLegalRep}}。

二、案件来源、调查经过及采取行政强制措施的情况
（案件来源与调查经过）

三、调查认定的事实及主要证据
{{summary}}

四、违法行为性质（案由）
{{causeText}}

五、处理意见及依据
{{basisText}}

六、自由裁量的理由、办案人员资格等其他需要说明的事项
（裁量理由；办案人员均持有效执法证件）'),
('ORDER_CORRECT', '责令改正通知书（{{caseNo}}）',
'{{partyName}}：

经查，你（单位）存在以下违法行为：{{summary}}

依据{{basisText}}，责令你（单位）自收到本通知书之日起改正上述违法行为，并于{{dueAt}}前将改正情况书面报告本机关。逾期不改正的，本机关将依法处理。

{{orgName}}
{{today}}'),
('CLOSE_REPORT', '行政处罚结案报告（{{caseNo}}）',
'案件名称：{{caseName}}
决定书文号：{{decisionNo}}
处罚决定：{{decisionContent}}
执行情况：罚没款项已执行/已申请人民法院强制执行/无须执行。
归档情况：一案一卷，文书齐全，按顺序装订。
承办机构意见：同意结案。'),
('ASSIST_LETTER', '协助调查函（{{caseNo}}）',
'（受函单位）：

我局在办理{{caseName}}（案号{{caseNo}}）中，需贵局协助调查取证。请于收到本函之日起15日内完成协查并函复。需要延期完成或者无法协助的，请在期限届满前告知我局。

{{orgName}}
{{today}}');

-- ============ 种子：2026 年节假日（示例，须按国办通知每年校核维护） ============
insert into sys_holiday (day, kind, name) values
('2026-01-01', 'HOLIDAY', '元旦'),
('2026-01-02', 'HOLIDAY', '元旦'),
('2026-02-16', 'HOLIDAY', '春节'), ('2026-02-17', 'HOLIDAY', '春节'), ('2026-02-18', 'HOLIDAY', '春节'),
('2026-02-19', 'HOLIDAY', '春节'), ('2026-02-20', 'HOLIDAY', '春节'), ('2026-02-21', 'HOLIDAY', '春节'), ('2026-02-22', 'HOLIDAY', '春节'),
('2026-04-05', 'HOLIDAY', '清明节'), ('2026-04-06', 'HOLIDAY', '清明节'),
('2026-05-01', 'HOLIDAY', '劳动节'), ('2026-05-04', 'HOLIDAY', '劳动节'), ('2026-05-05', 'HOLIDAY', '劳动节'),
('2026-06-19', 'HOLIDAY', '端午节'),
('2026-09-25', 'HOLIDAY', '中秋节'),
('2026-10-01', 'HOLIDAY', '国庆节'), ('2026-10-02', 'HOLIDAY', '国庆节'), ('2026-10-05', 'HOLIDAY', '国庆节'),
('2026-10-06', 'HOLIDAY', '国庆节'), ('2026-10-07', 'HOLIDAY', '国庆节');
