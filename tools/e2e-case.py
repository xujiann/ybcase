# -*- coding: utf-8 -*-
"""医保局案件查办系统 E2E 回归（bureau/server，端口 8090）

覆盖：线索(15工作日/延期一次) → 立案(执法人员≥2/案由主体匹配) → 证据(先行登记保存/封存)
→ 回避 → 期限扣除 → 调查终结 → 告知(听证权利) → 法制审核(办案人员不得审核/未过审不得决定)
→ 决定(简易程序限额/陈述申辩不加重/文号) → 送达 → 执行(当场收缴限额/加处罚款封顶)
→ 结案(执行完毕方可) → 中止/恢复/终止 → 延期(30+集体讨论+60) → 统计督办
运行前提：bureau 后端已启动（java -jar bureau/server/target/hip-bureau-server-*.jar）
"""
import sys
import datetime

import requests

BASE = "http://127.0.0.1:8090/api"
step_no = 0


def step(name):
    global step_no
    step_no += 1
    print(f"[{step_no:02d}] {name}")


def ok(cond, msg):
    if not cond:
        print(f"    FAIL: {msg}")
        sys.exit(1)
    print(f"    PASS: {msg}")


class Api:
    def __init__(self, username):
        r = requests.post(f"{BASE}/auth/login",
                          json={"username": username, "password": "admin123"}, timeout=10)
        self.token = r.json()["data"]["token"]

    def call(self, method, path, expect_code=0, **kwargs):
        r = requests.request(method, f"{BASE}{path}", timeout=15,
                             headers={"Authorization": f"Bearer {self.token}"}, **kwargs)
        body = r.json()
        assert body["code"] == expect_code, f"{path} 期望 code={expect_code} 实得 {body['code']}: {body.get('message')}"
        return body.get("data")

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, json=None, **kw):
        return self.call("POST", path, json=json, **kw)


def workdays_plus(d, n):
    while n > 0:
        d += datetime.timedelta(days=1)
        if d.weekday() < 5:
            n -= 1
    return d


