\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble"
    \key c \major
    \time #'(1 1 1 1) 4/4
    \tempo 4 = 120
    c'1 |
    \time #'(1 1 1) 3/4
    d'4 e'2 |
    f'2. |
    \bar "|."
  }
  \layout { }
}
