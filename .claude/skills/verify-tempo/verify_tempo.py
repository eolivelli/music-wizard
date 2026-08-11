#!/usr/bin/env python3
"""Independently verify a recording's tempo with estimators that do not share
MW's prior. See SKILL.md beside this file for when and how to use it.

Engines: Essentia's RhythmExtractor2013 (multifeature + degara) and
PercivalBpmEstimator, plus librosa's prior-free autocorrelation tempogram
peaks. madmom is deliberately absent: its model weights are CC BY-NC-SA.

Everything installs on first use into $MW_TEMPO_VERIFY_CACHE (default
~/.cache/mw-tempo-verify): a uv binary, one venv per engine. Nothing is
committed and nothing goes system-wide; Essentia is AGPL and is invoked as a
separate process only, the same standing LilyPond has.

  usage: python3 verify_tempo.py [--stated BPM] FILE
         python3 verify_tempo.py [--stated-map MAP] FILE [FILE ...]

MAP lines are "filename bpm"; files absent from the map get no verdict, only
the measured octave family. Verdicts, per the protocol on #349:

  CONFIRMED  a beat-tracking estimator reports the stated tempo directly
  FAMILY     the stated tempo is in the measured octave family but no
             estimator names it directly -- the level needs a musician's tap
             test, which no tool here can replace
  ABSENT     the stated tempo is not in the family at all; it is wrong

A confidence figure is never a confirmation (Essentia reports "excellent"
grid consistency on octave-halved readings), and two tools sharing a prior
agreeing is one measurement, not two.
"""

import argparse
import json
import math
import os
import subprocess
import sys
from pathlib import Path

CACHE = Path(os.environ.get("MW_TEMPO_VERIFY_CACHE",
                            str(Path.home() / ".cache" / "mw-tempo-verify")))
# The family a stated tempo may sit in relative to a measured candidate:
# octaves, the dotted relations, and the triplet third.
RATIOS = (1.0, 2.0, 0.5, 3.0, 1 / 3, 1.5, 2 / 3, 3 / 4, 4 / 3)
TOLERANCE = 0.02
ESTIMATORS = ("essentia_multifeature", "essentia_degara", "percival")


def run(cmd, **kw):
    result = subprocess.run(cmd, **kw)
    if result.returncode != 0:
        sys.exit(f"failed: {' '.join(str(c) for c in cmd)}")
    return result


def bootstrap():
    """First use: fetch uv, build one venv per engine. Idempotent."""
    uv = CACHE / "uv" / "uv"
    if not uv.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        print("bootstrapping: fetching uv ...", file=sys.stderr)
        run(["sh", "-c",
             "curl -LsSf https://astral.sh/uv/install.sh | "
             f"UV_INSTALL_DIR={CACHE / 'uv'} UV_NO_MODIFY_PATH=1 sh"],
            capture_output=True)
    for name, pkgs in (("venv-essentia", ["essentia"]),
                       ("venv-librosa", ["librosa", "soundfile"])):
        venv = CACHE / name
        if not (venv / "bin" / "python").exists():
            print(f"bootstrapping: {name} ({', '.join(pkgs)}) ...",
                  file=sys.stderr)
            run([uv, "venv", "--python", "3.12", str(venv)],
                capture_output=True)
            run([uv, "pip", "install", "--python",
                 str(venv / "bin" / "python"), *pkgs], capture_output=True)


def engine_essentia(path):
    import essentia.standard as es
    audio = es.MonoLoader(filename=path, sampleRate=44100)()
    bpm, _, confidence, _, _ = es.RhythmExtractor2013(
        method="multifeature")(audio)
    degara = es.RhythmExtractor2013(method="degara")(audio)[0]
    percival = es.PercivalBpmEstimator()(audio)
    return {"essentia_multifeature": round(float(bpm), 2),
            "confidence": round(float(confidence), 2),
            "essentia_degara": round(float(degara), 2),
            "percival": round(float(percival), 2)}


