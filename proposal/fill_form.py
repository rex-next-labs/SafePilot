"""공식 접수양식(DOCX)을 채운다.

- 01장 참가 정보, 04장 성명·앱명: applicant.json (개인정보라 git에 올리지 않는다)
- 02장 1~8번 박스: draft.md

사용법: python fill_form.py [출력 파일명]
양식 원본(contest-application-consent.docx)은 수정하지 않는다.
04장의 동의 체크, 본인 서명, 서명일자는 본인이 직접 작성한다.
"""
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

HERE = Path(__file__).resolve().parent
FORM = HERE / "contest-application-consent.docx"
DRAFT = HERE / "draft.md"
APPLICANT = HERE / "applicant.json"
OUT = HERE / (sys.argv[1] if len(sys.argv) > 1 else "SafePilot_기획서.docx")

TAGS = ["app_summary", "planning_background", "vehicle_scenario", "key_features",
        "pleos_plan", "reference_files", "reference_link", "shortform_link"]


def parse_draft(text):
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    app_name = re.search(r"^앱명:\s*(.+)$", text, re.M).group(1).strip()
    sections = {}
    for m in re.finditer(r"^## (\d)\..*?\n(.*?)(?=^## \d\.|\Z)", text, re.M | re.S):
        sections[int(m.group(1))] = m.group(2).strip()
    return app_name, sections


def run(text, bold=False):
    rpr = "<w:rPr><w:b/><w:color w:val=\"16324F\"/></w:rPr>" if bold else ""
    return f'<w:r>{rpr}<w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def para(text, kind="body"):
    spacing = '<w:spacing w:before="0" w:after="60" w:line="300" w:lineRule="auto"/>'
    if kind == "heading":
        ppr = f'<w:pPr><w:keepNext/><w:spacing w:before="120" w:after="60" w:line="300" w:lineRule="auto"/></w:pPr>'
        return f"<w:p>{ppr}{run(text, bold=True)}</w:p>"
    if kind == "bullet":
        ppr = f'<w:pPr>{spacing}<w:ind w:left="300" w:hanging="200"/></w:pPr>'
        return f"<w:p>{ppr}{run('· ' + text)}</w:p>"
    return f"<w:p><w:pPr>{spacing}</w:pPr>{run(text)}</w:p>"


def section_xml(body):
    out = []
    for line in body.splitlines():
        line = line.rstrip()
        if not line:
            continue
        indent = len(line) - len(line.lstrip())
        s = line.strip()
        if re.fullmatch(r"\*\*(.+)\*\*", s):
            out.append(para(s[2:-2], "heading"))
        elif s.startswith("- "):
            p = para(s[2:], "bullet")
            if indent:  # 하위 글머리표는 한 단계 더 들여쓴다
                p = p.replace('w:left="300"', 'w:left="600"')
            out.append(p)
        else:
            out.append(para(s))
    return "".join(out)


def rewrite_line(doc, needle, transform, start=0):
    """needle이 있는 문단의 글자를 이어 붙여 transform으로 바꾼 뒤 첫 글자 칸에 넣고 나머지 칸은 비운다.

    양식은 한 줄이 여러 run으로 쪼개져 있어서 run 단위로는 바꿀 수 없다."""
    at = doc.index(needle, start)
    p_start, p_end = doc.rindex("<w:p ", 0, at), doc.index("</w:p>", at)
    line = doc[p_start:p_end]
    texts = re.findall(r"<w:t(?: [^>]*)?>([^<]*)</w:t>", line)
    new_text = transform("".join(texts))
    first = [True]

    def put(m):
        if first[0]:
            first[0] = False
            return f'<w:t xml:space="preserve">{escape(new_text)}</w:t>'
        return "<w:t></w:t>"

    line = re.sub(r"<w:t(?: [^>]*)?>[^<]*</w:t>", put, line)
    return doc[:p_start] + line + doc[p_end:]


def put_in_cell(cell_p_end_marker_pos, doc, text):
    """빈 표 칸 문단(</w:p> 위치)에 글자 run을 넣는다."""
    run_xml = f'<w:r><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'
    return doc[:cell_p_end_marker_pos] + run_xml + doc[cell_p_end_marker_pos:]


def fill_row(doc, row_start, values):
    """row_start부터 시작하는 표 행의 칸을 순서대로 채운다. None인 칸은 건너뛴다."""
    pos = row_start
    for value in values:
        pos = doc.index("<w:tc>", pos)
        p_end = doc.index("</w:p>", pos)
        if value:
            doc = put_in_cell(p_end, doc, value)
        pos = doc.index("</w:tc>", pos)
    return doc


