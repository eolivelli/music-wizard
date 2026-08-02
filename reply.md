Round 1 replies. Findings 1, 2 and 3 fixed; 4, 5 and 6 fixed as prose; 7 answered.

### 1 — relative-pair tie broken by pitch-class index — **fixed, and it was worse than reported**

Confirmed and taken. Chasing it turned up the same defect one level down: the tie was not
even decided by scan order reliably. `C A7 Dm G7` scores C major and D minor **identically
in exact arithmetic** — 4.1667 each — and came out D minor because the two sums add the
same four terms in different order, so they differ in the last bit of a double. Scan order
never got a say.

So the fix is two parts, both in `KeyEstimator.beats`:

- **Ties are compared with a tolerance**, not for equality. The tolerance sits far above
  accumulation error and far below the narrowest margin any recording here produces.
- **A stated tie-break**: major over minor, then the simpler key signature, then the lowest
  pitch class. Major is a prior, not a reading — any margin at all outranks it, and the
  tonic confidence comes back at its floor to say the prior was all there was.

Your `Am Em Am Em` case gets a principled answer from the second step rather than an
arbitrary one: A minor and E minor tie, and A minor is the one that does not ask for a
sharp. `C A7 Dm G7` now answers C major, and the test says plainly that the prior decided
it — a secondary dominant is indistinguishable from a real one on this scoring, which is a
limitation worth having written down.

`theTieBreakIsTranspositionInvariant` sweeps the I–V–vi–IV loop through all twelve keys and
asserts both the answer and the floor confidence. `popLoopNamesItsKey` no longer rides on a
tie: it drops the vi, so it tests the diatonic fit its name claims.

### 2 — confidence saturates on arbitrarily little evidence — **fixed**

Confirmed; your 0.5s-in-240s case is exactly right and the neighbour's rule was the right
place to look. `confidence()` now takes a third factor, the share of the span that carried
a chord, multiplied in as `DownbeatEstimator.harmonicAgreement` multiplies its "there was
enough of it" term. `confidenceFallsWithHowLittleWasWeighed` pins both ends.
`noChordSpansAreIgnored` — which pinned the omission — is replaced by one that asserts only
what is true: silence cannot change *which* key is named.

On the second half, that every one-chord vamp returns 1.00/1.00: I tried the obvious
stronger fix, measuring the signature margin on the fit term alone so the tonic-chord
weight stops vouching for a signature it says nothing about. It is worse calibrated, not
better — every correct answer collapses into the 7–21% band, `gmajorblues` to 7%, for an
estimator that names the key right on ten of eleven files. I have not taken it. What I
think is actually true is that eleven files cannot calibrate a slope, which is what the PR
body already says about `DECISIVE_MARGIN`; I would rather leave the number honest about
*margin and coverage* than make it pessimistic in a way no measurement supports.

### 3 — tests stating discriminators the fixtures do not carry — **fixed**

- `durationDecidesRatherThanCount`: confirmed vacuous. New fixture is a long E against a
  short C–F–G, which really does answer C major counted a chord at a time and E major
  weighed by time.
- `aMinorWithItsDominantStaysMinor`: the comment named the wrong runner-up. It is E major,
  not C major, and the comment now says so.
- `aMinorBluesStaysMinor` and `bossaStaysMinor` claim nothing about the dominant rule and
  are property tests; `theDominantRaisesTheTonicConfidence` and
  `aMajorChordElsewhereIsNotADominant` are the two that isolate it, as you found. The
  ablation claim in the PR body is about the recording and is stated as such.

### 4 — the blues argument was wrong — **fixed**

You are right and I should have caught it: B flat is not in C major. The real mechanism is
sharper than the one I wrote. In a blues the chord that sounds most is the tonic and it is
a dominant seventh, so its flat seventh is foreign to the piece's own key and native to the
subdominant's — G7's F natural is in C major and not in G. Counting the sevenths docks the
right answer on every tonic bar and the wrong one on none. Both the javadoc and the test
comment now say that, and the javadoc points at the recording for the measured result.

### 5 — `keyLine` javadoc implied 100% means declared — **fixed**, clause cut.

### 6 — two harness rows cannot fail — **fixed as prose.** The `KEYS` comment now says the
two vamps' keys are read off their one chord rather than stated by `list.txt`, that those
rows are closer to a tautology than a measurement, that the four blues rows are one shape
transposed, and that `Eb7` throughout is as fairly called Eb Mixolydian.

### 7 — `mw-core` touched in a multi-module PR

Noted. The rule exists so the symbolic and audio tracks can be built in parallel without
colliding, and there is one PR in flight; the alternative here was a second copy of the
circle-of-fifths arithmetic in `mw-dsp`, which is the duplication `Key.tonicOf`'s own
javadoc argues against. Happy to split it if you would rather.

---

Baselines are being regenerated — the coverage factor moves several confidence figures. New
`premerge.sh` output and the diff to follow before the next round.
