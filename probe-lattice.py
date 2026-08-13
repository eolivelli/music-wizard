#!/usr/bin/env python3
"""How far each candidate bar-spacing rule puts the chart's bar lines from a
beat lattice combed straight out of the audio. Not committed: it re-spells
ChartLayout's arithmetic in Python, and the reference is tools/measure-tempo.py's
own comb (ffmpeg only, no BeatTracker, no mw code). Smaller is closer to the
music. Run from the repo root with probe-ws/<name>.mwz analysed.
"""
import json
import sys
from pathlib import Path

REPO = Path(".").resolve()
sys.path.insert(0, str(REPO / "tools"))
from importlib import import_module
mt = import_module("measure-tempo")

EXTRA = {
    "g-blues-shuffle-cc.mp3": (100.0, 112.0),
    "f-blues-swing-170.mp3": (162.0, 178.0),
    "pop-c-g-am-f-120.mp3": (114.0, 126.0),
    "cm-blues-68-95.mp3": (88.0, 102.0),
    "waltz-am-e7-160.mp3": (152.0, 168.0),
    "jazz-251-c-140.mp3": (132.0, 148.0),
}

STEADY = 0.2


def lattice(mp3, band):
    envelope = mt.onset_envelope(mp3)
    bpm = mt.comb(envelope, band[0], band[1], 0.02)[0][1]
    rate = mt.SAMPLE_RATE / mt.HOP
    period = 60.0 / bpm * rate
    teeth = int((len(envelope) - 1) / period)
    best, best_offset = -1.0, 0
    for offset in range(int(period)):
        total = sum(envelope[int(offset + k * period)] for k in range(teeth)
                    if int(offset + k * period) < len(envelope))
        if total > best:
            best, best_offset = total, offset
    return 60.0 / bpm, best_offset / rate, bpm


def grid_of(name):
    doc = json.loads((REPO / "probe-ws" / (name + ".mwz") / "score" / "score.json").read_text())
    beats = [b["seconds"] for b in doc["beatGrid"]["beats"]]
    downs = [b["seconds"] for b in doc["beatGrid"]["beats"] if b.get("downbeat")]
    intervals = sorted(beats[i] - beats[i - 1] for i in range(1, len(beats)))
    m = len(intervals) // 2
    median = (intervals[m] if len(intervals) % 2
              else (intervals[m - 1] + intervals[m]) / 2.0)
    band = [d for d in intervals if median * (1 - STEADY) <= d <= median * (1 + STEADY)]
    bar = 4.0 * (sum(band) / len(band) if band else median)
    return bar, downs


def phase_of(downs, bar):
    """ChartLayout.barPhase: circular median of the offsets, refused past half a beat."""
    nominated = downs[0]
    around = [ieee(d - nominated, bar) for d in downs]
    agreed, least = 0.0, float("inf")
    for candidate in around:
        total = sum(abs(ieee(other - candidate, bar)) for other in around)
        if total < least:
            least, agreed = total, candidate
    return nominated + agreed if abs(agreed) <= bar / 8 else nominated


def ieee(x, m):
    q = round(x / m)
    return x - q * m


def nearest(downs, prediction):
    lo, hi = 0, len(downs)
    while lo < hi:
        mid = (lo + hi) // 2
        if downs[mid] < prediction:
            lo = mid + 1
        else:
            hi = mid
    best, least = None, float("inf")
    for i in (lo - 1, lo):
        if 0 <= i < len(downs) and abs(downs[i] - prediction) < least:
            least, best = abs(downs[i] - prediction), downs[i]
    return best, least


def walk(downs, phase, bar, bars, mode):
    tolerance = bar / 8.0          # half a counted beat in 4/4
    lines, at = [phase], phase
    for _ in range(bars - 1):
        prediction = at + bar
        found, away = nearest(downs, prediction)
        step = 0.0 if (found is None or away > bar / 2) else found - prediction
        if mode == "constant":
            at = prediction
        elif mode == "refuse":
            at = prediction + (step if abs(step) <= tolerance else 0.0)
        elif mode == "clamp":
            at = prediction + max(-tolerance, min(tolerance, step))
        lines.append(at)
    return lines


def away_from(lines, period, offset):
    away = [abs((line - offset) - round((line - offset) / period) * period) for line in lines]
    return sum(away) / len(away) / period, max(away) / period


for name in sys.argv[1:]:
    band = mt.SEARCH.get(name) or EXTRA.get(name)
    if band is None:
        print(f"{name}: no documented band, skipped")
        continue
    period, offset, bpm = lattice(REPO / "samples" / name, band)
    bar, downs = grid_of(name)
    bars = len(downs)
    phase = phase_of(downs, bar)
    print(f"{name}: combed {bpm:.2f} BPM, beat {period:.4f}s; chart bar {bar:.4f}s "
          f"over {bars} bars")
    for mode, label in (("constant", "one constant bar length (main)"),
                        ("refuse", "moved onto the downbeat, refused past half a beat"),
                        ("clamp", "moved onto it as far as half a beat (this PR)")):
        mean, worst = away_from(walk(downs, phase, bar, bars, mode), period, offset)
        print(f"    {label:<52} mean {mean:.3f} beat, max {worst:.3f} beat")
    mean, worst = away_from(downs, period, offset)
    print(f"    {'the grid'+chr(39)+'s own downbeats, for reference':<52} "
          f"mean {mean:.3f} beat, max {worst:.3f} beat")
