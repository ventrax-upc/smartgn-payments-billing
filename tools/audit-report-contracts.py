"""Compare documented public endpoints with live service OpenAPI; read-only."""
from zipfile import ZipFile
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import re,json,sys,hashlib
report=Path(sys.argv[1])
with ZipFile(report) as z:
 root=ET.fromstring(z.read('word/document.xml'));ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
 paragraphs=[''.join(t.text or '' for t in p.findall('.//w:t',ns)) for p in root.findall('.//w:body//w:p',ns)]
services={'IAM':'http://localhost:18080','Installation & Device Management':'http://localhost:18081','Payments & Billing':'http://localhost:28083'}
expected={s:[] for s in services}
for start,end in [(5082,5330),(6097,6139)]:
 owner=None
 for i in range(start,end):
  text=paragraphs[i].strip()
  if text in services:owner=text
  if owner and text.startswith('/api/') and i+1<len(paragraphs) and paragraphs[i+1] in ('GET','POST','PUT','PATCH','DELETE'):expected[owner].append((paragraphs[i+1],text))
assert {name:len(ops) for name,ops in expected.items()}=={'IAM':12,'Installation & Device Management':29,'Payments & Billing':7}, 'Report layout/version changed; update extraction ranges before trusting this audit'
def normal(path):return re.sub(r'\{[^}]+\}','{}',path)
result={'report':report.name,'reportSha256':hashlib.sha256(report.read_bytes()).hexdigest(),'services':{}}
for name,base in services.items():
 api=json.loads(urlopen(base+'/v3/api-docs',timeout=20).read());actual={(method.upper(),normal(path)) for path,ops in api['paths'].items() for method in ops if method.lower() in ('get','post','put','patch','delete')}
 missing=[{'method':m,'path':p} for m,p in expected[name] if (m,normal(p)) not in actual]
 result['services'][name]={'expectedPublicOperations':len(expected[name]),'matched':len(expected[name])-len(missing),'missing':missing,'livePaths':len(api['paths'])}
 print(name,':',result['services'][name]['matched'],'/',len(expected[name]),'documented public operations present')
output=Path('.local/report-contract-audit.json')
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(result,indent=2))
assert all(not r['missing'] for r in result['services'].values()),'Missing documented endpoints'
