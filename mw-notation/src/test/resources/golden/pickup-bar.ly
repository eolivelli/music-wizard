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
    \partial 4
    g'4 |
    c''4. b'8 a'2 |
    g'1 |
    \bar "|."
  }
  \layout { }
}
