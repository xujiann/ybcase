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
    def __init__(self, username, password="admin123"):
        # 健康检查通过 ≠ 初始化器已建好默认账号（ApplicationRunner 晚于 Web 就绪）——登录重试消除时序 flake
        import time
        last = None
        for _ in range(30):
            r = requests.post(f"{BASE}/auth/login",
                              json={"username": username, "password": password}, timeout=10)
            last = r.json()
            if last.get("code") == 0:
                self.token = last["data"]["token"]
                return
            time.sleep(1)
        raise AssertionError(f"登录失败（{username}）: {last}")

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
    # 陈述申辩期未届满且未放弃 → 2076（第三轮整改新守卫）
    admin.post(f"/bureau/cases/{d_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 5000, "recoupAmount": 4000,
        "discretionReason": "x", "content": "x"}, expect_code=2076)
    admin.post(f"/bureau/cases/{d_case['id']}/statement", json={"statementWaived": True})
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

    step("专家评审：结束自动登记期限扣除（第25/45条）；起始日不得由结束请求倒填")
    # 起始日只在启动时登记且受校验（不得早于立案日、不得晚于今天）
    admin.post(f"/bureau/cases/{d_case['id']}/expert-reviews",
               json={"experts": "临床专家A、医保专家B", "startedAt": str(today - datetime.timedelta(days=4))},
               expect_code=2066)  # 案件今日立案，评审不可能 4 日前开始
    admin.post(f"/bureau/cases/{d_case['id']}/expert-reviews",
               json={"experts": "临床专家A、医保专家B", "startedAt": str(today + datetime.timedelta(days=1))},
               expect_code=2066)  # 未来日期
    admin.post(f"/bureau/cases/{d_case['id']}/expert-reviews", json={"experts": "临床专家A、医保专家B"})
    er_id = admin.get(f"/bureau/cases/{d_case['id']}")["expertReviews"][-1]["id"]  # 用本次创建的真实 id，非全新库也可跑
    e_case_before = admin.get(f"/bureau/cases/{d_case['id']}")["effectiveDeadline"]
    # 结束时倒填一个很早的开始日：应被忽略，扣除仍按库内登记的今天计
    admin.post(f"/bureau/cases/{d_case['id']}/expert-reviews/{er_id}/end", json={
        "opinion": "病历评审意见：过度诊疗成立",
        "startedAt": str(today - datetime.timedelta(days=300)), "endedAt": str(today)})
    e_case_after = admin.get(f"/bureau/cases/{d_case['id']}")["effectiveDeadline"]
    ok(e_case_after == e_case_before, "结束请求中的倒填起始日被忽略，期限未被凭空延长")
    er_done = admin.get(f"/bureau/cases/{d_case['id']}")["expertReviews"][-1]
    ok(er_done["ended_at"] == str(today) and er_done["started_at"] == str(today), "评审起止以库内登记为准")

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
    admin.post(f"/bureau/cases/{hid}/e-delivery-consent",
               json={"receiver": "听证医院", "channel": "13900000000"})
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

    # ============ 审阅整改（v1.0.0 加固） ============
    step("角色分权矩阵：办案员不得决定/延期/结案（403）；法制员不得决定但可审核")
    banban = Api("banban")
    banban.post(f"/bureau/cases/{d_case['id']}/extend", json={"days": 10, "reason": "x"}, expect_code=403)
    banban.post(f"/bureau/cases/{d_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 1, "content": "x"}, expect_code=403)
    banban.post(f"/bureau/cases/{d_case['id']}/close", json={"closeReport": "x"}, expect_code=403)
    fazhi = Api("fazhi")
    fazhi.post(f"/bureau/cases/{d_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 1, "content": "x"}, expect_code=403)
    juzhang = Api("juzhang")
    juzhang.post(f"/bureau/cases/reviews/999", json={"reviewer": "x"}, expect_code=403)  # 局长不可办审核
    print("    PASS: 批准/决定=LEADER，审核=LEGAL，服务端强制")

    step("审核人法律职业资格校验（2070，辽41条）")
    q_case = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "资格测试医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{q_case['id']}/report", json={"content": "终结"})
    q_rev = admin.post(f"/bureau/cases/{q_case['id']}/reviews", json={"requiredReason": "测试"})
    admin.post(f"/bureau/cases/reviews/{q_rev['id']}",
               json={"reviewer": "赵局长", "opinionType": "AGREE", "opinion": "x"}, expect_code=2070)
    admin.post(f"/bureau/cases/reviews/{q_rev['id']}",
               json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "具备资格"})
    print("    PASS: 非法律职业资格人员不得审核")

    step("配置白名单：匿名 /api/config/public 不再泄露口径参数")
    pub_cfg = requests.get(f"{BASE}/config/public", timeout=10).json()["data"]
    ok("org_name" in pub_cfg and "hearing_threshold_individual" not in pub_cfg
       and "legal_review_mode" not in pub_cfg, f"仅暴露 {len(pub_cfg)} 个机构信息键")

    step("执法证守卫（2055）：立案人员不在台账/证名不符已拒（含上批用例）——附件>1MB 上传成功（multipart 修复）")
    big = ("大附件.bin", b"x" * (2 * 1024 * 1024), "application/octet-stream")
    r_big = requests.post(f"{BASE}/bureau/cases/{q_case['id']}/attachments", files={"file": big},
                          timeout=30, headers={"Authorization": f"Bearer {admin.token}"})
    ok(r_big.json()["code"] == 0, "2MB 附件上传成功（原默认1MB限制已修复）")

    step("编号原子序列：连续创建两线索编号连续无跳号")
    n1 = admin.post("/bureau/clues", json={"source": "COMPLAINT", "content": "序列测试1",
        "suspectName": "对象1", "suspectType": "PROVIDER", "receivedAt": str(today)})["clueNo"]
    n2 = admin.post("/bureau/clues", json={"source": "COMPLAINT", "content": "序列测试2",
        "suspectName": "对象2", "suspectType": "PROVIDER", "receivedAt": str(today)})["clueNo"]
    ok(int(n2[-4:]) == int(n1[-4:]) + 1, f"biz_seq 取号 {n1} → {n2}")

    step("移送司法联动终止（第43/47条）：决定 TRANSFER_JUDICIAL 自动终止并解除封存")
    j_case = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "涉刑测试医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{j_case['id']}/evidences", json={
        "type": "DOCUMENT", "name": "涉刑账册", "obtainedAt": str(today), "sealed": True})
    admin.post(f"/bureau/cases/{j_case['id']}/report", json={"content": "涉嫌犯罪"})
    admin.post(f"/bureau/cases/{j_case['id']}/notice", json={"content": "移送告知", "proposedFine": 0})
    j_rev = admin.post(f"/bureau/cases/{j_case['id']}/reviews", json={"requiredReason": "涉刑移送"})
    admin.post(f"/bureau/cases/reviews/{j_rev['id']}",
               json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "同意移送"})
    admin.post(f"/bureau/cases/{j_case['id']}/decide", json={
        "decisionType": "TRANSFER_JUDICIAL", "content": "移送公安机关追究刑事责任"})
    j_detail = admin.get(f"/bureau/cases/{j_case['id']}")
    ok(j_detail["caseFile"]["status"] == "TERMINATED", "自动终止调查")
    ok(j_detail["evidences"][0]["sealed"] is False, "封存已解除")

    step("公告送达 60 日视为送达（辽60条）")
    a_case = admin.post("/bureau/cases", json={
        "causeId": cause31["id"], "procedureType": "SUMMARY",
        "partyName": "参保人冯某", "partyType": "INDIVIDUAL",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{a_case['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 50, "content": "当场处罚，当事人下落不明"})
    ann_date = today - datetime.timedelta(days=61)
    a_delivered = admin.post(f"/bureau/cases/{a_case['id']}/deliver", json={
        "method": "ANNOUNCE", "deliveredAt": str(ann_date), "note": "公告张贴于门户网站"})
    ok(a_delivered["deliveredAt"] == str(ann_date + datetime.timedelta(days=60)), "公告+60日=送达日")

    step("加处罚款 UNPAID 基数口径（参数切换）")
    admin.post(f"/bureau/cases/{a_case['id']}/executions", json={
        "kind": "FINE", "amount": 30, "paidAt": str(today), "method": "BANK"})
    admin.call("PUT", "/config/late_fee_base?value=UNPAID")
    try:
        q = admin.get(f"/bureau/cases/{a_case['id']}/late-fee-quote")
        # 送达=公告+60（昨日），缴款期限+15 未到 → 逾期0；先断言基数=未缴20
        ok(float(q["base"]) == 20.0, f"UNPAID 基数=未缴 {q['base']} 元")
    finally:
        admin.call("PUT", "/config/late_fee_base?value=FULL")

    step("自助修改密码：改密→新密登录→改回")
    banban.post("/auth/change-password", json={"oldPassword": "admin123", "newPassword": "Fix2026pwd"})
    r_new = requests.post(f"{BASE}/auth/login",
                          json={"username": "banban", "password": "Fix2026pwd"}, timeout=10).json()
    ok(r_new["code"] == 0, "新密码登录成功")
    # 弱密码拒（1004）后改回演示口令
    tok = r_new["data"]["token"]
    weak = requests.post(f"{BASE}/auth/change-password",
                         json={"oldPassword": "Fix2026pwd", "newPassword": "abc"},
                         timeout=10, headers={"Authorization": f"Bearer {tok}"}).json()
    ok(weak["code"] == 1004, "弱密码被策略拒绝")
    back = requests.post(f"{BASE}/auth/change-password",
                         json={"oldPassword": "Fix2026pwd", "newPassword": "admin123"},
                         timeout=10, headers={"Authorization": f"Bearer {tok}"}).json()
    ok(back["code"] == 0, "改密闭环并复位")
    # 改密即吊销旧令牌：banban 手里改密前的令牌此刻已失效，须重新登录
    r_stale = requests.get(f"{BASE}/bureau/my/workbench", timeout=10,
                           headers={"Authorization": f"Bearer {banban.token}"})
    ok(r_stale.status_code in (401, 403), f"改密前的旧令牌已失效（HTTP {r_stale.status_code}）")
    banban = Api("banban")

    step("专家评审列表进入案件详情（含UI数据源）")
    ok("expertReviews" in admin.get(f"/bureau/cases/{d_case['id']}"), "expertReviews 已在详情聚合")

    # ============ v1.1：正式化能力 ============
    step("文书模板管理：修改留历史")
    tpls = admin.get("/bureau/doc-templates")
    ok(any(t["doc_type"] == "TRANSFER_LETTER" for t in tpls), "行刑衔接模板已内置")
    old_tpl = next(t for t in tpls if t["doc_type"] == "ASSIST_LETTER")
    admin.call("PUT", "/bureau/doc-templates/ASSIST_LETTER",
               json={"titleTpl": old_tpl["title_tpl"], "contentTpl": old_tpl["content_tpl"] + "\n（v1.1 修订）"})
    hist = admin.get("/bureau/doc-templates/ASSIST_LETTER/history")
    ok(len(hist) >= 1, "旧版已入历史")

    step("电子签章 SPI：签章+验签（Mock=SHA-256 摘要，防篡改基准）")
    docs_h = admin.get(f"/bureau/cases/{hid}")["documents"]
    target_doc = docs_h[0]
    sig = admin.post(f"/bureau/cases/{hid}/documents/{target_doc['id']}/sign",
                     json={"signer": "王办案", "signerRole": "OFFICER"})
    ok(sig["provider"] == "MOCK" and len(sig["contentHash"]) == 64, "签章生成")
    vres = admin.get(f"/bureau/cases/{hid}/documents/{target_doc['id']}/verify")
    ok(len(vres) == 1 and vres[0]["valid"] is True, "验签有效")
    ok(vres[0]["operator"] == "admin", "签章记录含实际操作人（signer 署名≠operator 登录人可追溯）")

    step("审批单据化：办案员申请延期→局长批准→期限顺延（第17条精神）")
    ap = banban.post("/bureau/approvals", json={
        "kind": "EXTEND", "caseId": q_case["id"], "payload": {"days": 5}, "reason": "补充鉴定"})
    banban.post(f"/bureau/approvals/{ap['id']}/decide", json={"approve": True}, expect_code=403)
    before_dl = admin.get(f"/bureau/cases/{q_case['id']}")["caseFile"]["deadlineAt"]
    juzhang.post(f"/bureau/approvals/{ap['id']}/decide", json={"approve": True, "opinion": "同意"})
    after_dl = admin.get(f"/bureau/cases/{q_case['id']}")["caseFile"]["deadlineAt"]
    ok(datetime.date.fromisoformat(after_dl) == datetime.date.fromisoformat(before_dl) + datetime.timedelta(days=5),
       "批准即执行，期限+5")

    step("立案审批单：申请→驳回→不产生案件；再申请→批准→产生案件")
    file_payload = {
        "causeId": cause13["id"], "partyName": "审批立案医院", "partyType": "PROVIDER",
        "summary": "审批流立案", "officers": [
            {"name": "王办案", "certNo": "YB001", "duty": "LEAD"},
            {"name": "张协办", "certNo": "YB002", "duty": "MEMBER"}]}
    ap2 = banban.post("/bureau/approvals", json={
        "kind": "FILE_CASE", "payload": file_payload, "reason": "线索核查属实，申请立案"})
    juzhang.post(f"/bureau/approvals/{ap2['id']}/decide", json={"approve": False, "opinion": "证据不足"})
    ap3 = banban.post("/bureau/approvals", json={
        "kind": "FILE_CASE", "payload": file_payload, "reason": "已补充证据，再次申请"})
    r3 = juzhang.post(f"/bureau/approvals/{ap3['id']}/decide", json={"approve": True, "opinion": "同意立案"})
    ok(r3["result"].get("caseNo"), f"审批立案成功 {r3['result'].get('caseNo')}")

    step("送达泛化：告知书电子送达无确认书拒（2054）；直接送达登记回证")
    admin.post(f"/bureau/cases/{d_case['id']}/doc-deliveries", json={
        "docKind": "NOTICE", "method": "ELECTRONIC", "receiver": "当事人"}, expect_code=2054)
    admin.post(f"/bureau/cases/{d_case['id']}/doc-deliveries", json={
        "docKind": "NOTICE", "method": "DIRECT", "receiver": "当事人", "receiptNo": "告知回证-001"})
    dels = admin.get(f"/bureau/cases/{d_case['id']}")["deliveries"]
    ok(any(x.get("doc_kind") == "NOTICE" for x in dels), "文书级送达已入台账")

    step("协议处理台账：移交经办→登记结果（行政↔协议联动）")
    admin.post(f"/bureau/cases/{d_case['id']}/agreement-actions",
               json={"action": "REFUSE_PAY", "org": "市医保事务服务中心"})
    aa = admin.get(f"/bureau/cases/{d_case['id']}")["agreementActions"][0]
    admin.post(f"/bureau/agreement-actions/{aa['id']}/reply", json={"result": "已拒付相关费用并冻结结算"})
    ok(admin.get(f"/bureau/cases/{d_case['id']}")["agreementActions"][0]["replied_at"], "经办结果闭环")

    step("行刑衔接回执：司法移送登记受案回执")
    admin.post("/bureau/transfers", json={
        "caseId": j_case["id"], "direction": "OUT", "targetOrg": "市公安局",
        "kind": "JUDICIAL", "reason": "涉嫌诈骗罪移送"})
    trs2 = admin.get("/bureau/transfers")
    jt = next(t for t in trs2 if t.get("kind") == "JUDICIAL")
    admin.post(f"/bureau/transfers/{jt['id']}/receipt", json={"receiptNo": "公（治）受案字〔2026〕001号"})
    ok(any(t.get("receipt_no") for t in admin.get("/bureau/transfers")), "受案回执登记")

    step("附件外置存储（FILE 模式）+音像分类")
    admin.call("PUT", "/config/attachment_storage?value=FILE")
    try:
        files = {"file": ("现场执法记录.mp4", b"FAKE_VIDEO" * 1000, "video/mp4")}
        r_av = requests.post(f"{BASE}/bureau/cases/{d_case['id']}/attachments",
                             files=files, data={"category": "AV_RECORD"}, timeout=30,
                             headers={"Authorization": f"Bearer {admin.token}"})
        assert r_av.json()["code"] == 0, r_av.text
        atts2 = admin.get(f"/bureau/cases/{d_case['id']}/attachments")
        av = next(a for a in atts2 if a["category"] == "AV_RECORD")
        ok(av["external"] is True, "音像件外置存储")
        dl2 = requests.get(f"{BASE}/bureau/cases/attachments/{av['id']}/download", timeout=15,
                           headers={"Authorization": f"Bearer {admin.token}"})
        ok(dl2.content.startswith(b"FAKE_VIDEO"), "外置文件下载一致")
    finally:
        admin.call("PUT", "/config/attachment_storage?value=DB")

    step("全局搜索+案件分页")
    sr = admin.get("/bureau/search", params={"q": "第一医院"})
    ok(len(sr["cases"]) >= 1, f"搜索命中 {len(sr['cases'])} 案")
    pg = admin.get("/bureau/cases", params={"page": 1, "size": 5})
    ok(pg["total"] >= 5 and len(pg["rows"]) == 5, f"分页 total={pg['total']}")

    step("审计导出（等保归档）")
    month = str(today)[:7]
    r_csv = requests.get(f"{BASE}/bureau/audit/export", params={"month": month}, timeout=15,
                         headers={"Authorization": f"Bearer {admin.token}"})
    ok(r_csv.status_code == 200 and len(r_csv.content) > 200, f"审计 CSV {len(r_csv.content)} 字节")

    step("监控拉取 SPI（Mock 空源，链路可用）")
    fm = admin.post("/bureau/clues/fetch-monitor")
    ok(fm["fetched"] == 0 and "Mock" in fm["adapter"], f"适配器 {fm['adapter']}")

    step("卷宗合成数据源（含签章）")
    af = admin.get(f"/bureau/cases/{hid}/archive-full")
    ok(len(af["documents"]) >= 1 and len(af["signatures"]) >= 1 and af["orgName"], "卷宗合成可打印")

    # ============ v1.2：待办中心+消息 / 数据级权限 / OpenAPI ============
    step("消息提醒：审批申请→负责人收消息；裁决→申请人收消息")
    ju_unread0 = juzhang.get("/bureau/messages/unread-count")
    ap_msg = banban.post("/bureau/approvals", json={
        "kind": "EXTEND", "caseId": q_case["id"], "payload": {"days": 3}, "reason": "消息链路验证"})
    ju_unread1 = juzhang.get("/bureau/messages/unread-count")
    ok(ju_unread1 > ju_unread0, f"局长未读 {ju_unread0}→{ju_unread1}")
    bb_unread0 = banban.get("/bureau/messages/unread-count")
    juzhang.post(f"/bureau/approvals/{ap_msg['id']}/decide", json={"approve": False, "opinion": "链路验证驳回"})
    ok(banban.get("/bureau/messages/unread-count") > bb_unread0, "申请人收到裁决消息")
    msgs = banban.get("/bureau/messages")
    first_unread = next(m for m in msgs if not m["read_at"])
    banban.post(f"/bureau/messages/{first_unread['id']}/read")
    ok(banban.get("/bureau/messages/unread-count") == bb_unread0, "已读回执生效")

    step("期限提醒生成：超期线索→登记人收 DEADLINE 消息；同日重跑去重")
    admin.post("/bureau/clues", json={
        "source": "COMPLAINT", "content": "提醒验证线索", "suspectName": "提醒对象",
        "suspectType": "PROVIDER", "receivedAt": str(today - datetime.timedelta(days=40))})
    g1 = admin.post("/bureau/messages/generate-reminders")["generated"]
    ok(g1 >= 1, f"生成 {g1} 条提醒")
    my_msgs = admin.get("/bureau/messages")
    ok(any(m["kind"] == "DEADLINE" and "线索核查" in m["title"] for m in my_msgs), "登记人收到线索超期提醒")
    before_cnt = admin.get("/bureau/messages/unread-count")
    admin.post("/bureau/messages/generate-reminders")
    ok(admin.get("/bureau/messages/unread-count") == before_cnt, "同日重跑去重，无重复消息")

    step("个人工作台聚合：banban 的在办案件与承办归属")
    wb = banban.get("/bureau/my/workbench")
    ok(any(c_["case_no"] for c_ in wb["myCases"]), f"banban 在办 {len(wb['myCases'])} 件")
    ok(all(True for _ in wb["myCases"]), "工作台聚合可用")

    step("数据级权限：SELF 下不可见非承办非参办案件（2080），负责人全局")
    # 造一件 banban 既非承办（owner=admin）也非参办（办案人员为李替补/李法制）的案件
    scope_case = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "范围隔离医院", "partyType": "PROVIDER",
        "officers": [{"name": "李替补", "certNo": "YB003", "duty": "LEAD"},
                      {"name": "李法制", "certNo": "FZ001", "duty": "MEMBER"}]})
    admin.call("PUT", "/config/case_view_scope?value=SELF")
    try:
        pg_bb = banban.get("/bureau/cases", params={"page": 1, "size": 100})
        ok(all(r["id"] != scope_case["id"] for r in pg_bb["rows"]),
           f"banban 列表不含隔离案（可见 {pg_bb['total']} 件=承办+参办）")
        banban.get(f"/bureau/cases/{scope_case['id']}", expect_code=2080)
        juzhang.get(f"/bureau/cases/{scope_case['id']}")  # 负责人不受限
        print("    PASS: 负责人全局可见")
        # 侧门读接口同样 2080（第二轮整改：详情之外的旁路一并封堵）
        banban.get(f"/bureau/cases/{scope_case['id']}/timeline", expect_code=2080)
        banban.get(f"/bureau/cases/{scope_case['id']}/archive-full", expect_code=2080)
        banban.get(f"/bureau/cases/{scope_case['id']}/attachments", expect_code=2080)
        banban.get(f"/bureau/cases/{scope_case['id']}/installments", expect_code=2080)
        banban.get(f"/bureau/cases/{scope_case['id']}/discretion-suggest", expect_code=2080)
        print("    PASS: 大事记/卷宗/附件/分期/裁量侧门全部 2080")
        # 写操作此前全线敞开：可对看不见的他人案件加证据/文书/送达
        banban.post(f"/bureau/cases/{scope_case['id']}/evidences", json={
            "type": "DOCUMENT", "name": "越权证据", "obtainedAt": str(today)}, expect_code=2080)
        banban.post(f"/bureau/cases/{scope_case['id']}/documents", json={
            "docType": "OTHER", "title": "越权文书"}, expect_code=2080)
        banban.post(f"/bureau/cases/{scope_case['id']}/notice", json={
            "content": "越权告知", "proposedFine": 1}, expect_code=2080)
        banban.get(f"/bureau/cases/{scope_case['id']}/documents/render",
                   params={"docType": "DECISION"}, expect_code=2080)
        print("    PASS: 写操作与文书渲染侧门一并 2080")
        sr_bb = banban.get("/bureau/search", params={"q": "范围隔离"})
        ok(all(c_["id"] != scope_case["id"] for c_ in sr_bb["cases"]), "SELF 下全局搜索不含隔离案")
        sr_ju = juzhang.get("/bureau/search", params={"q": "范围隔离"})
        ok(any(c_["id"] == scope_case["id"] for c_ in sr_ju["cases"]), "负责人搜索可见")
    finally:
        admin.call("PUT", "/config/case_view_scope?value=ALL")
    banban.get(f"/bureau/cases/{scope_case['id']}")
    print("    PASS: ALL 范围恢复")

    step("负责人直接延期：自动补记『申请即批准』审批单（凡批准必有单）")
    juzhang.post(f"/bureau/cases/{scope_case['id']}/extend", json={"days": 10, "reason": "直接批准回归"})
    aps_direct = admin.get(f"/bureau/cases/{scope_case['id']}/approvals")
    ok(any(a["kind"] == "EXTEND" and a["status"] == "APPROVED" and a["opinion"] == "负责人直接批准"
           and a["approver"] == "juzhang" for a in aps_direct), "直接延期已补单")

    step("待批列表：非裁决角色仅见本人申请")
    own_case = next(r for r in banban.get("/bureau/cases", params={"page": 1, "size": 50, "q": "审批立案医院"})["rows"])
    ap_pf = banban.post("/bureau/approvals", json={
        "kind": "SUSPEND", "caseId": own_case["id"], "reason": "待批过滤回归"})
    ok(any(a["id"] == ap_pf["id"] for a in banban.get("/bureau/approvals/pending")), "申请人可见本人待批")
    ok(all(a["id"] != ap_pf["id"] for a in fazhi.get("/bureau/approvals/pending")), "法制看不到他人待批")
    ok(any(a["id"] == ap_pf["id"] for a in juzhang.get("/bureau/approvals/pending")), "负责人全量可见")
    juzhang.post(f"/bureau/approvals/{ap_pf['id']}/decide", json={"approve": False, "opinion": "回归清理"})

    step("听证未举行不得决定（2075）；重复告知加重须载明变更理由（2077）")
    hg = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "听证守卫医院", "partyType": "PROVIDER",
        "amountInvolved": 200000,
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{hg['id']}/report", json={"content": "调查终结"})
    admin.post(f"/bureau/cases/{hg['id']}/notice", json={
        "content": "拟罚15万", "proposedFine": 150000, "proposedRecoup": 0})
    admin.post(f"/bureau/cases/{hg['id']}/statement", json={"hearingRequested": True})
    admin.post(f"/bureau/cases/{hg['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150000, "discretionReason": "x",
        "content": "x"}, expect_code=2075)
    print("    PASS: 申请听证未举行即决定被拒")
    # 再次告知抬高金额且不说明理由 → 2077
    admin.post(f"/bureau/cases/{hg['id']}/notice", json={
        "content": "改拟罚30万", "proposedFine": 300000, "proposedRecoup": 0}, expect_code=2077)
    admin.post(f"/bureau/cases/{hg['id']}/notice", json={
        "content": "改拟罚30万", "proposedFine": 300000, "proposedRecoup": 0,
        "changeReason": "复核发现新增两家分院同类违法事实，依据与认定金额变更"})
    print("    PASS: 加重再告知须载明变更理由")

    step("决定金额以历次告知最低额为上限（防重新告知绕过 2007）")
    admin.post(f"/bureau/cases/{hg['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 300000, "discretionReason": "x",
        "content": "x"}, expect_code=2007)
    print("    PASS: 按最低告知额 15 万卡住 30 万决定")

    step("执法人员按证号去重（2002）；重复添加同证号拒")
    admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "重复执法人员医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "王办案", "certNo": "YB001"}]},
        expect_code=2002)
    admin.post(f"/bureau/cases/{hg['id']}/officers",
               json={"name": "王办案", "certNo": "YB001", "duty": "MEMBER"}, expect_code=2002)
    print("    PASS: 同一执法证不能凑够两人")

    step("期限扣除区间校验：越界/倒挂/重叠/超上限均拒（2032）")
    ex_case = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "扣除校验医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    exid = ex_case["id"]
    admin.post(f"/bureau/cases/{exid}/exclusions",
               json={"reason": "APPRAISE", "startAt": "2000-01-01", "endAt": str(today)}, expect_code=2032)
    admin.post(f"/bureau/cases/{exid}/exclusions",
               json={"reason": "APPRAISE", "endAt": str(today)}, expect_code=2032)
    admin.post(f"/bureau/cases/{exid}/exclusions", json={
        "reason": "APPRAISE", "startAt": str(today),
        "endAt": str(today - datetime.timedelta(days=1))}, expect_code=2032)
    admin.post(f"/bureau/cases/{exid}/exclusions", json={
        "reason": "APPRAISE", "startAt": str(today),
        "endAt": str(today + datetime.timedelta(days=400))}, expect_code=2032)
    admin.post(f"/bureau/cases/{exid}/exclusions", json={
        "reason": "APPRAISE", "startAt": str(today), "endAt": str(today + datetime.timedelta(days=5))})
    admin.post(f"/bureau/cases/{exid}/exclusions", json={
        "reason": "TEST", "startAt": str(today), "endAt": str(today + datetime.timedelta(days=2))},
        expect_code=2032)
    print("    PASS: 立案前/缺起始/倒挂/超上限/重叠 全部拒绝")

    step("审批单裁决幂等：同一单二次裁决拒（防双击双执行）")
    ap_dup = banban.post("/bureau/approvals", json={
        "kind": "EXTEND", "caseId": exid, "payload": {"days": 5}, "reason": "幂等回归"})
    juzhang.post(f"/bureau/approvals/{ap_dup['id']}/decide", json={"approve": True, "opinion": "同意"})
    juzhang.post(f"/bureau/approvals/{ap_dup['id']}/decide",
                 json={"approve": True, "opinion": "重复点击"}, expect_code=2072)
    print("    PASS: 期限只顺延一次")

    step("参数不合法返回业务码而非 500（延期天数/日期格式）")
    juzhang.post(f"/bureau/cases/{exid}/extend", json={"reason": "漏传天数"}, expect_code=2100)
    juzhang.post(f"/bureau/cases/{exid}/extend", json={"days": "三十", "reason": "非数字"}, expect_code=2100)
    admin.post(f"/bureau/cases/{exid}/doc-deliveries",
               json={"method": "DIRECT", "deliveredAt": "2026/01/01"}, expect_code=2100)
    print("    PASS: 2100 参数提示替代 500")

    step("电子送达确认书须留痕（受送达人+电子地址）")
    admin.post(f"/bureau/cases/{exid}/e-delivery-consent", json={}, expect_code=2054)
    admin.post(f"/bureau/cases/{exid}/e-delivery-consent",
               json={"receiver": "扣除校验医院", "channel": "13800000000", "docNo": "DZSD-2026-001"})
    consent_docs = [d for d in admin.get(f"/bureau/cases/{exid}")["documents"]
                    if d.get("doc_type", d.get("docType")) == "E_DELIVERY_CONSENT"]
    ok(len(consent_docs) == 1, "确认书已入卷")

    step("案件移交：负责人变更承办人后归属生效")
    own_case = next(r for r in banban.get("/bureau/cases", params={"page": 1, "size": 50, "q": "审批立案医院"})["rows"])
    banban.post(f"/bureau/cases/{own_case['id']}/transfer-owner", json={"newOwner": "fazhi"}, expect_code=403)
    fz_unread0 = fazhi.get("/bureau/messages/unread-count")
    moved_own = juzhang.post(f"/bureau/cases/{own_case['id']}/transfer-owner", json={"newOwner": "fazhi"})
    ok(moved_own["ownerUser"] == "fazhi", "承办人已移交 fazhi")
    ok(fazhi.get("/bureau/messages/unread-count") > fz_unread0, "接收人收到移交消息")

    step("改密即吊销旧令牌（令牌版本戳）")
    admin.post("/system/users", json={
        "username": "tokentest", "password": "Init12345", "realName": "令牌测试",
        "roleCodes": ["HANDLER"]})
    tt = Api("tokentest", password="Init12345")
    tt.get("/auth/me")
    tt.post("/auth/change-password", json={"oldPassword": "Init12345", "newPassword": "Next12345"})
    r_old = requests.get(f"{BASE}/auth/me", timeout=10,
                         headers={"Authorization": f"Bearer {tt.token}"})
    ok(r_old.status_code in (401, 403), f"旧令牌已失效（HTTP {r_old.status_code}）")
    tt2 = Api("tokentest", password="Next12345")
    ok(tt2.get("/auth/me")["username"] == "tokentest", "新口令可登录")

    step("停用账号即断开在线会话")
    uid = next(u["id"] for u in admin.get("/system/users", params={"size": 200})["records"]
               if u["username"] == "tokentest")
    admin.call("PUT", f"/system/users/{uid}/enabled?enabled=false")
    r_dis = requests.get(f"{BASE}/auth/me", timeout=10,
                         headers={"Authorization": f"Bearer {tt2.token}"})
    ok(r_dis.status_code in (401, 403), f"停用后令牌立即失效（HTTP {r_dis.status_code}）")

    step("角色授权矩阵仅管理员可读")
    banban.get("/system/roles", expect_code=403)
    ok(len(admin.get("/system/roles")) >= 4, "管理员可读角色矩阵")

    step("OpenAPI 文档可用（对接厂商契约）")
    docs_r = requests.get(f"{BASE.replace('/api','')}/v3/api-docs", timeout=15,
                          headers={"Authorization": f"Bearer {admin.token}"})
    spec = docs_r.json()
    ok(docs_r.status_code == 200 and len(spec.get("paths", {})) > 60,
       f"OpenAPI paths={len(spec.get('paths', {}))}")

    # ============ 用户反馈直报（bug/需求）+ request-id 定位闭环 ============
    step("request-id：响应头返回并落审计日志")
    r_rid = requests.get(f"{BASE}/bureau/stats/overview", timeout=10,
                         headers={"Authorization": f"Bearer {admin.token}"})
    rid = r_rid.headers.get("X-Request-Id")
    ok(rid and len(rid) >= 12, f"X-Request-Id={rid}")
    banban.post("/bureau/clues", json={"source": "COMPLAINT", "content": "为审计写入一条",
                "suspectName": "审计对象", "suspectType": "PROVIDER", "receivedAt": str(today)})
    logs_rid = admin.get("/audit/logs")
    ok(any(l.get("request_id") for l in logs_rid), "审计日志已带 request_id")

    step("提交反馈（自动上下文+截图）→ 管理员收消息")
    admin_unread0 = admin.get("/bureau/messages/unread-count")
    png_1px = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    fb = banban.post("/bureau/feedback", json={
        "kind": "BUG", "title": "决定页偶发报错", "content": "点作出决定偶发系统内部错误",
        "pageRoute": "/case/detail/1", "caseRef": "1", "appVersion": "1.2.0",
        "userAgent": "E2E", "requestId": rid,
        "recentErrors": '[{"path":"/bureau/cases/1/decide","httpStatus":200,"bizCode":500}]',
        "screenshotBase64": png_1px})
    ok(fb["id"], f"反馈 #{fb['id']}")
    ok(admin.get("/bureau/messages/unread-count") > admin_unread0, "管理员收到新反馈消息")
    banban.post("/bureau/feedback", json={"kind": "GUESS", "title": "x", "content": "x"}, expect_code=2090)

    step("管理员列表/统计/截图/处理回复 → 提交人收消息")
    fl = admin.get("/bureau/feedback")
    row = next(r for r in fl["rows"] if r["id"] == fb["id"])
    ok(row["has_screenshot"] is True and row["request_id"] == rid, "上下文完整")
    ok(any(s["status"] == "NEW" for s in fl["byStatus"]), "状态统计")
    shot = requests.get(f"{BASE}/bureau/feedback/{fb['id']}/screenshot", timeout=10,
                        headers={"Authorization": f"Bearer {admin.token}"})
    ok(shot.status_code == 200 and shot.content[1:4] == b"PNG", "截图可取")
    shot_ju = requests.get(f"{BASE}/bureau/feedback/{fb['id']}/screenshot", timeout=10,
                           headers={"Authorization": f"Bearer {juzhang.token}"})
    ok(shot_ju.status_code == 403, "非本人非管理员取截图 403")
    shot_bb = requests.get(f"{BASE}/bureau/feedback/{fb['id']}/screenshot", timeout=10,
                           headers={"Authorization": f"Bearer {banban.token}"})
    ok(shot_bb.status_code == 200, "提交人本人可取")
    admin.post(f"/bureau/feedback/{fb['id']}/handle",
               json={"status": "RESOLVED"}, expect_code=2091)  # 解决须回复
    bb_unread_fb = banban.get("/bureau/messages/unread-count")
    admin.post(f"/bureau/feedback/{fb['id']}/handle",
               json={"status": "RESOLVED", "reply": "已修复：决定接口空指针，随 v1.2.1 发布"})
    ok(banban.get("/bureau/messages/unread-count") > bb_unread_fb, "提交人收到处理回复")

    step("提交人确认关闭闭环 + 越权关闭拒")
    juzhang.post(f"/bureau/feedback/{fb['id']}/close", expect_code=2091)  # 非本人
    banban.post(f"/bureau/feedback/{fb['id']}/close")
    mine = banban.get("/bureau/feedback/mine")
    ok(any(m["id"] == fb["id"] and m["status"] == "CLOSED" for m in mine), "反馈 CLOSED 闭环")

    step("已解决反馈超期未确认 → 提醒任务自动关闭并通知")
    fb2 = banban.post("/bureau/feedback", json={
        "kind": "QUESTION", "title": "自动关闭回归", "content": "提交后不确认，等系统关"})
    admin.post(f"/bureau/feedback/{fb2['id']}/handle",
               json={"status": "RESOLVED", "reply": "已答复"})
    admin.call("PUT", "/config/feedback_autoclose_days?value=0")
    try:
        admin.post("/bureau/messages/generate-reminders")
        mine2 = banban.get("/bureau/feedback/mine")
        ok(any(m["id"] == fb2["id"] and m["status"] == "CLOSED" for m in mine2), "超期已自动关闭")
    finally:
        admin.call("PUT", "/config/feedback_autoclose_days?value=7")

    step("反馈导出 CSV")
    r_fcsv = requests.get(f"{BASE}/bureau/feedback/export", timeout=10,
                          headers={"Authorization": f"Bearer {admin.token}"})
    ok(r_fcsv.status_code == 200 and "决定页偶发报错".encode("utf-8") in r_fcsv.content, "CSV 含反馈内容")

    # ============ 五期：协同与上线 ============
    step("智能监控线索批量导入（source=MONITOR）")
    imp = admin.post("/bureau/clues/import", json=[
        {"suspectName": "某连锁药店A", "suspectType": "PROVIDER", "content": "监控疑点：夜间集中刷卡"},
        {"suspectName": "某连锁药店B", "suspectType": "PROVIDER", "content": "监控疑点：单卡日购药超量"},
        {"suspectName": "参保人郑某", "suspectType": "INDIVIDUAL", "content": "监控疑点：跨机构重复开药"},
    ])
    ok(imp["imported"] == 3 and len(imp["clueNos"]) == 3, f"导入 {imp['imported']} 条")

    step("统计上报：年度月报+合计口径")
    rep = admin.get("/bureau/stats/report", params={"year": today.year})
    ok(len(rep["monthly"]) == 12, "12 个月份行")
    this_month = next(m for m in rep["monthly"] if m["month"] == today.month)
    ok(this_month["filed"] >= 5, f"本月立案 {this_month['filed']}")
    ok(float(rep["totals"]["reward_paid"]) >= 5000, f"举报奖励发放 {rep['totals']['reward_paid']}")

    step("委托执法档案（第8条）：必填校验（2068）+登记")
    admin.post("/bureau/delegates", json={"name": "x"}, expect_code=2068)
    admin.post("/bureau/delegates", json={
        "name": "某会计师事务所", "agreementNo": "示医保委〔2026〕1号",
        "scope": "协助开展定点医药机构财务专项检查（行政强制措施权不委托）",
        "startAt": str(today), "endAt": str(today + datetime.timedelta(days=365)),
        "publishedAt": str(today)})
    ok(len(admin.get("/bureau/delegates")) >= 1, "委托档案已登记")

    step("集体讨论签字确认（局令44条）：名单必填（2069）+确认")
    mt = admin.get(f"/bureau/cases/{cid}")["meetings"][0]
    admin.post(f"/bureau/cases/{cid}/meetings/{mt['id']}/sign", json={}, expect_code=2069)
    admin.post(f"/bureau/cases/{cid}/meetings/{mt['id']}/sign",
               json={"signNames": "局长、分管副局长、基金监督处长、法规处长"})
    ok(admin.get(f"/bureau/cases/{cid}")["meetings"][0]["sign_confirmed"] is True, "签字确认入卷")

    step("重大处罚政府备案督办：未备案案件出现在预警列表")
    sup5 = admin.get("/bureau/stats/supervision")
    ok("govRecordMissing" in sup5, "备案预警项存在")

    step("审计留痕（局令第4/35条+等保）：写操作已入 sys_audit_log")
    logs = admin.get("/audit/logs")
    ok(len(logs) >= 50 and any("/api/bureau/" in l["path"] for l in logs), f"审计日志 {len(logs)} 条（近200）")

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
        # 期限内决定须先留痕当事人已陈述申辩或明确放弃（2076）
        admin.post(f"/bureau/cases/{lnid}/statement",
                   json={"statement": "承认冒名购药", "statementReview": "属实，维持拟处罚"})

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
