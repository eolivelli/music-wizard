\version "2.24.0"

\header {
  title = "Untitled"
  tagline = ##f
}

\score {
  \new Staff \with { instrumentName = "Voice" } {
    \clef "treble"
    \key c \major
    \time #'(3 3) 6/8
    \tempo \markup { \italic "ca." } 4. = 120
    c'8 d'8 e'8 f'4 g'8 |
    a'4.~ a'8 g'4 |
    f'2. |
    \bar "|."
  }
  \layout { }
}
