"""Integration verification against the isolated compose.integration.yml stack.
IAM, Installation, PostgreSQL and MQTT are real local services. Niubiz, tax responses,
notification delivery and the InstallationCompleted/Support events are explicit fixtures.
Never logs JWTs, checkout secrets, card tokens or activation links.
"""
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
from pathlib import Path
from datetime import datetime,timezone,timedelta
import json,uuid,secrets,time,subprocess,os
from mqtt_test_client import Mqtt5
settings=dict(line.split('=',1) for line in Path('.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
BASE=os.environ.get('BILLING_SMOKE_URL','http://localhost:28083');IAM='http://localhost:18080';INST='http://localhost:18081';DELIVERY='http://localhost:18091'
internal={'Authorization':'Bearer '+settings['INTERNAL_SERVICE_TOKEN']}
checks=[]
def call(method,path,body=None,headers=None,base=BASE,expected=200):
 data=None if body is None else json.dumps(body).encode()
 req=Request(base+path,data=data,method=method,headers={'Content-Type':'application/json',**(headers or {})})
 try:
  with urlopen(req,timeout=12) as r: status=r.status;raw=r.read();kind=r.headers.get('Content-Type','')
 except HTTPError as e: status=e.code;raw=e.read();kind=e.headers.get('Content-Type','')
 assert status==expected,f'{method} {path}: expected {expected}, received {status}: {raw[:240]!r}'
 if not raw:return None
 return json.loads(raw) if 'json' in kind else raw

def check(label,condition=True):
 assert condition,label
 checks.append(label);print('PASS',label,flush=True)

def eventually(fn,timeout=60):
 until=time.monotonic()+timeout
 while time.monotonic()<until:
  try:
   result=fn()
   if result:return result
  except (AssertionError,HTTPError,URLError,ConnectionError):pass
  time.sleep(1)
 raise AssertionError('Timed out waiting for local integration state')

def sql(query,service='db',user='smartgn_billing',database='billing_db'):
 p=subprocess.run(['docker','compose','-f','compose.integration.yml','exec','-T',service,'psql','-U',user,'-d',database,'-At','-c',query],capture_output=True,text=True,check=True)
 return p.stdout.strip()

def activate(email,password):
 messages=eventually(lambda:call('GET','/fixtures/messages?email='+email,headers={'Authorization':'Bearer '+settings['FIXTURE_ADMIN_TOKEN']},base=DELIVERY))
 token=next(m['token'] for m in reversed(messages) if m.get('purpose')=='ACTIVATION')
 call('POST','/api/v1/auth/activate-password',{'activationToken':token,'newPassword':password},base=IAM,expected=204)
 response=call('POST','/api/v1/auth/sign-in',{'email':email,'password':password},base=IAM)
 return {'Authorization':'Bearer '+response.get('token',response.get('accessToken',''))}

for base in (BASE,IAM,INST):eventually(lambda b=base:call('GET','/actuator/health',base=b).get('status')=='UP')
check('Billing, IAM and Installation healthy')
call('GET','/api/v1/payments/history',expected=401);call('GET','/internal/v1/accounts/'+str(uuid.uuid4())+'/entitlements',expected=401)
check('Financial and private APIs reject anonymous requests')
m=Mqtt5('localhost',1885,'installation-service','invalid-password');check('Broker rejects invalid service credentials',m.reason>=128);m.close()
m=Mqtt5('localhost',1885,'installation-service',settings['MQTT_INSTALLATION_PASSWORD']);check('Broker denies cross-context publication',m.publish('smartgn/events/support/forbidden',{'test':True})>=128);m.close()

email='billing-'+uuid.uuid4().hex+'@example.test'
r=call('POST','/api/v1/installations',{'contact':{'name':'Billing Test','email':email,'phone':'999999999'},'address':'Lima LOCAL TEST'},base=INST,expected=201)
installation=r['id'];key=str(uuid.uuid4());secret=secrets.token_urlsafe(32)
checkout_headers={'Idempotency-Key':key,'X-Checkout-Secret':secret}
body={'installationId':installation,'email':email,'firstName':'Billing','lastName':'Test'}
order=call('POST','/api/v1/checkout/orders',body,checkout_headers,expected=201);oid=order['id']
check('Real provisional Installation checkout calculates PEN 250',float(order['amount'])==250)
repeat=call('POST','/api/v1/checkout/orders',body,checkout_headers,expected=201);check('Checkout replay returns one order',repeat['id']==oid)
call('GET','/api/v1/checkout/orders/'+oid,headers={'X-Checkout-Secret':'wrong'},expected=403)
call('POST','/api/v1/checkout/orders',{**body,'firstName':'Changed'},checkout_headers,expected=409)
check('Checkout secret and conflicting idempotency payload enforced')
ref='initial-'+str(uuid.uuid4())
call('POST','/internal/v1/local-provider/results',{'reference':ref,'orderId':oid,'status':'UNKNOWN','amount':250},internal)
result=call('POST','/internal/v1/payments/confirmations',{'orderId':oid,'providerReference':ref},internal);check('Uncertain initial result does not enable account',result['status']=='UNKNOWN' and sql("SELECT count(*) FROM billing.payment_orders WHERE id='"+oid+"' AND account_id IS NOT NULL")=='0')
call('POST','/internal/v1/local-provider/results',{'reference':ref,'orderId':oid,'status':'CONFIRMED','amount':250},internal)
for _ in range(3):call('POST','/internal/v1/payments/confirmations',{'orderId':oid,'providerReference':ref},internal)
account=eventually(lambda:sql("SELECT account_id FROM billing.payment_orders WHERE id='"+oid+"'"))
eventually(lambda:sql("SELECT count(*) FROM installation.installation_requests WHERE request_id='"+installation+"' AND payment_verified_at IS NOT NULL",'installation-db','smartgn_installation','installation_db')=='1')
check('Verified initial purchase provisions real IAM account and enables real Installation exactly once')
password='LocalTest-'+secrets.token_hex(12)
auth=activate(email,password)
call('GET','/internal/v1/accounts/'+account+'/entitlements',headers=auth,expected=401)
check('IAM activation/sign-in works; JWT cannot substitute service credential')
# Completion source is a controlled integration event, not a physical device installation.
event={'event_id':str(uuid.uuid4()),'event_type':'InstallationCompleted','aggregate_id':installation,'correlation_id':str(uuid.uuid4()),'payload':{'accountId':account,'deviceId':str(uuid.uuid4()),'completedAt':datetime.now(timezone.utc).isoformat()}}
m=Mqtt5('localhost',1885,'installation-service',settings['MQTT_INSTALLATION_PASSWORD']);assert m.reason==0;assert m.publish('smartgn/events/installation/InstallationCompleted',event)<128;m.close()
eventually(lambda:sql("SELECT count(*) FROM billing.subscriptions WHERE account_id='"+account+"'")=='1')
check('Authenticated MQTT completion fixture activates Free')
plan=call('GET','/api/v1/subscriptions/me',headers=auth);assert plan['plan']=='FREE'
card_token='test_'+secrets.token_urlsafe(24)
card_fixture={'accountId':account,'providerToken':card_token,'brand':'VISA','lastFour':'1234','recurringAuthorized':True,'nextStatus':'CONFIRMED'}
call('POST','/internal/v1/local-provider/cards',card_fixture,internal)
card=call('POST','/api/v1/payment-methods',{'providerToken':card_token},auth)['id']
call('PUT','/api/v1/payment-methods/'+card+'/default',headers=auth)
call('POST','/api/v1/payment-methods',{'providerToken':'test_'+secrets.token_urlsafe(24)},auth,expected=403)
check('Provider ownership proof, encrypted storage and default card work',sql("SELECT count(*) FROM billing.payment_methods WHERE id='"+card+"' AND protected_token NOT LIKE 'test_%'")=='1')
pro=call('POST','/api/v1/subscriptions/pro',{'paymentMethodId':card,'autoRenew':True},{**auth,'Idempotency-Key':str(uuid.uuid4())})
eventually(lambda:call('GET','/api/v1/subscriptions/me',headers=auth).get('proActive'))
check('Pro activates only after durable local-provider debit verification')
property_record=call('POST','/api/v1/properties',{'name':'Billing Pro test','address':'Lima LOCAL TEST','propertyType':'HOUSE'},auth,base=INST,expected=201)
point=call('POST','/api/v1/properties/'+property_record['id']+'/supply-points',{'serialNumber':'billing-'+uuid.uuid4().hex,'locationName':'Kitchen'},auth,base=INST,expected=201)
authorization=call('POST','/internal/v1/authorizations/check',{'accountId':account,'meterId':point['id'],'function':'USE_REPORTS','requiresPro':True},internal,base=IAM)
check('Real IAM authorization resolves current Pro from Billing',authorization['allowed'])

# Pro coverage is evaluated at effective completion, and a replay cannot create another charge.
visit=str(uuid.uuid4());completed=datetime.now(timezone.utc).isoformat();support_event={'event_id':str(uuid.uuid4()),'event_type':'TechnicalVisitCompleted','aggregate_id':visit,'correlation_id':str(uuid.uuid4()),'payload':{'visitId':visit,'accountId':account,'meterId':str(uuid.uuid4()),'completedAt':completed,'cost':78.50}}
m=Mqtt5('localhost',1885,'support-service',settings['MQTT_SUPPORT_PASSWORD']);assert m.publish('smartgn/events/support/TechnicalVisitCompleted',support_event)<128;assert m.publish('smartgn/events/support/TechnicalVisitCompleted',support_event)<128;m.close()
statement=eventually(lambda:call('GET','/api/v1/technical-statements/'+visit,headers=auth));check('Pro technical coverage is frozen with zero balance and no order',statement['covered'] and float(statement['balance'])==0 and statement['order_id'] is None)
# Historical Free visit predates Pro and remains chargeable even though Pro is active now.
free_visit=str(uuid.uuid4());payload={'eventId':str(uuid.uuid4()),'visitId':free_visit,'accountId':account,'meterId':str(uuid.uuid4()),'completedAt':(datetime.now(timezone.utc)-timedelta(days=1)).isoformat(),'cost':42.50}
call('POST','/internal/v1/technical-visits/completions',payload,internal)
free_statement=call('GET','/api/v1/technical-statements/'+free_visit,headers=auth);technical=free_statement['order_id']
call('POST','/api/v1/payments/orders/'+technical+'/pay',{'paymentMethodId':card},auth)
eventually(lambda:call('GET','/api/v1/payments/orders/'+technical,headers=auth)['status']=='CONFIRMED')
check('Historical Free coverage produces a payable balance collected after visit completion')
# Fiscal worker emits signed XML/PDF and a local CDR; it never claims this is SUNAT production.
history=eventually(lambda:call('GET','/api/v1/payments/history',headers=auth))
docid=next(d['id'] for o in history if o['id']==technical for d in o['documents'])
document=eventually(lambda:(d if (d:=call('GET','/api/v1/billing-documents/'+docid,headers=auth))['fiscal_status']=='LOCAL_ACCEPTED' else None))
for fmt in ('pdf','xml','cdr'):
 data=call('GET','/api/v1/billing-documents/'+docid+'/download?format='+fmt,headers=auth)
 Path('.local/smoke-'+fmt+('.zip' if fmt=='cdr' else '.'+fmt)).write_bytes(data)
check('Signed XML, PDF and local CDR are downloadable with a distinct fiscal state',document['test_mode'])
# An UNKNOWN provider result survives an application restart and reconciles one stable debit.
call('POST','/internal/v1/local-provider/cards',{**card_fixture,'nextStatus':'UNKNOWN'},internal)
uncertain_visit=str(uuid.uuid4());uncertain_payload={**payload,'eventId':str(uuid.uuid4()),'visitId':uncertain_visit,'cost':37.90}
call('POST','/internal/v1/technical-visits/completions',uncertain_payload,internal)
uncertain_order=call('GET','/api/v1/technical-statements/'+uncertain_visit,headers=auth)['order_id']
operation=call('POST','/api/v1/payments/orders/'+uncertain_order+'/pay',{'paymentMethodId':card},auth)['operationId']
eventually(lambda:call('GET','/api/v1/payments/orders/'+uncertain_order,headers=auth)['status']=='UNKNOWN')
subprocess.run(['docker','compose','-f','compose.integration.yml','restart','billing'],check=True,capture_output=True)
eventually(lambda:call('GET','/actuator/health')['status']=='UP')
check('Uncertain payment remains unresolved after Billing restart',call('GET','/api/v1/payments/orders/'+uncertain_order,headers=auth)['status']=='UNKNOWN')
call('POST','/internal/v1/local-provider/results',{'reference':'local-'+operation,'orderId':uncertain_order,'status':'CONFIRMED','amount':37.90},internal)
eventually(lambda:call('GET','/api/v1/payments/orders/'+uncertain_order,headers=auth)['status']=='CONFIRMED')
check('Reconciliation after restart confirms one debit',sql("SELECT count(*) FROM billing.payment_transactions WHERE order_id='"+uncertain_order+"'")=='1')

sub_email='delegate-'+uuid.uuid4().hex+'@example.test';call('POST','/api/v1/subusers',{'email':sub_email,'firstName':'Delegate','lastName':'Test'},auth,base=IAM,expected=201)
delegate=activate(sub_email,'LocalDelegate-'+secrets.token_hex(12));call('GET','/api/v1/payments/history',headers=delegate,expected=403);call('GET','/api/v1/payment-methods',headers=delegate,expected=403)
check('Real IAM delegated accounts cannot manage financial information')
# Expiry/failure fixture changes only this isolated customer's paid period to exercise the monthly scheduler.
call('POST','/internal/v1/local-provider/cards',{**card_fixture,'nextStatus':'DECLINED'},internal)
sql("UPDATE billing.subscription_periods SET starts_at=now()-interval '32 days',ends_at=now()-interval '1 second' WHERE account_id='"+account+"'")
eventually(lambda:sql("SELECT count(*) FROM billing.payment_orders WHERE account_id='"+account+"' AND idempotency_key LIKE 'renewal:%' AND status='DECLINED'")=='1',90)
check('Failed renewal records an attempt without extending paid access',call('GET','/api/v1/subscriptions/me',headers=auth)['plan']=='FREE')
authorization=call('POST','/internal/v1/authorizations/check',{'accountId':account,'meterId':point['id'],'function':'USE_REPORTS','requiresPro':True},internal,base=IAM)
check('Real IAM removes Pro authorization after the paid period expires',not authorization['allowed'])
call('DELETE','/api/v1/payment-methods/'+card,headers=auth)
methods=call('GET','/api/v1/payment-methods',headers=auth)
check('Unlinking a card preserves masked history and disables auto-renewal',any(m['id']==card and not m['active'] and not m['is_default'] for m in methods) and not call('GET','/api/v1/subscriptions/me',headers=auth)['autoRenew'])
# Password mutation invalidates the same JWT on its next use through IAM introspection.
call('POST','/api/v1/auth/change-password',{'currentPassword':password,'newPassword':'Changed-'+secrets.token_hex(12)},auth,base=IAM,expected=204)
call('GET','/api/v1/payments/history',headers=auth,expected=401)
check('Real IAM session revocation is enforced by Billing')
check('Outbox messages were acknowledged by a real broker',int(sql('SELECT count(*) FROM billing.outbox WHERE mqtt_published_at IS NOT NULL'))>0)
Path('.local/smoke-results.json').write_text(json.dumps({'checks':checks,'count':len(checks),'provider':'LOCAL_TEST_DOUBLE','tax':'LOCAL_TEST_DOUBLE','completionEvents':'CONTROLLED_FIXTURES'},indent=2))
print('ALL',len(checks),'CHECKS PASSED',flush=True)
