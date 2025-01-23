# Stonecutter Hall of Fame

Utility project for the [Stonecutter homepage](https://stonecutter.kikugie.dev/) that collects mods using it.
It uses GitHub API to find repositories with `stonecutter.gradle[.kts]` files and searches for the matching projects
on Modrinth and CurseForge.

## Implementation
The main entrypoint is `search/Collector.get(token, config, cache)`. The given token is used to send requests
to the GitHub API. Cache stores information of previously queried projects, which can be used to run the process
without a token, as well as use bulk requests for mod information.

The working implementation can be found in the [Stonecutter repository](https://github.com/stonecutter-versioning/stonecutter/blob/0.5/buildSrc/src/main/kotlin/tasks/HallOfFameTask.kt).

## Contributing
This repository is only for the program implementation. If you want to make changes to the mod list on the homepage, such as
adding a closed-source or non-GitHub mod to the list, removing or fixing your mod entry, please make an issue in the [Stonecutter repository](https://github.com/stonecutter-versioning/stonecutter/issues).
