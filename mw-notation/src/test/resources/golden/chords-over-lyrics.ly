\version "2.24.0"

\header {
  title = "Chart Practice"
  composer = "Anonymous"
  tagline = ##f
}

\score {
  <<
  \new ChordNames \with {
    chordChanges = ##t
    \consists "Bar_engraver"
    \override BarLine.bar-extent = #'(-2 . 2)
  } {
    \tempo \markup { \italic "ca." } 4 = 120
    \chordmode {
      \time #'(1 1 1 1) 4/4
      c2 g2/b |
      a1:m7 |
      a1:m7 |
      r2 des2 |
    }
    \bar "|."
  }
  \new Lyrics \with {
    \override VerticalAxisGroup.staff-affinity = #DOWN
    \override VerticalAxisGroup.nonstaff-nonstaff-spacing.basic-distance = #3
    \override LyricText.self-alignment-X = #LEFT
    \override LyricHyphen.minimum-distance = #0.8
  } \lyricmode {
    "Sing"2 "hap"4 -- "py"4 |
    "song"1 |
    \skip 1 |
    \skip 4 "now"2. |
  }
  >>
  \layout { }
}
