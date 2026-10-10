"""Exercise a real, running MC bridge on loopback. Uses only a disposable second screen."""
import json,time,urllib.request
from pathlib import Path
BASE='http://127.0.0.1:3210/api'
def api(path,body=None,method=None):
    request=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'},method=method or ('GET' if body is None else 'POST'))
    with urllib.request.urlopen(request,timeout=10) as r:return json.load(r)
def until(predicate,timeout=12):
    end=time.time()+timeout
    while time.time()<end:
        data=api('/state')
        if predicate(data):return data
        time.sleep(.2)
    raise AssertionError('MC bridge did not reach expected state')
def main():
    d=until(lambda d:d['bridge']['online'] and any(s['online'] for s in d['screens']))
    primary=next(s['id'] for s in d['screens'] if s['online'])
    movie=next(r for r in d['resources'] if r['kind']=='vod' and r['status']=='ready' and 0<r.get('duration',0)<=5)
    def screen(d):return next(s for s in d['screens'] if s['id']==primary)
    def control(action,ids=None,**extra):api('/control',{'screenIds':ids or [primary],'action':action,**extra})
    control('load',resourceId=movie['id']);until(lambda d:screen(d)['url']==movie['url'] and not screen(d)['playing'])
    api('/screens/'+urllib.parse.quote(primary,safe=''),{'policy':{'onEnd':'hold','pauseWhenEmpty':False,'liveLatencySeconds':12}},'PATCH')
    control('seek',position=1.5);until(lambda d:screen(d)['positionMs']==1500)
    control('play');until(lambda d:screen(d)['playing'])
    until(lambda d:not screen(d)['playing'] and screen(d)['positionMs']==3900,10)
    print('PASS: load, seek, play and end hold on real MC',flush=True)
    api('/screens/'+urllib.parse.quote(primary,safe=''),{'policy':{'onEnd':'loop','pauseWhenEmpty':False,'liveLatencySeconds':12}},'PATCH')
    control('seek',position=3);control('play');until(lambda d:screen(d)['playing'] and screen(d)['positionMs']<2000,8)
    print('PASS: loop policy advances even in an empty world',flush=True)
    api('/screens/'+urllib.parse.quote(primary,safe=''),{'policy':{'onEnd':'loop','pauseWhenEmpty':True,'liveLatencySeconds':12}},'PATCH')
    until(lambda d:not screen(d)['playing']);print('PASS: no-audience pause',flush=True)
    api('/screens/'+urllib.parse.quote(primary,safe=''),{'policy':{'onEnd':'loop','pauseWhenEmpty':False,'liveLatencySeconds':12}},'PATCH')
    second='minecraft:overworld@6,100,-8'
    if not any(s['id']==second for s in api('/state')['screens']):api('/screens',{'name':'联调副屏','x':6,'y':100,'z':-8,'width':8})
    until(lambda d:any(s['id']==second and s['online'] for s in d['screens']))
    ids=[primary,second];control('load',ids,resourceId=movie['id']);control('play',ids)
    d=until(lambda d:all(any(s['id']==i and s['playing'] for s in d['screens']) for i in ids))
    delta=abs(next(s for s in d['screens'] if s['id']==primary)['positionMs']-next(s for s in d['screens'] if s['id']==second)['positionMs'])
    assert delta<100,delta;print('PASS: two screen authoritative timelines differ by '+str(delta)+' ms',flush=True)
    control('pause',ids);until(lambda d:all(not s['playing'] for s in d['screens'] if s['id'] in ids))
    control('remove',[second]);until(lambda d:all(not s['online'] for s in d['screens'] if s['id']==second))
    other=next(r for r in d['resources'] if r['kind']=='vod' and r['status']=='ready' and r['id']!=movie['id'])
    control('playlist',queue=[movie['id'],other['id']])
    api('/screens/'+urllib.parse.quote(primary,safe=''),{'policy':{'onEnd':'next','pauseWhenEmpty':False,'liveLatencySeconds':12}},'PATCH')
    control('load',resourceId=movie['id']);until(lambda d:screen(d)['url']==movie['url'])
    control('seek',position=3);control('play');until(lambda d:screen(d)['url']==other['url'] and screen(d)['playing'],10)
    print('PASS: queue advances to the next resource on real MC',flush=True)
    control('playlist',queue=[])
    api('/screens/'+urllib.parse.quote(primary,safe=''),{'policy':{'onEnd':'loop','pauseWhenEmpty':False,'liveLatencySeconds':12}},'PATCH')
    sample=next(r for r in d['resources'] if r['id']=='14250248-d8c9-492e-be66-d9e6ff70e548')
    control('load',resourceId=sample['id']);control('play')
    proof={'version':'0.2.0','verified':['real MC load/seek/play/pause/hold/loop','empty audience pause','second screen create/remove','queue advances to next resource'],'twoScreenTimelineDeltaMs':delta,'capacityTested':False}
    Path('.tools/console-mc-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
if __name__=='__main__':main()
