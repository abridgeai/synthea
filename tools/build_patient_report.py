#!/usr/bin/env python3
"""Render a physician-friendly HTML chart from a Synthea FHIR R4 patient bundle.

Builds a single self-contained HTML file (inline CSS, only a tiny expand/collapse
script) showing: patient banner, active problem list, medications, allergies,
collapsible encounters (each with vitals, diagnoses/procedures/orders, the LLM
clinical note, and the encounter transcript), then Labs and Imaging sections.
Requires the LLM notes/transcripts to have been injected into the bundle
(exporter.fhir.llm.inject=true); notes/transcripts are read from the bundle itself.

Usage:
  python3 tools/build_patient_report.py [BUNDLE.json] [OUT.html]

With no args it picks the single patient bundle in ./output/fhir/ (ignoring the
hospital/practitioner Information files) and writes ./output/patient_report.html.
Paths are resolved relative to the repo root (this file lives in tools/)."""
import json, glob, base64, html, re, os, sys
from datetime import datetime
from collections import defaultdict, OrderedDict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTDIR = os.path.join(REPO, "output")
if len(sys.argv) > 1:
    BUNDLE = sys.argv[1]
else:
    _cands = [f for f in glob.glob(os.path.join(OUTDIR, "fhir", "*.json"))
              if "Information" not in os.path.basename(f)]
    if not _cands:
        sys.exit("No patient bundle found in output/fhir/ (pass one as an argument).")
    if len(_cands) > 1:
        print("Multiple bundles found; using the first. Pass a path to choose:\n  "
              + "\n  ".join(sorted(_cands)), file=sys.stderr)
    BUNDLE = sorted(_cands)[0]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(OUTDIR, "patient_report.html")
b = json.load(open(BUNDLE))
res = [e["resource"] for e in b["entry"]]
byurl = {e.get("fullUrl"): e["resource"] for e in b["entry"]}

def R(rt): return [r for r in res if r.get("resourceType") == rt]
def b64(d):
    try: return base64.b64decode(d).decode("utf-8", "replace")
    except Exception: return ""
def clean(s): return re.sub(r"\d+$", "", s or "")
def dt(s): return s[:10] if s else ""
def dtt(s):
    if not s: return ""
    try: return datetime.fromisoformat(s).strftime("%Y-%m-%d %H:%M")
    except Exception: return s[:16].replace("T", " ")
def code_disp(cc):
    if not cc: return ""
    if cc.get("text"): return cc["text"]
    co = cc.get("coding", [{}])[0]
    return co.get("display", co.get("code", ""))
def enc_ref(r):
    e = r.get("encounter") or {}
    if e.get("reference"): return e["reference"]
    ctx = r.get("context", {}).get("encounter", [])
    return ctx[0]["reference"] if ctx else None

