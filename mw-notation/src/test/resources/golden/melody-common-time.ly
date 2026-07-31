\version "2.24.0"

\header {
  title = "Scale Practice"
  composer = "Anonymous"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble"
    \key c \major
    \time #'(1 1 1 1) 4/4
    \tempo \markup { \italic "ca." } 4 = 120
    c'4 d'4 e'4 f'4 |
    g'2 a'2 |
    c''8 b'8 a'8 g'8 f'2 |
    r4 e'4 c'2 |
    \bar "|."
  }
  \layout { }
}
