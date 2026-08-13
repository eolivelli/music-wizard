import json, sys
from pathlib import Path
def run(name):
    doc=json.loads(Path(f"probe-ws/{name}.mwz/score/score.json").read_text())
    beats=[b["seconds"] for b in doc["beatGrid"]["beats"]]
    downs=[b["seconds"] for b in doc["beatGrid"]["beats"] if b.get("downbeat")]
    if len(downs)<4: return
    iv=sorted(beats[i]-beats[i-1] for i in range(1,len(beats)))
    m=len(iv)//2
    med=iv[m] if len(iv)%2 else (iv[m-1]+iv[m])/2
    band=[d for d in iv if med*0.8<=d<=med*1.2]
    bar=4.0*(sum(band)/len(band) if band else med); tol=bar/8
    def ieee(x,mm): return x-round(x/mm)*mm
    nom=downs[0]; around=[ieee(d-nom,bar) for d in downs]
    agreed,least=0.0,float("inf")
    for c in around:
        t=sum(abs(ieee(o-c,bar)) for o in around)
        if t<least: least,agreed=t,c
    phase=nom+agreed if abs(agreed)<=tol else nom
    out=[]
    for mode in ("constant","refuse","clamp"):
        at=phase; dev=[abs(phase-downs[0])]
        for k in range(1,len(downs)):
            pred=at+bar
            best=min(downs,key=lambda d:abs(d-pred)); away=abs(best-pred)
            step=0.0 if away>bar/2 else best-pred
            if mode=="constant": at=pred
            elif mode=="refuse": at=pred+(step if abs(step)<=tol else 0.0)
            else: at=pred+max(-tol,min(tol,step))
            dev.append(abs(at-downs[k]))
        out.append((mode,sum(dev)/len(dev),max(dev),0))
    print(f"{name}: {len(downs)} bars, bar {bar:.3f}s, beat {bar/4:.3f}s")
    for mode,mean,worst,ref in out:
        label={"constant":"one constant bar length (main)",
               "refuse":"refused past half a beat",
               "clamp":"clamped to half a beat (this PR)"}[mode]
        print(f"    {label:<38} mean {mean:.3f}s  worst {worst:.3f}s")
for n in sys.argv[1:]: run(n)
