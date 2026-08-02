\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Piano" } {
    \clef "bass"
    \key c \major
    \time #'(1 1 1 1) 4/4
    \tempo \markup { \italic "ca." } 4 = 120
    <c e g>2 c4 b,4 |
    \bar "|."
  }
  \layout { }
}