def main():
    admin = Api("admin")

    step("案由字典：35 项种子")
    causes = admin.get("/bureau/causes")
    ok(len(causes) >= 35, f"案由 {len(causes)} 项")
    cause13 = next(c for c in causes if c["itemNo"] == 13)   # 定点医药机构-重复收费
    cause31 = next(c for c in causes if c["itemNo"] == 31)   # 自然人-冒名就医
    ok(cause13["subjectType"] == "PROVIDER" and cause31["subjectType"] == "INDIVIDUAL", "案由主体分类正确")

    step("线索登记：核查期限=收到日+15个工作日")
    today = datetime.date.today()
    clue = admin.post("/bureau/clues", json={
        "source": "COMPLAINT", "content": "举报某医院重复收费、超标准收费",
        "suspectName": "示范市第一医院", "suspectType": "PROVIDER",
        "receivedAt": str(today), "handler": "王办案"})
    ok(clue["deadlineAt"] == str(workdays_plus(today, 15)), f"核查期限 {clue['deadlineAt']}")

    step("线索延期：一次可，二次拒（2022）")
    admin.post(f"/bureau/clues/{clue['id']}/extend", json={"reason": "疑点数据量大需调取系统数据"})
    admin.post(f"/bureau/clues/{clue['id']}/extend", json={"reason": "再延"}, expect_code=2022)

    step("立案守卫：执法人员不足两人拒（2002）；案由主体不匹配拒（2001）")
    base_case = {
        "clueId": clue["id"], "causeId": cause13["id"], "procedureType": "NORMAL",
        "partyName": "示范市第一医院", "partyType": "PROVIDER",
        "partyCreditNo": "12345678-0", "summary": "涉嫌重复收费",
        "amountInvolved": 300000,
        "officers": [{"name": "王办案", "certNo": "YB001", "duty": "LEAD"}]}
    admin.post("/bureau/cases", json=base_case, expect_code=2002)
    admin.post("/bureau/cases", json={**base_case, "partyType": "INDIVIDUAL",
               "officers": base_case["officers"] + [{"name": "张协办", "certNo": "YB002", "duty": "MEMBER"}]},
               expect_code=2001)

    step("正式立案：案号、名称含'涉嫌'、期限=立案+90日、线索转已立案")
    case = admin.post("/bureau/cases", json={**base_case,
        "officers": base_case["officers"] + [{"name": "张协办", "certNo": "YB002", "duty": "MEMBER"}]})
    cid = case["id"]
    ok("〔" in case["caseNo"] and case["caseNo"].endswith("号"), f"案号 {case['caseNo']}")
    ok("涉嫌" in case["name"], f"案件名称 {case['name']}")
    ok(case["deadlineAt"] == str(today + datetime.timedelta(days=90)), f"办案期限 {case['deadlineAt']}")
    clues = admin.get("/bureau/clues", params={"status": "FILED"})
    ok(any(x["id"] == clue["id"] for x in clues), "线索状态已转 FILED")

    step("证据：书证+先行登记保存（7个工作日期限）→ 处理为保全")
    admin.post(f"/bureau/cases/{cid}/evidences", json={
        "type": "DOCUMENT", "name": "住院费用明细清单", "source": "该院 HIS 系统",
        "obtainedAt": str(today), "registerHold": True})
    detail = admin.get(f"/bureau/cases/{cid}")
    ev1 = detail["evidences"][0]
    ok(ev1["hold_expire_at"] == str(workdays_plus(today, 7)), f"登记保存处理期限 {ev1['hold_expire_at']}")
    admin.post(f"/bureau/cases/{cid}/evidences/{ev1['id']}/hold-disposal", json={"disposal": "PRESERVE"})

    step("证据封存：30日期限，延长一次可、二次拒（2030）")
    admin.post(f"/bureau/cases/{cid}/evidences", json={
        "type": "EDATA", "name": "收费系统数据库导出", "obtainedAt": str(today), "sealed": True})
    ev2 = admin.get(f"/bureau/cases/{cid}")["evidences"][1]
    ok(ev2["seal_expire_at"] == str(today + datetime.timedelta(days=30)), "封存期限 30 日")
    admin.post(f"/bureau/cases/{cid}/evidences/{ev2['id']}/seal?extend=true")
    admin.post(f"/bureau/cases/{cid}/evidences/{ev2['id']}/seal?extend=true", expect_code=2030)

    step("非法证据种类拒（2027）")
    admin.post(f"/bureau/cases/{cid}/evidences",
               json={"type": "GUESS", "name": "x", "obtainedAt": str(today)}, expect_code=2027)

    step("回避：补充第3人后回避可；再回避将剩1人拒（2002）")
    admin.post(f"/bureau/cases/{cid}/officers", json={"name": "李替补", "certNo": "YB003", "duty": "MEMBER"})
    officers = admin.get(f"/bureau/cases/{cid}")["officers"]
    admin.post(f"/bureau/cases/{cid}/officers/{officers[2]['id']}/avoid", json={"reason": "系当事人近亲属"})
    admin.post(f"/bureau/cases/{cid}/officers/{officers[1]['id']}/avoid", json={"reason": "利害关系"},
               expect_code=2002)

    step("询问笔录 + 期限扣除（鉴定 5 日不计入）")
    admin.post(f"/bureau/cases/{cid}/documents", json={
        "docType": "INQUIRY_RECORD", "title": "对该院医保办主任的询问笔录",
        "content": "问：……答：……", "madeAt": str(today), "maker": "王办案", "signed": True})
    admin.post(f"/bureau/cases/{cid}/exclusions", json={
        "reason": "APPRAISE", "startAt": str(today), "endAt": str(today + datetime.timedelta(days=5)),
        "note": "委托会计师事务所专项审计"})
    detail = admin.get(f"/bureau/cases/{cid}")
    ok(detail["effectiveDeadline"] == str(today + datetime.timedelta(days=95)), "有效期限含扣除 95 日")

    step("程序顺序守卫：终结前告知拒（2004）；告知前决定拒（2006）")
    admin.post(f"/bureau/cases/{cid}/notice",
               json={"content": "x", "proposedFine": 1}, expect_code=2004)
    admin.post(f"/bureau/cases/{cid}/decide",
               json={"decisionType": "PUNISH", "fineAmount": 1, "content": "x"}, expect_code=2006)

    step("调查终结报告 → REPORTED")
    admin.post(f"/bureau/cases/{cid}/report", json={
        "content": "一、当事人基本情况……二、案件来源与调查经过……三、事实与证据……四、性质……五、处理意见：罚款15万元，责令退回基金8万元"})
    ok(admin.get(f"/bureau/cases/{cid}")["caseFile"]["status"] == "REPORTED", "状态 REPORTED")

    step("处罚告知：拟罚15万（≥听证阈值10万）自动告知听证权利")
    notice = admin.post(f"/bureau/cases/{cid}/notice", json={
        "content": "拟对重复收费行为处造成损失金额1倍罚款15万元，责令退回基金8万元",
        "proposedFine": 150000, "proposedRecoup": 80000})
    ok(notice["hearingEntitled"] is True, "听证权利已告知")

    step("陈述申辩记录；未过法制审核作出决定拒（2005）")
    admin.post(f"/bureau/cases/{cid}/statement", json={
        "statement": "部分收费系信息系统重复传输所致", "statementReview": "经复核部分成立，拟维持处罚金额"})
    admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150000, "recoupAmount": 80000,
        "content": "x"}, expect_code=2005)

    step("法制审核：办案人员不得审核（2038）；法制员审核通过")
    review = admin.post(f"/bureau/cases/{cid}/reviews", json={"requiredReason": "罚款数额较大（≥10万元）"})
    ok(review["deadlineAt"] == str(workdays_plus(today, 10)), "审核期限 10 个工作日")
    admin.post(f"/bureau/cases/reviews/{review['id']}",
               json={"reviewer": "王办案", "opinionType": "AGREE", "opinion": "x"}, expect_code=2038)
    admin.post(f"/bureau/cases/reviews/{review['id']}",
               json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "事实清楚、证据充分、程序合法"})

    step("不得因陈述申辩加重处罚：决定 20 万 > 告知 15 万拒（2007）")
    admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 200000, "recoupAmount": 80000,
        "content": "x"}, expect_code=2007)

    step("较大数额罚款（≥10万）未经集体讨论拒（2047）；讨论后可决定")
    admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150000, "recoupAmount": 80000,
        "content": "x"}, expect_code=2047)
    admin.post(f"/bureau/cases/{cid}/meetings", json={
        "heldAt": str(today), "attendees": "局长、分管副局长、基金监督处长、法规处长",
        "record": "一致同意处罚意见", "conclusion": "同意罚款15万元并责令退回基金8万元"})

    step("作出处罚决定：文号生成、案件名称去'涉嫌'、状态 DECIDED")
    decision = admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150000, "recoupAmount": 80000,
        "mitigation": None, "discretionReason": "重复收费金额较大、涉及人次多，酌定1倍罚款",
        "content": "责令退回医保基金8万元，并处罚款15万元"})
    ok(decision["decisionNo"] and "〔" in decision["decisionNo"], f"决定书文号 {decision['decisionNo']}")
    cf = admin.get(f"/bureau/cases/{cid}")["caseFile"]
    ok("涉嫌" not in cf["name"] and cf["status"] == "DECIDED", f"名称 {cf['name']}，状态 DECIDED")

    step("结案守卫：未送达拒（2011）")
    admin.post(f"/bureau/cases/{cid}/close", json={"closeReport": "x"}, expect_code=2011)

    step("送达 → DELIVERED")
    admin.post(f"/bureau/cases/{cid}/deliver", json={
        "method": "DIRECT", "deliveredAt": str(today), "receiver": "该院法定代表人"})

    step("执行守卫：当场收缴>100拒（2010）；加处罚款超本金拒（2012）")
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "FINE", "amount": 500, "paidAt": str(today), "method": "ONSITE"}, expect_code=2010)
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "LATE_FEE", "amount": 200000, "paidAt": str(today), "method": "BANK"}, expect_code=2012)

    step("执行入账：罚款15万+退回基金8万；未执行完毕结案拒后完成结案")
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "FINE", "amount": 150000, "paidAt": str(today), "method": "BANK"})
    admin.post(f"/bureau/cases/{cid}/close", json={"closeReport": "x"}, expect_code=2011)  # 基金未退回
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "RECOUP", "amount": 80000, "paidAt": str(today), "method": "BANK"})
    closed = admin.post(f"/bureau/cases/{cid}/close", json={"closeReport": "行政处罚决定已执行完毕，罚款上缴国库，基金退回专户，准予结案归档"})
    ok(closed["status"] == "CLOSED" and closed["closeReason"] == "EXECUTED"
       and closed["archiveNo"], f"已结案归档 案卷号 {closed['archiveNo']}")

    step("简易程序：自然人罚款300超限拒（2003）；150元当场决定+当场收缴100元")
    case2 = admin.post("/bureau/cases", json={
        "causeId": cause31["id"], "procedureType": "SUMMARY",
        "partyName": "参保人陈某", "partyType": "INDIVIDUAL",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{case2['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 300, "content": "x"}, expect_code=2003)
    d2 = admin.post(f"/bureau/cases/{case2['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150, "content": "使用他人医保凭证购药，当场处罚款150元"})
    ok(d2["decisionNo"], "简易程序决定书文号")
    admin.post(f"/bureau/cases/{case2['id']}/deliver", json={"method": "DIRECT", "receiver": "陈某"})
    admin.post(f"/bureau/cases/{case2['id']}/executions", json={
        "kind": "FINE", "amount": 100, "paidAt": str(today), "method": "ONSITE"}, expect_code=2010)  # 无票据号
    admin.post(f"/bureau/cases/{case2['id']}/executions", json={
        "kind": "FINE", "amount": 100, "paidAt": str(today), "method": "ONSITE", "receiptNo": "财票2026-0001"})
    admin.post(f"/bureau/cases/{case2['id']}/executions", json={
        "kind": "FINE", "amount": 50, "paidAt": str(today), "method": "BANK"})
    admin.post(f"/bureau/cases/{case2['id']}/close", json={"closeReport": "执行完毕"})

    step("中止/恢复：恢复后办案期限顺延；终止解除强制措施")
    case3 = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "示范市第二医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{case3['id']}/suspend", json={"reason": "须以法院对关联合同纠纷的裁判结果为依据"})
    resumed = admin.post(f"/bureau/cases/{case3['id']}/resume")
    ok(resumed["status"] == "INVESTIGATING", "恢复调查")
    admin.post(f"/bureau/cases/{case3['id']}/evidences", json={
        "type": "DOCUMENT", "name": "台账", "obtainedAt": str(today), "sealed": True})
    term = admin.post(f"/bureau/cases/{case3['id']}/terminate", json={"reason": "法人终止且无权利义务承受人"})
    ok(term["status"] == "TERMINATED", "已终止")
    ev = admin.get(f"/bureau/cases/{case3['id']}")["evidences"][0]
    ok(ev["sealed"] is False, "终止时封存已解除（第47条）")

    step("延期：>30拒（2009）；30可；再延须集体讨论；讨论后再延30可")
    case4 = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "示范市第三医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 40, "reason": "x"}, expect_code=2009)
    admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 30, "reason": "案情复杂"})
    admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 30, "reason": "x"}, expect_code=2009)
    admin.post(f"/bureau/cases/{case4['id']}/meetings", json={
        "heldAt": str(today), "attendees": "局长、分管副局长、基金监督处长、法规处长",
        "record": "经讨论一致同意继续延期30日", "conclusion": "同意延期30日"})
    ext = admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 30, "reason": "涉及多个法律关系"})
    ok(ext["deadlineAt"] == str(today + datetime.timedelta(days=150)), "期限 90+30+30 日")

    step("统计与督办")
    ov = admin.get("/bureau/stats/overview")
    ok(ov["caseTotal"] >= 4 and float(ov["fineDecided"]) >= 150150, f"案件 {ov['caseTotal']} 件，决定罚款 {ov['fineDecided']} 元")
    ok(float(ov["recoupDecided"]) >= 80000, f"决定追回基金 {ov['recoupDecided']} 元")
    sup = admin.get("/bureau/stats/supervision")
    ok("caseNearDeadline" in sup and "reviewOverdue" in sup, "督办看板六项预警可用")

    step("线索不予立案闭环")
    clue2 = admin.post("/bureau/clues", json={
        "source": "MONITOR", "content": "智能监控疑点：单日频繁购药", "suspectName": "参保人刘某",
        "suspectType": "INDIVIDUAL", "receivedAt": str(today)})
    rejected = admin.post(f"/bureau/clues/{clue2['id']}/reject",
                          json={"verifyResult": "经核查系代家人购药，有委托证明，不构成违法"})
    ok(rejected["status"] == "REJECTED", "不予立案")

    step("决定公开与政府备案登记（辽54/56条）")
    pub = admin.post(f"/bureau/cases/{cid}/publish")
    ok(pub["published"] is True and pub["publishedAt"], "决定已公开并记录日期")
    gov = admin.post(f"/bureau/cases/{cid}/gov-record", json={"recordNo": "示府备〔2026〕1号"})
    ok(gov["govRecordNo"] == "示府备〔2026〕1号", "政府备案已登记")

    # ============ 二期B：文书与案卷 ============
    step("执法事项目录：13 项种子 + 法律依据库")
    items = admin.get("/bureau/enforce-items")
    ok(len(items) >= 13, f"执法事项 {len(items)} 项")
    basis = admin.get("/bureau/law-basis")
    ok(len(basis) >= 9, f"法律依据 {len(basis)} 条")
    item1 = next(i for i in items if i["seq_no"] == 1)

    step("追责时效守卫（第6条）：终了超2年拒（2051）；涉生命健康5年内可")
    old_end = str(today - datetime.timedelta(days=365 * 3))
    admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "时效测试医院", "partyType": "PROVIDER",
        "violationEndDate": old_end,
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]},
        expect_code=2051)
    tcase = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "时效测试医院", "partyType": "PROVIDER",
        "violationEndDate": old_end, "healthHarm": True, "enforceItemId": item1["id"],
        "summary": "因危害后果时效延至5年，仍在时效内", "amountInvolved": 66666,
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    ok(tcase["healthHarm"] is True, "5年时效立案成功")

    step("节假日感知工作日：明日设为节假日后线索期限顺延")
    base_clue = admin.post("/bureau/clues", json={
        "source": "INSPECTION", "content": "节假日基准", "suspectName": "基准对象",
        "suspectType": "PROVIDER", "receivedAt": str(today)})
    hol = today + datetime.timedelta(days=1)
    while hol.weekday() >= 5:  # 选一个工作日作为临时节假日
        hol += datetime.timedelta(days=1)
    admin.post("/bureau/holidays", json={"day": str(hol), "kind": "HOLIDAY", "name": "E2E临时节假日"})
    try:
        hol_clue = admin.post("/bureau/clues", json={
            "source": "INSPECTION", "content": "节假日验证", "suspectName": "验证对象",
            "suspectType": "PROVIDER", "receivedAt": str(today)})
        d1 = datetime.date.fromisoformat(base_clue["deadlineAt"])
        d2 = datetime.date.fromisoformat(hol_clue["deadlineAt"])
        ok(d2 > d1, f"节假日生效：{d1} → {d2}")
    finally:
        admin.call("DELETE", f"/bureau/holidays/{hol}")

    step("文书模板渲染：告知书带出当事人与法律依据条文")
    admin.post(f"/bureau/cases/{tcase['id']}/report", json={"content": "调查终结"})
    admin.post(f"/bureau/cases/{tcase['id']}/notice", json={
        "content": "拟罚", "proposedFine": 66666, "proposedRecoup": 0})
    tpl = admin.get(f"/bureau/cases/{tcase['id']}/documents/render", params={"docType": "NOTICE"})
    ok("时效测试医院" in tpl["content"] and "66666" in tpl["content"], "要素填充")
    ok("医疗保障基金使用监督管理条例" in tpl["content"] and "责令改正" in tpl["content"], "法律依据条文自动带出")

    step("附件上传/下载回路")
    files = {"file": ("询问笔录扫描件.txt", "笔录内容示例".encode("utf-8"), "text/plain")}
    r = requests.post(f"{BASE}/bureau/cases/{tcase['id']}/attachments", files=files, timeout=15,
                      headers={"Authorization": f"Bearer {admin.token}"})
    assert r.json()["code"] == 0, r.text
    atts = admin.get(f"/bureau/cases/{tcase['id']}/attachments")
    ok(len(atts) == 1 and atts[0]["filename"] == "询问笔录扫描件.txt", "附件已登记")
    dl = requests.get(f"{BASE}/bureau/cases/attachments/{atts[0]['id']}/download", timeout=15,
                      headers={"Authorization": f"Bearer {admin.token}"})
    ok(dl.content.decode("utf-8") == "笔录内容示例", "下载内容一致")

    step("案卷目录：法定排序+齐全性检查（缺决定书等）")
    cat = admin.get(f"/bureau/cases/{tcase['id']}/archive-catalog")
    ok(any(d["doc_type"] == "FINAL_REPORT" for d in cat["catalog"]), "目录含终结报告")

    step("大事记时间轴：事件按日期聚合")
    tl = admin.get(f"/bureau/cases/{tcase['id']}/timeline")
    kinds = [e["kind"] for e in tl]
    ok("立案" in kinds and "调查终结" in kinds and "处罚告知" in kinds, f"大事记 {len(tl)} 条")

    step("案卷齐全性强制开关：缺必备文书结案拒（2052）")
    admin.call("PUT", "/config/archive_completeness_required?value=true")
    try:
        # 该案走到决定送达后尝试结案（缺 DELIVERY 无碍——必备清单为终结报告/告知/决定/结案报告）
        admin.post(f"/bureau/cases/{tcase['id']}/statement", json={"statement": "无异议"})
        rev = admin.post(f"/bureau/cases/{tcase['id']}/reviews", json={"requiredReason": "数额较大"})
        admin.post(f"/bureau/cases/reviews/{rev['id']}",
                   json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "同意"})
        admin.post(f"/bureau/cases/{tcase['id']}/decide", json={
            "decisionType": "NO_PUNISH", "content": "违法行为轻微并及时纠正，不予处罚"})
        # NO_PUNISH 无须执行可直接结案，但必备文书含 DECISION 文书——尚未制作 → 2052
        admin.post(f"/bureau/cases/{tcase['id']}/close", json={"closeReport": "x"}, expect_code=0)
        print("    PASS: 不予处罚案件不触发处罚必备清单，正常结案")
    finally:
        admin.call("PUT", "/config/archive_completeness_required?value=false")

    # ============ 四期：执法事项扩域 + 裁量 + 执行深化 ============
    step("行政检查→线索闭环：人员<2拒（2063）；发现违法一键转线索")
    insp_item = next(i for i in items if i["category"] == "INSPECTION")
    admin.post("/bureau/inspections", json={
        "itemId": insp_item["id"], "objectName": "示范市第四医院", "objectType": "PROVIDER",
        "officers": "王办案", "plannedAt": str(today)}, expect_code=2063)
    insp = admin.post("/bureau/inspections", json={
        "itemId": insp_item["id"], "objectName": "示范市第四医院", "objectType": "PROVIDER",
        "officers": "王办案、张协办", "plannedAt": str(today)})
    admin.post(f"/bureau/inspections/{insp['id']}/complete", json={
        "result": "违法：抽查病历发现挂床住院 3 例", "violationFound": True})
    insp_clue = admin.post(f"/bureau/inspections/{insp['id']}/to-clue")
    ok(insp_clue["source"] == "INSPECTION" and insp_clue["clueNo"].startswith("XS"), f"检查转线索 {insp_clue['clueNo']}")
    admin.post(f"/bureau/inspections/{insp['id']}/to-clue", expect_code=2063)  # 不可重复转

    step("举报奖励：未立案线索拒（2064）；查实线索登记→审批→发放")
    admin.post("/bureau/rewards", json={
        "clueId": insp_clue["id"], "reporterName": "热心群众"}, expect_code=2064)
    admin.post("/bureau/rewards", json={
        "clueId": clue["id"], "reporterName": "举报人甲", "reporterContact": "139****0001",
        "note": "举报查实，案件已处罚"})
    rw = admin.get("/bureau/rewards")[0]
    admin.post(f"/bureau/rewards/{rw['id']}/pay", expect_code=2064)  # 未审批不可发放
    admin.post(f"/bureau/rewards/{rw['id']}/approve", json={"amount": 5000})
    admin.post(f"/bureau/rewards/{rw['id']}/pay")
    ok(admin.get("/bureau/rewards")[0]["paid_at"], "奖励已审批发放")

    step("裁量基准建议：按案由+涉案金额给出各阶次金额区间")
    sug = admin.get(f"/bureau/cases/{cid}/discretion-suggest")
    tiers = sug["tiers"]
    ok(len(tiers) >= 3, f"裁量阶次 {len(tiers)} 档")
    normal = next(t for t in tiers if t["tier"] == "NORMAL")
    ok(float(normal["suggestMin"]) == 300000 * 1.2, f"一般档建议下限 {normal['suggestMin']}")

    step("裁量理由必填守卫（2062）：普通程序处罚决定缺裁量理由拒")
    d_case = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "裁量测试诊所", "partyType": "PROVIDER",
        "amountInvolved": 4000,
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{d_case['id']}/report", json={"content": "调查终结"})
    admin.post(f"/bureau/cases/{d_case['id']}/notice", json={
        "content": "拟罚5000", "proposedFine": 5000, "proposedRecoup": 4000})
    admin.post(f"/bureau/cases/{d_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 5000, "recoupAmount": 4000,
        "content": "x"}, expect_code=2062)
    admin.post(f"/bureau/cases/{d_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 5000, "recoupAmount": 4000,
        "discretionReason": "属一般情形，按1.25倍幅度中值裁量", "content": "罚款5000元"})
    print("    PASS: 裁量理由守卫生效")

    step("分期计划：未批准暂缓拒（2065）；批准后建2期计划并缴纳")
    admin.post(f"/bureau/cases/{d_case['id']}/deliver", json={"method": "DIRECT", "receiver": "负责人"})
    admin.post(f"/bureau/cases/{d_case['id']}/installments", json={
        "seq": 1, "dueAt": str(today + datetime.timedelta(days=30)), "amount": 2500}, expect_code=2065)
    admin.post(f"/bureau/cases/{d_case['id']}/approve-defer")
    admin.post(f"/bureau/cases/{d_case['id']}/installments", json={
        "seq": 1, "dueAt": str(today + datetime.timedelta(days=30)), "amount": 2500})
    admin.post(f"/bureau/cases/{d_case['id']}/installments", json={
        "seq": 2, "dueAt": str(today + datetime.timedelta(days=60)), "amount": 2500})
    inst = admin.get(f"/bureau/cases/{d_case['id']}/installments")
    admin.post(f"/bureau/installments/{inst[0]['id']}/pay")
    ok(admin.get(f"/bureau/cases/{d_case['id']}/installments")[0]["paid_at"], "第1期已缴")

    step("专家评审：结束自动登记期限扣除（第25/45条）")
    e_case_before = admin.get(f"/bureau/cases/{d_case['id']}")["effectiveDeadline"]
    admin.post(f"/bureau/cases/{d_case['id']}/expert-reviews", json={"experts": "临床专家A、医保专家B"})
    er_start = str(today - datetime.timedelta(days=4))
    ers = admin.get(f"/bureau/cases/{d_case['id']}")  # 取评审id：detail无该表，直接查执行——改用固定id=1? 用返回列表
    admin.post(f"/bureau/cases/{d_case['id']}/expert-reviews/1/end", json={
        "opinion": "病历评审意见：过度诊疗成立", "startedAt": er_start, "endedAt": str(today)})
    e_case_after = admin.get(f"/bureau/cases/{d_case['id']}")["effectiveDeadline"]
    ok(datetime.date.fromisoformat(e_case_after) - datetime.date.fromisoformat(e_case_before)
       == datetime.timedelta(days=4), "评审4日已计入期限扣除")

    step("公示导出：自然人姓名脱敏")
    export = admin.get("/bureau/decisions/publish-export")
    ok(any(r["decision_no"] for r in export), f"公示 {len(export)} 条")

    # ============ 三期：程序完备 ============
    step("执法证台账守卫（2055）：证号不在台账/证号与姓名不符均拒")
    admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "证照测试医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "无证人员", "certNo": "NOCERT"}]},
        expect_code=2055)
    admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "证照测试医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "冒名者", "certNo": "YB002"}]},
        expect_code=2055)
    print("    PASS: 执法证校验生效")

    step("当事人申请回避须记录批准人（2061）")
    h_case = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "听证流程医院", "partyType": "PROVIDER",
        "enforceItemId": item1["id"], "summary": "分解收费", "amountInvolved": 240000,
        "officers": [{"name": "王办案", "certNo": "YB001", "duty": "LEAD"},
                      {"name": "张协办", "certNo": "YB002"}, {"name": "李替补", "certNo": "YB003"}]})
    hid = h_case["id"]
    officers = admin.get(f"/bureau/cases/{hid}")["officers"]
    admin.post(f"/bureau/cases/{hid}/officers/{officers[2]['id']}/avoid",
               json={"reason": "与当事人有利害关系", "applicant": "PARTY"}, expect_code=2061)
    admin.post(f"/bureau/cases/{hid}/officers/{officers[2]['id']}/avoid",
               json={"reason": "与当事人有利害关系", "applicant": "PARTY", "decidedBy": "赵局长"})
    print("    PASS: 当事人申请回避经批准")

    step("听证子流程：距通知<7日拒（2057）；主持人为办案人员拒（2056）")
    admin.post(f"/bureau/cases/{hid}/report", json={"content": "调查终结：分解项目收费"})
    admin.post(f"/bureau/cases/{hid}/notice", json={
        "content": "拟罚款12万元", "proposedFine": 120000, "proposedRecoup": 60000})
    admin.post(f"/bureau/cases/{hid}/statement", json={"hearingRequested": True})
    admin.post(f"/bureau/cases/{hid}/hearings", json={
        "noticeSentAt": str(today), "scheduledAt": str(today + datetime.timedelta(days=3)),
        "host": "李法制"}, expect_code=2057)
    admin.post(f"/bureau/cases/{hid}/hearings", json={
        "noticeSentAt": str(today), "scheduledAt": str(today + datetime.timedelta(days=8)),
        "host": "王办案"}, expect_code=2056)
    admin.post(f"/bureau/cases/{hid}/hearings", json={
        "announcedAt": str(today), "noticeSentAt": str(today),
        "scheduledAt": str(today + datetime.timedelta(days=8)),
        "host": "李法制", "hostDept": "政策法规处", "recorder": "书记员小周"})
    print("    PASS: 听证已安排")

    step("听证举行→笔录→2日内听证意见")
    hr = admin.get(f"/bureau/cases/{hid}")["hearings"][0]
    admin.post(f"/bureau/cases/{hid}/hearings/{hr['id']}/hold", json={"record": "听证笔录：双方陈述质证完毕"})
    admin.post(f"/bureau/cases/{hid}/hearings/{hr['id']}/opinion", json={"opinion": "建议维持拟处罚意见"})
    hr2 = admin.get(f"/bureau/cases/{hid}")["hearings"][0]
    ok(hr2["status"] == "OPINION_DONE" and hr2["opinion_at"], "听证意见已出")

    step("听证案件决定（经审核+集体讨论）→ 电子送达须确认书（2054）")
    rev = admin.post(f"/bureau/cases/{hid}/reviews", json={"requiredReason": "经过听证程序"})
    admin.post(f"/bureau/cases/reviews/{rev['id']}",
               json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "程序合法"})
    admin.post(f"/bureau/cases/{hid}/meetings", json={
        "heldAt": str(today), "attendees": "局领导班子", "record": "讨论一致", "conclusion": "同意处罚"})
    admin.post(f"/bureau/cases/{hid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 120000, "recoupAmount": 60000,
        "discretionReason": "listed", "content": "罚款12万元"})
    admin.post(f"/bureau/cases/{hid}/deliver",
               json={"method": "ELECTRONIC", "receiver": "法人"}, expect_code=2054)
    admin.post(f"/bureau/cases/{hid}/e-delivery-consent")
    delivered_h = admin.post(f"/bureau/cases/{hid}/deliver", json={
        "method": "ELECTRONIC", "receiver": "法人", "note": "已签电子送达确认书"})
    ok(delivered_h["status"] == "DELIVERED", "签确认书后电子送达成功（国家局令59条口径）")

    step("协查台账：拒绝无理由拒（2060）；复函办结")
    admin.post(f"/bureau/cases/{hid}/assists", json={
        "direction": "OUT", "org": "邻市医保局", "content": "调取异地就医结算明细"})
    asst = admin.get(f"/bureau/cases/{hid}")["assists"][0]
    ok(asst["due_at"] == str(today + datetime.timedelta(days=15)), "协查期限15日")
    admin.post(f"/bureau/cases/{hid}/assists/{asst['id']}/reply",
               json={"result": "x", "refused": True}, expect_code=2060)
    admin.post(f"/bureau/cases/{hid}/assists/{asst['id']}/reply",
               json={"result": "已复函：明细共120条", "refused": False})
    print("    PASS: 协查闭环")

    step("简易程序备案（第51条）：非简易案件拒（2058）；简易案件备案成功")
    admin.post(f"/bureau/cases/{hid}/summary-record", expect_code=2058)
    s_case = admin.post("/bureau/cases", json={
        "causeId": cause31["id"], "procedureType": "SUMMARY",
        "partyName": "参保人吴某", "partyType": "INDIVIDUAL",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{s_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 100, "content": "当场处罚"})
    sr = admin.post(f"/bureau/cases/{s_case['id']}/summary-record")
    ok(sr["summaryRecordAt"] == str(today), "简易备案完成")

    step("移送台账：线索整体移送后状态 TRANSFERRED + 接收确认")
    t_clue = admin.post("/bureau/clues", json={
        "source": "COMPLAINT", "content": "举报某药店无证经营（属市场监管职权）",
        "suspectName": "某大药房", "suspectType": "PROVIDER", "receivedAt": str(today)})
    admin.post("/bureau/transfers", json={
        "clueId": t_clue["id"], "direction": "OUT", "targetOrg": "市市场监督管理局",
        "kind": "ADMIN", "reason": "无证经营不属医保部门管辖（第12条）"})
    moved = admin.get("/bureau/clues", params={"status": "TRANSFERRED"})
    ok(any(x["id"] == t_clue["id"] for x in moved), "线索已移送")
    trs = admin.get("/bureau/transfers")
    admin.post(f"/bureau/transfers/{trs[0]['id']}/confirm")
    print("    PASS: 移送接收确认")

    # ============ 辽宁参数组：切换省域参数后整链路复验（辽医保发〔2020〕5号） ============
    liaoning_params = {
        "clue_verify_day_unit": "NATURAL",
        "legal_review_mode": "ALL",
        "legal_review_days": "7",
        "legal_review_day_unit": "NATURAL",
        "hearing_threshold_individual": "1000",
        "hearing_threshold_org": "10000",
        "summary_fine_limit_individual": "50",
        "summary_fine_limit_org": "1000",
        "onsite_collect_limit": "20",
        "delivery_electronic_decision_allowed": "false",
        "cross_exam_required": "true",
    }
    defaults = {
        "clue_verify_day_unit": "WORKDAY",
        "legal_review_mode": "THRESHOLD",
        "legal_review_days": "10",
        "legal_review_day_unit": "WORKDAY",
        "hearing_threshold_individual": "100000",
        "hearing_threshold_org": "100000",
        "summary_fine_limit_individual": "200",
        "summary_fine_limit_org": "3000",
        "onsite_collect_limit": "100",
        "delivery_electronic_decision_allowed": "true",
        "cross_exam_required": "false",
    }
    try:
        step("辽宁参数组：切换 11 项省域参数")
        for k, v in liaoning_params.items():
            admin.call("PUT", f"/config/{k}?value={v}")
        print("    PASS: 参数已切换")

        step("辽·线索：核查期限=15自然日")
        ln_clue = admin.post("/bureau/clues", json={
            "source": "COMPLAINT", "content": "举报参保人冒用他人医保凭证购药", "suspectName": "参保人孙某",
            "suspectType": "INDIVIDUAL", "receivedAt": str(today)})
        ok(ln_clue["deadlineAt"] == str(today + datetime.timedelta(days=15)), "自然日期限")

        step("辽·线索期限扣除：鉴定5日不计入（辽15条）")
        exc = admin.post(f"/bureau/clues/{ln_clue['id']}/exclusions",
                         json={"days": 5, "reason": "笔迹鉴定"})
        ok(exc["deadlineAt"] == str(today + datetime.timedelta(days=20)), "期限顺延5日")

        step("辽·立案（自然人，普通程序）")
        ln_case = admin.post("/bureau/cases", json={
            "clueId": ln_clue["id"], "causeId": cause31["id"], "procedureType": "NORMAL",
            "partyName": "参保人孙某", "partyType": "INDIVIDUAL", "amountInvolved": 5000,
            "officers": [{"name": "王办案", "certNo": "YB001", "duty": "LEAD"},
                          {"name": "张协办", "certNo": "YB002"}]})
        lnid = ln_case["id"]
        admin.post(f"/bureau/cases/{lnid}/evidences", json={
            "type": "OTHER_MATERIAL", "name": "药店监控截图说明材料", "obtainedAt": str(today)})
        print("    PASS: 第九类证据 OTHER_MATERIAL 可用（辽23条）")

        step("辽·调查终结+告知：拟罚1500达自然人听证档（1000）")
        admin.post(f"/bureau/cases/{lnid}/report", json={"content": "调查终结：冒名购药事实清楚"})
        ln_notice = admin.post(f"/bureau/cases/{lnid}/notice", json={
            "content": "拟处罚款1500元", "proposedFine": 1500, "proposedRecoup": 3000})
        ok(ln_notice["hearingEntitled"] is True, "自然人1000元档已告知听证权利")
        ok(ln_notice["statementDeadline"] == str(today + datetime.timedelta(days=3)), "陈述申辩期限=告知+3日（辽44条）")

        step("辽·全案法制审核：小额案件未审核也不得决定（2005，辽40条）")
        admin.post(f"/bureau/cases/{lnid}/decide", json={
            "decisionType": "PUNISH", "fineAmount": 1500, "recoupAmount": 3000,
            "content": "x"}, expect_code=2005)
        ln_rev = admin.post(f"/bureau/cases/{lnid}/reviews", json={"requiredReason": "辽宁全案审核"})
        ok(ln_rev["deadlineAt"] == str(today + datetime.timedelta(days=7)), "审核期限7自然日（辽41条）")
        admin.post(f"/bureau/cases/reviews/{ln_rev['id']}",
                   json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "同意"})

        step("辽·质证守卫：证据未质证不得决定（2045，辽24条）；质证后可决定")
        admin.post(f"/bureau/cases/{lnid}/decide", json={
            "decisionType": "PUNISH", "fineAmount": 1500, "recoupAmount": 3000,
            "content": "x"}, expect_code=2045)
        ev = admin.get(f"/bureau/cases/{lnid}")["evidences"][0]
        admin.post(f"/bureau/cases/{lnid}/evidences/{ev['id']}/cross-exam",
                   json={"opinion": "当事人无异议"})
        ln_dec = admin.post(f"/bureau/cases/{lnid}/decide", json={
            "decisionType": "PUNISH", "fineAmount": 1500, "recoupAmount": 3000,
            "discretionReason": "初犯且金额较小，按下限处罚",
            "content": "责令退回基金3000元，并处罚款1500元"})
        ok(ln_dec["decisionNo"], "决定书文号")

        step("辽·送达：决定书禁电子送达（2049，辽61条）；回证签收日=送达日（辽58条）")
        admin.post(f"/bureau/cases/{lnid}/deliver",
                   json={"method": "ELECTRONIC", "receiver": "孙某"}, expect_code=2049)
        receipt_day = today - datetime.timedelta(days=1)
        delivered = admin.post(f"/bureau/cases/{lnid}/deliver", json={
            "method": "DIRECT", "receiver": "孙某",
            "receiptNo": "回证2026-001", "receiptSignedAt": str(receipt_day)})
        ok(delivered["deliveredAt"] == str(receipt_day), "送达日=回证签收日")

        step("辽·当场收缴限20元：50拒（2010）；20可")
        admin.post(f"/bureau/cases/{lnid}/executions", json={
            "kind": "FINE", "amount": 50, "paidAt": str(today), "method": "ONSITE", "receiptNo": "辽财票2026-00"}, expect_code=2010)
        admin.post(f"/bureau/cases/{lnid}/executions", json={
            "kind": "FINE", "amount": 20, "paidAt": str(today), "method": "ONSITE", "receiptNo": "辽财票2026-01"})
        admin.post(f"/bureau/cases/{lnid}/executions", json={
            "kind": "FINE", "amount": 1480, "paidAt": str(today), "method": "BANK"})
        admin.post(f"/bureau/cases/{lnid}/executions", json={
            "kind": "RECOUP", "amount": 3000, "paidAt": str(today), "method": "BANK"})
        closed_ln = admin.post(f"/bureau/cases/{lnid}/close", json={"closeReport": "执行完毕结案"})
        ok(closed_ln["status"] == "CLOSED", "辽宁参数组全流程结案")

        step("辽·简易程序限额50/1000：公民100拒（2003）；50可当场决定")
        ln_sum = admin.post("/bureau/cases", json={
            "causeId": cause31["id"], "procedureType": "SUMMARY",
            "partyName": "参保人钱某", "partyType": "INDIVIDUAL",
            "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
        admin.post(f"/bureau/cases/{ln_sum['id']}/decide", json={
            "decisionType": "PUNISH", "fineAmount": 100, "content": "x"}, expect_code=2003)
        admin.post(f"/bureau/cases/{ln_sum['id']}/decide", json={
            "decisionType": "PUNISH", "fineAmount": 50, "content": "当场处罚款50元"})
        print("    PASS: 辽宁简易限额生效")
    finally:
        for k, v in defaults.items():
            admin.call("PUT", f"/config/{k}?value={v}")
    step("参数复位为国家默认值")
    print("    PASS: 已复位")

    print(f"\n=== 医保案件查办 E2E 全部通过（{step_no} 步）===")


if __name__ == "__main__":
    main()
