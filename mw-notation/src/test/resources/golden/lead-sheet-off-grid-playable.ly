\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  <<
  \new ChordNames \with {
    chordChanges = ##t
    \consists "Bar_engraver"
    \override BarLine.bar-extent = #'(-2 . 2)
  } {
    \chordmode {
      \time #'(1 1 1 1) 4/4
      c1 |
      c1 |
      g1 |
      g1 |
    }
  }
  \new Staff \with { instrumentName = "Voice (playable)" } {
    \clef "treble"
    \key c \major
    \time #'(1 1 1 1) 4/4
    \tempo \markup { \italic "ca." } 4 = 120
    e'4~ e'16 g'8.~ g'8 r16 b'16~ b'4 |
    e'4~ e'16 g'8.~ g'8 r16 b'16~ b'4 |
    e'4~ e'16 g'8.~ g'8 r16 b'16~ b'4 |
    e'4~ e'16 g'8.~ g'8 r16 b'16~ b'4 |
    \bar "|."
  }
  \new Lyrics \with {
    \override VerticalAxisGroup.staff-affinity = #UP
    \override VerticalAxisGroup.nonstaff-nonstaff-spacing.basic-distance = #3
    \override LyricText.self-alignment-X = #LEFT
    \override LyricHyphen.minimum-distance = #0.8
  } \lyricmode {
    \skip 64 "la"1*19/64 "di"4. "da"1*5/16 |
    \skip 64 "la"1*19/64 "di"4. "da"1*5/16 |
    \skip 64 "la"1*19/64 "di"4. "da"1*5/16 |
    \skip 64 "la"1*19/64 "di"4. "da"1*5/16 |
  }
  >>
  \layout { }
}
