\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble"
    \key a \minor
    \time #'(1 1 1) 3/4
    \tempo 4 = 90
    a'4 r4 b'4 |
    R2. |
    R2. |
    c''4 d''2 |
    \bar "|."
  }
  \layout { }
}
