# Agent instructions

## Branching

Do all work directly on `main` unless told otherwise. Don't create feature branches or worktrees unless asked.

Make every pull request from `main` unless told otherwise: its commits are the feature's own commits from `main`, put on top of upstream's main, leaving out everything else that's only on this fork (such as CI and this file). Don't rewrite the feature as new commits.

## Testing

Test every change on two Android emulators before calling it done:

1. **A standard smartphone**: a typical touchscreen phone AVD, such as a Pixel image.
2. **The Sonim X320**: an AVD set up to match the user's Sonim X320 (its screen size and density, its Android version, and a hardware keypad with D-pad navigation and no touch reliance).

A change counts as tested only once it works on both. If either emulator isn't available, say so; don't just skip it.

Take screenshots in both emulators that show the fix or feature working, and show them to the dev.

Other agents may be testing at the same time, so make your own emulator instances rather than using ones that already exist. Create a new AVD for each of the two (copy an existing one's `config.ini` for the same hardware, with fresh data), give each a name and `-port` no one else is using, and drive only those. Don't start, stop, wipe or change settings on emulators you didn't create. Shut yours down when you're done.

## What goes in the repo

Only code and docs. Never commit screenshots, recordings, logs, build outputs or other files that aren't code or docs unless specifically told to. Keep them outside the repo, such as in a temp directory.