def fill_applicant(doc, a, app_name):
    y, m, d = a["date"].split("-")
    doc = rewrite_line(doc, "참가 단위: ", lambda t: t.replace(f"□ {a['unit']}", f"■ {a['unit']}"))
    doc = rewrite_line(doc, "참가 부문: ", lambda t: re.sub(rf"□ ({re.escape(a['category'])})", r"■ \1", t))
    doc = rewrite_line(doc, "팀명(개인 참가 시 성명): ", lambda t: re.sub(r"_+", a["team_name"], t, count=1))
    doc = rewrite_line(doc, " 프로젝트명: _", lambda t: re.sub(r"_+", app_name, t, count=1))
    doc = rewrite_line(doc, "대표자 성명: ", lambda t: re.sub(
        r"(역할: )_+", lambda _m: _m.group(1) + a["role"],
        re.sub(r"(대표자 성명: )_+\s*", lambda _m: _m.group(1) + a["rep_name"] + "        ", t)))
    if a.get("affiliation"):
        doc = rewrite_line(doc, "대표자 소속(", lambda t: re.sub(r"_+", a["affiliation"], t, count=1))
    doc = rewrite_line(doc, "대표자 이메일:", lambda t: re.sub(r"_+", a["email"], t))
    doc = rewrite_line(doc, "대표자 휴대폰번호", lambda t: re.sub(r"_+", a["phone"], t))
    doc = rewrite_line(doc, "참가 인원: ", lambda t: re.sub(r"_+명", f"{a['members']}명", t))
    doc = rewrite_line(doc, "작성일: ", lambda t: re.sub(r"_+년 _+월 _+일", f"{y}년 {int(m)}월 {int(d)}일", t))
    # B. 참가 구성원 표의 첫 행: 성명, 소속, 휴대폰, 이메일, 역할
    header_end = doc.index("</w:tr>", doc.index("담당 역할"))
    doc = fill_row(doc, header_end, [a["rep_name"], a.get("affiliation"), a["phone"], a["email"], a["role"]])
    # 04장: 대표자 행의 성명 칸(동의·서명·일자는 본인이 작성), 하단 앱명
    sec4 = doc.index("동의 확인 및 서명")
    rep_cell = doc.index("<w:t>대표자</w:t>", sec4)
    doc = fill_row(doc, doc.index("</w:tc>", rep_cell), [a["rep_name"]])
    doc = rewrite_line(doc, "앱명 / 프로젝트명: _", lambda t: re.sub(r"_+", app_name, t), start=sec4)
    return doc


def fill(doc, app_name, sections):
    # 02장의 "앱명 / 프로젝트명: ____" 줄에 앱 이름을 채운다 (01장 것은 직접 작성)
    first_box = doc.index(f'<w:tag w:val="{TAGS[0]}"/>')
    label = doc.rindex(" 프로젝트명: ", 0, first_box)
    p_start, p_end = doc.rindex("<w:p ", 0, label), doc.index("</w:p>", label)
    line = doc[p_start:p_end]
    line = re.sub(r"( 프로젝트명: )_+", lambda m: m.group(1) + escape(app_name), line)
    line = re.sub(r"<w:t>_+</w:t>", "<w:t></w:t>", line)
    doc = doc[:p_start] + line + doc[p_end:]
    for n, tag in enumerate(TAGS, start=1):
        i = doc.index(f'<w:tag w:val="{tag}"/>')
        p_start = doc.rindex("<w:p ", 0, i)
        p_end = doc.index("</w:p>", i) + len("</w:p>")
        doc = doc[:p_start] + section_xml(sections[n]) + doc[p_end:]
        # 내용이 길어 박스가 페이지를 넘어가도 잘리지 않도록 행 분할 금지를 푼다
        row_start = doc.rindex("<w:tr ", 0, p_start)
        row_head_end = doc.index("</w:trPr>", row_start)
        doc = doc[:row_start] + doc[row_start:row_head_end].replace("<w:cantSplit/>", "") + doc[row_head_end:]
    return doc


def main():
    app_name, sections = parse_draft(DRAFT.read_text(encoding="utf-8"))
    missing = [n for n in range(1, 9) if n not in sections]
    if missing:
        sys.exit(f"draft.md에 없는 항목: {missing}")
    with zipfile.ZipFile(FORM) as zin, zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "word/document.xml":
                doc = fill(data.decode("utf-8"), app_name, sections)
                if APPLICANT.exists():
                    doc = fill_applicant(doc, json.loads(APPLICANT.read_text(encoding="utf-8")), app_name)
                else:
                    print("applicant.json이 없어 01장 참가 정보는 비워 둠")
                data = doc.encode("utf-8")
            zout.writestr(item, data)
    print(f"작성 완료: {OUT.name}")


if __name__ == "__main__":
    main()