def engine_librosa(path):
    import librosa
    y, sr = librosa.load(path, sr=22050)
    onset = librosa.onset.onset_strength(y=y, sr=sr, hop_length=512)
    tempogram = librosa.feature.tempogram(
        onset_envelope=onset, sr=sr, hop_length=512)
    mean = tempogram.mean(axis=1)
    freqs = librosa.tempo_frequencies(len(mean), sr=sr, hop_length=512)
    peaks = []
    for i in range(2, len(mean) - 1):
        if (mean[i] >= mean[i - 1] and mean[i] >= mean[i + 1]
                and math.isfinite(freqs[i]) and 30 <= freqs[i] <= 300):
            peaks.append((float(mean[i]), float(freqs[i])))
    peaks.sort(reverse=True)
    top = peaks[:5]
    scale = top[0][0] if top else 1.0
    return {"tempogram_peaks": [
        {"bpm": round(bpm, 1), "salience": round(s / scale, 2)}
        for s, bpm in top]}


def measure(path):
    """Run both engine venvs on one file, merge their JSON."""
    merged = {}
    for venv, engine in (("venv-essentia", "essentia"),
                         ("venv-librosa", "librosa")):
        python = CACHE / venv / "bin" / "python"
        result = subprocess.run(
            [str(python), __file__, "--engine", engine, path],
            capture_output=True, text=True)
        if result.returncode != 0:
            merged[f"{engine}_error"] = result.stderr.strip().splitlines()[-1:]
        else:
            merged.update(json.loads(result.stdout))
    return merged


def within(a, b):
    return b > 0 and abs(a - b) / b <= TOLERANCE


def verdict(measured, stated):
    direct = [name for name in ESTIMATORS
              if name in measured and within(measured[name], stated)]
    if direct:
        return f"CONFIRMED by {', '.join(direct)}"
    candidates = [measured[name] for name in ESTIMATORS if name in measured]
    candidates += [p["bpm"] for p in measured.get("tempogram_peaks", [])]
    if any(within(stated, c * r) for c in candidates for r in RATIOS):
        return "FAMILY only -- level unconfirmed, needs a tap test"
    return "ABSENT from the measured family -- the stated figure looks wrong"


def report(path, measured, stated):
    name = Path(path).name
    parts = []
    for key in ESTIMATORS:
        if key in measured:
            parts.append(f"{key.replace('essentia_', 'ess-')}="
                         f"{measured[key]:g}")
    if "confidence" in measured:
        parts.append(f"conf={measured['confidence']:g}/5.32")
    peaks = measured.get("tempogram_peaks", [])
    if peaks:
        parts.append("tempogram=" + " ".join(
            f"{p['bpm']:g}({p['salience']:g})" for p in peaks))
    for key in ("essentia_error", "librosa_error"):
        if key in measured:
            parts.append(f"{key}: {measured[key]}")
    print(f"{name}")
    print(f"  {'  '.join(parts)}")
    if stated is not None:
        print(f"  stated {stated:g}: {verdict(measured, stated)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=("essentia", "librosa"),
                        help=argparse.SUPPRESS)  # internal: run inside a venv
    parser.add_argument("--stated", type=float,
                        help="claimed BPM to check (single file)")
    parser.add_argument("--stated-map",
                        help="file of 'filename bpm' lines for batch runs")
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()

    if args.engine:
        engine = engine_essentia if args.engine == "essentia" else engine_librosa
        print(json.dumps(engine(args.files[0])))
        return

    stated_by_name = {}
    if args.stated_map:
        for line in Path(args.stated_map).read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                name, bpm = line.split()
                stated_by_name[name] = float(bpm)
    if args.stated is not None:
        if len(args.files) != 1:
            sys.exit("--stated takes exactly one file; use --stated-map for a batch")
        stated_by_name[Path(args.files[0]).name] = args.stated

    bootstrap()
    for path in args.files:
        if not Path(path).exists():
            print(f"{path}: not found")
            continue
        measured = measure(path)
        report(path, measured, stated_by_name.get(Path(path).name))


if __name__ == "__main__":
    main()
