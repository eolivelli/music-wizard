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
    \tempo \markup { \italic "ca." } 4 = 120
    c'2 g'2~ |
    g'4 e'4 c'2~ |
    c'2~ c'8 d'4. |
    \bar "|."
  }
  \layout { }
}
