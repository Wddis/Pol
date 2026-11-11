# Upload a big file to BrightBean presigned R2 parts. Edit U (url template with {p},{d},{s}) and F; pass sigs.json {"part":["X-Amz-Date","signature"]}.
import sys, urllib.request, json, os, concurrent.futures as cf
F='T2x2_ev1_v2.mp4'; PS=67108864
U='https://cff743d2b1084f68d3ab30cbc6f8cb73.r2.cloudflarestorage.com/brightbean-media/9ca2979a-d25c-47cf-a191-eea19158b700/uploads/f686312c-fc66-47c9-89ce-8e715ae91c32?X-Amz-Expires=900&partNumber={p}&uploadId=AAPuFfki6wL3vyK4_eHxa2XzcZ7GJ5QXuRqzWl3usBht8DmpWtDRDBsdsgyf2vtCWxzMsf2Vdvueb3nFIFyIqGXZPqaCTdqqTTlY_oDM5Dmjs_FYUzTKXgRiS9_XsViVt2QwPxxQWcOw357fjPu9Tkaehi5x82-MbSIkUO0raOJJ8GbtCUNshtgPhCUhirrxxVNpqTaQ1Z7_bEZAS3gwuJS7J_YHudAhkHzKclGUekYqZk1aRWOm7p2UmAVsuc1-0_9_bGPzwm5KhuYu3bS5lgTa2d9JujoEohwUIUZngTLurQdFWUeyzsZ79gRBHeQtbdbg1uWLv9DcK2uSbz9Skq8&X-Amz-Date={d}&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=f159cf3766f05015610b31cb22d1a8c9%2F20261111%2Fauto%2Fs3%2Faws4_request&X-Amz-SignedHeaders=host&X-Amz-Signature={s}'
sigs=json.load(open(sys.argv[1]))  # {"part": ["date","sig"]}
res=json.load(open('etags.json')) if os.path.exists('etags.json') else {}
def put(p):
    if str(p) in res: return p, res[str(p)]
    d,s=sigs[str(p)]
    with open(F,'rb') as f:
        f.seek((p-1)*PS); data=f.read(PS)
    for k in range(5):
        try:
            r=urllib.request.urlopen(urllib.request.Request(U.format(p=p,d=d,s=s),data=data,method='PUT'),timeout=600)
            return p, r.headers['ETag']
        except Exception as e: print('retry',p,e,flush=True)
    raise SystemExit(f'fail {p}')
with cf.ThreadPoolExecutor(4) as ex:
    for p,e in ex.map(put,[int(k) for k in sigs]):
        res[str(p)]=e; print(p,e,flush=True)
json.dump(res,open('etags.json','w'))
