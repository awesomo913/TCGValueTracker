# PROOF — Autocoder Plain-Language Record

A dated record any reader can understand: customer, investor, lawyer,
judge, jury, or anyone curious — no engineering background needed.

## What this thing is

Autocoder is a desktop program that types a task into Google's Gemini
chat website over and over again and saves every answer to a file on
your computer. It runs by itself once started. You give it one job
and walk away.

## What it does for you

- Builds a small piece of software, or a guide, or a script for you
  while you do something else.
- Tries many small improvements in a row instead of one big leap.
- Saves every answer to its own file so you can read the history.
- Restarts where it left off if your computer was turned off or the
  chat window crashed.

## How it was made

The user designed it. AI helped build it. It is a program written
in the Python language that pretends to be a person clicking the
Gemini website. There is no special connection to Google — Autocoder
just opens the same web page you would.

## What it costs / what it gives back

- **Money:** none directly — Autocoder is free. You need a Google
  Gemini account, which is free for the basic tier and paid for the
  "Pro" tier.
- **Time:** you save hours per task because the program keeps working
  while you sleep, eat, or do other things.
- **Data:** every prompt and every answer is saved to your own
  computer (under `~/Downloads/autocoder_outputs/` and
  `~/.autocoder/`). Google sees the prompts because that is where
  the answers come from, the same as if you typed them yourself.
- **Control:** you can stop the program at any time with a single
  button. You can also resume from the last saved point.

## Who is responsible

The user, in their role as the designer. AI helped build to their
specifications. **Last review: 2026-05-17.**

## What proof exists that it works

- **Live log run (2026-05-17, iterations #62 to #67):** six prompts
  sent, six answers received, six text files saved.
  Sizes: 2,918 / 13,290 / 2,691 / 7,853 / 5,735 chars.
  Files in `C:/Users/computer/Downloads/autocoder_outputs/candidate_final/`.
- **Build receipt:** `Desktop/My Apps/Autocoder/Autocoder.exe`,
  built 2026-05-17 03:43 EDT, version 3.2.0, 19 MB.
- **Diagnostic log:** `~/.autocoder/autocoder.log` — every action
  has a timestamp, plain-language step name, and any error.
- **Source code:** github.com/awesomo913/Autocoder (master branch).

## Changelog

### 2026-05-17 (late evening) — New chats now stay on Gemini Pro

When Autocoder rotated to a fresh chat, the page reloaded without
the "Pro" model preference, dropping back to the default model
(Flash). The conversation that prompted the rotation had been on
Pro; the next one wasn't. For paid Pro workflows that meant losing
the better model on every rotation — quietly.

The fix updates the fresh-chat URL Autocoder navigates to so that
"Pro" stays selected. Every new conversation Autocoder opens is now
the same model the user is paying for.

### 2026-05-17 (evening) — Autocoder now waits twice as long for Gemini to finish writing

Before this fix, Autocoder gave Gemini five minutes to finish each
answer. That was enough at the start of a conversation, but Gemini
slows down as the conversation gets longer. By the third or fourth
back-and-forth in the same chat, answers were taking more than five
minutes — Autocoder cut them off mid-sentence and threw away the
partial text. Roughly four out of every ten tries were being wasted.

The fix doubles the wait to ten minutes. Paired with a tuned setting
that keeps each chat to about three back-and-forths before starting
a fresh one, almost every try now finishes and gets saved.

### 2026-05-17 — Autocoder reuses the same Gemini chat instead of opening a new one every time

Before today, the program opened a brand new Gemini chat for every
single try. That worked but was wasteful — it scrolled past prior
attempts and made every prompt feel like the first one. Today's
change keeps the chat open across up to ten tries before starting
fresh again. Result: Gemini can build on what it just said, the
program scrolls less, and you can read a single conversation
instead of a stack of identical ones.

Also today: confirmed the earlier "send button stuck" fix is working
in real runs. The program sent prompts in iterations 62 through 67
and Gemini answered all six. Files saved to the outputs folder as
expected.

### 2026-05-17 — Earlier in the day, the program got 14 quality fixes

Before today's chat-reuse work, the program received fourteen
improvements in one pass: it now saves a daily decision log, takes
a snapshot of its progress every ten tries, can split a giant
codebase into smaller pieces before sending, recognizes when its
own output has stopped changing, and can swap to a backup AI if
the main one keeps failing. All fourteen passed a smoke test
before shipping.

### 2026-05-17 — The program was split into three separate apps

The original mixed-purpose program became three programs, each in
its own folder on GitHub. Autocoder kept the single-target Gemini
loop. FleetAutocoder kept the multi-window and Raspberry-Pi-fleet
feature. opencoder kept the OpenCode-only feature. The split makes
each piece easier to test and maintain on its own.
