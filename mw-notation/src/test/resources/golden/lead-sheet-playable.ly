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
      a1:m |
      f1 |
      g1:7 |
    }
  }
  \new Staff \with { instrumentName = "Voice (playable)" } {
    \clef "treble"
    \key c \major
    \new Voice = "melody" {
      \time #'(1 1 1 1) 4/4
      \tempo \markup { \italic "ca." } 4 = 120
      g'2 c''2 |
      e''1 |
      g'1 |
      c''1 |
      \bar "|."
    }
  }
  \new Lyrics \with {
    \override VerticalAxisGroup.staff-affinity = #UP
    \override VerticalAxisGroup.nonstaff-nonstaff-spacing.basic-distance = #3
    \override LyricText.self-alignment-X = #LEFT
    \override LyricHyphen.minimum-distance = #0.8
  } \lyricmode {
    \set associatedVoice = "melody"
    "one"2 "two"2 |
    "three"1 |
    "four"1 |
    "five"1 |
  }
  >>
  \layout { }
}
