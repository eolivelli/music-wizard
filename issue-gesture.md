Found reviewing #663, which made the octave fold decide a gesture rather than a
note.

`MelodyEstimator.oneGesture` groups notes by pitch alone: two notes that follow
one another in the estimate and lie within a whole tone are one gesture,
however long the silence between them. So a note can be kept in its own octave
by a neighbour tens of seconds earlier — the review measured single gestures
bridging silences of well over half a minute on the committed packages, and
built a fixture where a leakage note keeps a later note a whole tone away from
being folded across three quarters of a minute of nothing.

Nothing in either corpus or in the four field recordings is harmed by it today:
the notes that share a gesture across a long silence are ones the fold would
have left alone anyway, and the veto only ever declines a correction.

The obvious repair is not free. Requiring the notes to touch, with a silence no
longer than the shortest thing that can be a note, costs the whole of what the
gesture rule buys on the corpus read as given — `pop-axis` goes straight back
to its pre-#614 row, because the notes of a phrase in a mix are parted by
unvoiced stretches longer than any note. A longer tolerance is a new constant
with a shallow plateau: half a second holds one of that recording's two phrases
together and breaks the other.

What would settle it is a rule that reads the signal between the two notes
rather than a length.
