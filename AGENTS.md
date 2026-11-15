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

Other agents may be testing at the same time, so only use emulators no one else is using. For each of the two, first look for an existing AVD of that kind that's available: not running and not in use by another agent. Use it if there is one. Only if there isn't, create a new AVD (copy an existing one's `config.ini` for the same hardware, with fresh data) and give it a name and `-port` no one else is using. Don't start, stop, wipe or change settings on emulators another agent is using.

When you're done, shut down every emulator you used, and remove any AVD you created (`avdmanager delete avd -n <name>`, or delete its `.avd` folder and `.ini` file).

## What goes in the repo

Only code and docs. Never commit screenshots, recordings, logs, build outputs or other files that aren't code or docs unless specifically told to. Keep them outside the repo, such as in a temp directory.
