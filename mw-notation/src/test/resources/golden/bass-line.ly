\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Bass" } {
    \clef "bass_8"
    \key ees \major
    \time #'(1 1 1 1) 4/4
    \tempo 4 = 120
    ees,4 ees,4 aes,4 bes,4 |
    aes,,1 |
    \bar "|."
  }
  \layout { }
}
