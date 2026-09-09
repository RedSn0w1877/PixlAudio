"""Local acoustic evaluation; writes timing statistics, never uploads audio or lyrics."""
from pathlib import Path
import json, time, subprocess, re
import numpy as np
import onnxruntime as ort
import imageio_ffmpeg
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/phone-qa/global-alignment'
OUT.mkdir(parents=True,exist_ok=True)
t0=time.monotonic()
raw=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-v','error','-i',str(ROOT/'build/phone-qa/a-cold-play-test.webm'),'-f','f32le','-ac','1','-ar','16000','-'],capture_output=True,check=True).stdout
samples=np.frombuffer(raw,dtype='<f4')
vocab=json.loads((ROOT/'app/src/main/assets/tais/wav2vec2_vocab.json').read_text())
text=json.loads((ROOT/'build/phone-qa/resync-before/-3897328402086.json').read_text())['plainLyrics']
words=text.split()
symbols=[]; ranges=[]
for word in words:
 chars=[c for c in word.upper() if c=="'" or 'A'<=c<='Z']
 if not chars: ranges.append(None);continue
 if symbols:symbols.append(vocab['|'])
 a=len(symbols);symbols.extend(vocab[c] for c in chars if c in vocab);ranges.append((2*a+1,2*(len(symbols)-1)+1))
extended=np.zeros(len(symbols)*2+1,dtype=np.int64);extended[1::2]=symbols
frames=(len(samples)-400)//320+1
cache=OUT/'emissions.npy'
if cache.exists(): emissions=np.load(cache)
else:
 opts=ort.SessionOptions();opts.intra_op_num_threads=4
 session=ort.InferenceSession(str(ROOT/'app/src/main/assets/tais/wav2vec2_base_960h_fp32.onnx'),opts,providers=['CPUExecutionProvider'])
 rows=[]
 for first in range(0,frames,800):
  end=min(first+800,frames);input_first=max(0,first-100);input_end=min(frames,end+100)
  chunk=samples[input_first*320:(input_end-1)*320+400].astype(np.float64)
  chunk=((chunk-chunk.mean())/np.sqrt(chunk.var()+1e-7)).astype(np.float32)
  logits=session.run(['logits'],{'input_values':chunk[None,:]})[0][0]
  maximum=logits.max(axis=1,keepdims=True)
  lp=logits-maximum-np.log(np.exp(logits-maximum).sum(axis=1,keepdims=True))
  local=first-input_first;rows.extend(lp[local:local+end-first])
  print(json.dumps({'audioFramesDone':end,'totalFrames':frames,'elapsedSeconds':round(time.monotonic()-t0,1)}),flush=True)
 emissions=np.asarray(rows);np.save(cache,emissions)
length=len(extended);prev=np.full(length,-np.inf,dtype=np.float32);prev[0]=emissions[0,0];prev[1]=emissions[0,extended[1]]
back=np.zeros((frames,length),dtype=np.uint8)
can_skip=np.zeros(length,dtype=bool);can_skip[2:]=(extended[2:]!=0)&(extended[2:]!=extended[:-2])
for t in range(1,frames):
 one=np.roll(prev,1);one[0]=-np.inf
 two=np.roll(prev,2);two[~can_skip]=-np.inf
 candidates=np.stack([prev,one,two]);moves=np.argmax(candidates,axis=0);back[t]=moves
 prev=np.max(candidates,axis=0)+emissions[t,extended]
s=length-1 if prev[-1]>=prev[-2] else length-2
assert np.isfinite(prev[s])
path=np.zeros(frames,dtype=np.int32);path[-1]=s
for t in range(frames-1,0,-1):s-=int(back[t,s]);path[t-1]=s
firsts={};lasts={}
for t,s in enumerate(path):
 if extended[s]!=0:firsts.setdefault(int(s),t);lasts[int(s)]=t
starts=[];ends=[];scores=[]
for r in ranges:
 if r is None:starts.append(ends[-1] if ends else 0);ends.append(starts[-1]);continue
 a,b=r;matched=[x for x in range(a,b+1,2) if x in firsts]
 starts.append(min(firsts[x] for x in matched)*20);ends.append(max(lasts[x] for x in matched)*20)
 scores.append(float(np.mean([np.exp(emissions[firsts[x]:lasts[x]+1,extended[x]]).max() for x in matched])))
reference=json.loads((ROOT/'build/phone-qa/resync-before/-3897328402086.json').read_text())['syncedLyrics']
line_reference=[];line_actual=[];word_idx=0
for line in reference.splitlines():
 m=re.match(r'\[(\d+):(\d+(?:\.\d+)?)\](.*)',line)
 if not m:continue
 count=len(m[3].split())
 if count:
  line_reference.append(round((int(m[1])*60+float(m[2]))*1000));line_actual.append(starts[word_idx]);word_idx+=count
errors=[abs(a-b) for a,b in zip(line_reference,line_actual)]
result={'wordCount':len(words),'firstWordMs':starts[0],'lastWordMs':starts[-1],'referenceFirstLineMs':line_reference[0],'medianLineAnchorErrorMs':float(np.median(errors)),'maxLineAnchorErrorMs':max(errors),'wordBackwards':sum(a>b for a,b in zip(starts,starts[1:])),'meanCharacterPeakProbability':float(np.mean(scores)),'lowConfidenceWords':sum(s<0.2 for s in scores),'elapsedSeconds':round(time.monotonic()-t0,2),'lineStartsMs':line_actual}
(OUT/'result.json').write_text(json.dumps(result,indent=2))
(OUT/'word-times.json').write_text(json.dumps({'starts':starts,'ends':ends,'confidence':scores}))
print(json.dumps(result),flush=True)
