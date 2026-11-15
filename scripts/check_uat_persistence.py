"""Record/compare all four role views and actor-key replay around an owned app restart."""
import argparse
import http.cookiejar
import json
from pathlib import Path
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
    spec=json.loads((ROOT/'frontend/test-results/uat.json').read_text())
    assert spec['status']=='passed' and [case['id'] for case in spec['cases']]==['UAT01','UAT02','UAT03','UAT04','UAT05']
    assert all(case['status']=='passed' for case in spec['cases'])
    clients={}
    csrf={}
    def get(client,path):
        with client.open(base+path,timeout=15) as response:return json.load(response)
    for name in ['admin','requester','requester2','approver']:
        client=build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        wait_for_ready(client,base+'/actuator/health/readiness')
        initial=get(client,'/api/session')
        request=Request(base+'/api/login',data=urlencode({'username':name,'password':passwords[name]}).encode(),
            headers={initial['csrfHeader']:initial['csrfToken'],'Content-Type':'application/x-www-form-urlencoded'})
        with client.open(request,timeout=15) as response:assert response.status==204
        current=get(client,'/api/session')
        clients[name]=client;csrf[name]={current['csrfHeader']:current['csrfToken']}
    admin=clients['admin']
    state={'policy':get(admin,'/api/admin/policy'),'imports':get(admin,'/api/admin/imports'),
           'audit':get(admin,'/api/admin/audit'),'resources':get(admin,'/api/resources'),
           'requester_bookings':get(clients['requester'],'/api/bookings'),
           'requester2_bookings':get(clients['requester2'],'/api/bookings'),
           'approval_queue':get(clients['approver'],'/api/approvals')}
    assert all(sum(row['code']==code for row in state['resources'])==1 for code in spec['resourceCodes'])
    before=ROOT/'target/uat-before-restart.json'
    before.parent.mkdir(parents=True,exist_ok=True)
    if args.record:
        before.write_text(json.dumps(state,indent=2)+'\n')
        print('Five-case UAT role views recorded before app restart; no credentials printed')
        return
    assert state==json.loads(before.read_text()),'UAT role views changed across app restart'
    replay=spec['replayAfterRestart'];name=replay['actor']
    request=Request(base+'/api/bookings',data=json.dumps(replay['payload']).encode(),
        headers={**csrf[name],'Content-Type':'application/json','Idempotency-Key':replay['key']})
    with clients[name].open(request,timeout=15) as response:
        assert response.status==200 and response.headers.get('Idempotent-Replayed')=='true'
        assert json.load(response)==replay['originalResponse']
    assert get(admin,'/api/admin/audit')==state['audit'],'Replay added an audit event'
    report={'status':'passed','integration':'Actual fresh authenticated role sessions and HTTP reads; invoker stops/starts the app process with the same database',
        'checks':['exact policy','exact import batches','exact audit IDs/details','exact catalogue',
                  'exact two requester booking lists','exact approver queue','original actor-key response replay','replay adds no audit'],
        'formal_cases':5,'resource_count':len(state['resources']),'audit_count':len(state['audit']),
        'replayed_booking_id':replay['originalResponse']['id'],
        'limits':'Synthetic owned setup. Lifecycle stop/start must actually be executed by the invoker; this helper alone does not stop a process.'}
    (ROOT/'target/uat-persistence.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Exact UAT role views and actor-key replay verified after owned app-process restart')


if __name__=='__main__':main()
