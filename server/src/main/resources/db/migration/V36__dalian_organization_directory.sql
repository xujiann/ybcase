-- Administrative organization reference from the June 2025 Dalian presentation, slides 4–8.
-- New keys preserve all legacy departments, accounts and their assignments.
alter table sys_dept
    add column org_kind varchar(16) check (org_kind in ('ROOT', 'BUREAU', 'DISTRICT', 'AGENCY')),
    add column responsibility varchar(1000);

alter table sys_user drop constraint sys_user_account_category_check;
alter table sys_user add constraint sys_user_account_category_check
    check (account_category in ('DISTRICT', 'BUREAU', 'AGENCY'));

insert into sys_dept (name, code, type, sort_no, org_kind)
values ('大连市医疗保障局', 'DL_YB', 'ADMIN', 10, 'ROOT');

insert into sys_dept (parent_id, name, code, type, sort_no, org_kind)
select parent.id, node.name, node.code, 'ADMIN', node.sort_no, 'ROOT'
from sys_dept parent cross join (values
    ('市局机关', 'DL_YB_OFFICES', 11),
    ('直属分局', 'DL_YB_BRANCHES', 12),
    ('主城区监管协同', 'DL_YB_URBAN', 13),
    ('大连市医疗保障事务服务中心', 'DL_YB_CENTER', 14)
) as node(name, code, sort_no)
where parent.code = 'DL_YB';

insert into sys_dept (parent_id, name, code, type, sort_no, org_kind, responsibility)
select parent.id, unit.name, unit.code, 'ADMIN', unit.sort_no, unit.kind, unit.duty
from (values
    ('DL_YB_OFFICES', '机关党委办公室（综合处）', 'DL_OFF_GENERAL', 21, 'BUREAU', null),
    ('DL_YB_OFFICES', '规划财务与法规处', 'DL_OFF_LEGAL', 22, 'BUREAU', '基金运行管理、审计和法制审核'),
    ('DL_YB_OFFICES', '待遇保障处', 'DL_OFF_BENEFITS', 23, 'BUREAU', null),
    ('DL_YB_OFFICES', '医药服务管理处', 'DL_OFF_SERVICES', 24, 'BUREAU', null),
    ('DL_YB_OFFICES', '医药价格和招标采购处', 'DL_OFF_PRICING', 25, 'BUREAU', null),
    ('DL_YB_OFFICES', '基金监管一处', 'DL_OFF_FUND_1', 26, 'BUREAU', '全市医保基金监管综合协调，统筹跨区检查'),
    ('DL_YB_OFFICES', '基金监管二处', 'DL_OFF_FUND_2', 27, 'BUREAU', '主城区定点三级医院医保基金监管'),
    ('DL_YB_OFFICES', '基金监管三处', 'DL_OFF_FUND_3', 28, 'BUREAU', '中山区、西岗区、沙河口区除三级医院外的定点医药机构医保基金监管'),
    ('DL_YB_OFFICES', '基金监管四处', 'DL_OFF_FUND_4', 29, 'BUREAU', '甘井子区、高新园区除三级医院外的定点医药机构医保基金监管'),
    ('DL_YB_OFFICES', '基金监管五处', 'DL_OFF_FUND_5', 30, 'BUREAU', '大数据监管平台规则制定和评估、日常数据核查'),
    ('DL_YB_BRANCHES', '旅顺口分局', 'DL_BR_LVSHUN', 31, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_BRANCHES', '普兰店分局', 'DL_BR_PULANDIAN', 32, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_BRANCHES', '金普新区分局', 'DL_BR_JINPU', 33, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_BRANCHES', '长兴岛经济区分局', 'DL_BR_CHANGXING', 34, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_BRANCHES', '瓦房店分局', 'DL_BR_WAFANGDIAN', 35, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_BRANCHES', '庄河分局', 'DL_BR_ZHUANGHE', 36, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_BRANCHES', '长海分局', 'DL_BR_CHANGHAI', 37, 'DISTRICT', '辖区监督检查、投诉举报处置、协调和统计报送'),
    ('DL_YB_URBAN', '中山分局', 'DL_BR_ZHONGSHAN', 41, 'DISTRICT', '主城区监管协同'),
    ('DL_YB_URBAN', '西岗分局', 'DL_BR_XIGANG', 42, 'DISTRICT', '主城区监管协同'),
    ('DL_YB_URBAN', '沙河口分局', 'DL_BR_SHAHEKOU', 43, 'DISTRICT', '主城区监管协同'),
    ('DL_YB_URBAN', '甘井子分局', 'DL_BR_GANJINGZI', 44, 'DISTRICT', '主城区监管协同'),
    ('DL_YB_URBAN', '高新区分局', 'DL_BR_GAOXIN', 45, 'DISTRICT', '主城区监管协同'),
    ('DL_YB_CENTER', '核查一部', 'DL_CENTER_CHECK_1', 51, 'AGENCY', '定点医药机构现场检查、协议处理和线上审核'),
    ('DL_YB_CENTER', '核查二部', 'DL_CENTER_CHECK_2', 52, 'AGENCY', '定点医药机构现场检查、协议处理和线上审核'),
    ('DL_YB_CENTER', '核查三部', 'DL_CENTER_CHECK_3', 53, 'AGENCY', '定点医药机构现场检查、协议处理和线上审核')
) as unit(parent_code, name, code, sort_no, kind, duty)
join sys_dept parent on parent.code = unit.parent_code;
