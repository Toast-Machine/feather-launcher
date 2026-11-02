# Agent instructions

## Branching

Do all work directly on `main` unless told otherwise. Don't create feature branches or worktrees unless asked.

## Testing

Test every change on two Android emulators before calling it done:

1. **A standard smartphone**: a typical touchscreen phone AVD, such as a Pixel image.
2. **The Sonim X320**: an AVD set up to match the user's Sonim X320 (its screen size and density, its Android version, and a hardware keypad with D-pad navigation and no touch reliance).

A change counts as tested only once it works on both. If either emulator isn't available, say so; don't just skip it.

Take screenshots in both emulators that show the fix or feature working, and show them to the dev.

## What goes in the repo

Only code and docs. Never commit screenshots, recordings, logs, build outputs or other files that aren't code or docs unless specifically told to. Keep them outside the repo, such as in a temp directory.
