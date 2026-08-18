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
      c4 |
      f1 |
      g1:7 |
      c1 |
    }
  }
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble"
    \key c \major
    \time #'(1 1 1 1) 4/4
    \tempo \markup { \italic "ca." } 4 = 120
    \partial 4
    g'4 |
    c''1 |
    e''1 |
    d''1 |
    \bar "|."
  }
  \new Lyrics \with {
    \override VerticalAxisGroup.staff-affinity = #UP
    \override VerticalAxisGroup.nonstaff-nonstaff-spacing.basic-distance = #3
    \override LyricText.self-alignment-X = #LEFT
    \override LyricHyphen.minimum-distance = #0.8
  } \lyricmode {
    "one"4 |
    "two"1 |
    "three"1 |
    "four"1 |
  }
  >>
  \layout { }
}
