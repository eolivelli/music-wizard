\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble_8"
    \key c \major
    \time #'(3 3) 6/8
    \tempo \markup { \italic "ca." } 4. = 120
    c'8 d'8 e'8 f'8 g'8 a'8 |
    \tuplet 2/3 { c'8 d'8 } \tuplet 2/3 { e'8 f'8 } |
    a'4. \tuplet 2/3 { g'8 f'8 } |
    \bar "|."
  }
  \layout { }
}
