"""공식 접수양식(DOCX)을 채운다.

- 01장 참가 정보, 04장 성명·앱명: applicant.json (개인정보라 git에 올리지 않는다)
- 02장 1~8번 박스: draft.md (`![설명](diagrams/x.png)` 줄은 그림으로 넣는다)

사용법: python fill_form.py [출력 파일명]
양식 원본(contest-application-consent.docx)은 수정하지 않는다.
04장의 동의 체크, 본인 서명, 서명일자는 본인이 직접 작성한다.
"""
import json
import re
import struct
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
        # 박스(표 칸) 안에서 keepNext를 쓰면 Word가 칸 전체를 다음 페이지로 넘겨 앞 페이지가 비므로 쓰지 않는다
        ppr = f'<w:pPr><w:spacing w:before="120" w:after="60" w:line="300" w:lineRule="auto"/></w:pPr>'
        return f"<w:p>{ppr}{run(text, bold=True)}</w:p>"
    if kind == "bullet":
        ppr = f'<w:pPr>{spacing}<w:ind w:left="300" w:hanging="200"/></w:pPr>'
        return f"<w:p>{ppr}{run('· ' + text)}</w:p>"
    return f"<w:p><w:pPr>{spacing}</w:pPr>{run(text)}</w:p>"


# 본문에 넣을 그림: (관계 id, 박스 안 파일 이름, 원본 경로)
IMAGES = []
EMU_PER_INCH = 914400
MAX_W_IN, MAX_H_IN = 6.3, 8.0  # 박스 안쪽 폭(약 6.7인치)보다 조금 작게, 한 페이지를 넘지 않게


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    return struct.unpack(">II", head[16:24])


def image_para(alt, rel_path):
    src = (HERE / rel_path).resolve()
    w_px, h_px = png_size(src)
    w_in = MAX_W_IN
    h_in = w_in * h_px / w_px
    if h_in > MAX_H_IN:
        h_in = MAX_H_IN
        w_in = h_in * w_px / h_px
    cx, cy = int(w_in * EMU_PER_INCH), int(h_in * EMU_PER_INCH)
    n = len(IMAGES) + 1
    rid, name = f"rIdSafePilot{n}", f"safepilot_{n}.png"
    IMAGES.append((rid, name, src))
    a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    pic = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    return (
        '<w:p><w:pPr><w:spacing w:before="60" w:after="120"/><w:jc w:val="center"/></w:pPr><w:r><w:drawing>'
        f'<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="{cx}" cy="{cy}"/>'
        f'<wp:docPr id="{9000 + n}" name="{name}" descr="{escape(alt)}"/>'
        f'<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="{a}" noChangeAspect="1"/></wp:cNvGraphicFramePr>'
        f'<a:graphic xmlns:a="{a}"><a:graphicData uri="{pic}"><pic:pic xmlns:pic="{pic}">'
        f'<pic:nvPicPr><pic:cNvPr id="{9000 + n}" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr>'
        f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>'
        '</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>'
    )


def section_xml(body):
    out = []
    last_heading = None
    for line in body.splitlines():
        line = line.rstrip()
        if not line:
            continue
        indent = len(line) - len(line.lstrip())
        s = line.strip()
        img = re.fullmatch(r"!\[(.*?)\]\((.+?)\)", s)
        if img:
            p = image_para(img.group(1), img.group(2))
            if last_heading is not None:
                # 표 안에서는 keepNext가 무시되어 제목만 앞 페이지에 남으므로, 제목과 그림을 한 문단으로 묶는다
                out.pop()
                p = p.replace(
                    '<w:spacing w:before="60" w:after="120"/><w:jc w:val="center"/></w:pPr>',
                    '<w:spacing w:before="120" w:after="120"/></w:pPr>'
                    + run(last_heading, bold=True) + "<w:r><w:br/></w:r>",
                )
            out.append(p)
            last_heading = None
            continue
        last_heading = None
        if re.fullmatch(r"\*\*(.+)\*\*", s):
            out.append(para(s[2:-2], "heading"))
            last_heading = s[2:-2]
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
        # 본문을 먼저 채워야 그림 목록(IMAGES)이 정해지고, 관계 파일과 콘텐츠 형식에 반영할 수 있다
        doc = fill(zin.read("word/document.xml").decode("utf-8"), app_name, sections)
        if APPLICANT.exists():
            doc = fill_applicant(doc, json.loads(APPLICANT.read_text(encoding="utf-8")), app_name)
        else:
            print("applicant.json이 없어 01장 참가 정보는 비워 둠")
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "word/document.xml":
                data = doc.encode("utf-8")
            elif item.filename == "word/_rels/document.xml.rels" and IMAGES:
                rels = "".join(
                    f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/{name}"/>'
                    for rid, name, _ in IMAGES)
                data = data.decode("utf-8").replace("</Relationships>", rels + "</Relationships>").encode("utf-8")
            elif item.filename == "[Content_Types].xml" and IMAGES:
                t = data.decode("utf-8")
                if 'Extension="png"' not in t:
                    t = t.replace("<Default Extension=\"xml\"", '<Default Extension="png" ContentType="image/png"/><Default Extension="xml"', 1)
                data = t.encode("utf-8")
            zout.writestr(item, data)
        for _, name, src in IMAGES:
            zout.write(src, f"word/media/{name}")
    print(f"작성 완료: {OUT.name}")


if __name__ == "__main__":
    main()
