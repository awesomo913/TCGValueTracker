# PROOF — CursorCoder Plain-Language Record

A dated record any reader can understand: customer, investor, lawyer,
judge, jury, or anyone curious — no tech background needed.

---

## What this thing is

CursorCoder is a desktop program that types tasks into a code-writing
app called Cursor, waits for it to reply, and keeps going on its own
until you tell it to stop or it runs out of credits.

---

## What it does for you

- Types a coding task into Cursor for you, over and over, while you
  do something else.
- Builds real working programs and saves them to folders on your
  computer.
- Works through a whole list of app ideas, one by one, without you
  watching.
- Shows a running count of how many AI requests it has sent today.
- Stops on its own when your monthly request allowance is used up.
- Leaves your own copy of Cursor exactly as you left it — it runs
  in a separate, private window.

---

## How it was made

The user designed it. AI helped build it.

Cursor is a code-writing app. It has its own AI built in that can
write programs for you. CursorCoder talks to Cursor through a back
door called CDP (Chrome DevTools Protocol — the same remote-control
doorway that web browsers use for testing). It types into Cursor's
chat the same way you would, waits for the answer, then types the
next thing.

CursorCoder is based on an earlier program called Autocoder, which
did the same thing with Google's Gemini website. The user's design
decision was to reuse the same brain and swap only the part that
talks to the AI. About 90% of the code was already working.

Before building anything, the user ran a test to make sure the plan
would work. The test drove the real Cursor app end-to-end and got a
real reply back. Only after that test passed did the full build begin.

---

## What it costs / what it gives back

- **Money:** none for the program itself — CursorCoder is free.
  You need an existing Cursor account. The paid "Pro" tier has a
  monthly request allowance; CursorCoder is designed to use that
  allowance up on purpose before it resets.
- **Time:** you save the time you would have spent typing and waiting.
  The program works while you sleep, eat, or do other things.
- **Data:** every prompt goes to Cursor's AI service, the same as
  if you typed it yourself. Replies are saved to folders on your
  own computer.
- **Control:** you can stop it any time — there is a Stop button,
  a panic key (F10), and a simple "KILL" file you can create to
  halt it from anywhere. It saves its place so you can pick back
  up later.

---

## Who is responsible

The user, as the designer of this program. AI helped write the code
to the user's instructions. **Last review: 2026-05-21.**

---

## What proof exists that it works

- **Feasibility test (2026-05-21):** Before writing the full program,
  a live test drove the real Cursor app over its remote-control
  doorway. A prompt was typed in. Cursor's AI responded. The reply
  was read back. One real Pro request was used. The whole loop took
  about 9 seconds.
- **Engine smoke test (2026-05-21):** After the full build, the
  engine drove Cursor through Autocoder's own classes. A real reply
  came back, confirming all the pieces were wired together correctly.
- **18 automated tests passing (2026-05-21):** The nine new modules
  (launcher, mode switch, file collector, usage detector, request
  counter, idea-list reader, and others) each have their own test.
  All 18 pass.
- **Built program ready (2026-05-21):** `CursorCoder.exe` built and
  placed at `Desktop\My Apps\CursorCoder.exe` (212 MB).
- **Design and plan documents:**
  - `C:\Users\computer\Desktop\AI\docs\superpowers\specs\2026-05-21-cursor-coder-design.md`
  - `C:\Users\computer\Desktop\AI\docs\superpowers\plans\2026-05-21-cursor-coder.md`

---

## Changelog

### 2026-05-21 — First release

Today CursorCoder was built and shipped. It can open a private copy
of Cursor, sign in once, and then run coding tasks on a loop without
touching your real Cursor. It can work through one task with many
improvement rounds, or work through a whole list of tasks one at a
time. It counts every request it sends and stops cleanly when your
monthly allowance runs out. All basic safety controls are included:
stop button, panic key, and crash recovery. A ready-to-run program
file was created for Windows.
