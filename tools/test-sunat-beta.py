"""Send an explicitly supplied SIGNED summary to SUNAT's public beta service.
Uses only official public beta credentials and never accepts a production endpoint.
This does not validate emitter registration or authorize productive issuance.
"""
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from zipfile import ZipFile
from io import BytesIO
import xml.etree.ElementTree as ET
import base64,time,sys,json
xml=Path(sys.argv[1]).read_bytes();root=ET.fromstring(xml)
summary=root.find('{urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2}ID').text
assert summary.startswith('RC-')
name='20100066603-'+summary
output=Path('.local/sunat-beta');output.mkdir(parents=True,exist_ok=True)
(output/'sent-summary.xml').write_bytes(xml)
def request(body):
 soap="<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/' xmlns:ser='http://service.sunat.gob.pe' xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'><s:Header><wsse:Security><wsse:UsernameToken><wsse:Username>20100066603MODDATOS</wsse:Username><wsse:Password>MODDATOS</wsse:Password></wsse:UsernameToken></wsse:Security></s:Header><s:Body>"+body+"</s:Body></s:Envelope>"
 req=Request('https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService',data=soap.encode(),headers={'Content-Type':'text/xml; charset=utf-8','SOAPAction':''})
 try:
  with urlopen(req,timeout=20) as r:return r.read()
 except HTTPError as e:return e.read()
def field(doc,key):return next((n.text for n in doc.iter() if n.tag.split('}')[-1]==key),None)
archive=BytesIO()
with ZipFile(archive,'w') as z:z.writestr(name+'.xml',xml)
response=request('<ser:sendSummary><fileName>'+name+'.zip</fileName><contentFile>'+base64.b64encode(archive.getvalue()).decode()+'</contentFile></ser:sendSummary>')
(output/'submission-response.xml').write_bytes(response)
doc=ET.fromstring(response);ticket=field(doc,'ticket')
assert ticket, 'Beta rejected submission: '+str(field(doc,'faultcode'))+' '+str(field(doc,'faultstring'))
print('Beta ticket:',ticket,flush=True)
for _ in range(10):
 response=request('<ser:getStatus><ticket>'+ticket+'</ticket></ser:getStatus>');(output/'status-response.xml').write_bytes(response);doc=ET.fromstring(response)
 if field(doc,'statusCode')=='98':time.sleep(3);continue
 content=field(doc,'content');assert content,'No beta CDR: '+str(field(doc,'faultcode'))
 cdr=base64.b64decode(content);(output/'cdr.zip').write_bytes(cdr)
 with ZipFile(BytesIO(cdr)) as z:
  data=z.read(next(n for n in z.namelist() if n.endswith('.xml')));(output/'cdr.xml').write_bytes(data)
 doc=ET.fromstring(data);result={'environment':'SUNAT_BETA','ticket':ticket,'code':field(doc,'ResponseCode'),'description':field(doc,'Description'),'productionValidity':False};(output/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,ensure_ascii=True));assert result['code']=='0';break
else:raise RuntimeError('Beta processing is still pending; retain ticket for later consultation')
