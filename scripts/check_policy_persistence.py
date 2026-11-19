"""Configure/compare a synthetic weekly policy across an invoker-owned app restart."""
import argparse
import http.cookiejar
import json
import uuid
from datetime import datetime,timedelta,timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode,urlsplit
from urllib.request import Request,build_opener,HTTPCookieProcessor
from readiness import wait_for_ready

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://127.0.0.1:8080')
    parser.add_argument('--record',action='store_true')
    args=parser.parse_args()
    origin=urlsplit(args.base_url)
    if (origin.scheme!='http' or origin.hostname not in {'127.0.0.1','localhost','::1'}
            or origin.username or origin.password or origin.path not in {'','/'} or origin.query or origin.fragment):
        parser.error('Use the owned loopback demonstration origin')
    base=args.base_url.rstrip('/')
    passwords=json.loads((ROOT/'.secrets/demo-users.json').read_text())
    def get(client,path):
        with client.open(base+path,timeout=15) as response:return json.load(response)
    def login(name):
        client=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        wait_for_ready(client,base+'/actuator/health/readiness')
        token=get(client,'/api/session')
        request=Request(base+'/api/login',data=urlencode({'username':name,'password':passwords[name]}).encode(),
            headers={token['csrfHeader']:token['csrfToken'],'Content-Type':'application/x-www-form-urlencoded'})
        with client.open(request,timeout=15) as response:assert response.status==204
        return client,get(client,'/api/session')
    def post(client,session,path,data,key=None):
        headers={session['csrfHeader']:session['csrfToken'],'Content-Type':'application/json'}
        if key:headers['Idempotency-Key']=key
        request=Request(base+path,data=json.dumps(data).encode(),headers=headers)
        try:
            with client.open(request,timeout=15) as response:return response.status,json.load(response)
        except HTTPError as error:return error.code,json.load(error)
    admin,session=login('admin')
    before=ROOT/'target/weekly-policy-before-restart.json'
    before.parent.mkdir(parents=True,exist_ok=True)
    if args.record:
        initial=get(admin,'/api/admin/policy')
        status,weekly=post(admin,session,'/api/admin/policy',{'expectedVersion':initial['version'],
            'maxDurationMinutes':120,'approvalRequired':True,'horizonDays':7,
            'openingDays':[1,2,3,4,5,6,7],'opensAt':'09:00','closesAt':'17:00'})
        assert status==200 and weekly['horizonDays']==7 and weekly['opensAt']=='09:00'
        before.write_text(json.dumps({'initial':initial,'weekly':weekly},indent=2)+'\n')
        print('Restricted weekly policy recorded; invoker must actually restart the owned app')
        return
    saved=json.loads(before.read_text())
    observed=get(admin,'/api/admin/policy')
    assert observed==saved['weekly'],'Weekly policy changed across app restart'
    anonymous=build_opener()
    assert get(anonymous,'/api/policy')==observed
    requester,request_session=login('requester')
    room=get(anonymous,'/api/resources')[0]['id']
    day=datetime.now(timezone.utc).replace(hour=0,minute=0,second=0,microsecond=0)
    denials={}
    for code,days,hour in [('HORIZON_EXCEEDED',9,10),('OUTSIDE_OPENING_HOURS',2,8)]:
        start=day+timedelta(days=days,hours=hour)
        status,problem=post(requester,request_session,'/api/bookings',{'resourceId':room,
            'startsAt':start.isoformat(),'endsAt':(start+timedelta(hours=1)).isoformat()},str(uuid.uuid4()))
        assert status==400 and problem['code']==code
        denials[code]=True
    initial={k:v for k,v in saved['initial'].items() if k!='version'}
    status,restored=post(admin,session,'/api/admin/policy',{**initial,'expectedVersion':observed['version']})
    assert status==200 and {k:v for k,v in restored.items() if k!='version'}==initial
    report={'status':'passed','integration':'Actual fresh HTTP sessions before/after an invoker-owned app-process restart; same database',
        'restricted_policy_preserved_exactly':True,'public_policy_matches':True,'post_restart_denials':denials,
        'policy':observed,'initial_settings_restored':True,
        'limits':'Synthetic owned loopback demo. This verifier does not itself restart the process; the invoker must do so.'}
    (ROOT/'target/weekly-policy-restart.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Exact restricted weekly policy and requester enforcement verified after app restart; original settings restored')


if __name__=='__main__':
    main()
