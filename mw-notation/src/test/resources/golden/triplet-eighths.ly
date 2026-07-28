\version "2.24.0"

\header {
  title = "Triplet Practice"
  composer = "Anonymous"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble"
    \key c \major
    \time #'(1 1 1 1) 4/4
    \tempo 4 = 120
    c'8 d'8 e'8 f'8 g'8 a'8 b'8 c''8 |
    \tuplet 3/2 { c'8 d'8 e'8 } \tuplet 3/2 { f'8 g'8 a'8 } \tuplet 3/2 { b'8 c''8 d''8 } \tuplet 3/2 { e''8 d''8 c''8 } |
    \tuplet 3/2 { c'8 d'4 } e'4~ \tuplet 3/2 { e'8 f'4 } g'4 |
    c'1 |
    \bar "|."
  }
  \layout { }
}
