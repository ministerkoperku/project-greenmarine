#!/usr/bin/env python3
"""Validate actual GPU shadow edits and section-crossing freshness; report wall-time tails."""
import argparse
import hashlib
import json
import math
from pathlib import Path


def require(value, message):
    if not value: raise ValueError(message)


def distribution(values):
    values=sorted(v for v in values if math.isfinite(v) and v>0)
    require(len(values)>=60,'Insufficient raw rendered frame intervals')
    at=lambda q:values[max(0,math.ceil(len(values)*q)-1)]
    return {'count':len(values),'meanMs':sum(values)/len(values),'p95Ms':at(.95),'p99Ms':at(.99),'maxMs':values[-1]}


def verify_record(record, baseline=False, max_edit_ms=750, max_move_gap_ms=2000):
    require(record.get('status')=='complete' and record.get('restored') is True,'Incomplete probe or fixture not restored')
    require(record.get('capabilities',{}).get('backend')=='Vulkan' and record.get('capabilities',{}).get('hardwareRayTracing') is True,'Actual Vulkan hardware path required')
    require(record.get('packStatus',{}).get('state')=='LOADED' and record.get('surfaceRayFrames',0)>=40,'No sustained native surface submission')
    events=record.get('events',[])
    require(len(events)>=4 and [e.get('kind') for e in events[:4]]==['add','undo','add','undo'],'Two GPU edit/undo cycles were not exercised')
    samples=record.get('gpuSamples',[])
    require(samples,'Missing raw actual GPU readbacks')
    latencies=[]
    for index,event in enumerate(events):
        requested=event.get('requestedNanos',0)
        times=[event.get(k,0) for k in ('nativePublishedNanos','firstSubmittedNanos','gpuChangedFrameNanos','gpuObservedNanos')]
        require(requested>0 and all(t>=requested for t in times) and times[-1]>=times[-2],'Missing actual GPU/edit publication timestamps')
        require(times[0]<=times[1]<=times[-1],'Native publication/submission ordering is invalid')
        require(event.get('controlVisibility',0)>.75 and event.get('receiverNormalY',0)>.8,'Receiver/control do not sample the visible floor')
        clear=event['kind']=='undo'
        require(event.get('afterVisibility',-1)>.75 if clear else 0<=event.get('afterVisibility',-1)<.3,'GPU sunlight did not restore' if clear else 'GPU sunlight did not respond to an occluder')
        before=event.get('beforeVisibility',-1)
        require(before<.3 if clear else before>.75,'Event did not start from the opposite visible shadow state')
        latency={name:(t-requested)/1e6 for name,t in zip(('nativePublishMs','firstSubmitMs','gpuChangedFrameMs','gpuObservedMs'),times)}
        if not baseline: require(latency['gpuObservedMs']<=max_edit_ms,'Edit-to-GPU visibility exceeds the focused freshness budget')
        matching=[sample for sample in samples if sample.get('event')==index+1 and sample.get('observedNanos')==times[-1]]
        require(len(matching)==1,'Claimed GPU change lacks a matching actual readback')
        actual=matching[0]
        require(abs(actual.get('receiverVisibility',-1)-event['afterVisibility'])<.0001 and actual.get('controlVisibility',0)>.75
                and actual.get('receiverNormalY',0)>.8 and actual.get('controlNormalY',0)>.8,'Actual GPU readback disagrees with the event summary')
        latencies.append({'cycle':event.get('cycle'),'kind':event['kind'],**latency})
    movement=record.get('movement',{})
    require(movement.get('sectionCrossings',0)>=2 and movement.get('requestedBoundsChanges',0)>=2 and movement.get('finalBoundsMatched') is True,'Walking did not cross section bounds and recover native coverage')
    walking_samples=[sample for sample in samples if sample.get('phase') in (20,21)]
    require(movement.get('gpuSamples',0)>=30 and movement.get('gpuFailures',-1)==0 and len(walking_samples)>=30,'Walking GPU shadows/negative control did not remain valid')
    require(all(0<=sample.get('receiverVisibility',-1)<.3 and sample.get('controlVisibility',0)>.75
                and sample.get('receiverNormalY',0)>.8 and sample.get('controlNormalY',0)>.8 for sample in walking_samples),
            'Actual walking GPU readbacks show missing shadows or invalid receiver/control samples')
    if not baseline: require(movement.get('maxReadyGapMs',float('inf'))<=max_move_gap_ms,'Walking coverage remained stale beyond the focused budget')
    frames=record.get('frames',[])
    steady=distribution([row.get('deltaMs',0) for row in frames if not row.get('instrumentation') and 3<=row.get('phase',3)<=21])
    all_frames=distribution([row.get('deltaMs',0) for row in frames])
    async_stats={}
    if not baseline:
        require(record.get('submissionFaultTriggered') is True and record.get('submissionFaultRecovered') is True,'Actual post-submit exception/recovery was not verified')
        final=record.get('finalMetrics',{}).get('nativeStats',[])
        initial=events[0].get('metricsBefore',{}).get('nativeStats',[])
        require(len(final)==14 and len(initial)==14 and final[0]>0 and final[2]>0 and final[4]>=1,'Missing actual async native preparation/publication/slot statistics')
        require(final[11]-initial[11]==0,'Steady native path called device_wait_idle')
        async_stats={'prepareCount':final[0],'publishCount':final[2],'slots':final[4],'steadyDeviceIdleDelta':final[11]-initial[11]}
    return {'status':'baseline_measured' if baseline else 'accepted','accepted':not baseline,
            'metricsMode':record.get('metricsMode'),'editLatencies':latencies,'movement':movement,
            'asyncNative':async_stats,'walkingReadbacksVerified':len(walking_samples),'frameIntervals':steady,'allFrameIntervals':all_frames,'maxEditMs':max_edit_ms,'maxMovementGapMs':max_move_gap_ms,
            'sourceCases':record.get('sourceCases',[]),
            'limits':['Frame intervals are render callback wall intervals, not FPS or pure GPU frame times.',
                      'Steady intervals include phases3..21 and exclude screenshots; intentional fault/reset and startup remain in the all-frame summary.',
                      'Legacy publication/submission timestamps are render-observed upper bounds; dynamic native timestamps remain in raw metrics.',
                      'This finite controlled fixture does not prove universal walking coverage or renderer parity.']}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('folder',type=Path);parser.add_argument('--baseline',action='store_true')
    parser.add_argument('--max-edit-ms',type=float,default=750);parser.add_argument('--max-move-gap-ms',type=float,default=2000)
    args=parser.parse_args()
    try:
        raw=(args.folder/'result.json').read_bytes();record=json.loads(raw)
        summary=verify_record(record,args.baseline,args.max_edit_ms,args.max_move_gap_ms)
        sources={'result.json':hashlib.sha256(raw).hexdigest()}
        for case in summary['sourceCases']:
            path=args.folder/case;require(path.is_file(),'Missing runtime capture: '+case);sources[case]=hashlib.sha256(path.read_bytes()).hexdigest()
        require(len(sources)>=7,'Missing controlled GPU screenshot cases')
        summary['sourceHashes']=sources;code=0
    except (OSError,ValueError,KeyError) as error:
        summary={'status':'rejected','accepted':False,'error':str(error)};code=1
    (args.folder/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2));return code


if __name__=='__main__':raise SystemExit(main())