# ---------- markdown + transcript renderers ----------
def inline(s):
    s = html.escape(s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
    s = re.sub(r"(?<!\*)\*(?!\*)(.+?)\*(?!\*)", r"<em>\1</em>", s)
    return s
def md(text):
    out, in_ul, in_ol = [], False, False
    def close():
        nonlocal in_ul, in_ol
        if in_ul: out.append("</ul>"); in_ul = False
        if in_ol: out.append("</ol>"); in_ol = False
    for ln in (text or "").replace("\r\n", "\n").split("\n"):
        st = ln.strip()
        if not st: close(); continue
        h = re.match(r"^(#{1,6})\s+(.*)", st)
        if h:
            close(); lvl = min(len(h.group(1)) + 3, 6)
            out.append(f"<h{lvl}>{inline(h.group(2))}</h{lvl}>"); continue
        m = re.match(r"^[-*]\s+(.*)", st)
        if m:
            if not in_ul: close(); out.append("<ul>"); in_ul = True
            out.append(f"<li>{inline(m.group(1))}</li>"); continue
        m = re.match(r"^\d+\.\s+(.*)", st)
        if m:
            if not in_ol: close(); out.append("<ol>"); in_ol = True
            out.append(f"<li>{inline(m.group(1))}</li>"); continue
        close(); out.append(f"<p>{inline(st)}</p>")
    close()
    return "\n".join(out)
def transcript_html(text):
    out = []
    for ln in (text or "").replace("\r\n", "\n").split("\n"):
        st = ln.strip()
        if not st: continue
        m = re.match(r"^([A-Za-z][A-Za-z .]{0,15}?):\s*(.*)", st)
        if m:
            who, rest = m.group(1), m.group(2)
            wu = who.upper().replace(".", "")
            cls = "dr" if wu in ("DR", "DOCTOR", "CLINICIAN", "PROVIDER", "MD", "NP") else \
                  ("pt" if wu in ("PT", "PATIENT") else "other")
            out.append(f'<div class="turn {cls}"><span class="who">{html.escape(who)}</span>'
                       f'<span class="say">{html.escape(rest)}</span></div>')
        else:
            out.append(f'<div class="turn cont">{html.escape(st)}</div>')
    return "\n".join(out)

# ---------- patient ----------
p = R("Patient")[0]
nm = p["name"][0]
name = f'{clean(" ".join(nm.get("given", [])))} {clean(nm.get("family",""))}'.strip()
gender = p.get("gender", "").capitalize()
bd = p.get("birthDate", "")
addr = p.get("address", [{}])[0]
address = f'{" ".join(addr.get("line", []))}, {addr.get("city","")}, {addr.get("state","")} {addr.get("postalCode","")}'
mrn = ""
for idf in p.get("identifier", []):
    t = idf.get("type", {}).get("text", "") or code_disp(idf.get("type", {}))
    if "Medical Record" in t or (idf.get("system", "").endswith("mrn")):
        mrn = idf.get("value", "")
if not mrn and p.get("identifier"): mrn = p["identifier"][0].get("value", "")
def ext(url_frag):
    for e in p.get("extension", []):
        if url_frag in e.get("url", ""):
            for sub in e.get("extension", []):
                if sub.get("url") == "text": return sub["valueString"]
    return ""
race, eth = ext("us-core-race"), ext("us-core-ethnicity")

# reference date = latest encounter end
encs = R("Encounter")
all_dates = [e.get("period", {}).get("end") or e.get("period", {}).get("start") for e in encs]
ref = max(dt(d) for d in all_dates if d)
age = (datetime.fromisoformat(ref) - datetime.fromisoformat(bd)).days // 365 if bd else "?"

# ---------- index notes/transcripts/observations by encounter ----------
note_by_enc, trans_by_enc = {}, {}
for r in R("DiagnosticReport"):
    if any(c.get("code") == "34117-2" for c in r.get("code", {}).get("coding", [])):
        ref_e = enc_ref(r)
        if ref_e and r.get("presentedForm"):
            note_by_enc[ref_e] = b64(r["presentedForm"][0]["data"])
for r in R("DocumentReference"):
    codes = r.get("type", {}).get("coding", [])
    is_t = any((c.get("code") == "transcript") or ("transcript" in (c.get("display", "").lower())) for c in codes)
    if is_t:
        ref_e = enc_ref(r)
        if ref_e and r.get("content"):
            trans_by_enc[ref_e] = b64(r["content"][0]["attachment"]["data"])

obs_by_enc = defaultdict(list)
labs, imaging_obs = [], []
for r in R("Observation"):
    cats = [cc.get("code") for c in r.get("category", []) for cc in c.get("coding", [])]
    ref_e = enc_ref(r)
    entry = (r, cats, ref_e)
    if "laboratory" in cats: labs.append(entry)
    elif "imaging" in cats: imaging_obs.append(entry)
    if ref_e: obs_by_enc[ref_e].append(entry)

cond_by_enc, proc_by_enc, med_by_enc = defaultdict(list), defaultdict(list), defaultdict(list)
for r in R("Condition"): cond_by_enc[enc_ref(r)].append(r)
for r in R("Procedure"): proc_by_enc[enc_ref(r)].append(r)
for r in R("MedicationRequest"): med_by_enc[enc_ref(r)].append(r)

def obs_value(r):
    if "valueQuantity" in r:
        v = r["valueQuantity"]; return f'{v.get("value","")} {v.get("unit","")}'.strip()
    if "valueCodeableConcept" in r: return code_disp(r["valueCodeableConcept"])
    if "valueString" in r: return r["valueString"]
    if r.get("component"):  # e.g. BP
        parts = []
        for c in r["component"]:
            d = code_disp(c.get("code", {}))
            vq = c.get("valueQuantity", {})
            short = "Sys" if "Systolic" in d else ("Dia" if "Diastolic" in d else d)
            parts.append(f'{short} {vq.get("value","")}')
        u = r["component"][0].get("valueQuantity", {}).get("unit", "")
        return "/".join(str(x).split()[-1] for x in parts) + (f" {u}" if u else "") \
               if all("Sys" in code_disp(c.get("code",{})) or "Dia" in code_disp(c.get("code",{})) for c in r["component"]) \
               else "; ".join(parts)
    return ""

# vitals to surface prominently
VITAL_ORDER = ["Blood pressure", "Heart rate", "Respiratory rate", "Body temperature",
               "Oxygen saturation", "Body Height", "Body Weight", "Body mass index", "Pain severity"]
def vital_key(name):
    for i, v in enumerate(VITAL_ORDER):
        if name.lower().startswith(v.lower()[:6]): return i
    return 99

# ---------- HTML ----------
CSS = """
:root{--ink:#1a2230;--muted:#5b6472;--line:#e3e8ef;--bg:#f6f8fb;--card:#fff;
--accent:#2b6cb0;--accent2:#0b7285;--warn:#b4530a;--danger:#b02a37;--ok:#2f855a;}
*{box-sizing:border-box}
body{margin:0;font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
color:var(--ink);background:var(--bg);}
.wrap{max-width:1040px;margin:0 auto;padding:0 20px 80px}
header.banner{background:linear-gradient(135deg,#243b53,#334e68);color:#fff;padding:22px 0;margin-bottom:0}
header.banner .wrap{display:flex;justify-content:space-between;align-items:flex-end;gap:20px;flex-wrap:wrap;padding-bottom:0}
header.banner h1{margin:0 0 4px;font-size:26px;letter-spacing:.2px}
header.banner .sub{color:#cbd7e6;font-size:14px}
header.banner .demo{text-align:right;font-size:13px;color:#e2e8f0;line-height:1.7}
header.banner .demo b{color:#fff}
nav.toc{position:sticky;top:0;z-index:5;background:#fff;border-bottom:1px solid var(--line);
box-shadow:0 1px 4px rgba(0,0,0,.04)}
nav.toc .wrap{display:flex;gap:6px;flex-wrap:wrap;padding-top:10px;padding-bottom:10px}
nav.toc a{font-size:13px;text-decoration:none;color:var(--accent);padding:5px 11px;border:1px solid var(--line);
border-radius:20px;background:#fff}
nav.toc a:hover{background:#eef4fb}
section{margin-top:30px}
h2.sec{font-size:19px;margin:0 0 12px;padding-bottom:7px;border-bottom:2px solid var(--accent);color:#243b53;
display:flex;align-items:center;gap:9px}
h2.sec .n{font-size:12px;font-weight:600;color:var(--muted);background:#eef2f7;border-radius:12px;padding:1px 9px}
.encctl{margin-left:auto;display:flex;gap:6px}
.encctl button{font:inherit;font-size:12px;color:var(--accent);background:#fff;border:1px solid var(--line);
border-radius:6px;padding:3px 10px;cursor:pointer}
.encctl button:hover{background:#eef4fb}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
table{width:100%;border-collapse:collapse;font-size:13.5px;background:#fff;border:1px solid var(--line);border-radius:8px;overflow:hidden}
th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);vertical-align:top}
th{background:#f0f4f9;color:#41506a;font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.4px}
tr:last-child td{border-bottom:none}
.pill{display:inline-block;font-size:11px;font-weight:600;padding:2px 8px;border-radius:10px;background:#eef2f7;color:#41506a}
.pill.amb{background:#e6f0fb;color:#1c5088}.pill.emer{background:#fdeaea;color:#a12730}
.pill.imp{background:#fff2e0;color:#8a4b00}.pill.active{background:#e6f5ec;color:#256b45}
.enc{background:#fff;border:1px solid var(--line);border-radius:12px;margin-bottom:12px;overflow:hidden}
.enc>summary.hd{display:flex;justify-content:space-between;gap:12px;align-items:baseline;padding:12px 16px 12px 34px;
background:#f7fafc;flex-wrap:wrap;cursor:pointer;list-style:none;position:relative}
.enc>summary.hd::-webkit-details-marker{display:none}
.enc>summary.hd::before{content:"\\25B8";position:absolute;left:14px;top:13px;color:var(--muted);font-size:12px}
.enc[open]>summary.hd::before{content:"\\25BE"}
.enc[open]>summary.hd{border-bottom:1px solid var(--line)}
.enc>summary.hd:hover{background:#eef4fb}
.enc .hd .ttl{font-weight:600;font-size:15.5px;color:#243b53;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.enc .hd .rsn{font-weight:400;font-size:13px;color:var(--muted)}
.enc .hd .date{font-size:13px;color:var(--muted);white-space:nowrap}
.enc .body{padding:14px 16px}
.vitrow{display:flex;flex-wrap:wrap;gap:8px;margin:2px 0 14px}
.vit{background:#f4f8fc;border:1px solid #e0e9f3;border-radius:8px;padding:6px 10px;font-size:12.5px;min-width:92px}
.vit .lab{display:block;color:var(--muted);font-size:10.5px;text-transform:uppercase;letter-spacing:.3px}
.vit .val{font-weight:600;font-size:14px;color:#22303f}
.items{display:flex;flex-wrap:wrap;gap:18px;margin-bottom:12px}
.items .col{flex:1;min-width:200px}
.items h4{margin:0 0 5px;font-size:12px;text-transform:uppercase;letter-spacing:.4px;color:var(--accent2)}
.items ul{margin:0;padding-left:16px;font-size:13px}.items li{margin:2px 0}
.note{background:#fcfdff;border:1px solid #e4ecf5;border-left:3px solid var(--accent);border-radius:8px;
padding:4px 16px;margin-top:6px}
.note h4,.note h5{color:#243b53;margin:12px 0 4px}.note p{margin:6px 0}.note ul{margin:6px 0}
.note::before{content:"Clinical note";display:block;font-size:11px;text-transform:uppercase;letter-spacing:.5px;
color:var(--muted);margin:10px 0 2px;font-weight:600}
details.tr{margin-top:10px;border:1px solid var(--line);border-radius:8px;background:#fbfcfe}
details.tr summary{cursor:pointer;padding:9px 14px;font-size:12.5px;font-weight:600;color:var(--accent2);
list-style:none}
details.tr summary::-webkit-details-marker{display:none}
details.tr summary::before{content:"\\25B8 ";color:var(--muted)}
details.tr[open] summary::before{content:"\\25BE "}
.tbody{padding:6px 16px 14px}
.turn{margin:7px 0;font-size:13.5px}.turn .who{font-weight:700;color:#334e68;margin-right:6px}
.turn.dr .who{color:var(--accent)}.turn.pt .who{color:var(--accent2)}
.turn.cont{color:#4a5568;padding-left:8px}
.muted{color:var(--muted)}.small{font-size:12.5px}
.empty{color:var(--muted);font-style:italic;font-size:13px}
footer{margin-top:40px;padding-top:14px;border-top:1px solid var(--line);font-size:12px;color:var(--muted)}
@media print{nav.toc{position:static}body{background:#fff}.enc,.card,table{break-inside:avoid}}
@media(max-width:720px){.grid{grid-template-columns:1fr}header.banner .wrap{flex-direction:column;align-items:flex-start}header.banner .demo{text-align:left}}
"""

def esc(s): return html.escape(str(s or ""))
out = []
out.append(f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(name)} — Patient Chart</title><style>{CSS}</style></head><body>""")

out.append(f"""<header class="banner"><div class="wrap">
<div><h1>{esc(name)}</h1>
<div class="sub">Synthetic patient record · generated by Synthea · MRN {esc(mrn)}</div></div>
<div class="demo">
<b>{esc(age)} y/o {esc(gender)}</b> &nbsp; DOB {esc(bd)}<br>
{esc(race)}{(' · '+esc(eth)) if eth else ''}<br>
{esc(address)}</div>
</div></header>""")

# counts
active_cond = [r for r in R("Condition")
               if r.get("clinicalStatus", {}).get("coding", [{}])[0].get("code") == "active"]
out.append("""<nav class="toc"><div class="wrap">
<a href="#problems">Problem List</a><a href="#meds">Medications</a>
<a href="#allergies">Allergies</a><a href="#encounters">Encounters</a>
<a href="#labs">Labs</a><a href="#imaging">Imaging</a></div></nav>""")
out.append('<div class="wrap">')

# ---- Problem list ----
seen = OrderedDict()
for r in active_cond:
    d = code_disp(r.get("code", {}))
    if d not in seen: seen[d] = dt(r.get("onsetDateTime"))
out.append('<section id="problems"><h2 class="sec">Active Problem List '
           f'<span class="n">{len(seen)}</span></h2>')
if seen:
    out.append("<table><tr><th>Condition</th><th>Onset</th></tr>")
    for d, o in sorted(seen.items(), key=lambda x: x[1]):
        out.append(f"<tr><td>{esc(d)}</td><td class='muted'>{esc(o)}</td></tr>")
    out.append("</table>")
else: out.append('<p class="empty">No active problems.</p>')
out.append("</section>")

# ---- Medications ----
meds = R("MedicationRequest")
mseen = OrderedDict()
for r in meds:
    d = code_disp(r.get("medicationCodeableConcept", {}))
    st = r.get("status", "")
    key = d
    if key not in mseen: mseen[key] = (st, dt(r.get("authoredOn")))
out.append('<section id="meds"><h2 class="sec">Medications '
           f'<span class="n">{len(mseen)}</span></h2>')
if mseen:
    out.append("<table><tr><th>Medication</th><th>Status</th><th>First ordered</th></tr>")
    for d, (st, on) in sorted(mseen.items(), key=lambda x: x[1][1] or ""):
        pc = "pill active" if st == "active" else "pill"
        out.append(f"<tr><td>{esc(d)}</td><td><span class='{pc}'>{esc(st)}</span></td>"
                   f"<td class='muted'>{esc(on)}</td></tr>")
    out.append("</table>")
else: out.append('<p class="empty">None.</p>')
out.append("</section>")

# ---- Allergies ----
alg = R("AllergyIntolerance")
out.append('<section id="allergies"><h2 class="sec">Allergies '
           f'<span class="n">{len(alg)}</span></h2>')
if alg:
    out.append("<table><tr><th>Allergen</th><th>Category</th><th>Recorded</th></tr>")
    for r in alg:
        out.append(f"<tr><td>{esc(code_disp(r.get('code',{})))}</td>"
                   f"<td class='muted'>{esc(', '.join(r.get('category',[])))}</td>"
                   f"<td class='muted'>{esc(dt(r.get('recordedDate')))}</td></tr>")
    out.append("</table>")
else: out.append('<p class="empty">No known allergies.</p>')
out.append("</section>")

# ---- Encounters (reverse chronological) ----
out.append('<section id="encounters"><h2 class="sec">Encounters '
           f'<span class="n">{len(encs)}</span>'
           '<span class="encctl"><button type="button" onclick="encAll(true)">Expand all</button>'
           '<button type="button" onclick="encAll(false)">Collapse all</button></span></h2>')
enc_sorted = sorted(encs, key=lambda e: e.get("period", {}).get("start", ""), reverse=True)
for e in enc_sorted:
    url = None
    for k, v in byurl.items():
        if v is e: url = k; break
    per = e.get("period", {})
    typ = code_disp(e.get("type", [{}])[0]) if e.get("type") else "Encounter"
    cls = e.get("class", {}).get("code", "")
    clsmap = {"AMB": "amb", "EMER": "emer", "IMP": "imp", "VR": "amb"}
    clsname = {"AMB": "Ambulatory", "EMER": "Emergency", "IMP": "Inpatient", "VR": "Virtual"}
    reason = "; ".join(code_disp(rc) for rc in e.get("reasonCode", [])) if e.get("reasonCode") else ""
    prov = e.get("serviceProvider", {}).get("display", "")
    reason_chip = f'<span class="rsn">{esc(reason)}</span>' if reason else ""
    out.append('<details class="enc">')
    out.append(f'<summary class="hd"><div class="ttl">{esc(typ)} '
               f'<span class="pill {clsmap.get(cls,"amb")}">{esc(clsname.get(cls,cls))}</span>'
               f'{reason_chip}</div>'
               f'<div class="date">{esc(dtt(per.get("start")))}</div></summary>')
    out.append('<div class="body">')
    if prov: out.append(f'<div class="small muted" style="margin:0 0 10px">{esc(prov)}</div>')

    # vitals
    vobs = [o for (o, cats, re_) in obs_by_enc.get(url, []) if "vital-signs" in cats or "exam" in cats]
    if vobs:
        vobs_sorted = sorted(vobs, key=lambda o: vital_key(code_disp(o.get("code", {}))))
        out.append('<div class="vitrow">')
        for o in vobs_sorted:
            lab = code_disp(o.get("code", {}))
            lab = lab.replace("Blood pressure panel with all children optional", "Blood pressure")
            lab = lab.replace("Body mass index (BMI) [Ratio]", "BMI").replace(" [Score] - Reported","")
            lab = re.sub(r"\s*-\s*0-10 verbal numeric rating.*", "", lab)
            val = obs_value(o)
            if val:
                out.append(f'<div class="vit"><span class="lab">{esc(lab)}</span>'
                           f'<span class="val">{esc(val)}</span></div>')
        out.append('</div>')

    # dx / procedures / meds at this encounter
    conds = cond_by_enc.get(url, []); procs = proc_by_enc.get(url, []); emeds = med_by_enc.get(url, [])
    surv = [o for (o, cats, re_) in obs_by_enc.get(url, []) if "survey" in cats]
    if conds or procs or emeds or surv:
        out.append('<div class="items">')
        if conds:
            out.append('<div class="col"><h4>Diagnoses</h4><ul>' +
                       "".join(f"<li>{esc(code_disp(c.get('code',{})))}</li>" for c in conds) + "</ul></div>")
        if procs:
            out.append('<div class="col"><h4>Procedures</h4><ul>' +
                       "".join(f"<li>{esc(code_disp(c.get('code',{})))}</li>" for c in procs) + "</ul></div>")
        if emeds:
            out.append('<div class="col"><h4>Medications ordered</h4><ul>' +
                       "".join(f"<li>{esc(code_disp(c.get('medicationCodeableConcept',{})))}</li>" for c in emeds) + "</ul></div>")
        if surv:
            out.append('<div class="col"><h4>Assessments</h4><ul>' +
                       "".join(f"<li>{esc(code_disp(o.get('code',{})))}: <b>{esc(obs_value(o))}</b></li>" for o in surv) + "</ul></div>")
        out.append('</div>')

    # note
    note = note_by_enc.get(url)
    if note:
        out.append(f'<div class="note">{md(note)}</div>')
    else:
        out.append('<div class="empty">No clinical note for this encounter.</div>')
    # transcript
    tr = trans_by_enc.get(url)
    if tr:
        out.append('<details class="tr"><summary>Encounter transcript</summary>'
                   f'<div class="tbody">{transcript_html(tr)}</div></details>')
    out.append('</div></details>')  # body, enc
out.append("</section>")

# ---- Labs ----
out.append('<section id="labs"><h2 class="sec">Laboratory Results '
           f'<span class="n">{len(labs)}</span></h2>')
if labs:
    bydate = defaultdict(list)
    for (o, cats, re_) in labs:
        bydate[dt(o.get("effectiveDateTime"))].append(o)
    for d in sorted(bydate, reverse=True):
        out.append(f'<h4 class="muted" style="margin:14px 0 4px">{esc(d)}</h4>')
        out.append("<table><tr><th>Test</th><th>Value</th></tr>")
        for o in bydate[d]:
            out.append(f"<tr><td>{esc(code_disp(o.get('code',{})))}</td><td>{esc(obs_value(o))}</td></tr>")
        out.append("</table>")
else: out.append('<p class="empty">No laboratory results.</p>')
out.append("</section>")

# ---- Imaging ----
studies = R("ImagingStudy")
out.append('<section id="imaging"><h2 class="sec">Imaging '
           f'<span class="n">{len(studies)}</span></h2>')
if studies or imaging_obs:
    out.append("<table><tr><th>Date</th><th>Study</th><th>Modality</th><th>Body site</th></tr>")
    rows = []
    for s in studies:
        d = dt(s.get("started"))
        proc = code_disp(s.get("procedureCode", [{}])[0]) if s.get("procedureCode") else ""
        ser = s.get("series", [{}])[0]
        modality = ser.get("modality", {}).get("display", ser.get("modality", {}).get("code", ""))
        site = ser.get("bodySite", {}).get("display", "")
        rows.append((d, proc, modality, site))
    for (o, cats, re_) in imaging_obs:
        rows.append((dt(o.get("effectiveDateTime")), code_disp(o.get("code", {})), "Observation", obs_value(o)))
    for d, proc, modality, site in sorted(rows, reverse=True):
        out.append(f"<tr><td class='muted'>{esc(d)}</td><td>{esc(proc)}</td>"
                   f"<td>{esc(modality)}</td><td class='muted'>{esc(site)}</td></tr>")
    out.append("</table>")
else: out.append('<p class="empty">No imaging studies.</p>')
out.append("</section>")

out.append(f'<footer>Generated {datetime.now().strftime("%Y-%m-%d %H:%M")} from FHIR bundle '
           f'<code>{esc(os.path.basename(BUNDLE))}</code>. Synthetic data — not a real patient.</footer>')
out.append("""<script>
function encAll(open){document.querySelectorAll('#encounters details.enc')
  .forEach(function(d){d.open=open;});}
</script>""")
out.append("</div></body></html>")

open(OUT, "w", encoding="utf-8").write("\n".join(out))
print("wrote", OUT)
print("encounters:", len(encs), "| notes:", len(note_by_enc), "| transcripts:", len(trans_by_enc),
      "| labs:", len(labs), "| imaging studies:", len(studies))
